package app.marlboroadvance.mpvex.utils.media

import android.content.Context
import android.net.Uri
import android.util.Log
import app.marlboroadvance.mpvex.preferences.SubtitleJustification
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.player.controls.components.panels.SubtitlesBorderStyle
import `is`.xyz.mpv.MPVLib
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
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

  private val CJK_REGEX = Regex("[\\u4e00-\\u9fff\\u3400-\\u4dbf]")
  private val LATIN_REGEX = Regex("[a-zA-Z]")
  private val TAG_REGEX = Regex("\\{[^}]*\\}")
  private val ASS_SPLIT_REGEX = Regex("\\\\[Nn]")

  /**
   * Cleans inline ASS override tags from secondary subtitles so mpv's
   * secondary-sub-* properties can style font size, color, border, and position.
   */
  private val ASS_OVERRIDE_TAGS_REGEX =
    Regex("\\\\(fs\\d+|fn[^\\\\}]+|3c&H[0-9a-fA-F]+&|[1234]?c&H[0-9a-fA-F]+&|b[01]|shad\\d+|bord\\d+)")

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

    val cacheDir = File(context.cacheDir, "bilingual_subs_v2")
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

      val cacheDir = File(context.cacheDir, "bilingual_subs_v2")
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
    val playResX = Regex("PlayResX:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 1920
    val playResY = Regex("PlayResY:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 1080

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
      }.map { it.replace(Regex("<[^>]*>"), "").trim() }.filter { it.isNotEmpty() }

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
        val pClean = TAG_REGEX.replace(p, "").trim()
        if (pClean.isEmpty()) continue
        val hasCjk = CJK_REGEX.containsMatchIn(pClean)
        val hasLatin = LATIN_REGEX.containsMatchIn(pClean)

        if (hasCjk) {
          priParts.add(cleanAssOverrideTags(p))
        } else if (hasLatin) {
          secParts.add(cleanAssOverrideTags(p))
        } else {
          if (priParts.isNotEmpty()) {
            priParts.add(cleanAssOverrideTags(p))
          } else if (secParts.isNotEmpty()) {
            secParts.add(cleanAssOverrideTags(p))
          } else {
            priParts.add(cleanAssOverrideTags(p))
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

      val priText = if (cue.priParts.isNotEmpty()) cue.priParts.joinToString("\\N") else ""
      val cleanedSec = if (cue.secParts.isNotEmpty()) {
        cue.secParts
          .map { TAG_REGEX.replace(it, "").replace("\\h", " ").trim() }
          .filter { it.isNotEmpty() }
          .joinToString("\\N")
      } else ""

      // 1. Primary ASS dialogue (pure Chinese)
      if (priText.isNotEmpty()) {
        priWriter.write("Dialogue: 0,$startAss,$endAss,Default,,0,0,0,,$priText")
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

  private fun cleanAssOverrideTags(text: String): String {
    var cleaned = ASS_OVERRIDE_TAGS_REGEX.replace(text, "")
    cleaned = cleaned.replace(Regex("\\{\\s*\\}"), "")
    return cleaned.trim()
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
 * Applies libass style overrides to mpv via the `sub-ass-style-overrides` property,
 * targeting the dedicated `Secondary` style defined in bilingual subtitles generated by
 * [BilingualSubtitleParser].
 *
 * libmpv does not support native `secondary-sub-font`, `secondary-sub-font-size`,
 * `secondary-sub-color`, etc. (which fail silently). Instead, we configure mpv with
 * `secondary-sub-ass-override=scale` and pass `Style.Property=Value` overrides into
 * mpv's native `sub-ass-style-overrides` option.
 */
fun applySecondarySubStyleOverrides(preferences: SubtitlesPreferences) {
  val isBold = preferences.secondaryBold.get()
  val isItalic = preferences.secondaryItalic.get()
  val font = preferences.secondaryFont.get()
  val fontSize = preferences.secondaryFontSize.get()
  val subScale = preferences.secondarySubScale.get()
  val borderStyle = preferences.secondaryBorderStyle.get()
  val borderSize = preferences.secondaryBorderSize.get()
  val shadowOffset = preferences.secondaryShadowOffset.get()
  val textColor = preferences.secondaryTextColor.get()
  val borderColor = preferences.secondaryBorderColor.get()
  val bgColor = preferences.secondaryBackgroundColor.get()
  val justification = preferences.secondaryJustification.get()

  // Calculate effective font size taking scale into account
  val effectiveFontSize = (fontSize * subScale).roundToInt().coerceAtLeast(1)

  // In ASS: 1 = bottom-left, 2 = bottom-center, 3 = bottom-right
  val alignment = when (justification) {
    SubtitleJustification.Left -> 1
    SubtitleJustification.Right -> 3
    else -> 2
  }

  // BorderStyle: 1 = outline + shadow, 3 = opaque background box
  val assBorderStyle = if (borderStyle == SubtitlesBorderStyle.OpaqueBox) 3 else 1
  val assFont = if (font.isNotBlank() && font != "Default") font else "sans-serif"

  // Update vertical position based on line spacing relative to primary sub position
  val curSubPos = preferences.subPos.get()
  val spacing = preferences.secondarySubSpacing.get()
  val secPos = (curSubPos - spacing).coerceIn(0f, 150f)
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

  val overrides = listOf(
    "Secondary.Fontname=$assFont",
    "Secondary.Fontsize=$effectiveFontSize",
    "Secondary.PrimaryColour=${textColor.toAssColorString()}",
    "Secondary.OutlineColour=${borderColor.toAssColorString()}",
    "Secondary.BackColour=${bgColor.toAssColorString()}",
    "Secondary.Bold=${if (isBold) 1 else 0}",
    "Secondary.Italic=${if (isItalic) 1 else 0}",
    "Secondary.Outline=$borderSize",
    "Secondary.Shadow=$shadowOffset",
    "Secondary.BorderStyle=$assBorderStyle",
    "Secondary.Alignment=$alignment",
    "Secondary.MarginV=$effectiveMarginV",
  ).joinToString(",")

  MPVLib.setPropertyString("sub-ass-style-overrides", overrides)
  MPVLib.setPropertyString("secondary-sub-ass-override", "scale")
  MPVLib.setPropertyInt("secondary-sub-pos", secPosInt)
  val scaleByWindow = preferences.scaleByWindow.get()
  val scaleValue = if (scaleByWindow) "yes" else "no"
  MPVLib.setPropertyString("sub-ass-scale-with-window", scaleValue)
  MPVLib.setPropertyString("sub-ass-force-margins", scaleValue)
  MPVLib.setPropertyFloat("secondary-sub-scale", subScale)
}
