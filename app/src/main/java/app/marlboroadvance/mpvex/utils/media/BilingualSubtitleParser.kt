package app.marlboroadvance.mpvex.utils.media

import android.content.Context
import android.net.Uri
import android.util.Log
import app.marlboroadvance.mpvex.preferences.SubtitleJustification
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.player.controls.components.panels.SubtitlesBorderStyle
import `is`.xyz.mpv.MPVLib
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlin.math.roundToInt

// [Patch C-3] 移除 bilingualFile / bilingualTitle：程序不再自动生成任何合并的 [双语] 轨道，
// 只保留由单个双语文件拆分出的 [中] 主字幕 + [英] 副字幕（拆分不是合并，且是正例外观的来源）。
data class BilingualSplitResult(
  val primaryFile: File,
  val secondaryFile: File,
  val primaryTitle: String,
  val secondaryTitle: String,
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
object BilingualSubtitleParser : KoinComponent {
  private const val TAG = "BilingualSubParser"
  const val CACHE_DIR_NAME = "bilingual_subs_v6" // [Patch C-3/A-3/A-4] 生成内容变化，升级缓存目录使旧缓存失效

  // [Patch A-3/A-4] 读取用户的字幕设置，用于估算「同语言多行能否合并为一行」以及「副字幕多行时主字幕需上抬多少」
  private val subtitlesPreferences: SubtitlesPreferences by inject()

  /** 生成的 [中] 字幕样式里的默认 MarginV（与 writePrimaryAssHeader 中 Default 样式保持一致）。 */
  private const val PRIMARY_BASE_MARGIN_V = 20

  /** 副字幕一行的行高相对字号的系数（沿用基线 45 号字 ≈ 56 的实测比例）。 */
  private const val SECONDARY_LINE_HEIGHT_RATIO = 1.244f

  /**
   * [Patch A-3/A-4] 字幕排版参数。
   * 这些参数由用户当前字幕设置与屏幕比例决定，因此参与缓存 key：设置变化后下次加载字幕会重新生成。
   */
  data class LayoutParams(
    /** 主字幕（中文）单行可用宽度，单位：1080 画布像素。 */
    val primaryFontPx: Float,
    /** 副字幕（英文）字号，单位：1080 画布像素。 */
    val secondaryFontPx: Float,
    /** 一行可用的最大宽度，单位：1080 画布像素。 */
    val lineCapacityPx: Float,
    /** 副字幕多出一行时，主字幕需要上抬的像素（1080 画布）。 */
    val secondaryLineHeight: Int,
  ) {
    val signature: String
      get() = "p${primaryFontPx.roundToInt()}_s${secondaryFontPx.roundToInt()}_w${lineCapacityPx.roundToInt()}"
  }

  /** 根据用户设置和手机屏幕比例计算排版参数；任何异常都回落到基线默认值。 */
  fun computeLayout(context: Context): LayoutParams {
    return try {
      val prefs = subtitlesPreferences
      val dm = context.resources.displayMetrics
      // 以横屏观看为准：长边 / 短边 作为窗口宽高比（字幕按窗口宽度排版）
      val aspect = (maxOf(dm.widthPixels, dm.heightPixels).toFloat() /
        minOf(dm.widthPixels, dm.heightPixels).toFloat().coerceAtLeast(1f)).coerceIn(1.2f, 2.6f)
      val subScale = prefs.subScale.get().coerceAtLeast(0.1f)
      val overrideMode = prefs.overrideAssSubs.get().value
      // scale/no 模式下 [中] 使用自身样式（55，1080 画布）；force/strip 模式使用用户字号（mpv 以 720 为基准，换算到 1080 需 ×1.5）
      val primaryPx = if (overrideMode == "force" || overrideMode == "strip") {
        prefs.fontSize.get() * 1.5f * subScale
      } else {
        55f * subScale
      }
      val secondaryPx = (prefs.secondaryFontSize.get() * prefs.secondarySubScale.get()).coerceAtLeast(1f)
      LayoutParams(
        primaryFontPx = primaryPx.coerceAtLeast(1f),
        secondaryFontPx = secondaryPx,
        lineCapacityPx = 1080f * aspect * 0.88f, // 预留 12% 的左右边距与描边余量
        secondaryLineHeight = (secondaryPx * SECONDARY_LINE_HEIGHT_RATIO).roundToInt().coerceAtLeast(1),
      )
    } catch (e: Exception) {
      Log.w(TAG, "computeLayout failed, fallback to defaults: ${e.message}")
      LayoutParams(55f, 45f, 1080f * 2.0f * 0.88f, 56)
    }
  }

  /** 估算单个字符宽度（单位：em）。CJK/全角 = 1em，拉丁字母按平均字宽估算（偏保守，宁可不合并）。 */
  private fun charWidthEm(c: Char): Float {
    val code = c.code
    return when {
      code in 0x2E80..0x9FFF || code in 0xAC00..0xD7AF || code in 0xFF00..0xFFEF -> 1.0f
      c == ' ' -> 0.28f
      c.isUpperCase() -> 0.68f
      c.isDigit() -> 0.56f
      c.isLetter() -> 0.52f
      else -> 0.34f
    }
  }

  private fun textWidthPx(text: String, fontPx: Float): Float {
    var em = 0f
    for (ch in text) em += charWidthEm(ch)
    return em * fontPx
  }

  // 以「- 」「– 」「— 」开头的行是对话换人标记，不能与上一行合并
  private val DIALOGUE_DASH_REGEX = Regex("^[-\\u2013\\u2014]")

  /**
   * [Patch A-3] 同语言多余换行合并。
   * 仅当「同一语言的所有行合并成一行后，宽度仍在屏幕可用宽度内」才合并；否则保持原有换行，绝不强制合并。
   * 中英两种语言之间的换行不受影响（本函数每次只处理单一语言的行）。
   */
  internal fun mergeSameLanguageLines(lines: List<String>, fontPx: Float, capacityPx: Float): List<String> {
    if (lines.size < 2) return lines
    // 对话换人（行首破折号）不合并，避免把两个人的台词挤成一句
    if (lines.drop(1).any { DIALOGUE_DASH_REGEX.containsMatchIn(it) }) return lines
    val merged = lines.joinToString(" ")
    return if (textWidthPx(merged, fontPx) <= capacityPx) listOf(merged) else lines
  }

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

    // [Patch A-3/A-4] 排版参数参与缓存 key：字号/屏幕比例变化后重新生成
    val layout = computeLayout(context)
    val hashKey = ChecksumUtils.getCRC32(
      "${sourceFile.absolutePath}_${sourceFile.length()}_${sourceFile.lastModified()}_${layout.signature}"
    )
    val folder = File(cacheDir, hashKey)
    if (!folder.exists()) folder.mkdirs()

    val baseName = sourceFile.nameWithoutExtension
    val primaryFileName = "[中] $baseName.ass"
    val secondaryFileName = "[英] $baseName.ass"
    val primaryFile = File(folder, primaryFileName)
    val secondaryFile = File(folder, secondaryFileName)

    // Cache hit: 0ms execution
    if (primaryFile.exists() && primaryFile.length() > 0 &&
      secondaryFile.exists() && secondaryFile.length() > 0
    ) {
      Log.d(TAG, "Cache hit for bilingual subtitle: ${sourceFile.name}")
      return BilingualSplitResult(
        primaryFile = primaryFile,
        secondaryFile = secondaryFile,
        primaryTitle = "[中] $baseName",
        secondaryTitle = "[英] $baseName",
      )
    }

    return try {
      val rawBytes = sourceFile.readBytes()
      val content = readTextWithAutoEncoding(rawBytes)
      val result = if (ext == "srt") {
        splitSrt(content, folder, primaryFile, secondaryFile, baseName, layout)
      } else {
        splitAss(content, folder, primaryFile, secondaryFile, baseName, layout)
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

      // [Patch A-3/A-4] 排版参数参与缓存 key
      val layout = computeLayout(context)
      val hashKey = ChecksumUtils.getCRC32("${uri}_${rawBytes.size}_${layout.signature}")
      val folder = File(cacheDir, hashKey)
      if (!folder.exists()) folder.mkdirs()

      val baseName = fileName.substringBeforeLast('.')
      val primaryFileName = "[中] $baseName.ass"
      val secondaryFileName = "[英] $baseName.ass"
        val primaryFile = File(folder, primaryFileName)
      val secondaryFile = File(folder, secondaryFileName)
  
      if (primaryFile.exists() && primaryFile.length() > 0 &&
        secondaryFile.exists() && secondaryFile.length() > 0
      ) {
        return BilingualSplitResult(
          primaryFile = primaryFile,
          secondaryFile = secondaryFile,
            primaryTitle = "[中] $baseName",
          secondaryTitle = "[英] $baseName",
          )
      }

      val content = readTextWithAutoEncoding(rawBytes)
      if (ext == "srt") {
        splitSrt(content, folder, primaryFile, secondaryFile, baseName, layout)
      } else {
        splitAss(content, folder, primaryFile, secondaryFile, baseName, layout)
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
    baseName: String,
    layout: LayoutParams,
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

    return try {
      tempPri.bufferedWriter(Charsets.UTF_8).use { priWriter ->
        tempSec.bufferedWriter(Charsets.UTF_8).use { secWriter ->
          writePrimaryAssHeader(priWriter, baseName, playResX, playResY)
          writeSecondaryAssHeader(secWriter, baseName, playResX, playResY)
          writeAlignedCues(cues, priWriter, secWriter, layout)
        }
      }

      tempPri.renameTo(primaryFile)
      tempSec.renameTo(secondaryFile)

      BilingualSplitResult(
        primaryFile = primaryFile,
        secondaryFile = secondaryFile,
        primaryTitle = "[中] $baseName",
        secondaryTitle = "[英] $baseName",
      )
    } catch (e: Exception) {
      tempPri.delete()
      tempSec.delete()
      throw e
    }
  }

  private fun splitSrt(
    content: String,
    folder: File,
    primaryFile: File,
    secondaryFile: File,
    baseName: String,
    layout: LayoutParams,
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

    return try {
      tempPri.bufferedWriter(Charsets.UTF_8).use { priWriter ->
        tempSec.bufferedWriter(Charsets.UTF_8).use { secWriter ->
          writePrimaryAssHeader(priWriter, baseName, 1920, 1080)
          writeSecondaryAssHeader(secWriter, baseName, 1920, 1080)
          writeAlignedCues(cues, priWriter, secWriter, layout)
        }
      }

      tempPri.renameTo(primaryFile)
      tempSec.renameTo(secondaryFile)

      BilingualSplitResult(
        primaryFile = primaryFile,
        secondaryFile = secondaryFile,
        primaryTitle = "[中] $baseName",
        secondaryTitle = "[英] $baseName",
      )
    } catch (e: Exception) {
      tempPri.delete()
      tempSec.delete()
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
    layout: LayoutParams,
  ) {
    for (cue in cues) {
      val startAss = msToAssTime(cue.startMs)
      val endAss = msToAssTime(cue.endMs)

      val priCleanList = cue.priParts.map { cleanAssTags(it) }.filter { it.isNotEmpty() }
      val secCleanList = cue.secParts.map { cleanAssTags(it) }.filter { it.isNotEmpty() }

      // [Patch A-3] 同语言的多余换行在屏幕放得下时合并为一行；中英之间的分行保留
      val priLines = mergeSameLanguageLines(priCleanList, layout.primaryFontPx, layout.lineCapacityPx)
      val secLines = mergeSameLanguageLines(secCleanList, layout.secondaryFontPx, layout.lineCapacityPx)

      val priText = priLines.joinToString("\\N")
      val cleanedSec = secLines.joinToString("\\N")

      // 1. Primary ASS dialogue (pure Chinese)
      // [Patch A-4] 仅当副字幕（英文）合并后仍为多行时，才把主字幕整体上抬「多出的行数 × 副字幕实际行高」；
      // 副字幕单行时 MarginV 保持 0（沿用样式默认值），不会误抬主字幕。
      // 注意：cue 中非 0 的 MarginV 会「替换」样式默认值(20)，因此需加上 PRIMARY_BASE_MARGIN_V，
      // 否则实际上抬量会少 20 像素而仍与英文首行重叠。
      // 行高随副字幕字号/缩放动态计算，不再写死 56。
      if (priText.isNotEmpty()) {
        val extraSecLines = (secLines.size - 1).coerceIn(0, 3)
        val dynamicMarginV = if (extraSecLines > 0) PRIMARY_BASE_MARGIN_V + extraSecLines * layout.secondaryLineHeight else 0
        priWriter.write("Dialogue: 0,$startAss,$endAss,Default,,0,0,$dynamicMarginV,,$priText")
        priWriter.newLine()
      }

      // 2. Secondary ASS dialogue (pure English)
      if (cleanedSec.isNotEmpty()) {
        secWriter.write("Dialogue: 0,$startAss,$endAss,Secondary,,0,0,0,,$cleanedSec")
        secWriter.newLine()
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
  // [Patch A-2/B-4] secondary-sub-ass-override 不再在此写死为 scale：
  // 由 SubtitleAssPolicy 按副字幕轨道类型决定（拆分生成的 [英] 用 scale，其他 ASS 轨道用 strip）
  MPVLib.setPropertyInt("secondary-sub-pos", secPosInt)
  val scaleByWindow = preferences.scaleByWindow.get()
  val scaleValue = if (scaleByWindow) "yes" else "no"
  MPVLib.setPropertyString("sub-ass-scale-with-window", scaleValue)
  MPVLib.setPropertyString("sub-ass-force-margins", scaleValue)

  // Calculate dynamic secondary-sub-scale ratio so any secondary track (including plain text and embedded subtitles)
  // accurately reflects user's secondary font size preferences instead of blowing up to full 720p base size
  val priFontSize = preferences.fontSize.get().coerceAtLeast(1)
  val effectiveScaleRatio = (fontSize.toFloat() / priFontSize.toFloat()) * subScale
  MPVLib.setPropertyFloat("secondary-sub-scale", effectiveScaleRatio)

  // Safely forward secondary-sub-* properties supported by newer mpv builds
  runCatching { MPVLib.setPropertyString("secondary-sub-font", assFont) }
  runCatching { MPVLib.setPropertyString("secondary-sub-color", textColor.toAssColorString()) }
  runCatching { MPVLib.setPropertyString("secondary-sub-border-color", borderColor.toAssColorString()) }
  runCatching { MPVLib.setPropertyFloat("secondary-sub-border-size", borderSize) }
  runCatching { MPVLib.setPropertyBoolean("secondary-sub-bold", isBold) }
  runCatching { MPVLib.setPropertyBoolean("secondary-sub-italic", isItalic) }
}
