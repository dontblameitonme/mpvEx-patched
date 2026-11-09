package app.marlboroadvance.mpvex.utils.media

import android.content.Context
import android.net.Uri
import android.util.Log
import app.marlboroadvance.mpvex.utils.media.ChecksumUtils
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs

/**
 * Pre-processes MKV embedded subtitle tracks by extracting them into standard external
 * SRT and bilingual ASS subtitle files in cache.
 *
 * This allows embedded subtitles to be displayed using the normal, pristine external
 * subtitle pipeline of commit 8b209fd without hacking mpv's native dual-track rendering.
 */
object MkvSubtitleExtractor {
  private const val TAG = "MkvSubtitleExtractor"
  private const val CACHE_DIR_NAME = "bilingual_subs_v7"

  data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
  )

  data class ExtractedTrack(
    val trackNum: Int,
    val lang: String,
    val name: String,
    val codec: String,
  )

  /**
   * Pre-processes embedded subtitles from an MKV file on Dispatchers.IO.
   * If Chinese and English tracks are found, extracts them into standard SRT / ASS files
   * and loads them into mpv as external subtitle tracks.
   */
  suspend fun extractAndLoadIfBilingual(
    videoFile: File,
    context: Context,
    selectBilingual: Boolean = true,
  ): BilingualSplitResult? = withContext(Dispatchers.IO) {
    if (!videoFile.exists() || videoFile.length() <= 0) return@withContext null
    val ext = videoFile.extension.lowercase(Locale.ROOT)
    if (ext != "mkv") return@withContext null

    val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
    if (!cacheDir.exists()) cacheDir.mkdirs()

    val hashKey = ChecksumUtils.getCRC32(
      "mkv_sub_${videoFile.absolutePath}_${videoFile.length()}_${videoFile.lastModified()}"
    )
    val folder = File(cacheDir, hashKey)
    if (!folder.exists()) folder.mkdirs()

    val baseName = videoFile.nameWithoutExtension
    val primaryFile = File(folder, "[中] $baseName.ass")
    val secondaryFile = File(folder, "[英] $baseName.ass")
    val bilingualFile = File(folder, "[双语] $baseName.ass")

    // Cache hit: 0ms execution
    if (primaryFile.exists() && primaryFile.length() > 0 &&
      secondaryFile.exists() && secondaryFile.length() > 0 &&
      bilingualFile.exists() && bilingualFile.length() > 0
    ) {
      Log.d(TAG, "Cache hit for extracted MKV subtitles: ${videoFile.name}")
      val result = BilingualSplitResult(
        primaryFile = primaryFile,
        secondaryFile = secondaryFile,
        bilingualFile = bilingualFile,
        primaryTitle = "[中] $baseName",
        secondaryTitle = "[英] $baseName",
        bilingualTitle = "[双语] $baseName",
      )
      loadExtractedSubtitles(result, selectBilingual)
      return@withContext result
    }

    try {
      val result = extractFromMkv(videoFile, folder, baseName, primaryFile, secondaryFile, bilingualFile)
      if (result != null) {
        loadExtractedSubtitles(result, selectBilingual)
      }
      result
    } catch (e: Exception) {
      Log.e(TAG, "Failed to extract embedded subtitles from ${videoFile.name}: ${e.message}", e)
      null
    }
  }

  private fun loadExtractedSubtitles(result: BilingualSplitResult, select: Boolean) {
    // Add primary track ([中])
    MPVLib.command("sub-add", result.primaryFile.absolutePath, "auto", result.primaryTitle)
    // Add secondary track ([英])
    MPVLib.command("sub-add", result.secondaryFile.absolutePath, "auto", result.secondaryTitle)
    // Add unified flowing bilingual track ([双语])
    val mode = if (select) "select" else "auto"
    MPVLib.command("sub-add", result.bilingualFile.absolutePath, mode, result.bilingualTitle)
    Log.d(TAG, "Loaded extracted MKV subtitles as external files: ${result.bilingualTitle}")
  }

  private fun extractFromMkv(
    videoFile: File,
    folder: File,
    baseName: String,
    primaryFile: File,
    secondaryFile: File,
    bilingualFile: File,
  ): BilingualSplitResult? {
    return RandomAccessFile(videoFile, "r").use { raf ->
      val header = ByteArray(4)
      raf.readFully(header)
      if (header[0] != 0x1A.toByte() || header[1] != 0x45.toByte() ||
        header[2] != 0xDF.toByte() || header[3] != 0xA3.toByte()
      ) {
        return null
      }

      // Read first 1MB to find Tracks element (0x1654AE6B)
      val initialBuf = ByteArray(1024 * 1024)
      val readLen = raf.read(initialBuf)
      if (readLen <= 0) return null

      val tracksOffset = findSequence(initialBuf, 0, readLen, byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B), skipFirst = 100)
      if (tracksOffset == -1) return null

      val tracksPos = 4L + tracksOffset
      raf.seek(tracksPos)

      val tracksId = readEbmlId(raf)
      if (tracksId != 0x1654AE6BL) return null
      val tracksSize = readEbmlVint(raf) ?: return null
      val tracksEnd = raf.filePointer + tracksSize

      val subTracks = mutableListOf<ExtractedTrack>()

      while (raf.filePointer < tracksEnd) {
        val elId = readEbmlId(raf) ?: break
        val elSize = readEbmlVint(raf) ?: break
        val elEnd = raf.filePointer + elSize

        if (elId == 0xAEL) { // TrackEntry
          var trkNum: Int? = null
          var trkType: Int? = null
          var trkCodec = ""
          var trkLang = ""
          var trkName = ""

          while (raf.filePointer < elEnd) {
            val subId = readEbmlId(raf) ?: break
            val subSize = readEbmlVint(raf) ?: break
            val subStart = raf.filePointer

            when (subId) {
              0xD7L -> trkNum = readInt(raf, subSize.toInt())
              0x83L -> trkType = readInt(raf, subSize.toInt())
              0x86L -> trkCodec = readString(raf, subSize.toInt())
              0x22B59CL -> trkLang = readString(raf, subSize.toInt()) // Language
              0x22B59DL -> if (trkLang.isBlank()) trkLang = readString(raf, subSize.toInt()) // LanguageBCP47
              0x536EL -> trkName = readString(raf, subSize.toInt()) // Name
              else -> raf.seek(subStart + subSize)
            }
          }

          if (trkType == 17 && trkNum != null) {
            subTracks.add(
              ExtractedTrack(
                trackNum = trkNum,
                lang = trkLang.lowercase(Locale.ROOT),
                name = trkName.lowercase(Locale.ROOT),
                codec = trkCodec,
              )
            )
          }
        } else {
          raf.seek(elEnd)
        }
      }

      Log.d(TAG, "Found ${subTracks.size} subtitle tracks in MKV")
      if (subTracks.isEmpty()) return null

      // Find Chinese and English tracks
      var chiTrack: Int? = null
      var engTrack: Int? = null

      for (trk in subTracks) {
        val l = trk.lang
        val n = trk.name
        val isChi = l in setOf("chi", "zho", "zh", "chs", "cht") ||
          n.contains("中") || n.contains("chs") || n.contains("cht") ||
          n.contains("chinese") || n.contains("simplified") || n.contains("traditional")
        val isEng = l in setOf("eng", "en") ||
          n.contains("英") || n.contains("eng") || n.contains("english")

        if (chiTrack == null && isChi) {
          chiTrack = trk.trackNum
        }
        if (engTrack == null && isEng) {
          engTrack = trk.trackNum
        }
      }

      val targetTrackMap = mutableMapOf<Int, String>()
      if (chiTrack != null) targetTrackMap[chiTrack] = "chi"
      if (engTrack != null) targetTrackMap[engTrack] = "eng"

      if (targetTrackMap.isEmpty()) return null

      // Locate first Cluster (0x1F43B675) after Tracks
      raf.seek(tracksEnd)
      val searchBytes = ByteArray(1024 * 1024 * 60)
      val searchLen = raf.read(searchBytes)
      if (searchLen <= 0) return null

      val clusterOffset = findSequence(searchBytes, 0, searchLen, byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75))
      if (clusterOffset == -1) return null

      val firstClusterPos = tracksEnd + clusterOffset
      raf.seek(firstClusterPos)

      val chiCues = mutableListOf<SubtitleCue>()
      val engCues = mutableListOf<SubtitleCue>()

      while (true) {
        val cid = readEbmlId(raf) ?: break
        if (cid != 0x1F43B675L) break
        val clSize = readEbmlVint(raf) ?: break
        val clEnd = raf.filePointer + clSize
        var clTimecode = 0L

        while (raf.filePointer < clEnd) {
          val subId = readEbmlId(raf) ?: break
          val subSize = readEbmlVint(raf) ?: break

          when (subId) {
            0xE7L -> { // Timecode
              clTimecode = readInt(raf, subSize.toInt()).toLong()
            }
            0xA0L -> { // BlockGroup
              val bgEnd = raf.filePointer + subSize
              var bgTrk: Int? = null
              var bgTime = 0L
              var bgText = ""
              var bgDur = 2500L

              while (raf.filePointer < bgEnd) {
                val bid = readEbmlId(raf) ?: break
                val bsz = readEbmlVint(raf) ?: break
                val bStart = raf.filePointer

                when (bid) {
                  0xA1L -> { // Block
                    val tnum = readEbmlVint(raf)?.toInt()
                    if (tnum != null && tnum in targetTrackMap) {
                      bgTrk = tnum
                      val relTc = raf.readShort().toLong()
                      raf.readByte() // flags
                      bgTime = clTimecode + relTc
                      val textLen = (bStart + bsz - raf.filePointer).toInt()
                      if (textLen > 0) {
                        val textBytes = ByteArray(textLen)
                        raf.readFully(textBytes)
                        bgText = String(textBytes, Charsets.UTF_8).trim()
                      }
                    } else {
                      raf.seek(bStart + bsz)
                    }
                  }
                  0x9BL -> { // BlockDuration
                    bgDur = readInt(raf, bsz.toInt()).toLong()
                  }
                  else -> raf.seek(bStart + bsz)
                }
              }

              if (bgTrk != null && bgText.isNotEmpty()) {
                val cue = SubtitleCue(bgTime, bgTime + bgDur, bgText)
                if (targetTrackMap[bgTrk] == "chi") chiCues.add(cue) else engCues.add(cue)
              }
            }
            0xA3L -> { // SimpleBlock
              val blkStart = raf.filePointer
              val tnum = readEbmlVint(raf)?.toInt()
              if (tnum != null && tnum in targetTrackMap) {
                val relTc = raf.readShort().toLong()
                raf.readByte() // flags
                val absTime = clTimecode + relTc
                val textLen = (blkStart + subSize - raf.filePointer).toInt()
                if (textLen > 0) {
                  val textBytes = ByteArray(textLen)
                  raf.readFully(textBytes)
                  val text = String(textBytes, Charsets.UTF_8).trim()
                  val cue = SubtitleCue(absTime, absTime + 2500L, text)
                  if (targetTrackMap[tnum] == "chi") chiCues.add(cue) else engCues.add(cue)
                }
              } else {
                raf.seek(blkStart + subSize)
              }
            }
            else -> raf.seek(raf.filePointer + subSize)
          }
        }
      }

      Log.d(TAG, "Extracted ${chiCues.size} Chinese cues and ${engCues.size} English cues from MKV")
      if (chiCues.isEmpty() && engCues.isEmpty()) return null

      // Write aligned cues into standard primary, secondary, and bilingual ASS files
      val alignedCues = alignCues(chiCues, engCues)

      primaryFile.bufferedWriter(Charsets.UTF_8).use { priWriter ->
        secondaryFile.bufferedWriter(Charsets.UTF_8).use { secWriter ->
          bilingualFile.bufferedWriter(Charsets.UTF_8).use { biWriter ->
            writeAssHeader(priWriter, "[中] $baseName", isPrimary = true)
            writeAssHeader(secWriter, "[英] $baseName", isPrimary = false)
            writeBilingualAssHeader(biWriter, "[双语] $baseName")

            for (cue in alignedCues) {
              val startAss = BilingualSubtitleParser.msToAssTime(cue.startMs)
              val endAss = BilingualSubtitleParser.msToAssTime(cue.endMs)

              val priText = cue.priParts.joinToString(" ")
              val secText = cue.secParts.joinToString(" ")

              if (priText.isNotEmpty()) {
                priWriter.write("Dialogue: 0,$startAss,$endAss,Default,,0,0,0,,$priText")
                priWriter.newLine()
              }
              if (secText.isNotEmpty()) {
                secWriter.write("Dialogue: 0,$startAss,$endAss,Secondary,,0,0,0,,$secText")
                secWriter.newLine()
              }
              if (priText.isNotEmpty() && secText.isNotEmpty()) {
                biWriter.write("Dialogue: 0,$startAss,$endAss,Default,,0,0,0,,$priText\\N{\\rSecondary}$secText")
                biWriter.newLine()
              } else if (priText.isNotEmpty()) {
                biWriter.write("Dialogue: 0,$startAss,$endAss,Default,,0,0,0,,$priText")
                biWriter.newLine()
              } else if (secText.isNotEmpty()) {
                biWriter.write("Dialogue: 0,$startAss,$endAss,Secondary,,0,0,0,,$secText")
                biWriter.newLine()
              }
            }
          }
        }
      }

      BilingualSplitResult(
        primaryFile = primaryFile,
        secondaryFile = secondaryFile,
        bilingualFile = bilingualFile,
        primaryTitle = "[中] $baseName",
        secondaryTitle = "[英] $baseName",
        bilingualTitle = "[双语] $baseName",
      )
    }
  }

  private fun alignCues(
    chiCues: List<SubtitleCue>,
    engCues: List<SubtitleCue>,
  ): List<BilingualSubtitleParser.CueItem> {
    if (chiCues.isEmpty() && engCues.isEmpty()) return emptyList()
    if (chiCues.isEmpty()) {
      return engCues.map { BilingualSubtitleParser.CueItem(it.startMs, it.endMs, emptyList(), listOf(it.text)) }
    }
    if (engCues.isEmpty()) {
      return chiCues.map { BilingualSubtitleParser.CueItem(it.startMs, it.endMs, listOf(it.text), emptyList()) }
    }

    val matched = mutableListOf<BilingualSubtitleParser.CueItem>()
    val usedEng = BooleanArray(engCues.size)

    for (chi in chiCues) {
      var bestEngIdx = -1
      var bestOverlap = 0L

      for (j in engCues.indices) {
        if (usedEng[j]) continue
        val eng = engCues[j]
        val overlapStart = maxOf(chi.startMs, eng.startMs)
        val overlapEnd = minOf(chi.endMs, eng.endMs)
        val overlap = overlapEnd - overlapStart
        if (overlap > 0 && overlap > bestOverlap) {
          bestOverlap = overlap
          bestEngIdx = j
        } else if (bestOverlap == 0L && abs(chi.startMs - eng.startMs) <= 800) {
          bestEngIdx = j
        }
      }

      if (bestEngIdx != -1) {
        usedEng[bestEngIdx] = true
        val eng = engCues[bestEngIdx]
        val start = minOf(chi.startMs, eng.startMs)
        val end = maxOf(chi.endMs, eng.endMs)
        matched.add(BilingualSubtitleParser.CueItem(start, end, listOf(chi.text), listOf(eng.text)))
      } else {
        matched.add(BilingualSubtitleParser.CueItem(chi.startMs, chi.endMs, listOf(chi.text), emptyList()))
      }
    }

    // Add remaining unmatched English cues
    for (j in engCues.indices) {
      if (!usedEng[j]) {
        val eng = engCues[j]
        matched.add(BilingualSubtitleParser.CueItem(eng.startMs, eng.endMs, emptyList(), listOf(eng.text)))
      }
    }

    return matched.sortedBy { it.startMs }
  }

  private fun writeAssHeader(writer: java.io.BufferedWriter, title: String, isPrimary: Boolean) {
    writer.write("[Script Info]\nTitle: $title\nScriptType: v4.00+\nWrapStyle: 0\nScaledBorderAndShadow: yes\nYCbCr Matrix: None\nPlayResX: 1920\nPlayResY: 1080\n\n[V4+ Styles]\n")
    writer.write("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
    if (isPrimary) {
      writer.write("Style: Default,sans-serif,55,&H00FFFFFF,&H000000FF,&H00000000,&HFF000000,0,0,0,0,100,100,0,0,1,2.0,0,2,10,10,20,1\n\n")
    } else {
      writer.write("Style: Secondary,sans-serif,45,&H00FFFFFF,&H000000FF,&H00000000,&HFF000000,0,0,0,0,100,100,0,0,1,2.0,0,2,10,10,20,1\n\n")
    }
    writer.write("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
  }

  private fun writeBilingualAssHeader(writer: java.io.BufferedWriter, title: String) {
    writer.write("[Script Info]\nTitle: $title\nScriptType: v4.00+\nWrapStyle: 0\nScaledBorderAndShadow: yes\nYCbCr Matrix: None\nPlayResX: 1920\nPlayResY: 1080\n\n[V4+ Styles]\n")
    writer.write("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
    writer.write("Style: Default,sans-serif,55,&H00FFFFFF,&H000000FF,&H00000000,&HFF000000,0,0,0,0,100,100,0,0,1,2.0,0,2,10,10,20,1\n")
    writer.write("Style: Secondary,sans-serif,45,&H00FFFFFF,&H000000FF,&H00000000,&HFF000000,0,0,0,0,100,100,0,0,1,2.0,0,2,10,10,20,1\n\n")
    writer.write("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
  }

  private fun readEbmlId(raf: RandomAccessFile): Long? {
    val b0 = raf.read()
    if (b0 == -1) return null
    var mask = 0x80
    var len = 1
    while (mask > 0 && (b0 and mask) == 0) {
      mask = mask shr 1
      len++
    }
    if (len > 4) return null
    var id = b0.toLong()
    for (i in 1 until len) {
      val b = raf.read()
      if (b == -1) return null
      id = (id shl 8) or b.toLong()
    }
    return id
  }

  private fun readEbmlVint(raf: RandomAccessFile): Long? {
    val b0 = raf.read()
    if (b0 == -1) return null
    var mask = 0x80
    var len = 1
    while (mask > 0 && (b0 and mask) == 0) {
      mask = mask shr 1
      len++
    }
    if (len > 8) return null
    var value = (b0 and (mask - 1)).toLong()
    for (i in 1 until len) {
      val b = raf.read()
      if (b == -1) return null
      value = (value shl 8) or b.toLong()
    }
    return value
  }

  private fun readInt(raf: RandomAccessFile, length: Int): Int {
    var result = 0
    for (i in 0 until length) {
      val b = raf.read()
      if (b == -1) break
      result = (result shl 8) or (b and 0xFF)
    }
    return result
  }

  private fun readString(raf: RandomAccessFile, length: Int): String {
    if (length <= 0) return ""
    val bytes = ByteArray(length)
    raf.readFully(bytes)
    return String(bytes, Charsets.UTF_8).trimEnd { it == '\u0000' }
  }

  private fun findSequence(data: ByteArray, start: Int, length: Int, target: ByteArray, skipFirst: Int = 0): Int {
    if (length < target.size) return -1
    val limit = start + length - target.size
    for (i in (start + skipFirst)..limit) {
      var found = true
      for (j in target.indices) {
        if (data[i + j] != target[j]) {
          found = false
          break
        }
      }
      if (found) return i
    }
    return -1
  }
}
