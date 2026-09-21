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
          file.nameWithoutExtension.startsWith(baseName, ignoreCase = true)
      } ?: emptyList()

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
            val priFlag = if (isDefault) "select" else "auto"
            MPVLib.command("sub-add", splitResult.primaryFile.absolutePath, priFlag, "[中] ${subtitle.name}")
            MPVLib.command("sub-add", splitResult.secondaryFile.absolutePath, "auto", splitResult.secondaryTitle)
            // Also keep original untouched subtitle as fallback
            MPVLib.command("sub-add", subtitle.absolutePath, "auto", "[原版] ${subtitle.name}")

            if (isDefault) {
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
                  }
                }
                if (priId != null && secId != null) break
              }

              if (priId != null) {
                MPVLib.setPropertyInt("sid", priId)
                hasSelectedPrimary = true
              }
              if (secId != null) {
                MPVLib.setPropertyInt("secondary-sid", secId)
                applySecondarySubStyleOverrides(subtitlesPreferences)
                Log.d(TAG, "Autoload bilingual subtitle active: sid=$priId, secondary-sid=$secId")
              }
            }
            Log.d(TAG, "Autoloaded bilingual subtitle: ${subtitle.name} -> split into primary & secondary")
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
