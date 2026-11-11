package app.marlboroadvance.mpvex.utils.media

import `is`.xyz.mpv.MPVLib
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.marlboroadvance.mpvex.preferences.SubtitleJustification
import app.marlboroadvance.mpvex.preferences.SubtitleMode
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.player.controls.components.panels.SubtitlesBorderStyle
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Result of pre-processing a subtitle file or embedded MKV subtitle tracks into
 * standardized `.srt` files (for Single mode and independent track inspection) plus
 * a flowing non-overlapping `.ass` track (`[双语]`) for Dual mode.
 */
data class BilingualSplitResult(
  val primaryFile: File? = null,
  val secondaryFile: File? = null,
  val bilingualFile: File? = null,
  val primaryTitle: String? = null,
  val secondaryTitle: String? = null,
  val bilingualTitle: String? = null,
  val isBilingual: Boolean = false,
)

/** Clean, timing-normalized monolingual subtitle cue. */
data class PlainSubtitleCue(
  val startMs: Long,
  val endMs: Long,
  val text: String,
)

/** Clean bilingual cue with top (`primaryText`) and bottom (`secondaryText`) channels. */
data class BilingualSubtitleCue(
  val startMs: Long,
  val endMs: Long,
  val primaryText: String,
  val secondaryText: String,
)

object BilingualSubtitleParser {
  private const val TAG = "BilingualSubtitleParser"
  private const val CACHE_DIR_NAME = "bilingual_subs_v8"
  private val LEGACY_CACHE_DIRS = listOf(
    "bilingual_subs",
    "bilingual_subs_v5",
    "bilingual_subs_v6",
    "bilingual_subs_v7",
  )

  private const val MIN_CUE_MS = 100L
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

  // ---------------------------------------------------------------------------
  // Cache Management
  // ---------------------------------------------------------------------------

  fun getCacheDir(context: Context): File {
    val dir = File(context.cacheDir, CACHE_DIR_NAME)
    if (!dir.exists()) {
      dir.mkdirs()
      cleanupLegacyCacheDirs(context.cacheDir)
    }
    return dir
  }

  private fun cleanupLegacyCacheDirs(cacheRoot: File) {
    for (legacyName in LEGACY_CACHE_DIRS) {
      val legacyDir = File(cacheRoot, legacyName)
      if (legacyDir.exists()) {
        runCatching { legacyDir.deleteRecursively() }
      }
    }
  }

  fun computeCacheKey(sourceId: String, sizeBytes: Long, lastModifiedMs: Long): String {
    val raw = "$sourceId|$sizeBytes|$lastModifiedMs|v8"
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
  // Public Android Entrypoints
  // ---------------------------------------------------------------------------

  fun splitIfBilingual(
    file: File,
    context: Context,
    preferences: SubtitlesPreferences? = null,
  ): BilingualSplitResult? {
    if (!file.exists() || !file.canRead() || file.length() == 0L) return null
    val ext = file.extension.lowercase(Locale.ROOT)
    if (ext !in setOf("srt", "ass", "ssa", "vtt")) return null

    val key = computeCacheKey(file.absolutePath, file.length(), file.lastModified())
    val targetDir = getEntryCacheDir(context, key)
    val baseName = sanitizeFileNamePart(file.nameWithoutExtension)

    findValidCachedResult(targetDir, baseName)?.let { return it }

    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    val text = decodeSubtitleBytes(bytes)
    return processTextToCache(
      rawText = text,
      ext = ext,
      baseName = baseName,
      targetDir = targetDir,
      preferences = preferences,
    )
  }

  fun splitIfBilingual(
    uri: Uri,
    context: Context,
    displayNameHint: String? = null,
    preferences: SubtitlesPreferences? = null,
  ): BilingualSplitResult? {
    if (uri.scheme == "file") {
      val path = uri.path ?: return null
      return splitIfBilingual(File(path), context, preferences)
    }

    val rawName = displayNameHint
      ?: resolveDisplayName(uri, context)
      ?: uri.lastPathSegment?.substringAfterLast('/')
      ?: "subtitle.srt"
    val ext = rawName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (ext !in setOf("srt", "ass", "ssa", "vtt")) return null

    val bytes = runCatching {
      context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull() ?: return null
    if (bytes.isEmpty()) return null

    val text = decodeSubtitleBytes(bytes)
    val key = computeCacheKey(uri.toString(), bytes.size.toLong(), text.hashCode().toLong())
    val targetDir = getEntryCacheDir(context, key)
    val baseName = sanitizeFileNamePart(rawName.substringBeforeLast('.', rawName))

    findValidCachedResult(targetDir, baseName)?.let { return it }

    return processTextToCache(
      rawText = text,
      ext = ext,
      baseName = baseName,
      targetDir = targetDir,
      preferences = preferences,
    )
  }

  /**
   * Pairs two independent single-language subtitle files (e.g. external `zh.srt` + `en.srt`
   * selected in Dual mode) into a single bottom-anchored flowing `[双语]` ASS file so they lift
   * cleanly as one block and never collide when wrapped to multiple lines.
   */
  fun mergeTwoFilesToBilingualAss(
    primaryFile: File,
    secondaryFile: File,
    baseName: String,
    context: Context,
    preferences: SubtitlesPreferences? = null,
  ): File? {
    if (!primaryFile.exists() || !secondaryFile.exists()) return null
    val key = computeCacheKey(
      "${primaryFile.absolutePath}+${secondaryFile.absolutePath}",
      primaryFile.length() xor secondaryFile.length(),
      primaryFile.lastModified() xor secondaryFile.lastModified(),
    )
    val targetDir = getEntryCacheDir(context, key)
    val cleanBase = sanitizeFileNamePart(baseName)
    val outFile = File(targetDir, "[双语] $cleanBase.ass")
    if (isValidAssFile(outFile)) return outFile

    val priCues = parseFileToPlainCues(primaryFile)
    val secCues = parseFileToPlainCues(secondaryFile)
    if (priCues.isEmpty() || secCues.isEmpty()) return null

    val merged = alignCueStreams(priCues, secCues)
    if (merged.isEmpty()) return null

    return if (writeBilingualAssFile(merged, outFile, preferences)) outFile else null
  }

  private fun resolveDisplayName(uri: Uri, context: Context): String? =
    runCatching {
      context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
      }
    }.getOrNull()

  private fun findValidCachedResult(dir: File, baseName: String): BilingualSplitResult? {
    val pri = File(dir, "[中] $baseName.srt")
    val sec = File(dir, "[英] $baseName.srt")
    val bi = File(dir, "[双语] $baseName.ass")
    if (isValidSrtFile(pri) && isValidSrtFile(sec) && isValidAssFile(bi)) {
      return BilingualSplitResult(
        primaryFile = pri,
        secondaryFile = sec,
        bilingualFile = bi,
        primaryTitle = "[中] $baseName",
        secondaryTitle = "[英] $baseName",
        bilingualTitle = "[双语] $baseName",
        isBilingual = true,
      )
    }

    val std = File(dir, "[标准] $baseName.srt")
    if (isValidSrtFile(std)) {
      return BilingualSplitResult(
        primaryFile = std,
        primaryTitle = "[标准] $baseName",
        isBilingual = false,
      )
    }
    return null
  }

  // ---------------------------------------------------------------------------
  // Pure Kotlin Core: Pre-processing & Splitting
  // ---------------------------------------------------------------------------

  /**
   * Pure Kotlin processor usable directly in JVM unit tests as well as Android runtime.
   *
   * - Bilingual SRT/VTT/ASS -> produces `[中] <base>.srt`, `[英] <base>.srt`, `[双语] <base>.ass`.
   * - Monolingual messy/multi-line SRT/VTT (or malformed ASS with raw CR/LF inside dialogue)
   *   -> normalizes into a clean `[标准] <base>.srt`.
   * - Clean stylized monolingual ASS fansubs or already-clean single-line SRT -> returns null.
   */
  fun processTextToCache(
    rawText: String,
    ext: String,
    baseName: String,
    targetDir: File,
    preferences: SubtitlesPreferences? = null,
  ): BilingualSplitResult? {
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
      val priCues = classified.mapNotNull {
        if (it.primaryText.isNotEmpty()) PlainSubtitleCue(it.startMs, it.endMs, it.primaryText) else null
      }
      val secCues = classified.mapNotNull {
        if (it.secondaryText.isNotEmpty()) PlainSubtitleCue(it.startMs, it.endMs, it.secondaryText) else null
      }
      val biCues = classified.map {
        BilingualSubtitleCue(it.startMs, it.endMs, it.primaryText, it.secondaryText)
      }

      val priFile = File(targetDir, "[中] $cleanBase.srt")
      val secFile = File(targetDir, "[英] $cleanBase.srt")
      val biFile = File(targetDir, "[双语] $cleanBase.ass")

      val okPri = priCues.isNotEmpty() && writeSrtFile(priCues, priFile)
      val okSec = secCues.isNotEmpty() && writeSrtFile(secCues, secFile)
      val okBi = biCues.isNotEmpty() && writeBilingualAssFile(biCues, biFile, preferences)

      if (!okBi && !okPri && !okSec) return null
      return BilingualSplitResult(
        primaryFile = priFile.takeIf { okPri },
        secondaryFile = secFile.takeIf { okSec },
        bilingualFile = biFile.takeIf { okBi },
        primaryTitle = if (okPri) "[中] $cleanBase" else null,
        secondaryTitle = if (okSec) "[英] $cleanBase" else null,
        bilingualTitle = if (okBi) "[双语] $cleanBase" else null,
        isBilingual = okPri && okSec,
      )
    }

    // Preserve intact styled monolingual ASS fansubs unless broken raw newlines corrupt events.
    if (isAss && !hasBrokenRawNewlinesInAssEvents(rawText)) {
      return null
    }

    val needsNormalization = classified.any { it.wasDirty }
    if (!needsNormalization) return null

    val normalizedCues = classified.mapNotNull { cue ->
      val combined = joinNormalizedLines(
        (cue.primaryLines + cue.secondaryLines).filter { it.isNotEmpty() },
      )
      if (combined.isEmpty()) null else PlainSubtitleCue(cue.startMs, cue.endMs, combined)
    }
    if (normalizedCues.isEmpty()) return null

    val stdFile = File(targetDir, "[标准] $cleanBase.srt")
    if (!writeSrtFile(normalizedCues, stdFile)) return null

    return BilingualSplitResult(
      primaryFile = stdFile,
      primaryTitle = "[标准] $cleanBase",
      isBilingual = false,
    )
  }

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
        // Handle explicit single-line bilingual separators (e.g. "中文\tEnglish" or "中文 | English").
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
    // Fallback for unusual headers with empty Effect containing 8 commas after numeric ReadOrder,Layer
    if (Regex("""^\d+,\d+,""".trim()).containsMatchIn(normalized)) {
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

  fun cleanSubtitleTextSingle(raw: String, stripMatroskaPrefix: Boolean = false, isAss: Boolean = false): String =
    joinNormalizedLines(cleanPayloadToLines(raw, stripMatroskaPrefix = stripMatroskaPrefix, isAss = isAss))

  // ---------------------------------------------------------------------------
  // Cue Parsers (SRT / VTT / ASS)
  // ---------------------------------------------------------------------------

  fun parseFileToPlainCues(file: File): List<PlainSubtitleCue> {
    if (!file.exists() || file.length() == 0L) return emptyList()
    val ext = file.extension.lowercase(Locale.ROOT)
    val text = runCatching { decodeSubtitleBytes(file.readBytes()) }.getOrNull() ?: return emptyList()
    val rawCues = if (ext == "ass" || ext == "ssa") extractRawAssCues(text) else extractRawSrtOrVttCues(text)
    return rawCues.mapNotNull { cue ->
      val cleaned = cleanSubtitleTextSingle(cue.rawPayload, stripMatroskaPrefix = false, isAss = cue.fromAss)
      if (cleaned.isEmpty()) null else PlainSubtitleCue(cue.startMs, max(cue.endMs, cue.startMs + MIN_CUE_MS), cleaned)
    }
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
        // Recover cleanly when a cue misses the blank separator line before the next index+timing.
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

  private fun hasBrokenRawNewlinesInAssEvents(rawText: String): Boolean {
    val eventsIdx = rawText.indexOf("[Events]", ignoreCase = true)
    if (eventsIdx < 0) return false
    val lines = rawText.substring(eventsIdx).replace("\r\n", "\n").replace('\r', '\n').split('\n')
    for (line in lines) {
      val t = line.trim()
      if (t.isEmpty() || t.startsWith("[") || t.startsWith("Format:", ignoreCase = true) ||
        t.startsWith("Dialogue:", ignoreCase = true) || t.startsWith("Comment:", ignoreCase = true) ||
        t.startsWith(";")
      ) {
        continue
      }
      return true
    }
    return false
  }

  // ---------------------------------------------------------------------------
  // Timeline Alignment (MKV Dual Tracks & Paired External Tracks)
  // ---------------------------------------------------------------------------

  /**
   * Aligns primary (`chiCues`) and secondary (`engCues`) timelines cleanly:
   * - Supports 1:1 matching with boundary tolerance (no start/end flicker);
   * - Supports 1:N and N:1 splitting (keeps long Chinese line visible across 2 rapid English cuts);
   * - Preserves unmatched primary-only or secondary-only cues cleanly.
   */
  fun alignCueStreams(
    primaryCues: List<PlainSubtitleCue>,
    secondaryCues: List<PlainSubtitleCue>,
  ): List<BilingualSubtitleCue> {
    if (primaryCues.isEmpty()) {
      return secondaryCues.map { BilingualSubtitleCue(it.startMs, it.endMs, "", it.text) }
    }
    if (secondaryCues.isEmpty()) {
      return primaryCues.map { BilingualSubtitleCue(it.startMs, it.endMs, it.text, "") }
    }

    val priSorted = primaryCues.sortedBy { it.startMs }
    val secSorted = secondaryCues.sortedBy { it.startMs }

    val priMatches = Array(priSorted.size) { mutableListOf<Int>() }
    val secAssignedToPri = IntArray(secSorted.size) { -1 }

    var priHint = 0
    for (j in secSorted.indices) {
      val sec = secSorted[j]
      val secDur = max(1L, sec.endMs - sec.startMs)
      while (priHint < priSorted.size && priSorted[priHint].endMs + 450L < sec.startMs) {
        priHint++
      }

      var bestIdx = -1
      var bestScore = 0.0
      var k = max(0, priHint - 1)
      while (k < priSorted.size && priSorted[k].startMs - 450L <= sec.endMs) {
        val pri = priSorted[k]
        val overlap = max(0L, min(pri.endMs, sec.endMs) - max(pri.startMs, sec.startMs))
        val priDur = max(1L, pri.endMs - pri.startMs)
        val minDur = min(priDur, secDur).toDouble()
        val startDiff = kotlin.math.abs(pri.startMs - sec.startMs)
        val endDiff = kotlin.math.abs(pri.endMs - sec.endMs)

        val ratio = overlap / minDur
        val nearAligned = startDiff <= 400L && endDiff <= 600L
        if (ratio >= 0.45 || (overlap > 0L && nearAligned)) {
          val score = overlap.toDouble() - startDiff * 0.25
          if (score > bestScore || (bestIdx == -1 && nearAligned)) {
            bestScore = score
            bestIdx = k
          }
        }
        k++
      }

      if (bestIdx >= 0) {
        secAssignedToPri[j] = bestIdx
        priMatches[bestIdx].add(j)
      }
    }

    val out = ArrayList<BilingualSubtitleCue>(priSorted.size + secSorted.size / 4)

    for (i in priSorted.indices) {
      val pri = priSorted[i]
      val secIndices = priMatches[i]
      when {
        secIndices.isEmpty() -> {
          out.add(BilingualSubtitleCue(pri.startMs, pri.endMs, pri.text, ""))
        }

        secIndices.size == 1 -> {
          val sec = secSorted[secIndices[0]]
          val start = min(pri.startMs, sec.startMs)
          val end = max(pri.endMs, sec.endMs)
          out.add(BilingualSubtitleCue(start, end, pri.text, sec.text))
        }

        else -> {
          // 1 Primary spans N Secondary segments: preserve each Secondary segment timing while
          // stretching only outer boundaries to pri.start/pri.end so Chinese never vanishes mid-way.
          for (idxPos in secIndices.indices) {
            val sec = secSorted[secIndices[idxPos]]
            val segStart = if (idxPos == 0) min(pri.startMs, sec.startMs) else sec.startMs
            val segEnd = if (idxPos == secIndices.lastIndex) max(pri.endMs, sec.endMs) else sec.endMs
            out.add(BilingualSubtitleCue(segStart, max(segEnd, segStart + MIN_CUE_MS), pri.text, sec.text))
          }
        }
      }
    }

    for (j in secSorted.indices) {
      if (secAssignedToPri[j] == -1) {
        val sec = secSorted[j]
        out.add(BilingualSubtitleCue(sec.startMs, sec.endMs, "", sec.text))
      }
    }

    out.sortWith(compareBy<BilingualSubtitleCue> { it.startMs }.thenBy { it.endMs })
    return snapMinorOverlaps(out)
  }

  private fun snapMinorOverlaps(cues: List<BilingualSubtitleCue>): List<BilingualSubtitleCue> {
    if (cues.size < 2) return cues
    val adjusted = cues.toMutableList()
    for (i in 0 until adjusted.lastIndex) {
      val curr = adjusted[i]
      val next = adjusted[i + 1]
      if (curr.endMs > next.startMs) {
        val visibleMsIfTrimmed = next.startMs - curr.startMs
        val overlapMs = curr.endMs - next.startMs
        if (visibleMsIfTrimmed >= 600L || (visibleMsIfTrimmed >= MIN_CUE_MS && overlapMs <= 1500L)) {
          adjusted[i] = curr.copy(endMs = next.startMs)
        } else if (next.endMs - curr.endMs >= 300L && curr.endMs - curr.startMs >= MIN_CUE_MS) {
          adjusted[i + 1] = next.copy(startMs = curr.endMs)
        }
      }
    }
    return adjusted
  }

  // ---------------------------------------------------------------------------
  // Atomic Writers: Standard Pure .srt + Method A Flowing [双语].ass
  // ---------------------------------------------------------------------------

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

  /**
   * Writes unified flowing bilingual `.ass` (`Method A`):
   * - `Default` (Primary / Top) and `Secondary` (Bottom) are connected in one bottom-anchored paragraph:
   *   `Primary\N{\rSpacer}\h\N{\rSecondary}Secondary`.
   * - Whenever Secondary wraps onto 2+ lines, `libass` lifts Primary upward automatically with ZERO overlap!
   */
  fun writeBilingualAssFile(
    cues: List<BilingualSubtitleCue>,
    outFile: File,
    preferences: SubtitlesPreferences? = null,
  ): Boolean {
    val validCues = cues.filter { it.primaryText.isNotBlank() || it.secondaryText.isNotBlank() }
    if (validCues.isEmpty()) return false
    outFile.parentFile?.mkdirs()
    val tmpFile = File(outFile.parentFile, "${outFile.name}.tmp")

    val primary = preferences?.primaryProfileFor(SubtitleMode.Multi)
    val secondary = preferences?.secondaryProfile()
    val multiPos = preferences?.multiSubPos?.get() ?: 96.0f
    val marginV = posPercentToAssMarginV(multiPos)
    val gapSize = (preferences?.multiBilingualGap?.get() ?: 6).coerceIn(1, 50)

    return try {
      BufferedWriter(OutputStreamWriter(FileOutputStream(tmpFile), Charsets.UTF_8)).use { w ->
        w.write(buildBilingualAssHeader(primary, secondary, marginV, gapSize))
        for (cue in validCues) {
          val safeEnd = max(cue.endMs, cue.startMs + MIN_CUE_MS)
          val startAss = msToAssTime(cue.startMs)
          val endAss = msToAssTime(safeEnd)
          val hasPri = cue.primaryText.isNotBlank()
          val hasSec = cue.secondaryText.isNotBlank()

          when {
            hasPri && hasSec -> {
              val priEsc = escapeAssText(cue.primaryText)
              val secEsc = escapeAssText(cue.secondaryText)
              w.write("Dialogue: 0,$startAss,$endAss,Default,,0,0,0,,$priEsc\\N{\\rSpacer}\\h\\N{\\rSecondary}$secEsc")
            }
            hasPri -> {
              val priEsc = escapeAssText(cue.primaryText)
              w.write("Dialogue: 0,$startAss,$endAss,Default,,0,0,0,,$priEsc")
            }
            else -> {
              val secEsc = escapeAssText(cue.secondaryText)
              w.write("Dialogue: 0,$startAss,$endAss,Secondary,,0,0,0,,$secEsc")
            }
          }
          w.newLine()
        }
      }
      if (outFile.exists()) outFile.delete()
      tmpFile.renameTo(outFile) || run {
        tmpFile.copyTo(outFile, overwrite = true)
        tmpFile.delete()
        true
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed writing ASS file ${outFile.name}", e)
      runCatching { tmpFile.delete() }
      false
    }
  }

  private fun escapeAssText(text: String): String =
    text
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .replace("\n", "\\N")

  // ---------------------------------------------------------------------------
  // Pure Style Builders & Runtime MPV Mode Application
  // ---------------------------------------------------------------------------

  fun posPercentToAssMarginV(posPercent: Float): Int =
    ((100f - posPercent.coerceIn(0f, 100f)) / 100f * 1080f).roundToInt().coerceIn(0, 1080)

  private fun buildBilingualAssHeader(
    primary: SubtitlesPreferences.SubtitleStyleProfile?,
    secondary: SubtitlesPreferences.SubtitleStyleProfile?,
    marginV: Int,
    gapSize: Int,
  ): String {
    val defaultLine = buildAssStyleDefinition("Default", primary, defaultSize = 55, marginV = marginV)
    val secondaryLine = buildAssStyleDefinition("Secondary", secondary, defaultSize = 44, marginV = marginV)
    val spacerLine = "Style: Spacer,sans-serif,$gapSize,&HFF000000,&HFF000000,&HFF000000,&HFF000000,0,0,0,0,100,100,0,0,1,0,0,2,20,20,0,1"

    return buildString {
      appendLine("[Script Info]")
      appendLine("ScriptType: v4.00+")
      appendLine("PlayResX: 1920")
      appendLine("PlayResY: 1080")
      appendLine("WrapStyle: 0")
      appendLine("ScaledBorderAndShadow: yes")
      appendLine()
      appendLine("[V4+ Styles]")
      appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
      appendLine(defaultLine)
      appendLine(secondaryLine)
      appendLine(spacerLine)
      appendLine()
      appendLine("[Events]")
      appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
    }
  }

  private fun buildAssStyleDefinition(
    name: String,
    profile: SubtitlesPreferences.SubtitleStyleProfile?,
    defaultSize: Int,
    marginV: Int,
  ): String {
    val style = extractStyleSnapshot(profile, defaultSize, marginV)
    return "Style: $name,${style.fontName},${style.fontSize},${style.primaryAss},${style.primaryAss},${style.outlineAss},${style.backAss},${style.boldAss},${style.italicAss},0,0,100,100,0,0,${style.borderStyleAss},${style.outlineWidth},${style.shadow},${style.alignmentAss},20,20,$marginV,1"
  }

  private data class AssStyleSnapshot(
    val fontName: String,
    val fontSize: Int,
    val primaryAss: String,
    val outlineAss: String,
    val backAss: String,
    val boldAss: Int,
    val italicAss: Int,
    val borderStyleAss: Int,
    val outlineWidth: String,
    val shadow: Int,
    val alignmentAss: Int,
    val marginV: Int,
  )

  private fun extractStyleSnapshot(
    profile: SubtitlesPreferences.SubtitleStyleProfile?,
    defaultSize: Int,
    marginV: Int,
  ): AssStyleSnapshot {
    val fontName = profile?.font?.get()?.takeIf { it.isNotBlank() } ?: "sans-serif"
    val fontSize = profile?.fontSize?.get() ?: defaultSize
    val textCol = profile?.textColor?.get() ?: Color.White.toArgb()
    val borderCol = profile?.borderColor?.get() ?: Color.Black.toArgb()
    val bgCol = profile?.backgroundColor?.get() ?: Color.Transparent.toArgb()
    val bStyle = profile?.borderStyle?.get() ?: SubtitlesBorderStyle.OutlineAndShadow
    val borderSize = profile?.borderSize?.get() ?: 3.0f
    val shadow = profile?.shadowOffset?.get() ?: 0
    val bold = profile?.bold?.get() ?: false
    val italic = profile?.italic?.get() ?: false
    val justify = profile?.justification?.get() ?: SubtitleJustification.Auto

    val borderStyleAss = if (bStyle == SubtitlesBorderStyle.OpaqueBox) 3 else 1
    // In libass, BorderStyle=3 uses OutlineColour as the opaque box background.
    val effectiveOutlineCol = when {
      bStyle == SubtitlesBorderStyle.OpaqueBox && (bgCol ushr 24) > 0 -> bgCol
      else -> borderCol
    }
    // Keep shadow visible in BorderStyle=1 when user enables shadowOffset while bgCol is transparent.
    val effectiveBackCol = when {
      bStyle == SubtitlesBorderStyle.OpaqueBox -> borderCol
      (bgCol ushr 24) == 0 && shadow > 0 -> 0x80000000.toInt()
      else -> bgCol
    }
    val alignmentAss = when (justify) {
      SubtitleJustification.Left -> 1
      SubtitleJustification.Right -> 3
      SubtitleJustification.Center, SubtitleJustification.Auto -> 2
    }

    return AssStyleSnapshot(
      fontName = fontName.replace(",", " "),
      fontSize = fontSize,
      primaryAss = textCol.toAssColorString(),
      outlineAss = effectiveOutlineCol.toAssColorString(),
      backAss = effectiveBackCol.toAssColorString(),
      boldAss = if (bold) -1 else 0,
      italicAss = if (italic) -1 else 0,
      borderStyleAss = borderStyleAss,
      outlineWidth = String.format(Locale.US, "%.1f", borderSize),
      shadow = shadow,
      alignmentAss = alignmentAss,
      marginV = marginV,
    )
  }

  /**
   * Builds the comma-separated `sub-ass-style-overrides` string for Dual mode, driving
   * `Default`, `Secondary`, and `Spacer` completely from user preferences.
   */
  fun buildBilingualAssStyleOverrides(
    primary: SubtitlesPreferences.SubtitleStyleProfile,
    secondary: SubtitlesPreferences.SubtitleStyleProfile,
    marginV: Int,
    gapSize: Int,
  ): String {
    val priSnap = extractStyleSnapshot(primary, defaultSize = 55, marginV = marginV)
    val secSnap = extractStyleSnapshot(secondary, defaultSize = 44, marginV = marginV)

    val parts = mutableListOf<String>()
    appendOverridesForStyle("Default", priSnap, includeAlignment = true, out = parts)
    appendOverridesForStyle("Secondary", secSnap, includeAlignment = false, out = parts)
    parts.add("Spacer.Fontname=sans-serif")
    parts.add("Spacer.Fontsize=${gapSize.coerceIn(1, 50)}")
    parts.add("Spacer.PrimaryColour=&HFF000000")
    parts.add("Spacer.OutlineColour=&HFF000000")
    parts.add("Spacer.BackColour=&HFF000000")
    parts.add("Spacer.BorderStyle=1")
    parts.add("Spacer.Outline=0")
    parts.add("Spacer.Shadow=0")
    return parts.joinToString(",")
  }

  private fun appendOverridesForStyle(
    styleName: String,
    s: AssStyleSnapshot,
    includeAlignment: Boolean,
    out: MutableList<String>,
  ) {
    out.add("$styleName.Fontname=${s.fontName}")
    out.add("$styleName.Fontsize=${s.fontSize}")
    out.add("$styleName.PrimaryColour=${s.primaryAss}")
    out.add("$styleName.OutlineColour=${s.outlineAss}")
    out.add("$styleName.BackColour=${s.backAss}")
    out.add("$styleName.Bold=${s.boldAss}")
    out.add("$styleName.Italic=${s.italicAss}")
    out.add("$styleName.BorderStyle=${s.borderStyleAss}")
    out.add("$styleName.Outline=${s.outlineWidth}")
    out.add("$styleName.Shadow=${s.shadow}")
    out.add("$styleName.MarginV=${s.marginV}")
    if (includeAlignment) {
      out.add("$styleName.Alignment=${s.alignmentAss}")
    }
  }

  /**
   * Computes all MPV subtitle options for [mode] as a uniform list so both initial
   * `MPVLib.setOptionString` and runtime `MPVLib.setPropertyString` share one source of truth.
   */
  fun buildMpvSubtitleOptions(
    preferences: SubtitlesPreferences,
    mode: SubtitleMode = preferences.subtitleMode.get(),
    activeTrackTitle: String? = null,
  ): List<Pair<String, String>> {
    val primary = preferences.primaryProfileFor(mode)
    val layout = preferences.layoutProfileFor(mode)
    val secondary = preferences.secondaryProfile()

    val scaleByWin = if (layout.scaleByWindow.get()) "yes" else "no"
    val borderStyle = primary.borderStyle.get()
    val effectiveBg = primary.backgroundColor.get()

    val isSynthesizedBilingualActive =
      mode == SubtitleMode.Multi && (activeTrackTitle == null || activeTrackTitle.startsWith("[双语]"))

    val assOverride =
      if (isSynthesizedBilingualActive) {
        "scale"
      } else {
        layout.overrideAssSubs.get().value
      }

    val assStyleOverrides =
      if (mode == SubtitleMode.Multi) {
        val marginV = posPercentToAssMarginV(layout.subPos.get())
        val gap = preferences.multiBilingualGap.get().coerceIn(1, 50)
        buildBilingualAssStyleOverrides(primary, secondary, marginV, gap)
      } else {
        ""
      }

    val secScaleRatio =
      (secondary.fontSize.get().toFloat() / primary.fontSize.get().coerceAtLeast(1).toFloat())
        .coerceIn(0.4f, 1.5f) * preferences.secondarySubScale.get()

    val borderSizeStr = String.format(Locale.US, "%.2f", primary.borderSize.get())

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
      "sub-pos" to layout.subPos.get().roundToInt().coerceIn(0, 100).toString(),
      "sub-line-spacing" to layout.subSpacing.get().toString(),
      "sub-ass-line-spacing" to layout.subSpacing.get().toString(),
      "sub-ass-justify" to if (assOverride == "force" || assOverride == "strip") "yes" else "no",
      "sub-scale-by-window" to scaleByWin,
      "sub-use-margins" to scaleByWin,
      "sub-ass-scale-with-window" to scaleByWin,
      "sub-ass-force-margins" to scaleByWin,
      "sub-ass-override" to assOverride,
      "secondary-sub-ass-override" to "scale",
      "secondary-sub-pos" to layout.subPos.get().roundToInt().coerceIn(0, 100).toString(),
      "secondary-sub-scale" to String.format(Locale.US, "%.3f", secScaleRatio),
      "sub-ass-style-overrides" to assStyleOverrides,
    )
  }

  /**
   * Applies pre-init MPV options for the currently active subtitle mode.
   */
  fun applyStartupSubtitleOptions(preferences: SubtitlesPreferences, mode: SubtitleMode = preferences.subtitleMode.get()) {
    for ((key, value) in buildMpvSubtitleOptions(preferences, mode = mode, activeTrackTitle = null)) {
      runCatching { MPVLib.setOptionString(key, value) }
    }
  }

  /**
   * Applies runtime MPV subtitle properties for [mode], cleanly isolating Single-language SRT/ASS
   * rendering from Dual-language `[双语]` ASS rendering.
   */
  fun applySubtitlePreferencesForMode(
    preferences: SubtitlesPreferences,
    mode: SubtitleMode = preferences.subtitleMode.get(),
  ) {
    val activeTitle = runCatching { MPVLib.getPropertyString("current-tracks/sub/title") }.getOrNull()
    for ((key, value) in buildMpvSubtitleOptions(preferences, mode = mode, activeTrackTitle = activeTitle)) {
      runCatching { MPVLib.setPropertyString(key, value) }
    }
  }

  // ---------------------------------------------------------------------------
  // Validation, Encoding, Time & Character Helpers
  // ---------------------------------------------------------------------------

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

  fun isValidAssFile(file: File?): Boolean {
    if (file == null || !file.exists() || file.length() < 64L) return false
    return runCatching {
      file.bufferedReader(Charsets.UTF_8).use { r ->
        var checked = 0
        while (checked < 60) {
          val line = r.readLine() ?: break
          if (line.startsWith("Dialogue:", ignoreCase = true)) return true
          checked++
        }
        false
      }
    }.getOrDefault(false)
  }

  fun decodeSubtitleBytes(bytes: ByteArray): String {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
      return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
      return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
      return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }

    val utf8 = String(bytes, Charsets.UTF_8)
    if (!utf8.contains('\uFFFD')) return utf8

    val gbk = runCatching { Charset.forName("GB18030") }.getOrElse { Charset.forName("GBK") }
    return String(bytes, gbk)
  }

  fun msToSrtTime(ms: Long): String {
    val safe = max(0L, ms)
    val h = safe / 3_600_000L
    val m = (safe % 3_600_000L) / 60_000L
    val s = (safe % 60_000L) / 1000L
    val mmm = safe % 1000L
    return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, mmm)
  }

  fun msToAssTime(ms: Long): String {
    val safe = max(0L, ms)
    val h = safe / 3_600_000L
    val m = (safe % 3_600_000L) / 60_000L
    val s = (safe % 60_000L) / 1000L
    val cs = (safe % 1000L) / 10L
    return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
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
}

/**
 * Formats an Android/Compose ARGB Int color (`0xAARRGGBB`) into `#AARRGGBB` for mpv options.
 */
fun Int.toMpvColorHex(): String = String.format(Locale.US, "#%08X", this)

/**
 * Converts an Android/Compose ARGB Int color (`0xAARRGGBB`) to ASS hex (`&HAABBGGRR`).
 * Note ASS alpha is inverted compared to Android alpha: `00` = opaque, `FF` = transparent.
 */
fun Int.toAssColorString(): String {
  val a = (this ushr 24) and 0xFF
  val r = (this ushr 16) and 0xFF
  val g = (this ushr 8) and 0xFF
  val b = this and 0xFF
  val assAlpha = 255 - a
  return String.format(Locale.US, "&H%02X%02X%02X%02X", assAlpha, b, g, r)
}

/**
 * Legacy helper alias maintained so any callers migrating from earlier commits invoke
 * [BilingualSubtitleParser.applySubtitlePreferencesForMode].
 */
fun applySecondarySubStyleOverrides(preferences: SubtitlesPreferences) {
  BilingualSubtitleParser.applySubtitlePreferencesForMode(preferences)
}
