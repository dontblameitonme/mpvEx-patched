package app.marlboroadvance.mpvex.utils.media

import android.util.Log
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.player.TrackNode
import `is`.xyz.mpv.MPVLib

/**
 * [Patch A-1 / A-2 / B-4] ASS/SSA 字幕样式策略（按轨道动态决定 libass 的 override 模式）。
 *
 * 背景：内嵌或外挂的 ASS/SSA 字幕自带特效、定位、画布绘制等标签，在 `scale` 等模式下会原样渲染，
 * 导致用户在字幕设置面板里配置的字号/颜色/位置完全失效（反例 4）。
 *
 * 做法：对「非本程序拆分生成」的 ASS/SSA 文本轨道强制使用 mpv 的 `strip`（剥离全部 ASS 标签与样式，
 * 只保留文本与时间轴，之后按 sub-* 参数当纯文本渲染，效果等同标准 SRT 外挂字幕）。
 * 不需要额外抽取/转换文件，内嵌轨道与外挂 ASS 都适用。
 *
 * 例外（保持基线 8b209fd 行为不变）：
 * - 程序拆分生成的 `[中]` 主字幕：它依赖 ASS 的 MarginV 做主/副字幕避让（A-4），沿用用户选择的覆盖模式。
 * - 程序拆分生成的 `[英]` 副字幕：它依赖自定义的 `Secondary` 样式，副字幕 override 保持 `scale`。
 *
 * 该策略在「轨道切换 / 文件重新加载 / 用户修改覆盖模式」时都会重新应用，避免切轨后设置丢失（B-4）。
 */
object SubtitleAssPolicy {
  private const val TAG = "SubtitleAssPolicy"

  /** 轨道的最小描述，便于同时支持 TrackNode 与直接从 mpv 读取的轨道。 */
  data class TrackInfo(
    val codec: String?,
    val externalFilename: String?,
  )

  // 记录上次已应用的模式，避免重复调用 JNI
  @Volatile private var lastApplied: Triple<String, String, String>? = null

  /** 是否为本程序拆分生成的 [中]/[英] 缓存字幕（位于 BilingualSubtitleParser 的缓存目录）。 */
  fun isGeneratedTrack(track: TrackInfo?): Boolean =
    track?.externalFilename?.contains(BilingualSubtitleParser.CACHE_DIR_NAME) == true

  /** 是否为带样式/特效的 ASS/SSA 文本轨道。 */
  fun isStyledAssTrack(track: TrackInfo?): Boolean {
    if (track == null) return false
    val codec = track.codec?.lowercase()
    if (codec == "ass" || codec == "ssa") return true
    val ext = track.externalFilename?.substringBefore('?')?.substringAfterLast('.', "")?.lowercase()
    return ext == "ass" || ext == "ssa"
  }

  /** 主字幕应使用的 sub-ass-override 值。 */
  fun resolvePrimaryMode(userMode: String, track: TrackInfo?): String =
    if (isStyledAssTrack(track) && !isGeneratedTrack(track)) "strip" else userMode

  /** 副字幕应使用的 secondary-sub-ass-override 值。 */
  fun resolveSecondaryMode(track: TrackInfo?): String =
    if (isStyledAssTrack(track) && !isGeneratedTrack(track)) "strip" else "scale"

  /** 根据当前主/副字幕轨道（来自 ViewModel 的轨道列表）应用策略。 */
  fun apply(
    preferences: SubtitlesPreferences,
    tracks: List<TrackNode>,
    primarySid: Int,
    secondarySid: Int,
  ) {
    fun info(id: Int): TrackInfo? =
      if (id <= 0) null else tracks.firstOrNull { it.id == id }?.let { TrackInfo(it.codec, it.externalFilename) }
    applyInternal(preferences, info(primarySid), info(secondarySid))
  }

  /** 直接从 mpv 读取当前主/副字幕轨道并应用策略（供无 ViewModel 的位置，如设置面板、文件加载时使用）。 */
  fun applyFromMpv(preferences: SubtitlesPreferences) {
    val sid = MPVLib.getPropertyString("sid")?.toIntOrNull() ?: 0
    val secSid = MPVLib.getPropertyString("secondary-sid")?.toIntOrNull() ?: 0
    var primary: TrackInfo? = null
    var secondary: TrackInfo? = null
    if (sid > 0 || secSid > 0) {
      val count = MPVLib.getPropertyInt("track-list/count") ?: 0
      for (i in 0 until count) {
        if (MPVLib.getPropertyString("track-list/$i/type") != "sub") continue
        val id = MPVLib.getPropertyInt("track-list/$i/id") ?: continue
        if (id != sid && id != secSid) continue
        val info = TrackInfo(
          codec = MPVLib.getPropertyString("track-list/$i/codec"),
          externalFilename = MPVLib.getPropertyString("track-list/$i/external-filename"),
        )
        if (id == sid) primary = info
        if (id == secSid) secondary = info
      }
    }
    applyInternal(preferences, primary, secondary, force = true)
  }

  private fun applyInternal(
    preferences: SubtitlesPreferences,
    primary: TrackInfo?,
    secondary: TrackInfo?,
    force: Boolean = false,
  ) {
    val userMode = preferences.overrideAssSubs.get().value
    val priMode = resolvePrimaryMode(userMode, primary)
    val secMode = resolveSecondaryMode(secondary)
    val key = Triple(userMode, priMode, secMode)
    if (!force && key == lastApplied) return
    lastApplied = key
    runCatching {
      MPVLib.setPropertyString("sub-ass-override", priMode)
      MPVLib.setPropertyString("secondary-sub-ass-override", secMode)
      if (priMode == "force" || priMode == "strip") {
        MPVLib.setPropertyBoolean("sub-ass-justify", true)
      }
    }.onFailure { Log.e(TAG, "Failed to apply ASS override policy", it) }
    Log.d(TAG, "ASS override applied: primary=$priMode, secondary=$secMode (user=$userMode)")
  }

  /** 新文件加载时重置缓存，确保策略一定会重新应用。 */
  fun reset() {
    lastApplied = null
  }
}
