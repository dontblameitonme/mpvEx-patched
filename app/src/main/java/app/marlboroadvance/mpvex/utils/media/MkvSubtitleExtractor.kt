package app.marlboroadvance.mpvex.utils.media

import android.content.Context
import android.net.Uri
import android.util.Log
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * High-speed, buffered-channel MKV (EBML/Matroska) embedded subtitle extractor.
 *
 * Key design choices:
 * 1. Uses a 4 MB in-memory window over [FileChannel] so scanning 10+ GB 4K MKV files issues
 *    only a few thousand syscalls instead of millions of single-byte reads.
 * 2. Properly follows `subStart + subSize` boundaries for every EBML element (including
 *    `SeekHead`, `Attachments`, `Tags`, `Chapters`, `Cues`, and `LanguageBCP47`), preventing
 *    skipped tracks and false magic-byte matches inside `SeekHead`.
 * 3. Scores tracks cleanly (`Forced`/`SDH`/`Commentary` penalized; Simplified > Generic >
 *    Traditional) and probes unlabeled (`und` / empty-language) text tracks in the same
 *    single pass so real-world files with missing language tags still pair accurately.
 * 4. Emits pure standardized `.srt` files for individual languages (`[中]`, `[英]`, `[标准]`)
 *    plus a flowing non-overlapping `.ass` (`[双语]`) for Dual mode.
 */
object MkvSubtitleExtractor {
  private const val TAG = "MkvSubtitleExtractor"
  private const val BUFFER_CAPACITY = 4 * 1024 * 1024 // 4 MB sequential read window

  private const val ID_EBML = 0x1A45DFA3L
  private const val ID_SEGMENT = 0x18538067L
  private const val ID_INFO = 0x1549A966L
  private const val ID_TIMECODE_SCALE = 0x2AD7B1L
  private const val ID_TRACKS = 0x1654AE6BL
  private const val ID_TRACK_ENTRY = 0xAEL
  private const val ID_TRACK_NUMBER = 0xD7L
  private const val ID_TRACK_TYPE = 0x83L
  private const val ID_FLAG_DEFAULT = 0x88L
  private const val ID_FLAG_FORCED = 0x55AAL
  private const val ID_FLAG_HEARING_IMPAIRED = 0x55ACL
  private const val ID_NAME = 0x536EL
  private const val ID_LANGUAGE = 0x22B59CL
  private const val ID_LANGUAGE_BCP47 = 0x22B59DL
  private const val ID_CODEC_ID = 0x86L
  private const val ID_CLUSTER = 0x1F43B675L
  private const val ID_CLUSTER_TIMECODE = 0xE7L
  private const val ID_BLOCK_GROUP = 0xA0L
  private const val ID_BLOCK = 0xA1L
  private const val ID_BLOCK_DURATION = 0x9BL
  private const val ID_SIMPLE_BLOCK = 0xA3L
  private const val ID_CUES = 0x1C53BB6BL
  private const val ID_TAGS = 0x1254C367L
  private const val ID_CHAPTERS = 0x1043A770L
  private const val ID_ATTACHMENTS = 0x1941A469L
  private const val ID_SEEK_HEAD = 0x114D9B74L

  private const val TRACK_TYPE_SUBTITLE = 0x11

  /**
   * Extracts and normalizes embedded text subtitle tracks from a local MKV path or `content://` URI.
   * Returns null quickly when the media is not an MKV/Matroska container or holds no text tracks.
   */
  suspend fun extractSubtitles(
    videoSource: String,
    context: Context,
    preferences: SubtitlesPreferences? = null,
    displayNameHint: String? = null,
  ): BilingualSplitResult? = withContext(Dispatchers.IO) {
    val ctx = coroutineContext

    if (videoSource.startsWith("content://")) {
      val uri = runCatching { Uri.parse(videoSource) }.getOrNull() ?: return@withContext null
      val resolvedName = displayNameHint
        ?: resolveContentDisplayName(uri, context)
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "video.mkv"

      val ext = resolvedName.substringAfterLast('.', "").lowercase(Locale.ROOT)
      if (ext.isNotEmpty() && ext !in setOf("mkv", "webm", "mka", "mks")) {
        // Still allow missing extension (some SAF providers omit extension) — EBML check verifies magic bytes.
        if (ext in setOf("mp4", "m4v", "mov", "avi", "ts", "m2ts", "flv", "wmv")) return@withContext null
      }

      return@withContext runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
          val size = pfd.statSize
          if (size <= 0L) return@use null
          val key = BilingualSubtitleParser.computeCacheKey(videoSource, size, 0L)
          val targetDir = BilingualSubtitleParser.getEntryCacheDir(context, key)
          val baseName = BilingualSubtitleParser.sanitizeFileNamePart(
            resolvedName.substringBeforeLast('.', resolvedName),
          )
          findCachedMkvResult(targetDir, baseName)?.let { return@use it }

          FileInputStream(pfd.fileDescriptor).use { fis ->
            val ch = fis.channel
            extractFromChannel(
              channel = ch,
              fileSize = ch.size(),
              baseName = baseName,
              targetDir = targetDir,
              preferences = preferences,
              checkActive = { ctx.ensureActive() },
            )
          }
        }
      }.onFailure {
        if (it is kotlinx.coroutines.CancellationException) throw it
        Log.w(TAG, "content:// MKV extraction failed: ${it.message}")
      }.getOrNull()
    }

    val file = File(videoSource)
    if (!file.exists() || !file.canRead() || file.length() < 64L) return@withContext null
    val ext = file.extension.lowercase(Locale.ROOT)
    if (ext !in setOf("mkv", "webm", "mka", "mks")) return@withContext null

    val key = BilingualSubtitleParser.computeCacheKey(file.absolutePath, file.length(), file.lastModified())
    val targetDir = BilingualSubtitleParser.getEntryCacheDir(context, key)
    val baseName = BilingualSubtitleParser.sanitizeFileNamePart(file.nameWithoutExtension)

    findCachedMkvResult(targetDir, baseName)?.let { return@withContext it }

    return@withContext runCatching {
      FileInputStream(file).use { fis ->
        val ch = fis.channel
        extractFromChannel(
          channel = ch,
          fileSize = ch.size(),
          baseName = baseName,
          targetDir = targetDir,
          preferences = preferences,
          checkActive = { ctx.ensureActive() },
        )
      }
    }.onFailure {
      if (it is kotlinx.coroutines.CancellationException) throw it
      Log.w(TAG, "File MKV extraction failed for ${file.name}: ${it.message}")
    }.getOrNull()
  }

  private fun resolveContentDisplayName(uri: Uri, context: Context): String? =
    runCatching {
      context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
      }
    }.getOrNull()

  private fun findCachedMkvResult(dir: File, baseName: String): BilingualSplitResult? {
    val pri = File(dir, "[中] $baseName.srt")
    val sec = File(dir, "[英] $baseName.srt")
    val bi = File(dir, "[双语] $baseName.ass")
    val hasPri = BilingualSubtitleParser.isValidSrtFile(pri)
    val hasSec = BilingualSubtitleParser.isValidSrtFile(sec)
    val hasBi = BilingualSubtitleParser.isValidAssFile(bi)
    if (hasPri && hasSec && hasBi) {
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
    if (hasPri && !hasSec && !hasBi) {
      return BilingualSplitResult(primaryFile = pri, primaryTitle = "[中] $baseName", isBilingual = false)
    }
    if (!hasPri && hasSec && !hasBi) {
      return BilingualSplitResult(secondaryFile = sec, secondaryTitle = "[英] $baseName", isBilingual = false)
    }
    val std = File(dir, "[标准] $baseName.srt")
    if (BilingualSubtitleParser.isValidSrtFile(std)) {
      return BilingualSplitResult(primaryFile = std, primaryTitle = "[标准] $baseName", isBilingual = false)
    }
    return null
  }

  // ---------------------------------------------------------------------------
  // Pure Kotlin Channel Parser & Extractor (Directly Unit-Testable on JVM!)
  // ---------------------------------------------------------------------------

  data class MkvSubtitleTrackMeta(
    val trackNumber: Int,
    val codecId: String,
    val language: String,
    val languageBcp47: String,
    val name: String,
    val flagDefault: Boolean,
    val flagForced: Boolean,
    val flagHearingImpaired: Boolean,
  ) {
    val isAssCodec: Boolean
      get() = codecId.contains("ASS", ignoreCase = true) || codecId.contains("SSA", ignoreCase = true)
  }

  private enum class LangBucket {
    CHINESE,
    ENGLISH,
    OTHER_KNOWN,
    AMBIGUOUS,
  }

  private data class CapturedRawCue(
    val startMs: Long,
    val endMs: Long,
    val hasExplicitDuration: Boolean,
    val lines: List<String>,
  ) {
    val joinedText: String by lazy { BilingualSubtitleParser.joinNormalizedLines(lines) }
  }

  fun extractFromChannel(
    channel: FileChannel,
    fileSize: Long,
    baseName: String,
    targetDir: File,
    preferences: SubtitlesPreferences? = null,
    checkActive: () -> Unit = {},
  ): BilingualSplitResult? {
    if (fileSize < 64L) return null
    val reader = BufferedChannelReader(channel, fileSize)

    // Verify EBML magic signature right at file offset 0 (never false-match SeekHead).
    val firstId = reader.readEbmlId()
    if (firstId != ID_EBML) return null
    val ebmlSize = reader.readVintSize() ?: return null
    if (ebmlSize.sizeBytes >= 0) {
      reader.seek(reader.position + ebmlSize.sizeBytes)
    }

    // Locate top-level Segment element cleanly.
    var segEnd = -1L
    while (reader.position < fileSize) {
      checkActive()
      val id = reader.readEbmlId() ?: break
      val sz = reader.readVintSize() ?: break
      val payloadStart = reader.position
      val elemEnd = if (sz.isUnknown) fileSize else min(fileSize, payloadStart + sz.sizeBytes)
      if (id == ID_SEGMENT) {
        segEnd = elemEnd
        break
      }
      if (sz.isUnknown || elemEnd <= payloadStart) break
      reader.seek(elemEnd)
    }
    if (segEnd <= 0L) return null

    var timecodeScaleNs = 1_000_000L
    val allTextTracks = mutableListOf<MkvSubtitleTrackMeta>()
    var tracksParsed = false

    // Phase 1 & 2 interleaved along Segment children: collect Info & Tracks first,
    // skipping SeekHead / Attachments / Chapters / Tags / Cues without reading them into RAM.
    // Once Clusters begin AND Tracks exist, decode subtitle packets on the fly.
    var selectedChi: MkvSubtitleTrackMeta? = null
    var selectedEng: MkvSubtitleTrackMeta? = null
    val trackBuffers = mutableMapOf<Int, MutableList<CapturedRawCue>>()
    var ambiguousIds = emptySet<Int>()
    var targetIds = emptySet<Int>()

    fun prepareTrackTargets() {
      if (allTextTracks.isEmpty()) return
      selectedChi = pickBestChineseTrack(allTextTracks)
      selectedEng = pickBestEnglishTrack(allTextTracks)

      val ids = mutableSetOf<Int>()
      selectedChi?.let { ids.add(it.trackNumber) }
      selectedEng?.let { ids.add(it.trackNumber) }

      // Whenever either Chinese or English wasn't declared via metadata (e.g. Good.Night.Oppy
      // Track #8 `SDH` with empty lang, or Explained `und`), capture non-forced ambiguous/other
      // text tracks during the exact same sequential pass for lightweight language classification.
      if (selectedChi == null || selectedEng == null) {
        val extras = allTextTracks
          .asSequence()
          .filter { t ->
            t.trackNumber !in ids &&
              !isForcedOrCommentary(t) &&
              classifyTrackBucket(t) != LangBucket.OTHER_KNOWN
          }
          .take(6)
          .map { it.trackNumber }
          .toSet()
        ambiguousIds = extras
        ids.addAll(extras)
      }

      // If metadata declared ONLY non-zh/en tracks (e.g. Ukrainian-only), still allow cleaning one track.
      if (ids.isEmpty()) {
        allTextTracks.firstOrNull { !isForcedOrCommentary(it) }?.let { fallback ->
          ambiguousIds = setOf(fallback.trackNumber)
          ids.add(fallback.trackNumber)
        }
      }

      targetIds = ids
      for (id in ids) {
        trackBuffers[id] = mutableListOf()
      }
    }

    var clusterCount = 0
    while (reader.position < segEnd) {
      checkActive()
      val cid = reader.readEbmlId() ?: break
      val csz = reader.readVintSize() ?: break
      val cStart = reader.position
      val cEnd = if (csz.isUnknown) segEnd else min(segEnd, cStart + csz.sizeBytes)

      when (cid) {
        ID_INFO -> {
          timecodeScaleNs = parseSegmentInfoTimecodeScale(reader, cEnd)
          reader.seek(cEnd)
        }

        ID_TRACKS -> {
          parseTracksElement(reader, cEnd, allTextTracks)
          tracksParsed = true
          prepareTrackTargets()
          reader.seek(cEnd)
        }

        ID_CLUSTER -> {
          if (!tracksParsed || targetIds.isEmpty()) {
            if (csz.isUnknown) break
            reader.seek(cEnd)
          } else {
            clusterCount++
            if ((clusterCount and 0x3F) == 0) checkActive()
            val stoppedAtTopLevel = parseClusterSubtitles(
              reader = reader,
              clusterEnd = cEnd,
              clusterIsUnknownSize = csz.isUnknown,
              timecodeScaleNs = timecodeScaleNs,
              tracksById = allTextTracks.associateBy { it.trackNumber },
              targetIds = targetIds,
              out = trackBuffers,
            )
            if (!csz.isUnknown && !stoppedAtTopLevel) {
              reader.seek(cEnd)
            }
          }
        }

        ID_SEEK_HEAD, ID_CUES, ID_TAGS, ID_CHAPTERS, ID_ATTACHMENTS -> {
          if (csz.isUnknown) break
          reader.seek(cEnd)
        }

        else -> {
          if (csz.isUnknown || cEnd <= cStart) break
          reader.seek(cEnd)
        }
      }
    }

    // Resolve final Chinese, English, or Standard tracks using header selection + text probing.
    val trackMap = allTextTracks.associateBy { it.trackNumber }
    var resolvedChiTrack = selectedChi
    var resolvedEngTrack = selectedEng
    var resolvedOtherTrack: MkvSubtitleTrackMeta? = null

    if (ambiguousIds.isNotEmpty() && (resolvedChiTrack == null || resolvedEngTrack == null)) {
      val scoredAmbiguous = ambiguousIds.mapNotNull { id ->
        val meta = trackMap[id] ?: return@mapNotNull null
        val cues = trackBuffers[id].orEmpty()
        if (cues.isEmpty()) return@mapNotNull null
        Triple(meta, cues, inspectCueLanguageSample(cues))
      }

      if (resolvedChiTrack == null) {
        resolvedChiTrack = scoredAmbiguous
          .filter { (_, _, sample) -> sample.cjkRatio >= 0.25 }
          .maxWithOrNull(
            compareBy<Triple<MkvSubtitleTrackMeta, List<CapturedRawCue>, LanguageProbe>> {
              scoreChineseTrack(it.first)
            }.thenBy { it.second.size },
          )?.first
      }

      if (resolvedEngTrack == null) {
        resolvedEngTrack = scoredAmbiguous
          .filter { (meta, _, sample) ->
            meta.trackNumber != resolvedChiTrack?.trackNumber &&
              sample.cjkRatio < 0.15 &&
              sample.asciiLetterRatio >= 0.55
          }
          .maxWithOrNull(
            compareBy<Triple<MkvSubtitleTrackMeta, List<CapturedRawCue>, LanguageProbe>> {
              scoreEnglishTrack(it.first)
            }.thenBy { it.second.size },
          )?.first
      }

      if (resolvedChiTrack == null && resolvedEngTrack == null) {
        resolvedOtherTrack = scoredAmbiguous
          .maxByOrNull { it.second.size }
          ?.first
      }
    }

    val chiRaw = resolvedChiTrack?.let { trackBuffers[it.trackNumber].orEmpty() }.orEmpty()
    val engRaw = resolvedEngTrack?.let { trackBuffers[it.trackNumber].orEmpty() }.orEmpty()

    // Fallback: if an MKV contains ONE internally-bilingual track (e.g., fansub Chinese+English
    // in a single S_TEXT/ASS or S_TEXT/UTF8 stream), split it via processTextToCache instead of
    // concatenating both languages onto one line.
    if (chiRaw.isNotEmpty() && engRaw.isEmpty() && isInternallyBilingualTrack(chiRaw)) {
      splitSingleInternallyBilingualTrack(chiRaw, baseName, targetDir, preferences)?.let { return it }
    } else if (chiRaw.isEmpty() && engRaw.isEmpty()) {
      val otherRaw = resolvedOtherTrack?.let { trackBuffers[it.trackNumber].orEmpty() }.orEmpty()
      if (otherRaw.isNotEmpty() && isInternallyBilingualTrack(otherRaw)) {
        splitSingleInternallyBilingualTrack(otherRaw, baseName, targetDir, preferences)?.let { return it }
      }
    }

    val chiCues = finalizePlainCues(chiRaw)
    val engCues = finalizePlainCues(engRaw)

    if (chiCues.isEmpty() && engCues.isEmpty()) {
      val otherCues = resolvedOtherTrack?.let { finalizePlainCues(trackBuffers[it.trackNumber].orEmpty()) }.orEmpty()
      if (otherCues.isEmpty()) return null
      val stdFile = File(targetDir, "[标准] $baseName.srt")
      if (!BilingualSubtitleParser.writeSrtFile(otherCues, stdFile)) return null
      return BilingualSplitResult(
        primaryFile = stdFile,
        primaryTitle = "[标准] $baseName",
        isBilingual = false,
      )
    }

    val priFile = File(targetDir, "[中] $baseName.srt")
    val secFile = File(targetDir, "[英] $baseName.srt")
    val biFile = File(targetDir, "[双语] $baseName.ass")

    val wrotePri = chiCues.isNotEmpty() && BilingualSubtitleParser.writeSrtFile(chiCues, priFile)
    val wroteSec = engCues.isNotEmpty() && BilingualSubtitleParser.writeSrtFile(engCues, secFile)

    if (wrotePri && wroteSec) {
      val aligned = BilingualSubtitleParser.alignCueStreams(chiCues, engCues)
      val wroteBi = aligned.isNotEmpty() && BilingualSubtitleParser.writeBilingualAssFile(aligned, biFile, preferences)
      return BilingualSplitResult(
        primaryFile = priFile,
        secondaryFile = secFile,
        bilingualFile = biFile.takeIf { wroteBi },
        primaryTitle = "[中] $baseName",
        secondaryTitle = "[英] $baseName",
        bilingualTitle = if (wroteBi) "[双语] $baseName" else null,
        isBilingual = true,
      )
    }

    if (wrotePri) {
      return BilingualSplitResult(
        primaryFile = priFile,
        primaryTitle = "[中] $baseName",
        isBilingual = false,
      )
    }

    if (wroteSec) {
      return BilingualSplitResult(
        secondaryFile = secFile,
        secondaryTitle = "[英] $baseName",
        isBilingual = false,
      )
    }

    return null
  }

  // ---------------------------------------------------------------------------
  // EBML Element Parsers
  // ---------------------------------------------------------------------------

  private fun parseSegmentInfoTimecodeScale(reader: BufferedChannelReader, infoEnd: Long): Long {
    var scale = 1_000_000L
    while (reader.position < infoEnd) {
      val id = reader.readEbmlId() ?: break
      val sz = reader.readVintSize() ?: break
      val payloadStart = reader.position
      val elemEnd = min(infoEnd, payloadStart + max(0L, sz.sizeBytes))
      if (id == ID_TIMECODE_SCALE && sz.sizeBytes in 1L..8L) {
        scale = reader.readUnsignedInt(sz.sizeBytes.toInt()).coerceAtLeast(1L)
      }
      if (elemEnd <= payloadStart && sz.sizeBytes == 0L) {
        reader.seek(elemEnd)
      } else if (elemEnd > payloadStart) {
        reader.seek(elemEnd)
      } else {
        break
      }
    }
    return scale
  }

  private fun parseTracksElement(
    reader: BufferedChannelReader,
    tracksEnd: Long,
    out: MutableList<MkvSubtitleTrackMeta>,
  ) {
    while (reader.position < tracksEnd) {
      val id = reader.readEbmlId() ?: break
      val sz = reader.readVintSize() ?: break
      val start = reader.position
      val end = min(tracksEnd, start + max(0L, sz.sizeBytes))
      if (id == ID_TRACK_ENTRY && end > start) {
        parseSingleTrackEntry(reader, end)?.let { out.add(it) }
      }
      if (end > start) {
        reader.seek(end)
      } else if (sz.sizeBytes == 0L) {
        reader.seek(end)
      } else {
        break
      }
    }
  }

  private fun parseSingleTrackEntry(reader: BufferedChannelReader, entryEnd: Long): MkvSubtitleTrackMeta? {
    var trackNum = -1
    var trackType = -1
    var codecId = ""
    var lang = ""
    var langBcp47 = ""
    var name = ""
    var flagDefault = true // Matroska spec default = 1
    var flagForced = false
    var flagHearingImpaired = false

    while (reader.position < entryEnd) {
      val id = reader.readEbmlId() ?: break
      val sz = reader.readVintSize() ?: break
      val start = reader.position
      val end = min(entryEnd, start + max(0L, sz.sizeBytes))
      val len = (end - start).toInt().coerceAtLeast(0)

      when (id) {
        ID_TRACK_NUMBER -> if (len in 1..4) trackNum = reader.readUnsignedInt(len).toInt()
        ID_TRACK_TYPE -> if (len in 1..4) trackType = reader.readUnsignedInt(len).toInt()
        ID_FLAG_DEFAULT -> if (len in 1..4) flagDefault = reader.readUnsignedInt(len) != 0L
        ID_FLAG_FORCED -> if (len in 1..4) flagForced = reader.readUnsignedInt(len) != 0L
        ID_FLAG_HEARING_IMPAIRED -> if (len in 1..4) flagHearingImpaired = reader.readUnsignedInt(len) != 0L
        ID_LANGUAGE -> lang = reader.readAsciiOrUtf8(len).lowercase(Locale.ROOT)
        ID_LANGUAGE_BCP47 -> langBcp47 = reader.readAsciiOrUtf8(len).lowercase(Locale.ROOT)
        ID_NAME -> name = reader.readAsciiOrUtf8(len)
        ID_CODEC_ID -> codecId = reader.readAsciiOrUtf8(len).uppercase(Locale.ROOT)
      }

      if (end > start) {
        reader.seek(end)
      } else if (sz.sizeBytes == 0L) {
        reader.seek(end)
      } else {
        break
      }
    }

    if (trackNum <= 0 || trackType != TRACK_TYPE_SUBTITLE) return null
    if (!codecId.startsWith("S_TEXT/")) return null

    return MkvSubtitleTrackMeta(
      trackNumber = trackNum,
      codecId = codecId,
      language = lang,
      languageBcp47 = langBcp47,
      name = name,
      flagDefault = flagDefault,
      flagForced = flagForced,
      flagHearingImpaired = flagHearingImpaired,
    )
  }

  /**
   * Parses subtitle blocks inside one `Cluster`.
   * Returns `true` only if an unknown-size Cluster terminated because it hit the next top-level
   * Segment child element (leaving reader positioned at that next top-level ID).
   */
  private fun parseClusterSubtitles(
    reader: BufferedChannelReader,
    clusterEnd: Long,
    clusterIsUnknownSize: Boolean,
    timecodeScaleNs: Long,
    tracksById: Map<Int, MkvSubtitleTrackMeta>,
    targetIds: Set<Int>,
    out: MutableMap<Int, MutableList<CapturedRawCue>>,
  ): Boolean {
    var clusterTimeRaw = 0L

    while (reader.position < clusterEnd) {
      val elemPos = reader.position
      val id = reader.readEbmlId() ?: break

      // Handle unknown-size streaming Clusters that end right before another Segment child.
      if (clusterIsUnknownSize &&
        (id == ID_CLUSTER || id == ID_CUES || id == ID_TAGS || id == ID_CHAPTERS || id == ID_ATTACHMENTS)
      ) {
        reader.seek(elemPos)
        return true
      }

      val sz = reader.readVintSize() ?: break
      val start = reader.position
      val end = if (sz.isUnknown) clusterEnd else min(clusterEnd, start + sz.sizeBytes)

      when (id) {
        ID_CLUSTER_TIMECODE -> {
          val len = (end - start).toInt()
          if (len in 1..8) clusterTimeRaw = reader.readUnsignedInt(len)
        }

        ID_BLOCK_GROUP -> {
          parseBlockGroup(reader, end, clusterTimeRaw, timecodeScaleNs, tracksById, targetIds, out)
        }

        ID_SIMPLE_BLOCK -> {
          parseBlockPayload(
            reader = reader,
            blockEnd = end,
            clusterTimeRaw = clusterTimeRaw,
            timecodeScaleNs = timecodeScaleNs,
            durationUnits = -1L,
            tracksById = tracksById,
            targetIds = targetIds,
            out = out,
          )
        }
      }

      if (end > start) {
        reader.seek(end)
      } else if (sz.sizeBytes == 0L) {
        reader.seek(end)
      } else {
        break
      }
    }
    return false
  }

  private fun parseBlockGroup(
    reader: BufferedChannelReader,
    groupEnd: Long,
    clusterTimeRaw: Long,
    timecodeScaleNs: Long,
    tracksById: Map<Int, MkvSubtitleTrackMeta>,
    targetIds: Set<Int>,
    out: MutableMap<Int, MutableList<CapturedRawCue>>,
  ) {
    var blockStart = -1L
    var blockEnd = -1L
    var durationUnits = -1L

    while (reader.position < groupEnd) {
      val id = reader.readEbmlId() ?: break
      val sz = reader.readVintSize() ?: break
      val start = reader.position
      val end = min(groupEnd, start + max(0L, sz.sizeBytes))

      when (id) {
        ID_BLOCK -> {
          blockStart = start
          blockEnd = end
        }
        ID_BLOCK_DURATION -> {
          val len = (end - start).toInt()
          if (len in 1..8) durationUnits = reader.readUnsignedInt(len)
        }
      }

      if (end > start) {
        reader.seek(end)
      } else if (sz.sizeBytes == 0L) {
        reader.seek(end)
      } else {
        break
      }
    }

    if (blockStart >= 0L && blockEnd > blockStart + 4L) {
      reader.seek(blockStart)
      parseBlockPayload(
        reader = reader,
        blockEnd = blockEnd,
        clusterTimeRaw = clusterTimeRaw,
        timecodeScaleNs = timecodeScaleNs,
        durationUnits = durationUnits,
        tracksById = tracksById,
        targetIds = targetIds,
        out = out,
      )
    }
  }

  private fun parseBlockPayload(
    reader: BufferedChannelReader,
    blockEnd: Long,
    clusterTimeRaw: Long,
    timecodeScaleNs: Long,
    durationUnits: Long,
    tracksById: Map<Int, MkvSubtitleTrackMeta>,
    targetIds: Set<Int>,
    out: MutableMap<Int, MutableList<CapturedRawCue>>,
  ) {
    val trackVint = reader.readVintSize() ?: return
    val trackNum = trackVint.sizeBytes.toInt()
    if (trackNum !in targetIds || reader.position + 3L > blockEnd) return

    val relTime = reader.readSignedInt16()
    val flags = reader.readUnsignedByte()
    if (flags < 0) return
    val lacing = (flags ushr 1) and 0x03

    // Fast path: 99.9% of subtitle packets are unlaced (lacing == 0).
    // Even if laced frame count byte exists, skipping non-text or reading single payload cleanly avoids crash.
    if (lacing != 0) return

    val payloadLen = (blockEnd - reader.position).toInt()
    if (payloadLen <= 0 || payloadLen > 65_536) return

    val meta = tracksById[trackNum] ?: return
    val rawBytes = reader.readBytes(payloadLen)
    val rawText = String(rawBytes, Charsets.UTF_8)

    val cleanedLines = BilingualSubtitleParser.cleanPayloadToLines(
      rawPayload = rawText,
      stripMatroskaPrefix = meta.isAssCodec,
      isAss = meta.isAssCodec,
    )
    if (cleanedLines.isEmpty()) return

    val startMs = max(0L, ((clusterTimeRaw + relTime) * timecodeScaleNs) / 1_000_000L)
    val hasExplicitDuration = durationUnits > 0L
    val durMs = if (hasExplicitDuration) {
      max(150L, (durationUnits * timecodeScaleNs) / 1_000_000L)
    } else {
      2_500L
    }

    out[trackNum]?.add(
      CapturedRawCue(
        startMs = startMs,
        endMs = startMs + durMs,
        hasExplicitDuration = hasExplicitDuration,
        lines = cleanedLines,
      ),
    )
  }

  // ---------------------------------------------------------------------------
  // Track Scoring & Ambiguous Track Text Language Probe
  // ---------------------------------------------------------------------------

  private fun isForcedOrCommentary(t: MkvSubtitleTrackMeta): Boolean {
    if (t.flagForced) return true
    val n = t.name.lowercase(Locale.ROOT)
    return "forced" in n || "强制" in n || "強制" in n ||
      "commentary" in n || "评论" in n || "評論" in n || "解说" in n || "解說" in n
  }

  private fun classifyTrackBucket(t: MkvSubtitleTrackMeta): LangBucket {
    val l = t.language
    val b = t.languageBcp47
    val n = t.name.lowercase(Locale.ROOT)

    val isZh = l in setOf("chi", "zho", "zh", "cmn", "yue") ||
      b.startsWith("zh") || b.startsWith("cmn") || b.startsWith("yue") ||
      "chi" in n || "zho" in n || "chinese" in n ||
      "chs" in n || "cht" in n || "simp" in n || "trad" in n ||
      "中" in n || "简" in n || "繁" in n
    if (isZh) return LangBucket.CHINESE

    val isEn = l in setOf("eng", "en") ||
      b == "en" || b.startsWith("en-") ||
      "eng" in n || "english" in n || "英" in n
    if (isEn) return LangBucket.ENGLISH

    if ((l.isEmpty() || l == "und") && (b.isEmpty() || b == "und")) {
      return LangBucket.AMBIGUOUS
    }
    return LangBucket.OTHER_KNOWN
  }

  private fun pickBestChineseTrack(tracks: List<MkvSubtitleTrackMeta>): MkvSubtitleTrackMeta? =
    tracks.filter { classifyTrackBucket(it) == LangBucket.CHINESE }
      .maxByOrNull { scoreChineseTrack(it) }

  private fun pickBestEnglishTrack(tracks: List<MkvSubtitleTrackMeta>): MkvSubtitleTrackMeta? =
    tracks.filter { classifyTrackBucket(it) == LangBucket.ENGLISH }
      .maxByOrNull { scoreEnglishTrack(it) }

  private fun scoreChineseTrack(t: MkvSubtitleTrackMeta): Int {
    val n = t.name.lowercase(Locale.ROOT)
    val b = t.languageBcp47
    var score = 100
    if (t.flagForced || "forced" in n || "强制" in n || "強制" in n) score -= 1000
    if ("commentary" in n || "解说" in n || "解說" in n || "评论" in n || "評論" in n) score -= 500
    if ("yue" in n || "cantonese" in n || "粤" in n) score -= 150
    if ("sdh" in n || t.flagHearingImpaired) score -= 20

    if ("chs" in n || "simp" in n || "hans" in b || "简" in n || "sc" in n) score += 50
    else if ("cht" in n || "trad" in n || "hant" in b || "繁" in n || "hk" in b || "tw" in b || "tc" in n) score += 10
    else score += 25 // generic zh/chi beats Traditional when neither explicitly labeled

    if (t.flagDefault) score += 5
    return score
  }

  private fun scoreEnglishTrack(t: MkvSubtitleTrackMeta): Int {
    val n = t.name.lowercase(Locale.ROOT)
    var score = 100
    if (t.flagForced || "forced" in n || "signs" in n || "songs" in n) score -= 1000
    if ("commentary" in n) score -= 500
    if ("sdh" in n || t.flagHearingImpaired || "cc" in n || "hearing" in n) score -= 30
    else score += 40 // Prefer clean non-SDH full English over SDH when both exist

    if (t.flagDefault) score += 5
    return score
  }

  private data class LanguageProbe(
    val cjkRatio: Double,
    val asciiLetterRatio: Double,
  )

  private fun inspectCueLanguageSample(cues: List<CapturedRawCue>): LanguageProbe {
    var totalLetters = 0
    var cjkCount = 0
    var asciiCount = 0
    val step = max(1, cues.size / 60)
    var i = 0
    while (i < cues.size && totalLetters < 2000) {
      for (ch in cues[i].joinedText) {
        val code = ch.code
        when {
          code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0x3040..0x30FF || code in 0xAC00..0xD7AF -> {
            cjkCount++
            totalLetters++
          }
          ch in 'A'..'Z' || ch in 'a'..'z' -> {
            asciiCount++
            totalLetters++
          }
          ch.isLetter() -> {
            totalLetters++
          }
        }
      }
      i += step
    }
    if (totalLetters == 0) return LanguageProbe(0.0, 0.0)
    return LanguageProbe(
      cjkRatio = cjkCount.toDouble() / totalLetters,
      asciiLetterRatio = asciiCount.toDouble() / totalLetters,
    )
  }

  private fun isInternallyBilingualTrack(cues: List<CapturedRawCue>): Boolean {
    var bilingualCount = 0
    for (c in cues) {
      if (c.lines.size >= 2) {
        val hasCjk = c.lines.any { BilingualSubtitleParser.containsCjk(it) }
        val hasNonCjk = c.lines.any { !BilingualSubtitleParser.containsCjk(it) && it.any(Char::isLetter) }
        if (hasCjk && hasNonCjk) {
          bilingualCount++
          if (bilingualCount >= 3) return true
        }
      }
    }
    return false
  }

  private fun splitSingleInternallyBilingualTrack(
    rawCues: List<CapturedRawCue>,
    baseName: String,
    targetDir: File,
    preferences: SubtitlesPreferences?,
  ): BilingualSplitResult? {
    val srtText = buildString {
      val finalized = rawCues.sortedBy { it.startMs }
      for ((idx, c) in finalized.withIndex()) {
        val safeEnd = max(c.endMs, c.startMs + 150L)
        append(idx + 1).append('\n')
        append(BilingualSubtitleParser.msToSrtTime(c.startMs))
          .append(" --> ")
          .append(BilingualSubtitleParser.msToSrtTime(safeEnd))
          .append('\n')
        append(c.lines.joinToString("\n")).append("\n\n")
      }
    }
    return BilingualSubtitleParser.processTextToCache(
      rawText = srtText,
      ext = "srt",
      baseName = baseName,
      targetDir = targetDir,
      preferences = preferences,
    )
  }

  private fun finalizePlainCues(raw: List<CapturedRawCue>): List<PlainSubtitleCue> {
    if (raw.isEmpty()) return emptyList()
    val sorted = raw.sortedBy { it.startMs }
    val out = ArrayList<PlainSubtitleCue>(sorted.size)
    for (i in sorted.indices) {
      val curr = sorted[i]
      val text = curr.joinedText
      if (text.isBlank()) continue
      var endMs = curr.endMs
      // Only clamp fallback duration when BlockDuration was absent so back-to-back cues don't overlap.
      if (!curr.hasExplicitDuration && i + 1 < sorted.size) {
        val nextStart = sorted[i + 1].startMs
        if (nextStart > curr.startMs + 200L && endMs > nextStart - 40L) {
          endMs = nextStart - 40L
        }
      }
      out.add(PlainSubtitleCue(curr.startMs, max(endMs, curr.startMs + 150L), text))
    }
    return out
  }

  // ---------------------------------------------------------------------------
  // Buffered FileChannel Reader (4 MB Sequential Window)
  // ---------------------------------------------------------------------------

  private data class VintSizeResult(
    val sizeBytes: Long,
    val isUnknown: Boolean,
  )

  private class BufferedChannelReader(
    private val channel: FileChannel,
    private val fileSize: Long,
  ) {
    private val buf = ByteArray(BUFFER_CAPACITY)
    private val byteBuf = ByteBuffer.wrap(buf)
    private var bufStart = 0L
    private var bufLen = 0
    var position = 0L
      private set

    fun seek(target: Long) {
      val clamped = target.coerceIn(0L, fileSize)
      position = clamped
    }

    private fun ensureAvailable(): Boolean {
      if (position >= fileSize) return false
      if (position >= bufStart && position < bufStart + bufLen) return true

      bufStart = position
      byteBuf.clear()
      var total = 0
      while (total < buf.size && bufStart + total < fileSize) {
        val n = channel.read(byteBuf, bufStart + total)
        if (n <= 0) break
        total += n
      }
      bufLen = total
      return bufLen > 0
    }

    fun readUnsignedByte(): Int {
      if (!ensureAvailable()) return -1
      val idx = (position - bufStart).toInt()
      position++
      return buf[idx].toInt() and 0xFF
    }

    fun readSignedInt16(): Int {
      val b0 = readUnsignedByte()
      val b1 = readUnsignedByte()
      if (b0 < 0 || b1 < 0) return 0
      return ((b0.toByte().toInt()) shl 8) or b1
    }

    fun readBytes(count: Int): ByteArray {
      val out = ByteArray(count)
      var offset = 0
      while (offset < count) {
        if (!ensureAvailable()) break
        val idx = (position - bufStart).toInt()
        val avail = bufLen - idx
        val chunk = min(avail, count - offset)
        System.arraycopy(buf, idx, out, offset, chunk)
        position += chunk
        offset += chunk
      }
      return if (offset == count) out else out.copyOf(offset)
    }

    fun readAsciiOrUtf8(count: Int): String {
      if (count <= 0) return ""
      val raw = readBytes(count)
      var end = raw.size
      while (end > 0 && raw[end - 1] == 0.toByte()) end--
      return String(raw, 0, end, Charsets.UTF_8)
    }

    fun readUnsignedInt(byteCount: Int): Long {
      var res = 0L
      for (i in 0 until byteCount) {
        val b = readUnsignedByte()
        if (b < 0) break
        res = (res shl 8) or b.toLong()
      }
      return res
    }

    fun readEbmlId(): Long? {
      val b0 = readUnsignedByte()
      if (b0 <= 0) return null
      var mask = 0x80
      var len = 1
      while (len <= 4 && (b0 and mask) == 0) {
        mask = mask ushr 1
        len++
      }
      if (len > 4) return null
      var value = b0.toLong()
      for (i in 1 until len) {
        val next = readUnsignedByte()
        if (next < 0) return null
        value = (value shl 8) or next.toLong()
      }
      return value
    }

    fun readVintSize(): VintSizeResult? {
      val b0 = readUnsignedByte()
      if (b0 <= 0) return null
      var mask = 0x80
      var len = 1
      while (len <= 8 && (b0 and mask) == 0) {
        mask = mask ushr 1
        len++
      }
      if (len > 8) return null
      var value = (b0 and (mask - 1)).toLong()
      for (i in 1 until len) {
        val next = readUnsignedByte()
        if (next < 0) return null
        value = (value shl 8) or next.toLong()
      }
      val unknownSentinel = (1L shl (len * 7)) - 1L
      return VintSizeResult(value, value == unknownSentinel)
    }
  }
}
