package app.marlboroadvance.mpvex.utils.media

import android.util.Log
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import org.koin.java.KoinJavaComponent.inject

/**
 * Utility for managing playback state when files are renamed or deleted.
 *
 * Note on external deletions: playback state is keyed by a path-aware identifier
 * ("name_pathHash"), which can't be reversed back into a path. So a file deleted
 * by another app leaves its resume row behind. These orphans are intentionally
 * left in place: they're never surfaced in any UI, cost only a few bytes, and
 * would only ever match again if a file were recreated at the exact same path
 * (in which case resuming there is acceptable). In-app deletion is fully cleaned
 * up via [onVideoDeleted]. Reconciling external deletions would require storing
 * the path (a schema change) for negligible benefit.
 */
object PlaybackStateOps {
  private const val TAG = "PlaybackStateOps"
  private val repository: PlaybackStateRepository by inject(PlaybackStateRepository::class.java)

  /**
   * Called when a video file is renamed
   * Updates the playback state entry to use the new filename
   *
   * @param oldPath The original file path
   * @param newPath The new file path after renaming
   */
  suspend fun onVideoRenamed(
    oldPath: String,
    newPath: String,
  ) {
    if (oldPath.isBlank() || newPath.isBlank()) return

    try {
      // Playback state is keyed by a path-aware identifier, so a rename changes
      // both the name and the path component of the key.
      val oldTitle = MediaIdentifier.forLocalPath(oldPath)
      val newTitle = MediaIdentifier.forLocalPath(newPath)

      if (oldTitle != newTitle) {
        repository.updateMediaTitle(oldTitle, newTitle)
        Log.d(TAG, "✓ Updated playback state: $oldPath -> $newPath")
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to update playback state: ${e.message}")
    }
  }

  /**
   * Called when a video file is deleted
   * Removes its playback state entry
   *
   * @param filePath The path of the deleted file
   */
  suspend fun onVideoDeleted(filePath: String) {
    if (filePath.isBlank()) return

    try {
      val title = MediaIdentifier.forLocalPath(filePath)
      repository.deleteByTitle(title)
      Log.d(TAG, "✓ Deleted playback state for: $filePath")
    } catch (e: Exception) {
      Log.w(TAG, "Failed to delete playback state: ${e.message}")
    }
  }

  /**
   * Prunes all playback states and generated subtitle caches whose local video files no longer exist.
   * Keeps database lean and eliminates stale configs when files are deleted.
   */
  suspend fun pruneOrphanedStates(context: android.content.Context? = null) {
    try {
      val allStates = repository.getAllPlaybackStates()
      var prunedCount = 0
      for (state in allStates) {
        val path = state.localVideoPath
        if (!path.isNullOrBlank()) {
          val file = java.io.File(path)
          if (!file.exists()) {
            repository.deleteByTitle(state.mediaTitle)
            prunedCount++
            Log.d(TAG, "✓ Pruned orphaned playback state for deleted file: $path")

            // Clean up any temporary subtitle cache files generated for this video
            if (context != null) {
              val cacheSubDir = java.io.File(context.cacheDir, "subtitles")
              if (cacheSubDir.exists()) {
                val baseName = file.nameWithoutExtension
                cacheSubDir.listFiles { f -> f.name.contains(baseName) }?.forEach { subFile ->
                  subFile.delete()
                  Log.d(TAG, "✓ Deleted orphaned subtitle cache: ${subFile.name}")
                }
              }
            }
          }
        }
      }
      if (prunedCount > 0) {
        Log.d(TAG, "✓ Pruning complete: removed $prunedCount orphaned playback states")
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to prune orphaned playback states: ${e.message}")
    }
  }
}
