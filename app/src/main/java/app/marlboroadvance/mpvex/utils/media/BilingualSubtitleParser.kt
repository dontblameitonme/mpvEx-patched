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

  fun splitIfBilingual(sourceFile: File, context: Context): BilingualSplitResult? {
    if (!sourceFile.exists() || sourceFile.length() <= 0) return null

    val ext = sourceFile.extension.lowercase(Locale.ROOT)
    if (ext !in listOf("ass", "ssa", "srt")) return null

    val cacheDir = File(context.cacheDir, "bilingual_subs")
    if (!cacheDir.exists()) cacheDir.mkdirs()

    val hashKey = ChecksumUtils.getCRC32(
      "${sourceFile.absolutePath}_${sourceFile.length()}_${sourceFile.lastModified()}"
    )
    val folder = File(cacheDir, hashKey)
    if (!folder.exists()) folder.mkdirs()

    val primaryFileName = "[中] ${sourceFile.name}"
    val secondaryFileName = "[英] ${sourceFile.nameWithoutExtension}.ass"
    val bilingualFileName = "[双语] ${sourceFile.nameWithoutExtension}.ass"
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
        primaryTitle = "[中] ${sourceFile.nameWithoutExtension}",
        secondaryTitle = "[英] ${sourceFile.nameWithoutExtension}",
        bilingualTitle = "[双语] ${sourceFile.nameWithoutExtension}",
      )
    }

    return try {
      val rawBytes = sourceFile.readBytes()
      val content = readTextWithAutoEncoding(rawBytes)
      val result = if (ext == "srt") {
        splitSrt(content, folder, primaryFile, secondaryFile, bilingualFile, sourceFile.nameWithoutExtension)
      } else {
        splitAss(content, folder, primaryFile, secondaryFile, bilingualFile, sourceFile.nameWithoutExtension)
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

      val cacheDir = File(context.cacheDir, "bilingual_subs")
      if (!cacheDir.exists()) cacheDir.mkdirs()

      val hashKey = ChecksumUtils.getCRC32("${uri}_${rawBytes.size}")
      val folder = File(cacheDir, hashKey)
      if (!folder.exists()) folder.mkdirs()

      val baseName = fileName.substringBeforeLast('.')
      val primaryFileName = "[中] $fileName"
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
    val lines = content.lines()
    var totalDialogues = 0
    var bilingualCount = 0

    // First quick pass: verify if content has bilingual dialogues
    for (line in lines) {
      if (line.startsWith("Dialogue:", ignoreCase = true)) {
        totalDialogues++
        val parts = line.split(",", limit = 10)
        if (parts.size == 10) {
          val text = parts[9]
          if (text.contains("\\N", ignoreCase = true)) {
            val subParts = text.split(ASS_SPLIT_REGEX)
            var hasCjkPart = false
            var hasLatinPart = false
            for (p in subParts) {
              val pClean = TAG_REGEX.replace(p, "")
              if (CJK_REGEX.containsMatchIn(pClean)) hasCjkPart = true
              else if (LATIN_REGEX.containsMatchIn(pClean)) hasLatinPart = true
            }
            if (hasCjkPart && hasLatinPart) {
              bilingualCount++
            }
          }
        }
      }
    }

    if (totalDialogues == 0 || bilingualCount < 5 || (bilingualCount.toDouble() / totalDialogues < 0.1 && bilingualCount < 20)) {
      Log.d(TAG, "Not a bilingual ASS subtitle (bilingual=$bilingualCount, total=$totalDialogues)")
      return null
    }

    Log.d(TAG, "Splitting bilingual ASS subtitle: $bilingualCount/$totalDialogues bilingual dialogues")

    val tempPri = File.createTempFile("pri_", ".tmp", folder)
    val tempSec = File.createTempFile("sec_", ".tmp", folder)
    val tempBi = File.createTempFile("bi_", ".tmp", folder)

    try {
      tempPri.bufferedWriter(Charsets.UTF_8).use { priWriter ->
        tempSec.bufferedWriter(Charsets.UTF_8).use { secWriter ->
          tempBi.bufferedWriter(Charsets.UTF_8).use { biWriter ->
            // Write standard ASS header with dedicated 'Secondary' style definition
            writeSecondaryAssHeader(secWriter, baseName)
            // Write bilingual ASS header with both 'Default' and 'Secondary' style definitions
            writeBilingualAssHeader(biWriter, baseName)

            for (line in lines) {
              if (!line.startsWith("Dialogue:", ignoreCase = true)) {
                priWriter.write(line)
                priWriter.newLine()
                continue
              }

              val parts = line.split(",", limit = 10)
              if (parts.size < 10) {
                priWriter.write(line)
                priWriter.newLine()
                continue
              }

              val startT = parts[1]
              val endT = parts[2]
              val rawText = parts[9]
              val subParts = rawText.split(ASS_SPLIT_REGEX)

              val priParts = mutableListOf<String>()
              val secParts = mutableListOf<String>()

              for (p in subParts) {
                val pClean = TAG_REGEX.replace(p, "")
                val hasCjk = CJK_REGEX.containsMatchIn(pClean)
                val hasLatin = LATIN_REGEX.containsMatchIn(pClean)

                if (hasCjk) {
                  priParts.add(p)
                } else if (hasLatin) {
                  secParts.add(cleanAssOverrideTags(p))
                } else {
                  // Numbers, punctuation, sound effects
                  if (priParts.isNotEmpty()) {
                    priParts.add(p)
                  } else {
                    priParts.add(p)
                  }
                }
              }

              // 1. Write primary ASS dialogue (pure Chinese)
              val priText = if (priParts.isNotEmpty()) priParts.joinToString("\\N") else ""
              val newPriLine = parts.take(9).joinToString(",") + "," + priText
              priWriter.write(newPriLine)
              priWriter.newLine()

              // 2. Write secondary ASS dialogue using 'Secondary' style (pure English)
              val cleanedSec = if (secParts.isNotEmpty()) {
                secParts
                  .map { TAG_REGEX.replace(it, "").replace("\\h", " ").trim() }
                  .filter { it.isNotEmpty() }
                  .joinToString("\\N")
              } else ""

              if (cleanedSec.isNotEmpty()) {
                secWriter.write("Dialogue: 0,$startT,$endT,Secondary,,0,0,0,,$cleanedSec")
                secWriter.newLine()
              }

              // 3. Write unified bilingual ASS dialogue (Solution 1:流式排版, zero overlap)
              if (priText.isNotEmpty() && cleanedSec.isNotEmpty()) {
                biWriter.write("Dialogue: 0,$startT,$endT,Default,,0,0,0,,$priText\\N{\\rSecondary}$cleanedSec")
                biWriter.newLine()
              } else if (priText.isNotEmpty()) {
                biWriter.write("Dialogue: 0,$startT,$endT,Default,,0,0,0,,$priText")
                biWriter.newLine()
              } else if (cleanedSec.isNotEmpty()) {
                biWriter.write("Dialogue: 0,$startT,$endT,Secondary,,0,0,0,,$cleanedSec")
                biWriter.newLine()
              }
            }
          }
        }
      }

      tempPri.renameTo(primaryFile)
      tempSec.renameTo(secondaryFile)
      tempBi.renameTo(bilingualFile)

      return BilingualSplitResult(
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
    val blocks = content.replace("\r\n", "\n").split("\n\n").filter { it.isNotBlank() }
    var totalCues = 0
    var bilingualCount = 0

    // Check if bilingual
    for (block in blocks) {
      val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
      if (lines.size >= 3 && lines[1].contains("-->")) {
        totalCues++
        val textLines = lines.drop(2)
        var hasCjk = false
        var hasLatin = false
        for (tl in textLines) {
          if (CJK_REGEX.containsMatchIn(tl)) hasCjk = true
          else if (LATIN_REGEX.containsMatchIn(tl)) hasLatin = true
        }
        if (hasCjk && hasLatin) {
          bilingualCount++
        }
      }
    }

    if (totalCues == 0 || bilingualCount < 5 || (bilingualCount.toDouble() / totalCues < 0.1 && bilingualCount < 20)) {
      Log.d(TAG, "Not a bilingual SRT subtitle (bilingual=$bilingualCount, total=$totalCues)")
      return null
    }

    Log.d(TAG, "Splitting bilingual SRT subtitle: $bilingualCount/$totalCues bilingual cues")

    val tempPri = File.createTempFile("pri_", ".tmp", folder)
    val tempSec = File.createTempFile("sec_", ".tmp", folder)
    val tempBi = File.createTempFile("bi_", ".tmp", folder)

    try {
      tempPri.bufferedWriter(Charsets.UTF_8).use { priWriter ->
        tempSec.bufferedWriter(Charsets.UTF_8).use { secWriter ->
          tempBi.bufferedWriter(Charsets.UTF_8).use { biWriter ->
            var priIndex = 1
            // Write standard ASS header with dedicated 'Secondary' style definition
            writeSecondaryAssHeader(secWriter, baseName)
            // Write bilingual ASS header with both 'Default' and 'Secondary' style definitions
            writeBilingualAssHeader(biWriter, baseName)

            for (block in blocks) {
              val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
              if (lines.size < 3 || !lines[1].contains("-->")) continue

              val timecode = lines[1]
              val textLines = lines.drop(2)

              val priLines = mutableListOf<String>()
              val secLines = mutableListOf<String>()

              for (tl in textLines) {
                val hasCjk = CJK_REGEX.containsMatchIn(tl)
                val hasLatin = LATIN_REGEX.containsMatchIn(tl)

                if (hasCjk) {
                  priLines.add(tl)
                } else if (hasLatin) {
                  secLines.add(tl)
                } else {
                  if (priLines.isNotEmpty()) priLines.add(tl)
                  else priLines.add(tl)
                }
              }

              // 1. Write primary SRT (pure Chinese)
              if (priLines.isNotEmpty()) {
                priWriter.write(priIndex.toString())
                priWriter.newLine()
                priWriter.write(timecode)
                priWriter.newLine()
                priWriter.write(priLines.joinToString("\n"))
                priWriter.newLine()
                priWriter.newLine()
                priIndex++
              }

              // 2. Write secondary ASS (pure English) & 3. Unified bilingual ASS
              val times = timecode.split("-->").map { it.trim() }
              if (times.size == 2) {
                val startAss = srtTimeToAss(times[0])
                val endAss = srtTimeToAss(times[1])
                val cleanedSec = secLines.joinToString("\\N")
                val priAssText = priLines.joinToString("\\N")

                if (cleanedSec.isNotEmpty()) {
                  secWriter.write("Dialogue: 0,$startAss,$endAss,Secondary,,0,0,0,,$cleanedSec")
                  secWriter.newLine()
                }

                if (priAssText.isNotEmpty() && cleanedSec.isNotEmpty()) {
                  biWriter.write("Dialogue: 0,$startAss,$endAss,Default,,0,0,0,,$priAssText\\N{\\rSecondary}$cleanedSec")
                  biWriter.newLine()
                } else if (priAssText.isNotEmpty()) {
                  biWriter.write("Dialogue: 0,$startAss,$endAss,Default,,0,0,0,,$priAssText")
                  biWriter.newLine()
                } else if (cleanedSec.isNotEmpty()) {
                  biWriter.write("Dialogue: 0,$startAss,$endAss,Secondary,,0,0,0,,$cleanedSec")
                  biWriter.newLine()
                }
              }
            }
          }
        }
      }

      tempPri.renameTo(primaryFile)
      tempSec.renameTo(secondaryFile)
      tempBi.renameTo(bilingualFile)

      return BilingualSplitResult(
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

  private fun cleanAssOverrideTags(text: String): String {
    var cleaned = ASS_OVERRIDE_TAGS_REGEX.replace(text, "")
    cleaned = cleaned.replace(Regex("\\{\\s*\\}"), "")
    return cleaned.trim()
  }

  fun srtTimeToAss(srtTime: String): String {
    val parts = srtTime.trim().split(":")
    if (parts.size != 3) return srtTime
    val h = parts[0].toIntOrNull() ?: 0
    val m = parts[1].toIntOrNull() ?: 0
    val secParts = parts[2].split(",", ".")
    val s = secParts.getOrNull(0)?.toIntOrNull() ?: 0
    val msStr = secParts.getOrNull(1) ?: "0"
    val cs = (msStr.padEnd(3, '0').take(3).toIntOrNull() ?: 0) / 10
    return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
  }

  /**
   * Writes a standard ASS header containing a dedicated 'Secondary' style definition.
   * This dedicated style allows mpv's sub-ass-style-overrides (e.g., Secondary.Fontsize=...)
   * to independently style the secondary subtitle in real time without affecting the primary track.
   */
  private fun writeSecondaryAssHeader(writer: BufferedWriter, baseName: String) {
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
    writer.write("PlayResX: 1920")
    writer.newLine()
    writer.write("PlayResY: 1080")
    writer.newLine()
    writer.newLine()
    writer.write("[V4+ Styles]")
    writer.newLine()
    writer.write("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
    writer.newLine()
    // Default Secondary style: Fontsize 45, Alignment 2 (bottom center), white text, black border, base MarginV 20
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
   * Enables Solution 1 flowing paragraph layout with independent style control via sub-ass-style-overrides.
   */
  private fun writeBilingualAssHeader(writer: BufferedWriter, baseName: String) {
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
    writer.write("PlayResX: 1920")
    writer.newLine()
    writer.write("PlayResY: 1080")
    writer.newLine()
    writer.newLine()
    writer.write("[V4+ Styles]")
    writer.newLine()
    writer.write("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
    writer.newLine()
    // Default style: Primary (Chinese), Fontsize 55, Alignment 2
    writer.write("Style: Default,sans-serif,55,&H00FFFFFF,&H000000FF,&H00000000,&HFF000000,0,0,0,0,100,100,0,0,1,2.0,0,2,10,10,20,1")
    writer.newLine()
    // Secondary style: Secondary (English), Fontsize 45, Alignment 2
    writer.write("Style: Secondary,sans-serif,45,&H00FFFFFF,&H000000FF,&H00000000,&HFF000000,0,0,0,0,100,100,0,0,1,2.0,0,2,10,10,20,1")
    writer.newLine()
    writer.newLine()
    writer.write("[Events]")
    writer.newLine()
    writer.write("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
    writer.newLine()
  }

  private fun assTimeToSrt(assTime: String): String {
    val parts = assTime.trim().split(":")
    if (parts.size != 3) return assTime
    val h = parts[0].toIntOrNull() ?: 0
    val m = parts[1].toIntOrNull() ?: 0
    val secParts = parts[2].split(".")
    val s = secParts.getOrNull(0)?.toIntOrNull() ?: 0
    val csStr = secParts.getOrNull(1) ?: "0"
    val ms = csStr.padEnd(3, '0').take(3).toIntOrNull() ?: 0
    return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms)
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
}
