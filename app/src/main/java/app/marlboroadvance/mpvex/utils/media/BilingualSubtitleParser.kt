package app.marlboroadvance.mpvex.utils.media

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import app.marlboroadvance.mpvex.preferences.SubtitleJustification
import app.marlboroadvance.mpvex.preferences.SubtitleMode
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.player.controls.components.panels.SubtitlesBorderStyle
import `is`.xyz.mpv.MPVLib
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class BilingualSplitResult(
  val primaryFile: File,
  val secondaryFile: File,
  val bilingualFile: File,
  val primaryTitle: String,
  val secondaryTitle: String,
  val bilingualTitle: String,
  val isBilingual: Boolean = true,
)

/** Clean, timing-normalized monolingual subtitle cue (used by the MKV embedded-subtitle extractor). */
data class PlainSubtitleCue(
  val startMs: Long,
  val endMs: Long,
  val text: String,
)

/**
 * High-performance parser and splitter for single-file bilingual subtitles.
 * Detects Chinese-English (or CJK + Latin) bilingual dialogues, and splits them into:
 * - Primary track: Chinese lines (positioned at bottom, styled by Primary Subtitle settings)
 * - Secondary track: English lines (clean SRT/ASS, styled by Secondary Subtitle settings)
 *
 * Utilizes CRC32-based disk caching so repeated loads cost 0ms.
 */
object BilingualSubtitleParser {
  private const val TAG = "BilingualSubParser"
  private const val CACHE_DIR_NAME = "bilingual_subs_v5"

  private val CJK_REGEX = Regex("[\\u4e00-\\u9fff\\u3400-\\u4dbf]")
  private val LATIN_REGEX = Regex("[a-zA-Z]")
  private val TAG_REGEX = Regex("\\{[^}]*\\}")
  private val ASS_SPLIT_REGEX = Regex("\\\\[Nn]")

  /**
   * Cleans invisible Unicode bidirectional control characters (LRE, RLE, PDF, LRO, RLO, LRM, RLM),
   * byte order mark, and zero-width spaces common in web-ripped subtitles (e.g. \u202a).
   */
  private val BIDI_AND_CONTROL_CHARS_REGEX = Regex("[\\u202a-\\u202e\\u200e\\u200f\\ufeff\\u200b]")

  /**
   * Fixes common UTF-8 / Latin-1 non-breaking space mojibake (e.g. Â\u00A0 or Â followed by space),
   * normalizing them to a standard ASCII space.
   */
  private val MOJIBAKE_NBSP_REGEX = Regex("(?:\\u00C2|Â)[\\u00A0\\s]+|[\\u00A0]")

  // ---------------------------------------------------------------------------
  // Plain-SRT pipeline (shared with MkvSubtitleExtractor)
  // ---------------------------------------------------------------------------
  private const val PLAIN_CACHE_DIR_NAME = "bilingual_subs_plain"
  private const val MIN_CUE_MS = 100L
  private const val MPV_PLAY_RES_Y = 720f
  private const val MPV_DEFAULT_MARGIN_LR = 40f // MarginL(20) + MarginR(20) in PlayRes units

  private val ASS_DRAWING_TAG = Regex("""\{\\p[1-9]\d*[^}]*\}""")
  private val ASS_OVERRIDE_TAG = Regex("""\{\\[^}]*\}|\{\}""")
  private val HTML_TAG = Regex("""</?(?:i|b|u|s|font|span|ruby|rt|rp)\b[^>]*>""", RegexOption.IGNORE_CASE)
  private val VTT_POSITION_TAG = Regex("""</?(?:c(?:\.[^>]*)?|v(?:\s+[^>]*)?|lang(?:\s+[^>]*)?|\d{2}:\d{2}(?::\d{2})?\.\d{3})>""", RegexOption.IGNORE_CASE)
  private val CONTROL_AND_BIDI_CHARS = Regex("""[\uFEFF\u200B-\u200F\u202A-\u202E\u2066-\u2069\u00AD\u0000-\u0009\u000B-\u001F\u007F]""")
  private val MATROSKA_ASS_PREFIX = Regex("""^\d+,\d+,[^,]*,[^,]*,\d+,\d+,\d+,[^,]*,(.*)$""", RegexOption.DOT_MATCHES_ALL)
  private val SRT_TIMING_REGEX = Regex(
    """(\d{1,2}:)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(\d{1,2}:)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})""",
  )
  private val ASS_TIME_REGEX = Regex("""(\d+):(\d{2}):(\d{2})\.(\d{2,3})""")
  private val SPEAKER_DASH_START = Regex("""^[-\u2013\u2014\u2212\uFF0D]\s*\S""")
  private val SPEAKER_LABEL_LINE = Regex("""^(?:[-\u2013\u2014\u2212\uFF0D]|[\p{L}\d\s]{1,20}[:：])\s*""")
  private val WHITESPACE_RUN = Regex("""[ \t]+""")

  fun sanitizeSubtitleText(text: String): String {
    if (text.isEmpty()) return text
    var result = BIDI_AND_CONTROL_CHARS_REGEX.replace(text, "")
    result = MOJIBAKE_NBSP_REGEX.replace(result, " ")
    return result.trim()
  }

  /**
   * Cleans all inline ASS style and override tags ({...}) and hard spaces (\\h),
   * leaving pure subtitle text styled strictly by the user's mpvEx preferences.
   */
  fun cleanAssTags(text: String): String {
    if (text.isEmpty()) return text
    val sanitized = text.replace(Regex("\\{\\\\p[1-9]\\}[^\\{]*(\\{\\\\p0\\}|$)"), "")
    val withoutTags = TAG_REGEX.replace(sanitized, "").replace("\\h", " ")
    return sanitizeSubtitleText(withoutTags)
  }

  data class CueItem(
    val startMs: Long,
    val endMs: Long,
    val priParts: List<String>,
    val secParts: List<String>,
  )

  fun splitIfBilingual(sourceFile: File, context: Context): BilingualSplitResult? {
    if (!sourceFile.exists() || sourceFile.length() <= 0) return null

    val ext = sourceFile.extension.lowercase(Locale.ROOT)
    if (ext !in listOf("ass", "ssa", "srt")) return null

    val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
    if (!cacheDir.exists()) cacheDir.mkdirs()

    val hashKey = ChecksumUtils.getCRC32(
      "${sourceFile.absolutePath}_${sourceFile.length()}_${sourceFile.lastModified()}"
    )
    val folder = File(cacheDir, hashKey)
    if (!folder.exists()) folder.mkdirs()

    val baseName = sourceFile.nameWithoutExtension
    val primaryFileName = "[中] $baseName.ass"
    val secondaryFileName = "[英] $baseName.ass"
    val bilingualFileName = "[双语] $baseName.ass"
    val primaryFile = File(folder, primaryFileName)
    val secondaryFile = File(folder, secondaryFileName)
    val bilingualFile = File(folder, bilingualFileName)

    // Cache hit: 0ms execution
    if (primaryFile.exists() && primaryFile.length() > 0 &&
      secondaryFile.exists() && secondaryFile.length() > 0 &&
      bilingualFile.exists() && bilingualFile.length() > 0
    ) {
      Log.d(TAG, "Cache hit for bilingual subtitle: ${sourceFile.name}")
      return BilingualSplitResult(
        primaryFile = primaryFile,
        secondaryFile = secondaryFile,
        bilingualFile = bilingualFile,
        primaryTitle = "[中] $baseName",
        secondaryTitle = "[英] $baseName",
        bilingualTitle = "[双语] $baseName",
      )
    }

    return try {
      val rawBytes = sourceFile.readBytes()
      val content = readTextWithAutoEncoding(rawBytes)
      val result = if (ext == "srt") {
        splitSrt(content, folder, primaryFile, secondaryFile, bilingualFile, baseName)
      } else {
        splitAss(content, folder, primaryFile, secondaryFile, bilingualFile, baseName)
      }
      result
    } catch (e: Exception) {
      Log.e(TAG, "Failed to split bilingual subtitle ${sourceFile.name}: ${e.message}", e)
      null
    }
  }

  fun splitIfBilingual(uri: Uri, context: Context): BilingualSplitResult? {
    return try {
      val inputStream = context.contentResolver.openInputStream(uri) ?: return null
      val rawBytes = inputStream.use { it.readBytes() }
      if (rawBytes.isEmpty()) return null

      val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "subtitle.ass"
      val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
      if (ext !in listOf("ass", "ssa", "srt")) return null

      val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
      if (!cacheDir.exists()) cacheDir.mkdirs()

      val hashKey = ChecksumUtils.getCRC32("${uri}_${rawBytes.size}")
      val folder = File(cacheDir, hashKey)
      if (!folder.exists()) folder.mkdirs()

      val baseName = fileName.substringBeforeLast('.')
      val primaryFileName = "[中] $baseName.ass"
      val secondaryFileName = "[英] $baseName.ass"
      val bilingualFileName = "[双语] $baseName.ass"
      val primaryFile = File(folder, primaryFileName)
      val secondaryFile = File(folder, secondaryFileName)
      val bilingualFile = File(folder, bilingualFileName)

      if (primaryFile.exists() && primaryFile.length() > 0 &&
        secondaryFile.exists() && secondaryFile.length() > 0 &&
        bilingualFile.exists() && bilingualFile.length() > 0
      ) {
        return BilingualSplitResult(
          primaryFile = primaryFile,
          secondaryFile = secondaryFile,
          bilingualFile = bilingualFile,
          primaryTitle = "[中] $baseName",
          secondaryTitle = "[英] $baseName",
          bilingualTitle = "[双语] $baseName",
        )
      }

      val content = readTextWithAutoEncoding(rawBytes)
      if (ext == "srt") {
        splitSrt(content, folder, primaryFile, secondaryFile, bilingualFile, baseName)
      } else {
        splitAss(content, folder, primaryFile, secondaryFile, bilingualFile, baseName)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to split bilingual subtitle from URI $uri: ${e.message}", e)
      null
    }
  }

  private fun splitAss(
    content: String,
    folder: File,
    primaryFile: File,
    secondaryFile: File,
    bilingualFile: File,
    baseName: String,
  ): BilingualSplitResult? {
    // Force standard 1080p canvas for all split ASS subtitles, eliminating font scaling bugs
    // caused by legacy low-resolution canvases (e.g. 288p, 360p, 576p)
    val playResX = 1920
    val playResY = 1080

    val cues = parseAssToCues(content)
    val bilingualCount = cues.count { it.priParts.isNotEmpty() && it.secParts.isNotEmpty() }

    if (cues.isEmpty() || bilingualCount < 5 || (bilingualCount.toDouble() / cues.size < 0.1 && bilingualCount < 20)) {
      Log.d(TAG, "Not a bilingual ASS subtitle (bilingual=$bilingualCount, total=${cues.size})")
      return null
    }

    Log.d(TAG, "Splitting bilingual ASS subtitle: $bilingualCount/${cues.size} bilingual dialogues")

    val tempPri = File.createTempFile("pri_", ".tmp", folder)
    val tempSec = File.createTempFile("sec_", ".tmp", folder)
    val tempBi = File.createTempFile("bi_", ".tmp", folder)

    return try {
      tempPri.bufferedWriter(Charsets.UTF_8).use { priWriter ->
        tempSec.bufferedWriter(Charsets.UTF_8).use { secWriter ->
          tempBi.bufferedWriter(Charsets.UTF_8).use { biWriter ->
            writePrimaryAssHeader(priWriter, baseName, playResX, playResY)
            writeSecondaryAssHeader(secWriter, baseName, playResX, playResY)
            writeBilingualAssHeader(biWriter, baseName, playResX, playResY)
            writeAlignedCues(cues, priWriter, secWriter, biWriter)
          }
        }
      }

      tempPri.renameTo(primaryFile)
      tempSec.renameTo(secondaryFile)
      tempBi.renameTo(bilingualFile)

      BilingualSplitResult(
        primaryFile = primaryFile,
        secondaryFile = secondaryFile,
        bilingualFile = bilingualFile,
        primaryTitle = "[中] $baseName",
        secondaryTitle = "[英] $baseName",
        bilingualTitle = "[双语] $baseName",
      )
    } catch (e: Exception) {
      tempPri.delete()
      tempSec.delete()
      tempBi.delete()
      throw e
    }
  }

  private fun splitSrt(
    content: String,
    folder: File,
    primaryFile: File,
    secondaryFile: File,
    bilingualFile: File,
    baseName: String,
  ): BilingualSplitResult? {
    val cues = parseSrtToCues(content)
    val bilingualCount = cues.count { it.priParts.isNotEmpty() && it.secParts.isNotEmpty() }

    if (cues.isEmpty() || bilingualCount < 5 || (bilingualCount.toDouble() / cues.size < 0.1 && bilingualCount < 20)) {
      Log.d(TAG, "Not a bilingual SRT subtitle (bilingual=$bilingualCount, total=${cues.size})")
      return null
    }

    Log.d(TAG, "Splitting bilingual SRT subtitle: $bilingualCount/${cues.size} bilingual cues")

    val tempPri = File.createTempFile("pri_", ".tmp", folder)
    val tempSec = File.createTempFile("sec_", ".tmp", folder)
    val tempBi = File.createTempFile("bi_", ".tmp", folder)

    return try {
      tempPri.bufferedWriter(Charsets.UTF_8).use { priWriter ->
        tempSec.bufferedWriter(Charsets.UTF_8).use { secWriter ->
          tempBi.bufferedWriter(Charsets.UTF_8).use { biWriter ->
            writePrimaryAssHeader(priWriter, baseName, 1920, 1080)
            writeSecondaryAssHeader(secWriter, baseName, 1920, 1080)
            writeBilingualAssHeader(biWriter, baseName, 1920, 1080)
            writeAlignedCues(cues, priWriter, secWriter, biWriter)
          }
        }
      }

      tempPri.renameTo(primaryFile)
      tempSec.renameTo(secondaryFile)
      tempBi.renameTo(bilingualFile)

      BilingualSplitResult(
        primaryFile = primaryFile,
        secondaryFile = secondaryFile,
        bilingualFile = bilingualFile,
        primaryTitle = "[中] $baseName",
        secondaryTitle = "[英] $baseName",
        bilingualTitle = "[双语] $baseName",
      )
    } catch (e: Exception) {
      tempPri.delete()
      tempSec.delete()
      tempBi.delete()
      throw e
    }
  }

  private fun parseSrtToCues(content: String): List<CueItem> {
    val rawCues = mutableListOf<CueItem>()
    val normalized = content.replace("\r\n", "\n")
    val blocks = normalized.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }

    for (block in blocks) {
      val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
      val timeLineIdx = lines.indexOfFirst { it.contains("-->") }
      if (timeLineIdx == -1) continue

      val timeLine = lines[timeLineIdx]
      val timeParts = timeLine.split("-->").map { it.trim() }
      if (timeParts.size < 2) continue

      val startMs = parseTimeToMs(timeParts[0]) ?: continue
      val endStr = timeParts[1].substringBefore(" ").trim()
      val endMs = parseTimeToMs(endStr) ?: continue

      val textLines = lines.drop(timeLineIdx + 1)
      val linePieces = textLines.flatMap { line ->
        line.split(Regex("(?i)<br\\s*/?>|\\\\N"))
      }.map { 
        sanitizeSubtitleText(it.replace(Regex("<[^>]*>"), ""))
      }.filter { it.isNotEmpty() }

      val priParts = mutableListOf<String>()
      val secParts = mutableListOf<String>()

      for (cleaned in linePieces) {
        val hasCjk = CJK_REGEX.containsMatchIn(cleaned)
        val hasLatin = LATIN_REGEX.containsMatchIn(cleaned)

        if (hasCjk) {
          priParts.add(cleaned)
        } else if (hasLatin) {
          secParts.add(cleaned)
        } else {
          if (priParts.isNotEmpty()) {
            priParts.add(cleaned)
          } else if (secParts.isNotEmpty()) {
            secParts.add(cleaned)
          } else {
            priParts.add(cleaned)
          }
        }
      }

      if (priParts.isNotEmpty() || secParts.isNotEmpty()) {
        rawCues.add(CueItem(startMs, endMs, priParts, secParts))
      }
    }

    return alignCues(rawCues)
  }

  private fun parseAssToCues(content: String): List<CueItem> {
    val rawCues = mutableListOf<CueItem>()
    val lines = content.lines()

    for (line in lines) {
      val trimmed = line.trim()
      if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) continue

      val parts = trimmed.split(",", limit = 10)
      if (parts.size < 10) continue

      val startMs = parseTimeToMs(parts[1]) ?: continue
      val endMs = parseTimeToMs(parts[2]) ?: continue
      val rawText = parts[9]
      val subParts = rawText.split(ASS_SPLIT_REGEX)

      val priParts = mutableListOf<String>()
      val secParts = mutableListOf<String>()

      for (p in subParts) {
        val pClean = cleanAssTags(p)
        if (pClean.isEmpty()) continue
        val hasCjk = CJK_REGEX.containsMatchIn(pClean)
        val hasLatin = LATIN_REGEX.containsMatchIn(pClean)

        if (hasCjk) {
          priParts.add(pClean)
        } else if (hasLatin) {
          secParts.add(pClean)
        } else {
          if (priParts.isNotEmpty()) {
            priParts.add(pClean)
          } else if (secParts.isNotEmpty()) {
            secParts.add(pClean)
          } else {
            priParts.add(pClean)
          }
        }
      }

      if (priParts.isNotEmpty() || secParts.isNotEmpty()) {
        rawCues.add(CueItem(startMs, endMs, priParts, secParts))
      }
    }

    return alignCues(rawCues)
  }

  /**
   * Intelligently aligns and unifies timestamps for adjacent/overlapping CJK & Latin cues.
   * If a subtitle file specifies Chinese and English dialogues in separate cues with
   * slight timestamp discrepancies (e.g. 100-1200ms offset), this merges them into a
   * single synchronized cue with interval [min(Start), max(End)].
   */
  private fun alignCues(rawCues: List<CueItem>): List<CueItem> {
    if (rawCues.isEmpty()) return emptyList()

    val sorted = rawCues.sortedWith(compareBy({ it.startMs }, { it.endMs }))
    val result = mutableListOf<CueItem>()
    val used = BooleanArray(sorted.size)

    for (i in sorted.indices) {
      if (used[i]) continue
      val cur = sorted[i]

      // If cue already has both primary and secondary, keep as is
      if (cur.priParts.isNotEmpty() && cur.secParts.isNotEmpty()) {
        result.add(cur)
        used[i] = true
        continue
      }

      val isCurPriOnly = cur.priParts.isNotEmpty() && cur.secParts.isEmpty()
      val isCurSecOnly = cur.secParts.isNotEmpty() && cur.priParts.isEmpty()

      if (!isCurPriOnly && !isCurSecOnly) {
        used[i] = true
        continue
      }

      var bestMatchIdx = -1
      var bestMatchOverlap = -1L

      val maxLookahead = minOf(sorted.size - 1, i + 15)
      for (j in (i + 1)..maxLookahead) {
        if (used[j]) continue
        val cand = sorted[j]
        if (cand.startMs - cur.startMs > 2500) break

        val isCandOpposite = if (isCurPriOnly) {
          cand.secParts.isNotEmpty() && cand.priParts.isEmpty()
        } else {
          cand.priParts.isNotEmpty() && cand.secParts.isEmpty()
        }

        if (isCandOpposite) {
          val overlapStart = maxOf(cur.startMs, cand.startMs)
          val overlapEnd = minOf(cur.endMs, cand.endMs)
          val overlap = overlapEnd - overlapStart
          val startDiff = Math.abs(cur.startMs - cand.startMs)
          val endDiff = Math.abs(cur.endMs - cand.endMs)

          if (overlap > 0 || (startDiff <= 1200 && endDiff <= 1500)) {
            if (overlap > bestMatchOverlap) {
              bestMatchOverlap = overlap
              bestMatchIdx = j
            }
          }
        }
      }

      if (bestMatchIdx != -1) {
        val match = sorted[bestMatchIdx]
        used[i] = true
        used[bestMatchIdx] = true

        val unifiedStart = minOf(cur.startMs, match.startMs)
        val unifiedEnd = maxOf(cur.endMs, match.endMs)
        val mergedPri = if (isCurPriOnly) cur.priParts else match.priParts
        val mergedSec = if (isCurSecOnly) cur.secParts else match.secParts

        result.add(
          CueItem(
            startMs = unifiedStart,
            endMs = unifiedEnd,
            priParts = mergedPri,
            secParts = mergedSec,
          )
        )
      } else {
        used[i] = true
        result.add(cur)
      }
    }

    return result.sortedWith(compareBy({ it.startMs }, { it.endMs }))
  }

  private fun writeAlignedCues(
    cues: List<CueItem>,
    priWriter: BufferedWriter,
    secWriter: BufferedWriter,
    biWriter: BufferedWriter,
  ) {
    for (cue in cues) {
      val startAss = msToAssTime(cue.startMs)
      val endAss = msToAssTime(cue.endMs)

      val priCleanList = cue.priParts.map { cleanAssTags(it) }.filter { it.isNotEmpty() }
      val secCleanList = cue.secParts.map { cleanAssTags(it) }.filter { it.isNotEmpty() }

      // Merge wrapped lines of the same language inside one cue into a single logical line
      // (CJK joined directly, Latin joined with a space). Only genuine two-speaker turns keep
      // an explicit line break, which is emitted as ASS \N so the file stays line-based.
      val priText = joinNormalizedLines(priCleanList).replace("\n", "\\N")
      val cleanedSec = joinNormalizedLines(secCleanList).replace("\n", "\\N")

      // 1. Primary ASS dialogue (pure Chinese)
      // When secondary subtitle has multiple lines (secCleanList.size > 1),
      // dynamically elevate primary subtitle MarginV so it stays cleanly above secondary subtitle.
      if (priText.isNotEmpty()) {
        val secLineCount = secCleanList.size
        // 56 ASS pixels per extra line of secondary subtitle ensures zero multi-line overlap
        val dynamicMarginV = if (secLineCount > 1) (secLineCount - 1) * 56 else 0
        priWriter.write("Dialogue: 0,$startAss,$endAss,Default,,0,0,$dynamicMarginV,,$priText")
        priWriter.newLine()
      }

      // 2. Secondary ASS dialogue (pure English)
      if (cleanedSec.isNotEmpty()) {
        secWriter.write("Dialogue: 0,$startAss,$endAss,Secondary,,0,0,0,,$cleanedSec")
        secWriter.newLine()
      }

      // 3. Unified bilingual ASS dialogue (Solution 1: flowing layout, zero overlap)
      if (priText.isNotEmpty() && cleanedSec.isNotEmpty()) {
        biWriter.write("Dialogue: 0,$startAss,$endAss,Default,,0,0,0,,$priText\\N{\\rSecondary}$cleanedSec")
        biWriter.newLine()
      } else if (priText.isNotEmpty()) {
        biWriter.write("Dialogue: 0,$startAss,$endAss,Default,,0,0,0,,$priText")
        biWriter.newLine()
      } else if (cleanedSec.isNotEmpty()) {
        biWriter.write("Dialogue: 0,$startAss,$endAss,Secondary,,0,0,0,,$cleanedSec")
        biWriter.newLine()
      }
    }
  }

  fun parseTimeToMs(timeStr: String): Long? {
    val clean = timeStr.trim()
    val parts = clean.split(":")
    if (parts.size != 3) return null
    val h = parts[0].toLongOrNull() ?: return null
    val m = parts[1].toLongOrNull() ?: return null
    val secParts = parts[2].split(",", ".")
    val s = secParts.getOrNull(0)?.toLongOrNull() ?: return null
    val subStr = secParts.getOrNull(1) ?: "0"
    val ms = when (subStr.length) {
      0 -> 0L
      1 -> (subStr.toLongOrNull() ?: 0L) * 100
      2 -> (subStr.toLongOrNull() ?: 0L) * 10
      else -> subStr.take(3).toLongOrNull() ?: 0L
    }
    return h * 3600000L + m * 60000L + s * 1000L + ms
  }

  fun msToAssTime(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0)
    val cs = (safeMs % 1000) / 10
    val totalSec = safeMs / 1000
    val s = totalSec % 60
    val totalMin = totalSec / 60
    val m = totalMin % 60
    val h = totalMin / 60
    return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
  }

  fun srtTimeToAss(srtTime: String): String {
    val ms = parseTimeToMs(srtTime) ?: return srtTime
    return msToAssTime(ms)
  }

  private fun writePrimaryAssHeader(
    writer: BufferedWriter,
    baseName: String,
    playResX: Int = 1920,
    playResY: Int = 1080,
  ) {
    writer.write("[Script Info]")
    writer.newLine()
    writer.write("Title: [中] $baseName")
    writer.newLine()
    writer.write("ScriptType: v4.00+")
    writer.newLine()
    writer.write("WrapStyle: 0")
    writer.newLine()
    writer.write("ScaledBorderAndShadow: yes")
    writer.newLine()
    writer.write("YCbCr Matrix: None")
    writer.newLine()
    writer.write("PlayResX: $playResX")
    writer.newLine()
    writer.write("PlayResY: $playResY")
    writer.newLine()
    writer.newLine()
    writer.write("[V4+ Styles]")
    writer.newLine()
    writer.write("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
    writer.newLine()
    writer.write("Style: Default,sans-serif,55,&H00FFFFFF,&H000000FF,&H00000000,&HFF000000,0,0,0,0,100,100,0,0,1,2.0,0,2,10,10,20,1")
    writer.newLine()
    writer.newLine()
    writer.write("[Events]")
    writer.newLine()
    writer.write("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
    writer.newLine()
  }

  /**
   * Writes a standard ASS header containing a dedicated 'Secondary' style definition.
   */
  private fun writeSecondaryAssHeader(
    writer: BufferedWriter,
    baseName: String,
    playResX: Int = 1920,
    playResY: Int = 1080,
  ) {
    writer.write("[Script Info]")
    writer.newLine()
    writer.write("Title: [英] $baseName")
    writer.newLine()
    writer.write("ScriptType: v4.00+")
    writer.newLine()
    writer.write("WrapStyle: 0")
    writer.newLine()
    writer.write("ScaledBorderAndShadow: yes")
    writer.newLine()
    writer.write("YCbCr Matrix: None")
    writer.newLine()
    writer.write("PlayResX: $playResX")
    writer.newLine()
    writer.write("PlayResY: $playResY")
    writer.newLine()
    writer.newLine()
    writer.write("[V4+ Styles]")
    writer.newLine()
    writer.write("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
    writer.newLine()
    writer.write("Style: Secondary,sans-serif,45,&H00FFFFFF,&H000000FF,&H00000000,&HFF000000,0,0,0,0,100,100,0,0,1,2.0,0,2,10,10,20,1")
    writer.newLine()
    writer.newLine()
    writer.write("[Events]")
    writer.newLine()
    writer.write("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
    writer.newLine()
  }

  /**
   * Writes a unified bilingual ASS header with both 'Default' (primary Chinese) and 'Secondary' (English) styles.
   */
  private fun writeBilingualAssHeader(
    writer: BufferedWriter,
    baseName: String,
    playResX: Int = 1920,
    playResY: Int = 1080,
  ) {
    writer.write("[Script Info]")
    writer.newLine()
    writer.write("Title: [双语] $baseName")
    writer.newLine()
    writer.write("ScriptType: v4.00+")
    writer.newLine()
    writer.write("WrapStyle: 0")
    writer.newLine()
    writer.write("ScaledBorderAndShadow: yes")
    writer.newLine()
    writer.write("YCbCr Matrix: None")
    writer.newLine()
    writer.write("PlayResX: $playResX")
    writer.newLine()
    writer.write("PlayResY: $playResY")
    writer.newLine()
    writer.newLine()
    writer.write("[V4+ Styles]")
    writer.newLine()
    writer.write("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
    writer.newLine()
    writer.write("Style: Default,sans-serif,55,&H00FFFFFF,&H000000FF,&H00000000,&HFF000000,0,0,0,0,100,100,0,0,1,2.0,0,2,10,10,20,1")
    writer.newLine()
    writer.write("Style: Secondary,sans-serif,45,&H00FFFFFF,&H000000FF,&H00000000,&HFF000000,0,0,0,0,100,100,0,0,1,2.0,0,2,10,10,20,1")
    writer.newLine()
    writer.newLine()
    writer.write("[Events]")
    writer.newLine()
    writer.write("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
    writer.newLine()
  }

  // ---------------------------------------------------------------------------
  // Plain-SRT cache management (shared with MkvSubtitleExtractor)
  // ---------------------------------------------------------------------------

  fun getCacheDir(context: Context): File {
    val dir = File(context.cacheDir, PLAIN_CACHE_DIR_NAME)
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  fun computeCacheKey(sourceId: String, sizeBytes: Long, lastModifiedMs: Long): String {
    val raw = "$sourceId|$sizeBytes|$lastModifiedMs|plain"
    val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.take(6).joinToString("") { "%02x".format(it) }
  }

  fun getEntryCacheDir(context: Context, key: String): File {
    val dir = File(getCacheDir(context), key)
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  fun sanitizeFileNamePart(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|\r\n]+"""), "_").trim().ifEmpty { "subtitle" }

  // ---------------------------------------------------------------------------
  // Plain-SRT cleaning, joining & writing helpers
  // ---------------------------------------------------------------------------

  /**
   * Strips MKV `ReadOrder,...` prefix (when [stripMatroskaPrefix] is true), ASS `{...}` tags,
   * HTML styling tags, bidi/BOM control characters, and splits into trimmed non-empty lines.
   * Returns empty list for ASS vector drawing blocks (`\p1`..`\p9`).
   */
  fun cleanPayloadToLines(
    rawPayload: String,
    stripMatroskaPrefix: Boolean = false,
    isAss: Boolean = false,
  ): List<String> {
    var payload = rawPayload
    if (stripMatroskaPrefix) {
      payload = stripMatroskaAssPrefix(payload)
    }
    if (ASS_DRAWING_TAG.containsMatchIn(payload)) {
      val strippedCheck = ASS_OVERRIDE_TAG.replace(payload, "").trim()
      if (strippedCheck.isEmpty() || Regex("""^[mlbscpa0-9.\s-]+$""", RegexOption.IGNORE_CASE).matches(strippedCheck)) {
        return emptyList()
      }
    }

    payload = payload
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .replace("\\N", "\n")
      .replace("\\n", "\n")
      .replace("\\h", " ")

    payload = ASS_OVERRIDE_TAG.replace(payload, "")
    payload = HTML_TAG.replace(payload, "")
    if (!isAss) {
      payload = VTT_POSITION_TAG.replace(payload, "")
    }
    payload = CONTROL_AND_BIDI_CHARS.replace(payload, "")

    return payload
      .split('\n')
      .map { WHITESPACE_RUN.replace(it, " ").trim() }
      .filter { it.isNotEmpty() }
  }

  fun stripMatroskaAssPrefix(raw: String): String {
    val normalized = raw.trimStart('\uFEFF')
    MATROSKA_ASS_PREFIX.matchEntire(normalized)?.let { return it.groupValues[1] }
    if (Regex("""^\d+,\d+,""").containsMatchIn(normalized)) {
      var commas = 0
      for (i in normalized.indices) {
        if (normalized[i] == ',') {
          commas++
          if (commas == 8) return normalized.substring(i + 1)
        } else if (normalized[i] == '\n') {
          break
        }
      }
    }
    return raw
  }

  /**
   * Joins wrapped lines belonging to the SAME language into one clean logical line, preserving
   * explicit `\n` ONLY between genuine two-speaker dialogue turns (`- Speaker A\n- Speaker B`).
   * Avoids inserting spaces between adjacent CJK characters while joining Latin phrases with `" "`.
   */
  fun joinNormalizedLines(lines: List<String>): String {
    if (lines.isEmpty()) return ""
    if (lines.size == 1) return lines[0]

    val sb = StringBuilder(lines[0])
    for (i in 1 until lines.size) {
      val prev = lines[i - 1]
      val next = lines[i]
      val isTwoSpeakerTurn =
        SPEAKER_DASH_START.containsMatchIn(next) &&
          (i == 1 && SPEAKER_LABEL_LINE.containsMatchIn(prev) || SPEAKER_DASH_START.containsMatchIn(prev))

      if (isTwoSpeakerTurn) {
        sb.append('\n').append(next)
      } else {
        val prevLast = prev.lastOrNull()
        val nextFirst = next.firstOrNull()
        val needSpace =
          prevLast != null &&
            nextFirst != null &&
            !(isCjkOrCjkPunct(prevLast) || isCjkOrCjkPunct(nextFirst))
        if (needSpace) sb.append(' ')
        sb.append(next)
      }
    }
    return sb.toString().trim()
  }

  /**
   * Deduplicates identical simultaneous cues (common in multi-layer ASS files) and snaps minor
   * overlapping cue end times so MPV never stacks two consecutive cues on top of each other.
   */
  fun normalizeMonolingualCueList(cues: List<PlainSubtitleCue>): List<PlainSubtitleCue> {
    if (cues.isEmpty()) return emptyList()
    val sorted = cues.sortedWith(compareBy<PlainSubtitleCue> { it.startMs }.thenBy { it.endMs })
    val merged = ArrayList<PlainSubtitleCue>(sorted.size)

    for (cue in sorted) {
      if (cue.text.isBlank()) continue
      val prev = merged.lastOrNull()
      if (prev != null && kotlin.math.abs(prev.startMs - cue.startMs) <= 80L && kotlin.math.abs(prev.endMs - cue.endMs) <= 80L) {
        if (prev.text == cue.text) {
          continue
        } else {
          val joined = joinNormalizedLines(listOf(prev.text, cue.text))
          merged[merged.lastIndex] = prev.copy(endMs = max(prev.endMs, cue.endMs), text = joined)
          continue
        }
      }
      merged.add(cue)
    }

    for (i in 0 until merged.lastIndex) {
      val curr = merged[i]
      val next = merged[i + 1]
      if (curr.endMs > next.startMs) {
        val visibleIfTrimmed = next.startMs - curr.startMs
        if (visibleIfTrimmed >= MIN_CUE_MS) {
          merged[i] = curr.copy(endMs = next.startMs)
        }
      }
    }
    return merged
  }

  /**
   * Writes clean, pure UTF-8 standard `.srt` cues with zero formatting/positioning hacks.
   * Uses atomic write-then-rename so interrupted runs never poison cache files.
   */
  fun writeSrtFile(cues: List<PlainSubtitleCue>, outFile: File): Boolean {
    val validCues = cues.filter { it.text.isNotBlank() }
    if (validCues.isEmpty()) return false
    outFile.parentFile?.mkdirs()
    val tmpFile = File(outFile.parentFile, "${outFile.name}.tmp")
    return try {
      BufferedWriter(OutputStreamWriter(FileOutputStream(tmpFile), Charsets.UTF_8)).use { w ->
        var idx = 1
        for (cue in validCues) {
          val safeEnd = max(cue.endMs, cue.startMs + MIN_CUE_MS)
          w.write(idx.toString())
          w.newLine()
          w.write("${msToSrtTime(cue.startMs)} --> ${msToSrtTime(safeEnd)}")
          w.newLine()
          w.write(cue.text)
          w.newLine()
          w.newLine()
          idx++
        }
      }
      if (outFile.exists()) outFile.delete()
      tmpFile.renameTo(outFile) || run {
        tmpFile.copyTo(outFile, overwrite = true)
        tmpFile.delete()
        true
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed writing SRT file ${outFile.name}", e)
      runCatching { tmpFile.delete() }
      false
    }
  }

  fun isValidSrtFile(file: File?): Boolean {
    if (file == null || !file.exists() || file.length() < 12L) return false
    return runCatching {
      file.bufferedReader(Charsets.UTF_8).use { r ->
        var checked = 0
        while (checked < 30) {
          val line = r.readLine() ?: break
          if (line.contains("-->")) return true
          checked++
        }
        false
      }
    }.getOrDefault(false)
  }

  fun msToSrtTime(ms: Long): String {
    val safe = max(0L, ms)
    val h = safe / 3_600_000L
    val m = (safe % 3_600_000L) / 60_000L
    val s = (safe % 60_000L) / 1000L
    val mmm = safe % 1000L
    return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, mmm)
  }

  fun containsCjk(text: String): Boolean = text.any { isCjkChar(it) }

  private fun containsLetter(text: String): Boolean = text.any { it.isLetter() }

  private fun isCjkChar(c: Char): Boolean {
    val code = c.code
    return code in 0x4E00..0x9FFF ||
      code in 0x3400..0x4DBF ||
      code in 0x3040..0x30FF ||
      code in 0xAC00..0xD7AF
  }

  private fun isCjkOrCjkPunct(c: Char): Boolean {
    if (isCjkChar(c)) return true
    val code = c.code
    return code in 0x3000..0x303F || code in 0xFF00..0xFFEF
  }

  // ---------------------------------------------------------------------------
  // Plain-SRT cue extraction (SRT / VTT / ASS) & cache processor
  // ---------------------------------------------------------------------------

  private data class RawCue(
    val startMs: Long,
    val endMs: Long,
    val rawPayload: String,
    val fromAss: Boolean,
  )

  private data class ClassifiedCue(
    val startMs: Long,
    val endMs: Long,
    val primaryLines: List<String>,
    val secondaryLines: List<String>,
    val primaryText: String,
    val secondaryText: String,
    val wasDirty: Boolean,
  )

  private fun classifyRawCue(raw: RawCue): ClassifiedCue? {
    val lines = cleanPayloadToLines(raw.rawPayload, stripMatroskaPrefix = false, isAss = raw.fromAss)
    if (lines.isEmpty()) return null

    val hadFormattingNoise =
      lines.size > 1 ||
        raw.rawPayload.contains('\n') ||
        raw.rawPayload.contains('\r') ||
        ASS_OVERRIDE_TAG.containsMatchIn(raw.rawPayload) ||
        HTML_TAG.containsMatchIn(raw.rawPayload) ||
        CONTROL_AND_BIDI_CHARS.containsMatchIn(raw.rawPayload)

    val priLines = mutableListOf<String>()
    val secLines = mutableListOf<String>()

    for (line in lines) {
      if (containsCjk(line)) {
        val inlineSplit = trySplitInlineBilingualLine(line)
        if (inlineSplit != null) {
          priLines.add(inlineSplit.first)
          secLines.add(inlineSplit.second)
        } else {
          priLines.add(line)
        }
      } else {
        secLines.add(line)
      }
    }

    val priText = joinNormalizedLines(priLines)
    val secText = joinNormalizedLines(secLines)
    if (priText.isEmpty() && secText.isEmpty()) return null

    return ClassifiedCue(
      startMs = raw.startMs,
      endMs = max(raw.endMs, raw.startMs + MIN_CUE_MS),
      primaryLines = priLines,
      secondaryLines = secLines,
      primaryText = priText,
      secondaryText = secText,
      wasDirty = hadFormattingNoise,
    )
  }

  private fun trySplitInlineBilingualLine(line: String): Pair<String, String>? {
    val sep = when {
      line.contains('\t') -> "\t"
      line.contains(" | ") -> " | "
      else -> return null
    }
    val idx = line.indexOf(sep)
    if (idx <= 0) return null
    val left = line.substring(0, idx).trim()
    val right = line.substring(idx + sep.length).trim()
    if (left.isEmpty() || right.length < 2) return null
    if (containsCjk(left) && !containsCjk(right) && containsLetter(right)) {
      return left to right
    }
    return null
  }

  private fun extractRawSrtOrVttCues(rawText: String): List<RawCue> {
    val lines = rawText
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .split('\n')

    val result = mutableListOf<RawCue>()
    var i = 0
    while (i < lines.size) {
      val trimmed = lines[i].trim()
      val match = SRT_TIMING_REGEX.find(trimmed)
      if (match == null) {
        i++
        continue
      }

      val startMs = parseSrtRegexTime(match, groupOffset = 1)
      val endMs = parseSrtRegexTime(match, groupOffset = 5)
      i++

      val body = StringBuilder()
      while (i < lines.size) {
        val nextLine = lines[i]
        val nextTrim = nextLine.trim()
        if (nextTrim.isEmpty()) {
          i++
          break
        }
        if (nextTrim.all { it.isDigit() } && i + 1 < lines.size && SRT_TIMING_REGEX.containsMatchIn(lines[i + 1])) {
          break
        }
        if (SRT_TIMING_REGEX.containsMatchIn(nextTrim)) {
          break
        }
        if (body.isNotEmpty()) body.append('\n')
        body.append(nextLine)
        i++
      }

      val payload = body.toString().trim()
      if (startMs >= 0 && payload.isNotEmpty()) {
        result.add(RawCue(startMs, max(endMs, startMs + MIN_CUE_MS), payload, fromAss = false))
      }
    }
    return result
  }

  private fun extractRawAssCues(rawText: String): List<RawCue> {
    val lines = rawText.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val result = mutableListOf<RawCue>()
    var inEvents = false
    var startIdx = 1
    var endIdx = 2
    var textIdx = 9
    var formatColumns = 10

    for (line in lines) {
      val trimmed = line.trim()
      if (trimmed.startsWith("[")) {
        inEvents = trimmed.equals("[Events]", ignoreCase = true)
        continue
      }
      if (!inEvents) continue

      if (trimmed.startsWith("Format:", ignoreCase = true)) {
        val cols = trimmed.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
        formatColumns = cols.size
        startIdx = cols.indexOf("start").takeIf { it >= 0 } ?: 1
        endIdx = cols.indexOf("end").takeIf { it >= 0 } ?: 2
        textIdx = cols.indexOf("text").takeIf { it >= 0 } ?: (cols.size - 1)
        continue
      }

      if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) continue
      val afterColon = trimmed.substringAfter(':').trimStart()
      val parts = afterColon.split(',', limit = max(formatColumns, textIdx + 1))
      if (parts.size <= max(max(startIdx, endIdx), textIdx)) continue

      val startMs = parseAssTime(parts[startIdx].trim())
      val endMs = parseAssTime(parts[endIdx].trim())
      val textPayload = parts[textIdx]
      if (startMs >= 0 && textPayload.isNotBlank()) {
        result.add(RawCue(startMs, max(endMs, startMs + MIN_CUE_MS), textPayload, fromAss = true))
      }
    }
    return result
  }

  private fun parseSrtRegexTime(m: MatchResult, groupOffset: Int): Long {
    val hasHour = !m.groupValues[groupOffset].isNullOrEmpty()
    val h = if (hasHour) m.groupValues[groupOffset].trimEnd(':').toLongOrNull() ?: 0L else 0L
    val min = m.groupValues[groupOffset + 1].toLongOrNull() ?: 0L
    val sec = m.groupValues[groupOffset + 2].toLongOrNull() ?: 0L
    val fracRaw = m.groupValues[groupOffset + 3]
    val ms = when (fracRaw.length) {
      1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
      2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
      else -> fracRaw.take(3).toLongOrNull() ?: 0L
    }
    return ((h * 3600L) + (min * 60L) + sec) * 1000L + ms
  }

  private fun parseAssTime(timeStr: String): Long {
    val m = ASS_TIME_REGEX.find(timeStr.trim()) ?: return -1L
    val h = m.groupValues[1].toLongOrNull() ?: 0L
    val min = m.groupValues[2].toLongOrNull() ?: 0L
    val s = m.groupValues[3].toLongOrNull() ?: 0L
    val frac = m.groupValues[4]
    val ms = if (frac.length == 2) (frac.toLongOrNull() ?: 0L) * 10L else (frac.take(3).toLongOrNull() ?: 0L)
    return ((h * 3600L) + (min * 60L) + s) * 1000L + ms
  }

  /**
   * Pure processor that turns raw subtitle text into standardized single-line `.srt` cache files.
   *
   * - Bilingual SRT/VTT/ASS -> produces `[中] <base>.srt` and `[英] <base>.srt`.
   * - Monolingual ASS/SSA or messy multi-line SRT/VTT -> normalizes into `<base>.normalized.srt`.
   * - Already-clean single-line monolingual SRT -> returns null so the original file is used directly.
   */
  fun processTextToCache(
    rawText: String,
    ext: String,
    baseName: String,
    targetDir: File,
  ): MkvExtractionResult? {
    if (!targetDir.exists()) targetDir.mkdirs()
    val cleanBase = sanitizeFileNamePart(baseName)
    val isAss = ext == "ass" || ext == "ssa"

    val rawCues = if (isAss) extractRawAssCues(rawText) else extractRawSrtOrVttCues(rawText)
    if (rawCues.isEmpty()) return null

    val classified = rawCues.mapNotNull { classifyRawCue(it) }
    if (classified.isEmpty()) return null

    val bilingualCount = classified.count { it.primaryText.isNotEmpty() && it.secondaryText.isNotEmpty() }
    val isBilingual = bilingualCount >= 3 || (bilingualCount >= 1 && bilingualCount * 4 >= classified.size)

    if (isBilingual) {
      val priCues = normalizeMonolingualCueList(
        classified.mapNotNull {
          if (it.primaryText.isNotEmpty()) PlainSubtitleCue(it.startMs, it.endMs, it.primaryText) else null
        },
      )
      val secCues = normalizeMonolingualCueList(
        classified.mapNotNull {
          if (it.secondaryText.isNotEmpty()) PlainSubtitleCue(it.startMs, it.endMs, it.secondaryText) else null
        },
      )

      val priFile = File(targetDir, "[中] $cleanBase.srt")
      val secFile = File(targetDir, "[英] $cleanBase.srt")

      val okPri = priCues.isNotEmpty() && writeSrtFile(priCues, priFile)
      val okSec = secCues.isNotEmpty() && writeSrtFile(secCues, secFile)

      if (!okPri && !okSec) return null
      return MkvExtractionResult(
        chineseFile = priFile.takeIf { okPri },
        englishFile = secFile.takeIf { okSec },
        standardFile = null,
        baseName = cleanBase,
      )
    }

    // For monolingual files: always normalize ASS/SSA files (to strip PlayResX/Y canvas, styles,
    // and {\pos}/{\an} tags) and any SRT/VTT files that contain multi-line breaks or tag noise.
    val needsNormalization = isAss || classified.any { it.wasDirty }
    if (!needsNormalization) return null

    val rawNormalized = classified.mapNotNull { cue ->
      val combined = joinNormalizedLines(
        (cue.primaryLines + cue.secondaryLines).filter { it.isNotEmpty() },
      )
      if (combined.isEmpty()) null else PlainSubtitleCue(cue.startMs, cue.endMs, combined)
    }
    val normalizedCues = normalizeMonolingualCueList(rawNormalized)
    if (normalizedCues.isEmpty()) return null

    val stdFile = File(targetDir, "$cleanBase.normalized.srt")
    if (!writeSrtFile(normalizedCues, stdFile)) return null

    return MkvExtractionResult(
      chineseFile = null,
      englishFile = null,
      standardFile = stdFile,
      baseName = cleanBase,
    )
  }

  // ---------------------------------------------------------------------------
  // Dynamic Secondary Wrap Line Estimator & Primary Lift Calculator
  // ---------------------------------------------------------------------------

  /**
   * Estimates how many visual lines `libass` will render for [secondaryText] on the current
   * player viewport (`viewWidthPx` x `viewHeightPx`).
   *
   * Key calibration against `libass` (`ass_font.c`):
   * In `libass`, `Fontsize` in `PlayResY = 720` specifies the full `(ascender - descender)` height,
   * whereas Android `Paint.setTextSize(S)` sets the EM-square (`descent - ascent ≈ 1.17 * S`).
   * We calibrate `Paint.textSize` so `descent - ascent` equals the exact `libass` font size in
   * `PlayResY = 720` units. This guarantees single-line English sentences are NEVER misclassified
   * as 2 lines, while genuine multi-line wrapped sentences on narrow/portrait screens return `>= 2`.
   */
  fun estimateSecondaryVisualLineCount(
    secondaryText: String?,
    viewWidthPx: Int,
    viewHeightPx: Int,
    preferences: SubtitlesPreferences,
  ): Int {
    if (secondaryText.isNullOrBlank()) return 0
    val cleanedLines = cleanPayloadToLines(secondaryText, stripMatroskaPrefix = false, isAss = false)
    if (cleanedLines.isEmpty()) return 0

    val safeW = if (viewWidthPx > 0) viewWidthPx.toFloat() else 1920f
    val safeH = if (viewHeightPx > 0) viewHeightPx.toFloat() else 1080f
    val aspect = (safeW / safeH).coerceIn(0.4f, 3.5f)
    val playResX = MPV_PLAY_RES_Y * aspect
    val maxLineWidthPlayRes = (playResX - MPV_DEFAULT_MARGIN_LR).coerceAtLeast(180f)

    val multiLayout = preferences.layoutProfileFor(SubtitleMode.Multi)
    val secProfile = preferences.secondaryProfile()
    val secFontSizePlayRes =
      (secProfile.fontSize.get().toFloat() * multiLayout.subScale.get() * preferences.secondarySubScale.get())
        .coerceIn(12f, 160f)

    var totalVisualLines = 0
    for (paragraph in cleanedLines) {
      val lineWidthPlayRes = measureTextWidthInPlayRes(
        text = paragraph,
        secFontSizePlayRes = secFontSizePlayRes,
        bold = secProfile.bold.get(),
        italic = secProfile.italic.get(),
      )
      // Allow a 4% kerning/margin tolerance so borderline single-line sentences that libass
      // keeps on 1 line never falsely trigger a lift.
      val wrappedCount = if (lineWidthPlayRes <= maxLineWidthPlayRes * 1.04f) {
        1
      } else {
        ceil(lineWidthPlayRes / maxLineWidthPlayRes).toInt().coerceAtLeast(2)
      }
      totalVisualLines += wrappedCount
    }
    return totalVisualLines.coerceIn(1, 5)
  }

  private fun measureTextWidthInPlayRes(
    text: String,
    secFontSizePlayRes: Float,
    bold: Boolean,
    italic: Boolean,
  ): Float {
    // Try native Android Skia Paint first, calibrated to libass (ascender - descender = Fontsize).
    val paintMeasured = runCatching {
      val paint = Paint(Paint.ANTI_ALIAS_FLAG)
      val style = when {
        bold && italic -> Typeface.BOLD_ITALIC
        bold -> Typeface.BOLD
        italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
      }
      paint.typeface = Typeface.create(Typeface.SANS_SERIF, style)
      paint.textSize = secFontSizePlayRes
      val fm = paint.fontMetrics
      val metricsHeight = if (fm != null) (fm.descent - fm.ascent) else 0f
      if (metricsHeight > 1f) {
        paint.textSize = secFontSizePlayRes * (secFontSizePlayRes / metricsHeight)
      }
      paint.measureText(text)
    }.getOrDefault(0f)

    if (paintMeasured > 0f) {
      return paintMeasured
    }

    // Fallback for local JVM unit tests where android.graphics.Paint is unmocked and returns 0f:
    // uses calibrated average glyph advance in libass PlayResY=720 units (em ≈ Fontsize / 1.17).
    val emSize = secFontSizePlayRes / 1.17f
    var width = 0f
    for (ch in text) {
      width += when {
        isCjkOrCjkPunct(ch) -> emSize * 1.0f
        ch == ' ' -> emSize * 0.28f
        ch in "ilI1.,:;!|'" -> emSize * 0.28f
        ch in "mwMW@%" -> emSize * 0.78f
        ch.isUpperCase() -> emSize * 0.60f
        else -> emSize * 0.48f
      }
    }
    if (bold) width *= 1.05f
    return width
  }

  /**
   * Computes the effective `sub-pos` for the primary subtitle track (`sid`).
   *
   * - When in `Single` mode, or when no secondary subtitle is active, or when the current
   *   secondary subtitle fits on **1 line**, returns the exact user-configured `subPos`
   *   (untouched).
   * - When in `Multi` mode and the current secondary subtitle wraps onto `N >= 2` visual lines,
   *   lifts `sub-pos` upward by `(N - 1)` secondary line heights so the bottom-anchored multi-line
   *   secondary subtitle never collides with the primary subtitle above it.
   */
  fun computeEffectivePrimarySubPos(
    preferences: SubtitlesPreferences,
    mode: SubtitleMode = preferences.subtitleMode.get(),
    secondaryText: String? = null,
    viewWidthPx: Int = 1920,
    viewHeightPx: Int = 1080,
    isSecondaryActive: Boolean = false,
  ): Int {
    val layout = preferences.layoutProfileFor(mode)
    val basePos = layout.subPos.get()
    if (mode != SubtitleMode.Multi || !isSecondaryActive || secondaryText.isNullOrBlank()) {
      return basePos.roundToInt().coerceIn(0, 150)
    }

    val visualLines = estimateSecondaryVisualLineCount(
      secondaryText = secondaryText,
      viewWidthPx = viewWidthPx,
      viewHeightPx = viewHeightPx,
      preferences = preferences,
    )
    if (visualLines <= 1) {
      return basePos.roundToInt().coerceIn(0, 150)
    }

    val extraLines = (visualLines - 1).coerceIn(1, 4)
    val secFontSizePlayRes =
      (preferences.secondaryFontSize.get().toFloat() * layout.subScale.get() * preferences.secondarySubScale.get())
        .coerceIn(12f, 160f)
    // Each wrapped secondary line takes (secFontSizePlayRes * 1.02 / 720) * 100 percent of screen height (~6.4% at 45pt)
    val liftPerExtraLinePercent = (secFontSizePlayRes * 1.02f / MPV_PLAY_RES_Y) * 100f
    val liftedPos = basePos - (extraLines * liftPerExtraLinePercent)
    return liftedPos.roundToInt().coerceIn(10, 150)
  }

  // ---------------------------------------------------------------------------
  // Runtime MPV Subtitle Options Application
  // ---------------------------------------------------------------------------

  /**
   * Computes all MPV subtitle options for [mode] as a uniform list so both initial
   * `MPVLib.setOptionString` and runtime `MPVLib.setPropertyString` share one source of truth.
   *
   * Primary options come from `primaryProfileFor(mode)` / `layoutProfileFor(mode)`; the secondary
   * subtitle keeps the baseline mechanism (`secondary-sub-ass-override=scale` +
   * `sub-ass-style-overrides` targeting the generated `Secondary` ASS style).
   */
  fun buildMpvSubtitleOptions(
    preferences: SubtitlesPreferences,
    mode: SubtitleMode = preferences.subtitleMode.get(),
    secondaryText: String? = null,
    viewWidthPx: Int = 0,
    viewHeightPx: Int = 0,
    isSecondaryTrackActive: Boolean = false,
  ): List<Pair<String, String>> {
    val primary = preferences.primaryProfileFor(mode)
    val layout = preferences.layoutProfileFor(mode)

    val scaleByWin = if (layout.scaleByWindow.get()) "yes" else "no"
    val borderStyle = primary.borderStyle.get()
    val effectiveBg = primary.backgroundColor.get()
    val assOverride = layout.overrideAssSubs.get().value
    val borderSizeStr = String.format(Locale.US, "%.2f", primary.borderSize.get())

    val effectivePrimaryPos = computeEffectivePrimarySubPos(
      preferences = preferences,
      mode = mode,
      secondaryText = secondaryText,
      viewWidthPx = viewWidthPx,
      viewHeightPx = viewHeightPx,
      isSecondaryActive = isSecondaryTrackActive,
    )

    // --- Secondary subtitle style overrides (baseline mechanism, mode-aware primary inputs) ---
    val secProfile = preferences.secondaryProfile()
    val secBold = secProfile.bold.get()
    val secItalic = secProfile.italic.get()
    val secFont = secProfile.font.get()
    val secFontSize = secProfile.fontSize.get()
    val secScale = preferences.secondarySubScale.get()
    val secBorderStyle = secProfile.borderStyle.get()
    val secBorderSize = secProfile.borderSize.get()
    val secShadowOffset = secProfile.shadowOffset.get()
    val secTextColor = secProfile.textColor.get()
    val secBorderColor = secProfile.borderColor.get()
    val secBgColor = secProfile.backgroundColor.get()
    val secJustification = secProfile.justification.get()

    // Calculate effective font size taking scale into account
    val effectiveSecFontSize = (secFontSize * secScale).roundToInt().coerceAtLeast(1)

    // In ASS: 1 = bottom-left, 2 = bottom-center, 3 = bottom-right
    val secAlignment = when (secJustification) {
      SubtitleJustification.Left -> 1
      SubtitleJustification.Right -> 3
      else -> 2
    }

    // BorderStyle: 1 = outline + shadow, 3 = opaque background box
    val secAssBorderStyle = if (secBorderStyle == SubtitlesBorderStyle.OpaqueBox) 3 else 1
    val assFont = if (secFont.isNotBlank() && secFont != "Default") secFont else "sans-serif"

    // Update vertical position based on line spacing relative to the current mode's primary sub position
    val spacing = preferences.secondarySubSpacing.get()
    val secPos = (layout.subPos.get() - spacing).coerceIn(0f, 150f)
    preferences.secondarySubPos.set(secPos)

    // In mpv, secondary-sub-pos is an integer percentage (0-150) of screen height.
    // To provide truly continuous 0.1-level precision on screen, we split secPos into:
    // 1) Integer baseline: secPosInt = secPos.roundToInt()
    // 2) Fractional difference: diff = secPos - secPosInt (-0.5 to +0.5)
    // In 1080p ASS, 1% of screen height is ~10.8 pixels.
    // For bottom-aligned ASS subtitles (Alignment 2), higher MarginV moves text UP,
    // while higher sub-pos moves text DOWN.
    // Therefore, if diff > 0 (secPos > secPosInt, text should move lower), MarginV decreases.
    val secPosInt = secPos.roundToInt()
    val diff = secPos - secPosInt.toFloat()
    val pixelOffset = -(diff * 10.8f).roundToInt()
    val baseMarginV = 20
    val effectiveMarginV = (baseMarginV + pixelOffset).coerceAtLeast(0)

    val styleOverrides = listOf(
      "Secondary.Fontname=$assFont",
      "Secondary.Fontsize=$effectiveSecFontSize",
      "Secondary.PrimaryColour=${secTextColor.toAssColorString()}",
      "Secondary.OutlineColour=${secBorderColor.toAssColorString()}",
      "Secondary.BackColour=${secBgColor.toAssColorString()}",
      "Secondary.Bold=${if (secBold) 1 else 0}",
      "Secondary.Italic=${if (secItalic) 1 else 0}",
      "Secondary.Outline=$secBorderSize",
      "Secondary.Shadow=$secShadowOffset",
      "Secondary.BorderStyle=$secAssBorderStyle",
      "Secondary.Alignment=$secAlignment",
      "Secondary.MarginV=$effectiveMarginV",
    ).joinToString(",")

    // Dynamic secondary-sub-scale ratio so any secondary track accurately reflects the user's
    // secondary font size relative to the CURRENT mode's primary font size.
    val priFontSize = primary.fontSize.get().coerceAtLeast(1)
    val effectiveScaleRatio = (secFontSize.toFloat() / priFontSize.toFloat()) * secScale

    return listOf(
      "sub-font" to primary.font.get(),
      "sub-font-size" to primary.fontSize.get().toString(),
      "sub-bold" to if (primary.bold.get()) "yes" else "no",
      "sub-italic" to if (primary.italic.get()) "yes" else "no",
      "sub-justify" to primary.justification.get().value,
      "sub-border-style" to borderStyle.value,
      "sub-border-size" to borderSizeStr,
      "sub-outline-size" to borderSizeStr,
      "sub-shadow-offset" to primary.shadowOffset.get().toString(),
      "sub-color" to primary.textColor.get().toMpvColorHex(),
      "sub-back-color" to effectiveBg.toMpvColorHex(),
      "sub-border-color" to primary.borderColor.get().toMpvColorHex(),
      "sub-scale" to String.format(Locale.US, "%.3f", layout.subScale.get()),
      "sub-pos" to effectivePrimaryPos.toString(),
      "sub-line-spacing" to layout.subSpacing.get().toString(),
      "sub-ass-line-spacing" to layout.subSpacing.get().toString(),
      "sub-ass-justify" to if (assOverride == "force" || assOverride == "strip") "yes" else "no",
      "sub-scale-by-window" to scaleByWin,
      "sub-use-margins" to scaleByWin,
      "sub-ass-scale-with-window" to scaleByWin,
      "sub-ass-force-margins" to scaleByWin,
      "sub-ass-override" to assOverride,
      "secondary-sub-ass-override" to "scale",
      "secondary-sub-pos" to secPosInt.toString(),
      "secondary-sub-scale" to String.format(Locale.US, "%.3f", effectiveScaleRatio),
      "sub-ass-style-overrides" to styleOverrides,
      // Safely forward secondary-sub-* properties supported by newer mpv builds
      "secondary-sub-font" to assFont,
      "secondary-sub-color" to secTextColor.toAssColorString(),
      "secondary-sub-border-color" to secBorderColor.toAssColorString(),
      "secondary-sub-border-size" to secBorderSize.toString(),
      "secondary-sub-bold" to if (secBold) "yes" else "no",
      "secondary-sub-italic" to if (secItalic) "yes" else "no",
    )
  }

  /**
   * Applies pre-init MPV options for the currently active subtitle mode.
   */
  fun applyStartupSubtitleOptions(
    preferences: SubtitlesPreferences,
    mode: SubtitleMode = preferences.subtitleMode.get(),
  ) {
    for ((key, value) in buildMpvSubtitleOptions(preferences, mode = mode)) {
      runCatching { MPVLib.setOptionString(key, value) }
    }
  }

  /**
   * Applies runtime MPV subtitle properties for [mode], cleanly isolating Single-language
   * preferences from Dual-language preferences.
   */
  fun applySubtitlePreferencesForMode(
    preferences: SubtitlesPreferences,
    mode: SubtitleMode = preferences.subtitleMode.get(),
    secondaryText: String? = null,
    viewWidthPx: Int = 0,
    viewHeightPx: Int = 0,
    isSecondaryTrackActive: Boolean = false,
  ) {
    for ((key, value) in buildMpvSubtitleOptions(
      preferences = preferences,
      mode = mode,
      secondaryText = secondaryText,
      viewWidthPx = viewWidthPx,
      viewHeightPx = viewHeightPx,
      isSecondaryTrackActive = isSecondaryTrackActive,
    )) {
      runCatching { MPVLib.setPropertyString(key, value) }
    }
  }

  private fun readTextWithAutoEncoding(bytes: ByteArray): String {
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
      return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
      return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
      return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }

    return try {
      val decoder = Charsets.UTF_8.newDecoder()
      decoder.onMalformedInput(CodingErrorAction.REPORT)
      decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
      decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) {
      try {
        String(bytes, Charset.forName("GB18030"))
      } catch (e2: Exception) {
        String(bytes, Charsets.UTF_8)
      }
    }
  }
}

/**
 * Converts an Android ARGB Int color into ASS/SSA color format (&HAABBGGRR).
 * Note: ASS uses inverse alpha (00 = fully opaque, FF = fully transparent)
 * and BGR channel ordering.
 */
fun Int.toAssColorString(): String {
  val a = (this shr 24) and 0xFF
  val r = (this shr 16) and 0xFF
  val g = (this shr 8) and 0xFF
  val b = this and 0xFF
  val assAlpha = 255 - a
  return String.format(Locale.US, "&H%02X%02X%02X%02X", assAlpha, b, g, r)
}

/**
 * Formats an Android/Compose ARGB Int color (`0xAARRGGBB`) into `#AARRGGBB` for mpv options.
 */
fun Int.toMpvColorHex(): String = String.format(Locale.US, "#%08X", this)

/**
 * Legacy entry point kept for all existing call sites: applies the full runtime MPV subtitle
 * option set (primary profile of the current mode + the baseline `Secondary` style overrides)
 * via [BilingualSubtitleParser.applySubtitlePreferencesForMode].
 */
fun applySecondarySubStyleOverrides(preferences: SubtitlesPreferences) {
  BilingualSubtitleParser.applySubtitlePreferencesForMode(preferences)
}
