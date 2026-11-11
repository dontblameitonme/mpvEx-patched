package app.marlboroadvance.mpvex.utils.media

import android.content.Context
import android.net.Uri
import android.util.Log
import app.marlboroadvance.mpvex.preferences.SubtitleMode
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
import app.marlboroadvance.mpvex.ui.player.getRealFilePath
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Simple utility for automatically loading subtitle files
 * Finds subtitles in the same directory that match the video filename
 */
object SubtitleOps : KoinComponent {
  private const val TAG = "SubtitleOps"
  private val context: Context by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val networkRepository: NetworkRepository by inject()

  private fun shouldSkipNetworkSubtitleAutoload(videoFilePath: String, videoFileName: String): Boolean {
    val p = videoFilePath.lowercase(Locale.getDefault())
    val n = videoFileName.lowercase(Locale.getDefault())

    val looksLikePlaylist =
      p.endsWith(".m3u") || p.endsWith(".m3u8") ||
        p.contains(".m3u?") || p.contains(".m3u8?") ||
        n.endsWith(".m3u") || n.endsWith(".m3u8")

    val genericName = n.isBlank() || n == "network stream"

    return looksLikePlaylist || genericName
  }

  suspend fun autoloadSubtitles(
    videoFilePath: String,
    videoFileName: String,
    networkConnectionId: Long = -1L,
    autoSelect: Boolean = true,
  ): Boolean = withContext(Dispatchers.IO) {
    try {
      // Skip file descriptor URIs (these don't have a parent directory concept)
      if (videoFilePath.startsWith("fd://")) return@withContext false

      // Resolve local / content:// MKV subtitles seamlessly instead of bailing out early.

      // Check if this is a network file with connection ID (SMB/FTP/WebDAV via proxy)
      if (networkConnectionId != -1L) {
        // For network files, scan the directory using network client
        autoloadNetworkFileSubtitles(videoFilePath, videoFileName, networkConnectionId)
        return@withContext false
      }

      // Check if this is a network stream (http, https, ftp, ftps, smb, webdav, etc.)
      val isNetworkStream =
        !videoFilePath.startsWith("content://") &&
          videoFilePath.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*"))

      if (isNetworkStream) {
        if (shouldSkipNetworkSubtitleAutoload(videoFilePath, videoFileName)) {
          Log.d(TAG, "Skipping network subtitle autoload for: $videoFilePath")
          return@withContext false
        }
        // For network streams, try to load subtitles with common extensions
        autoloadNetworkSubtitles(videoFilePath, videoFileName)
        false
      } else {
        // For local files and content:// URIs, scan directory / extract embedded MKV subtitles
        autoloadLocalSubtitles(videoFilePath, videoFileName, autoSelect)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error loading subtitles", e)
      false
    }
  }

  /**
   * Autoload subtitles for network files (SMB/FTP/WebDAV)
   * Lists files in the same directory and loads matching subtitle files via proxy
   */
  private suspend fun autoloadNetworkFileSubtitles(
    videoFilePath: String,
    videoFileName: String,
    networkConnectionId: Long,
  ) {
    try {
      Log.d(TAG, "Autoloading subtitles for network file: $videoFilePath")
      
      // Get the network connection
      val connection = networkRepository.getConnectionById(networkConnectionId)
      if (connection == null) {
        Log.w(TAG, "Network connection not found: $networkConnectionId")
        return
      }

      // Get the directory path (parent of the video file)
      val directoryPath = videoFilePath.substringBeforeLast('/', "")
      if (directoryPath.isEmpty()) {
        Log.w(TAG, "Could not determine directory path from: $videoFilePath")
        return
      }

      Log.d(TAG, "Scanning directory: $directoryPath")

      // Get base name without extension
      val baseName = videoFileName.substringBeforeLast('.')

      // List files in the directory
      val filesResult = networkRepository.listFiles(connection, directoryPath)
      if (filesResult.isFailure) {
        Log.w(TAG, "Failed to list network directory: ${filesResult.exceptionOrNull()?.message}")
        return
      }

      val files = filesResult.getOrNull() ?: emptyList()
      
      // Filter for subtitle files that match the video base name
      val subtitles = files.filter { file ->
        !file.isDirectory &&
          isSubtitleFile(file.name) &&
          file.name.substringBeforeLast('.').startsWith(baseName, ignoreCase = true)
      }

      if (subtitles.isEmpty()) {
        Log.d(TAG, "No matching subtitle files found for: $baseName")
        return
      }

      Log.d(TAG, "Found ${subtitles.size} subtitle file(s)")

      // Load subtitles via proxy
      val proxy = NetworkStreamingProxy.getInstance()
      
      // Keep mpv calls off the main thread for network-backed subtitle sources to avoid ANRs.
      subtitles.forEachIndexed { index, subtitle ->
        try {
          // Extract just the filename without path for display
          // Handle both forward slashes and backslashes
          val displayName = subtitle.name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .takeIf { it.isNotBlank() } ?: subtitle.name

          Log.d(TAG, "Processing subtitle - name: '${subtitle.name}', displayName: '$displayName', path: '${subtitle.path}'")

          // Create a URL-safe filename for the streamId
          val urlSafeFilename = displayName
            .replace(" ", ".")
            .replace(Regex("[^a-zA-Z0-9._-]"), "")

          // Register subtitle stream with proxy using the filename in streamId
          val streamId = urlSafeFilename
          val proxyUrl = proxy.registerStream(
            streamId = streamId,
            connection = connection,
            filePath = subtitle.path,
            fileSize = subtitle.size,
            mimeType = "text/plain",
          )

          // Get current subtitle track count before adding
          val trackCountBefore = MPVLib.getPropertyInt("track-list/count") ?: 0

          // Use "select" for the first subtitle, "auto" for others
          val flag = if (index == 0) "select" else "auto"
          MPVLib.command("sub-add", proxyUrl, flag)

          // Set the title for the newly added subtitle track
          val trackCountAfter = MPVLib.getPropertyInt("track-list/count") ?: 0
          if (trackCountAfter > trackCountBefore) {
            val newTrackIndex = trackCountAfter - 1
            MPVLib.setPropertyString("track-list/$newTrackIndex/title", displayName)
            Log.d(TAG, "Loaded network subtitle: '$displayName' (track $newTrackIndex) via proxy (flag=$flag)")
          } else {
            Log.d(TAG, "Loaded network subtitle: '$displayName' via proxy (flag=$flag)")
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to load subtitle ${subtitle.name}: ${e.message}", e)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error autoloading network subtitles", e)
    }
  }

  private suspend fun autoloadLocalSubtitles(
    videoFilePath: String,
    videoFileName: String,
    autoSelect: Boolean = true,
  ): Boolean {
    var videoFile: File? = if (videoFilePath.startsWith("content://")) null else File(videoFilePath)
    var videoDirectory = videoFile?.parentFile
    if (videoDirectory == null && videoFilePath.startsWith("content://")) {
      val real = runCatching { Uri.parse(videoFilePath).getRealFilePath(context) }.getOrNull()
      if (real != null) {
        videoFile = File(real)
        videoDirectory = videoFile.parentFile
      }
    }

    val baseName = videoFileName.substringBeforeLast('.')
    val subtitles =
      videoDirectory?.listFiles()?.filter { file ->
        file.isFile &&
          isSubtitleFile(file.name) &&
          isMatchingSubtitle(baseName, file.nameWithoutExtension)
      }?.sortedWith(compareByDescending<File> { file ->
        val nameLower = file.name.lowercase(Locale.ROOT)
        var score = 0
        if (nameLower.startsWith(baseName.lowercase(Locale.ROOT))) score += 10
        if (nameLower.contains("双语") || nameLower.contains("bilingual") || nameLower.contains("merged") ||
          nameLower.contains("chn.eng") || nameLower.contains("chs&eng") || nameLower.contains("chs.eng")
        ) {
          score += 20
        }
        score
      }) ?: emptyList()

    if (subtitles.isNotEmpty()) {
      return withContext(Dispatchers.IO) {
        var hasActivatedPrimary = false
        var upgradedPreprocessedActive = false
        val plainChineseCandidates = mutableListOf<File>()
        val plainEnglishCandidates = mutableListOf<File>()
        var hasBilingualAlready = false

        subtitles.forEachIndexed { index, subtitle ->
          val isDefault = (index == 0)
          val splitResult = runCatching {
            BilingualSubtitleParser.splitIfBilingual(subtitle, context, subtitlesPreferences)
          }.getOrNull()

          if (splitResult != null) {
            if (splitResult.bilingualFile != null) hasBilingualAlready = true
            val shouldSelectNow = autoSelect && isDefault && !hasActivatedPrimary
            val activated = addAndActivatePreprocessedResult(
              splitResult = splitResult,
              subtitlesPreferences = subtitlesPreferences,
              autoSelect = shouldSelectNow,
              fallbackPlainFile = subtitle,
            )
            if (activated && shouldSelectNow) {
              hasActivatedPrimary = true
              upgradedPreprocessedActive = true
            }
            Log.d(
              TAG,
              "Autoloaded pre-processed subtitle: ${subtitle.name} (bilingual=${splitResult.isBilingual}, select=$shouldSelectNow)",
            )
          } else {
            val lower = subtitle.nameWithoutExtension.lowercase(Locale.ROOT)
            if (looksLikeChineseTag(lower)) plainChineseCandidates.add(subtitle)
            else if (looksLikeEnglishTag(lower)) plainEnglishCandidates.add(subtitle)

            val flag = if (autoSelect && isDefault && !hasActivatedPrimary) {
              hasActivatedPrimary = true
              "select"
            } else {
              "auto"
            }
            MPVLib.command("sub-add", subtitle.absolutePath, flag, subtitle.name)
            Log.d(TAG, "Loaded local subtitle: ${subtitle.name} (flag=$flag)")
          }
        }

        // If external directory contains separate zh + en subtitle files and no [双语] track exists,
        // synthesize a bottom-anchored flowing [双语] track so Dual mode avoids static-pos collisions.
        if (!hasBilingualAlready && plainChineseCandidates.isNotEmpty() && plainEnglishCandidates.isNotEmpty()) {
          val pairedAss = BilingualSubtitleParser.mergeTwoFilesToBilingualAss(
            primaryFile = plainChineseCandidates.first(),
            secondaryFile = plainEnglishCandidates.first(),
            baseName = baseName,
            context = context,
            preferences = subtitlesPreferences,
          )
          if (pairedAss != null) {
            val pairedActivated = addAndActivatePreprocessedResult(
              splitResult = BilingualSplitResult(
                bilingualFile = pairedAss,
                bilingualTitle = "[双语] $baseName",
                isBilingual = true,
              ),
              subtitlesPreferences = subtitlesPreferences,
              autoSelect = autoSelect && subtitlesPreferences.subtitleMode.get() == SubtitleMode.Multi,
            )
            if (pairedActivated && autoSelect && subtitlesPreferences.subtitleMode.get() == SubtitleMode.Multi) {
              hasActivatedPrimary = true
              upgradedPreprocessedActive = true
            }
          }
        }
        upgradedPreprocessedActive
      }
    }

    // No external subtitles matched: extract embedded text tracks from MKV (supports File and content:// URIs).
    val isMkv =
      videoFile?.extension?.equals("mkv", ignoreCase = true) == true ||
        videoFileName.substringAfterLast('.', "").equals("mkv", ignoreCase = true) ||
        videoFilePath.startsWith("content://")

    if (isMkv) {
      return withContext(Dispatchers.IO) {
        val source = videoFile?.absolutePath ?: videoFilePath
        val splitResult = MkvSubtitleExtractor.extractSubtitles(
          videoSource = source,
          context = context,
          preferences = subtitlesPreferences,
          displayNameHint = videoFileName,
        )
        if (splitResult != null) {
          val activated = addAndActivatePreprocessedResult(
            splitResult = splitResult,
            subtitlesPreferences = subtitlesPreferences,
            autoSelect = autoSelect,
          )
          Log.d(
            TAG,
            "Successfully pre-processed MKV subtitles: ${splitResult.bilingualTitle ?: splitResult.primaryTitle ?: splitResult.secondaryTitle}",
          )
          activated
        } else {
          false
        }
      }
    }
    return false
  }

  /**
   * Registers preprocessed subtitle files from [splitResult] into mpv (skipping duplicates) and,
   * when [autoSelect] is true, waits until mpv indexes them and selects the appropriate track for
   * the active [SubtitleMode] (`[双语]` flowing ASS in Multi, or pure `.srt` in Single).
   */
  suspend fun addAndActivatePreprocessedResult(
    splitResult: BilingualSplitResult,
    subtitlesPreferences: SubtitlesPreferences,
    autoSelect: Boolean,
    fallbackPlainFile: File? = null,
    fallbackTitle: String? = null,
  ): Boolean {
    val itemsToAdd = buildList {
      splitResult.primaryFile?.takeIf { it.exists() && it.length() > 0L }?.let {
        add(it to (splitResult.primaryTitle ?: it.nameWithoutExtension))
      }
      splitResult.secondaryFile?.takeIf { it.exists() && it.length() > 0L }?.let {
        add(it to (splitResult.secondaryTitle ?: it.nameWithoutExtension))
      }
      splitResult.bilingualFile?.takeIf { it.exists() && it.length() > 0L }?.let {
        add(it to (splitResult.bilingualTitle ?: it.nameWithoutExtension))
      }
      fallbackPlainFile?.takeIf { it.exists() && it.length() > 0L }?.let {
        add(it to (fallbackTitle ?: "[原版] ${it.name}"))
      }
    }
    if (itemsToAdd.isEmpty()) return false

    val existingPaths = collectExistingExternalSubPaths()
    for ((file, title) in itemsToAdd) {
      if (file.absolutePath !in existingPaths) {
        MPVLib.command("sub-add", file.absolutePath, "auto", title)
      }
    }

    if (!autoSelect) return true

    val expectedPaths = itemsToAdd
      .filter { it.first != fallbackPlainFile }
      .map { it.first.absolutePath }
      .toSet()

    var priId: Int? = null
    var secId: Int? = null
    var biId: Int? = null

    for (attempt in 0 until 30) {
      delay(50)
      val count = MPVLib.getPropertyInt("track-list/count") ?: 0
      var seenExpected = 0
      for (i in 0 until count) {
        if (MPVLib.getPropertyString("track-list/$i/type") != "sub") continue
        val extPath = MPVLib.getPropertyString("track-list/$i/external-filename") ?: continue
        val id = MPVLib.getPropertyInt("track-list/$i/id") ?: continue
        if (id <= 0) continue

        if (extPath in expectedPaths) seenExpected++
        if (splitResult.primaryFile != null && extPath == splitResult.primaryFile.absolutePath) {
          priId = id
        } else if (splitResult.secondaryFile != null && extPath == splitResult.secondaryFile.absolutePath) {
          secId = id
        } else if (splitResult.bilingualFile != null && extPath == splitResult.bilingualFile.absolutePath) {
          biId = id
        }
      }
      if (seenExpected >= expectedPaths.size) break
    }

    val mode = subtitlesPreferences.subtitleMode.get()
    var activated = false
    if (mode == SubtitleMode.Multi) {
      when {
        biId != null -> {
          MPVLib.setPropertyInt("sid", biId)
          MPVLib.setPropertyString("secondary-sid", "no")
          activated = true
        }
        priId != null && secId != null -> {
          MPVLib.setPropertyInt("sid", priId)
          MPVLib.setPropertyInt("secondary-sid", secId)
          activated = true
        }
        priId != null || secId != null -> {
          MPVLib.setPropertyInt("sid", (priId ?: secId)!!)
          MPVLib.setPropertyString("secondary-sid", "no")
          activated = true
        }
      }
    } else {
      val singleId = priId ?: secId ?: biId
      if (singleId != null) {
        MPVLib.setPropertyInt("sid", singleId)
        MPVLib.setPropertyString("secondary-sid", "no")
        activated = true
      }
    }

    if (activated) {
      val curDelay = MPVLib.getPropertyDouble("sub-delay") ?: 0.0
      val curSpeed = MPVLib.getPropertyDouble("sub-speed") ?: 1.0
      MPVLib.setPropertyDouble("secondary-sub-delay", curDelay)
      MPVLib.setPropertyDouble("secondary-sub-speed", curSpeed)
      BilingualSubtitleParser.applySubtitlePreferencesForMode(subtitlesPreferences, mode)
    }
    return activated
  }

  private fun collectExistingExternalSubPaths(): Set<String> {
    val count = MPVLib.getPropertyInt("track-list/count") ?: 0
    val out = mutableSetOf<String>()
    for (i in 0 until count) {
      if (MPVLib.getPropertyString("track-list/$i/type") == "sub") {
        MPVLib.getPropertyString("track-list/$i/external-filename")?.let { out.add(it) }
      }
    }
    return out
  }

  private fun looksLikeChineseTag(lowerName: String): Boolean =
    lowerName.contains(".chi") || lowerName.contains(".zh") || lowerName.contains(".chs") ||
      lowerName.contains(".cht") || lowerName.contains("_zh") || lowerName.contains("中文") ||
      lowerName.contains("简") || lowerName.contains("繁")

  private fun looksLikeEnglishTag(lowerName: String): Boolean =
    lowerName.contains(".eng") || lowerName.endsWith(".en") || lowerName.contains(".en.") ||
      lowerName.contains("_en") || lowerName.contains("english") || lowerName.contains("英文")

  private suspend fun autoloadNetworkSubtitles(
    videoFilePath: String,
    videoFileName: String,
  ) {
    // Get base name without extension
    val baseName = videoFileName.substringBeforeLast('.')

    // Get the base URL (path without the filename)
    val lastSlashIndex = videoFilePath.lastIndexOf('/')
    if (lastSlashIndex == -1) return

    val baseUrl = videoFilePath.substring(0, lastSlashIndex + 1)

    // Common subtitle extensions to try
    val subtitleExtensions = listOf("srt", "ass", "ssa", "vtt", "sub")

    // Keep mpv calls off the main thread for network URLs to avoid ANRs.
    // Try each subtitle extension
    subtitleExtensions.forEachIndexed { index, ext ->
      val subtitleUrl = "$baseUrl$baseName.$ext"
      try {
        // Try to add the subtitle - MPV will handle if it doesn't exist
        // Use "auto" flag so MPV doesn't select it if it's not found
        // Only use "select" for the first one (.srt)
        val flag = if (index == 0) "select" else "auto"
        MPVLib.command("sub-add", subtitleUrl, flag, "$baseName.$ext")
        Log.d(TAG, "Attempting to load network subtitle: $subtitleUrl (flag=$flag)")
      } catch (e: Exception) {
        Log.d(TAG, "Could not load network subtitle $subtitleUrl: ${e.message}")
      }
    }
  }

  private val BRACKET_TAGS_REGEX = Regex("""\[.*?\]|【.*?】|\(.*?\)|（.*?）""")
  private val SPLIT_DELIMITERS_REGEX = Regex("[\\s._\\-+]+")
  private val MEDIA_NOISE_TOKENS = setOf(
    "1080p", "720p", "2160p", "4k", "x264", "x265", "h264", "h265", "hevc", "repack",
    "webdl", "web-dl", "web", "dl", "amzn", "nf", "ddp5", "atmos", "ac3", "aac", "merged",
    "chn", "eng", "chs", "cht", "chs&eng", "sub", "ass", "ssa", "srt", "vtt", "mkv", "mp4",
    "dts", "bluray", "bdrip", "hdrip", "remux"
  )

  fun isMatchingSubtitle(videoBaseName: String, subBaseName: String): Boolean {
    val vLower = videoBaseName.lowercase(Locale.ROOT)
    val sLower = subBaseName.lowercase(Locale.ROOT)

    // 1. Direct prefix / suffix / containment check
    if (sLower.startsWith(vLower) || vLower.startsWith(sLower)) return true
    if (sLower.contains(vLower) || vLower.contains(sLower)) return true

    // 2. Cleaned bracket tags check (e.g. 【简英双语】, [SubHD])
    val vClean = BRACKET_TAGS_REGEX.replace(vLower, " ").trim()
    val sClean = BRACKET_TAGS_REGEX.replace(sLower, " ").trim()
    if (vClean.isNotEmpty() && sClean.isNotEmpty()) {
      if (sClean.contains(vClean) || vClean.contains(sClean)) return true
    }

    // 3. Token-based overlap matching (resilient to reordered or different tags)
    val vTokens = extractCoreTokens(vLower)
    val sTokens = extractCoreTokens(sLower)
    if (vTokens.isEmpty() || sTokens.isEmpty()) return false

    val vSet = vTokens.toSet()
    val sSet = sTokens.toSet()
    val common = vSet.intersect(sSet)
    val minLen = minOf(vSet.size, sSet.size)

    return (common.size >= 2 && (common.size.toFloat() / minLen.toFloat()) >= 0.6f)
  }

  private fun extractCoreTokens(name: String): List<String> {
    val clean = BRACKET_TAGS_REGEX.replace(name, " ")
    return SPLIT_DELIMITERS_REGEX.split(clean)
      .map { it.trim().lowercase(Locale.ROOT) }
      .filter { it.isNotEmpty() && it.length > 1 && it !in MEDIA_NOISE_TOKENS && !it.all { c -> c.isDigit() } }
  }

  private fun isSubtitleFile(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
    return extension in setOf(
      // Common & modern
      "srt", "vtt", "ass", "ssa",
      // DVD / Blu-ray
      "sub", "idx", "sup",
      // Streaming / XML / Professional
      "xml", "ttml", "dfxp", "itt", "ebu", "imsc", "usf",
      // Online platforms
      "sbv", "srv1", "srv2", "srv3", "json",
      // Legacy & niche
      "sami", "smi", "mpl", "pjs", "stl", "rt", "psb", "cap",
      // Broadcast captions
      "scc", "vttx",
      // Karaoke / lyrics
      "lrc", "krc",
      // Fallback / raw text
      "txt", "pgs"
    )
  }
}
