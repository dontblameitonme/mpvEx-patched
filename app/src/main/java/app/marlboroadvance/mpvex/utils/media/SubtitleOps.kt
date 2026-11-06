package app.marlboroadvance.mpvex.utils.media

import android.content.Context
import android.net.Uri
import android.util.Log
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
  ) = withContext(Dispatchers.IO) {
    try {
      // Skip file descriptor URIs (these don't have a parent directory concept)
      if (videoFilePath.startsWith("fd://")) return@withContext

      // For content:// URIs, we can't autoload (no access to parent directory)
      if (videoFilePath.startsWith("content://")) return@withContext

      // Check if this is a network file with connection ID (SMB/FTP/WebDAV via proxy)
      if (networkConnectionId != -1L) {
        // For network files, scan the directory using network client
        autoloadNetworkFileSubtitles(videoFilePath, videoFileName, networkConnectionId)
        return@withContext
      }

      // Check if this is a network stream (http, https, ftp, ftps, smb, webdav, etc.)
      val isNetworkStream = videoFilePath.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*"))

      if (isNetworkStream) {
        if (shouldSkipNetworkSubtitleAutoload(videoFilePath, videoFileName)) {
          Log.d(TAG, "Skipping network subtitle autoload for: $videoFilePath")
          return@withContext
        }
        // For network streams, try to load subtitles with common extensions
        autoloadNetworkSubtitles(videoFilePath, videoFileName)
      } else {
        // For local files, scan the directory
        autoloadLocalSubtitles(videoFilePath, videoFileName)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error loading subtitles", e)
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
  ) {
    var videoFile = File(videoFilePath)
    var videoDirectory = videoFile.parentFile
    if (videoDirectory == null && videoFilePath.startsWith("content://")) {
      val real = runCatching { Uri.parse(videoFilePath).getRealFilePath(context) }.getOrNull()
      if (real != null) {
        videoFile = File(real)
        videoDirectory = videoFile.parentFile
      }
    }
    if (videoDirectory == null) return
    val baseName = videoFileName.substringBeforeLast('.')

    val subtitles =
      videoDirectory.listFiles()?.filter { file ->
        file.isFile &&
          isSubtitleFile(file.name) &&
          isMatchingSubtitle(baseName, file.nameWithoutExtension)
      }?.sortedWith(compareByDescending<File> { file ->
        val nameLower = file.name.lowercase(Locale.ROOT)
        var score = 0
        if (nameLower.startsWith(baseName.lowercase(Locale.ROOT))) score += 10
        if (nameLower.contains("双语") || nameLower.contains("bilingual") || nameLower.contains("merged") ||
            nameLower.contains("chn.eng") || nameLower.contains("chs&eng") || nameLower.contains("chs.eng")) {
          score += 20
        }
        score
      }) ?: emptyList()

    if (subtitles.isNotEmpty()) {
      // Run subtitle file splitting and track loading strictly on Dispatchers.IO to prevent UI thread freezes
      withContext(Dispatchers.IO) {
        var hasSelectedPrimary = false
        subtitles.forEachIndexed { index, subtitle ->
          val isDefault = (index == 0)
          val splitResult = runCatching {
            BilingualSubtitleParser.splitIfBilingual(subtitle, context)
          }.getOrNull()

          if (splitResult != null) {
            MPVLib.command("sub-add", splitResult.primaryFile.absolutePath, "auto", splitResult.primaryTitle)
            MPVLib.command("sub-add", splitResult.secondaryFile.absolutePath, "auto", splitResult.secondaryTitle)
            MPVLib.command("sub-add", splitResult.bilingualFile.absolutePath, "auto", splitResult.bilingualTitle)
            // Also keep original untouched subtitle as fallback
            MPVLib.command("sub-add", subtitle.absolutePath, "auto", "[原版] ${subtitle.name}")

            if (isDefault) {
              val mode = subtitlesPreferences.subtitleMode.get()
              var biId: Int? = null
              var priId: Int? = null
              var secId: Int? = null
              for (attempt in 0 until 30) {
                delay(50)
                val count = MPVLib.getPropertyInt("track-list/count") ?: 0
                for (i in 0 until count) {
                  val type = MPVLib.getPropertyString("track-list/$i/type")
                  if (type != "sub") continue
                  val extPath = MPVLib.getPropertyString("track-list/$i/external-filename") ?: ""
                  val title = MPVLib.getPropertyString("track-list/$i/title") ?: ""
                  val id = MPVLib.getPropertyInt("track-list/$i/id") ?: continue

                  if (id > 0) {
                    if (extPath == splitResult.primaryFile.absolutePath || title.startsWith("[中]")) {
                      priId = id
                    }
                    if (extPath == splitResult.secondaryFile.absolutePath || title.startsWith("[英]")) {
                      secId = id
                    }
                    if (extPath == splitResult.bilingualFile.absolutePath || title.startsWith("[双语]")) {
                      biId = id
                    }
                  }
                }
                if (priId != null && secId != null) break
              }

              if (mode == app.marlboroadvance.mpvex.preferences.SubtitleMode.Multi) {
                // In Multi Mode: activate dual tracks [中] + [英] with top priority
                if (priId != null && secId != null) {
                  MPVLib.setPropertyInt("sid", priId)
                  MPVLib.setPropertyInt("secondary-sid", secId)
                  val curDelay = MPVLib.getPropertyDouble("sub-delay") ?: 0.0
                  val curSpeed = MPVLib.getPropertyDouble("sub-speed") ?: 1.0
                  MPVLib.setPropertyDouble("secondary-sub-delay", curDelay)
                  MPVLib.setPropertyDouble("secondary-sub-speed", curSpeed)
                  applySecondarySubStyleOverrides(subtitlesPreferences)
                  hasSelectedPrimary = true
                  Log.d(TAG, "Autoload bilingual subtitle active: sid=$priId ([中]), secondary-sid=$secId ([英])")
                } else if (biId != null) {
                  MPVLib.setPropertyInt("sid", biId)
                  MPVLib.setPropertyString("secondary-sid", "no")
                  applySecondarySubStyleOverrides(subtitlesPreferences)
                  hasSelectedPrimary = true
                  Log.d(TAG, "Autoload bilingual subtitle active: sid=$biId ([双语])")
                } else if (priId != null) {
                  MPVLib.setPropertyInt("sid", priId)
                  hasSelectedPrimary = true
                }
              } else {
                // In Single Mode: activate pure primary track, secondary disabled
                if (priId != null) {
                  MPVLib.setPropertyInt("sid", priId)
                  MPVLib.setPropertyString("secondary-sid", "no")
                  hasSelectedPrimary = true
                  Log.d(TAG, "Autoload single subtitle active: sid=$priId ([中])")
                } else if (biId != null) {
                  MPVLib.setPropertyInt("sid", biId)
                  MPVLib.setPropertyString("secondary-sid", "no")
                  hasSelectedPrimary = true
                }
              }
            }
            Log.d(TAG, "Autoloaded bilingual subtitle: ${subtitle.name} -> loaded bilingual, primary & secondary")
          } else {
            // MPV command format: sub-add <url> [<flags> [<title>]]
            // Use "select" for the first autoloaded subtitle so it is enabled by default
            val flag = if (isDefault && !hasSelectedPrimary) "select" else "auto"
            MPVLib.command("sub-add", subtitle.absolutePath, flag, subtitle.name)
            Log.d(TAG, "Loaded local subtitle: ${subtitle.name} (flag=$flag)")
          }
        }
      }
    }
  }

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
