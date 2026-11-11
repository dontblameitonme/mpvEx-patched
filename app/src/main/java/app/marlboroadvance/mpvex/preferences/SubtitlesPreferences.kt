package app.marlboroadvance.mpvex.preferences

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import app.marlboroadvance.mpvex.preferences.preference.Preference
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import app.marlboroadvance.mpvex.preferences.preference.getEnum
import app.marlboroadvance.mpvex.ui.player.controls.components.panels.SubtitlesBorderStyle

import androidx.annotation.StringRes
import app.marlboroadvance.mpvex.R

class SubtitlesPreferences(
  preferenceStore: PreferenceStore,
) {
  init {
    preferenceStore.migrateBooleanToString("sub_override_ass") { if (it) "force" else "no" }
    preferenceStore.migrateIntToFloat("sub_border_size") { if (it <= 4) it.toFloat() else 3.0f }
    preferenceStore.migrateIntToFloat("secondary_sub_border_size") { if (it <= 4) it.toFloat() else 3.0f }
    preferenceStore.migrateIntToFloat("secondary_sub_spacing") { it.toFloat() }
    preferenceStore.migrateIntToFloat("sub_pos") { it.toFloat() }
    preferenceStore.migrateIntToFloat("secondary_sub_pos") { it.toFloat() }
  }

  val preferredLanguages = preferenceStore.getString("sub_preferred_languages")
  val autoloadMatchingSubtitles = preferenceStore.getBoolean("sub_autoload_enabled", true)

  val fontsFolder = preferenceStore.getString("sub_fonts_folder")
  val font = preferenceStore.getString("sub_font", "")
  val fontSize = preferenceStore.getInt("sub_font_size", 55)
  val subScale = preferenceStore.getFloat("sub_scale", 1f)
  val borderSize = preferenceStore.getFloat("sub_border_size", 3.0f)
  val bold = preferenceStore.getBoolean("sub_bold", false)
  val italic = preferenceStore.getBoolean("sub_italic", false)

  val textColor = preferenceStore.getInt("sub_color_text", Color.White.toArgb())

  val borderColor = preferenceStore.getInt("sub_color_border", Color.Black.toArgb())
  val borderStyle = preferenceStore.getEnum("sub_border_style", SubtitlesBorderStyle.OutlineAndShadow)
  val shadowOffset = preferenceStore.getInt("sub_shadow_offset", 0)
  val backgroundColor = preferenceStore.getInt("sub_color_bg", Color.Transparent.toArgb())

  val justification = preferenceStore.getEnum("sub_justify", SubtitleJustification.Auto)
  val subPos = preferenceStore.getFloat("sub_pos", 90.0f)
  val subSpacing = preferenceStore.getInt("sub_spacing", 0)

  // Secondary Subtitle preferences
  val secondaryFont = preferenceStore.getString("secondary_sub_font", "")
  val secondaryFontSize = preferenceStore.getInt("secondary_sub_font_size", 45)
  val secondarySubScale = preferenceStore.getFloat("secondary_sub_scale", 1f)
  val secondaryBorderSize = preferenceStore.getFloat("secondary_sub_border_size", 3.0f)
  val secondaryBold = preferenceStore.getBoolean("secondary_sub_bold", false)
  val secondaryItalic = preferenceStore.getBoolean("secondary_sub_italic", false)

  val secondaryTextColor = preferenceStore.getInt("secondary_sub_color_text", Color.White.toArgb())
  val secondaryBorderColor = preferenceStore.getInt("secondary_sub_color_border", Color.Black.toArgb())
  val secondaryBorderStyle = preferenceStore.getEnum("secondary_sub_border_style", SubtitlesBorderStyle.OutlineAndShadow)
  val secondaryShadowOffset = preferenceStore.getInt("secondary_sub_shadow_offset", 0)
  val secondaryBackgroundColor = preferenceStore.getInt("secondary_sub_color_bg", Color.Transparent.toArgb())

  val secondaryJustification = preferenceStore.getEnum("secondary_sub_justify", SubtitleJustification.Auto)
  val secondarySubPos = preferenceStore.getFloat("secondary_sub_pos", 100.0f)
  val secondarySubSpacing = preferenceStore.getFloat("secondary_sub_spacing", -10.0f)

  val overrideAssSubs =
    preferenceStore.getObject(
      key = "sub_override_ass",
      defaultValue = SubAssOverride.Scale,
      serializer = { it.value },
      deserializer = { SubAssOverride.fromValue(it) },
    )
  val scaleByWindow = preferenceStore.getBoolean("sub_scale_by_window", true)

  val defaultSubDelay = preferenceStore.getInt("sub_default_delay")
  val defaultSubSpeed = preferenceStore.getFloat("sub_default_speed", 1f)
  
  val pickerPath = preferenceStore.getString("sub_picker_path")
  
  val subtitleSaveFolder = preferenceStore.getString("sub_save_folder", "")
  val subtitleMode = preferenceStore.getEnum("sub_mode", SubtitleMode.Multi)

  // Dual-language (SubtitleMode.Multi) independent primary & layout preferences.
  // Keeping these separate from `sub_*` ensures tweaking single-subtitle styling never affects
  // bilingual styling (and vice versa).
  val multiPrimaryFont = preferenceStore.getString("multi_pri_sub_font", "")
  val multiPrimaryFontSize = preferenceStore.getInt("multi_pri_sub_font_size", 55)
  val multiPrimaryBorderSize = preferenceStore.getFloat("multi_pri_sub_border_size", 3.0f)
  val multiPrimaryBold = preferenceStore.getBoolean("multi_pri_sub_bold", false)
  val multiPrimaryItalic = preferenceStore.getBoolean("multi_pri_sub_italic", false)

  val multiPrimaryTextColor = preferenceStore.getInt("multi_pri_sub_color_text", Color.White.toArgb())
  val multiPrimaryBorderColor = preferenceStore.getInt("multi_pri_sub_color_border", Color.Black.toArgb())
  val multiPrimaryBorderStyle =
    preferenceStore.getEnum("multi_pri_sub_border_style", SubtitlesBorderStyle.OutlineAndShadow)
  val multiPrimaryShadowOffset = preferenceStore.getInt("multi_pri_sub_shadow_offset", 0)
  val multiPrimaryBackgroundColor = preferenceStore.getInt("multi_pri_sub_color_bg", Color.Transparent.toArgb())
  val multiPrimaryJustification =
    preferenceStore.getEnum("multi_pri_sub_justify", SubtitleJustification.Auto)

  val multiSubScale = preferenceStore.getFloat("multi_sub_scale", 1f)
  val multiSubPos = preferenceStore.getFloat("multi_sub_pos", 96.0f)
  val multiBilingualGap = preferenceStore.getInt("multi_bilingual_gap", 6)
  val multiSubSpacing = preferenceStore.getInt("multi_sub_spacing", 0)
  val multiOverrideAssSubs =
    preferenceStore.getEnum("multi_sub_override_ass", SubAssOverride.Scale)
  val multiScaleByWindow = preferenceStore.getBoolean("multi_sub_scale_by_window", true)

  // Tracks video keys upgraded to v8 normalized subtitles so stale embedded selections in DB upgrade once.
  val upgradedV8Videos = preferenceStore.getStringSet("sub_v8_upgraded_videos", emptySet())

  fun primaryProfileFor(mode: SubtitleMode): SubtitleStyleProfile =
    when (mode) {
      SubtitleMode.Single ->
        SubtitleStyleProfile(
          font = font,
          fontSize = fontSize,
          borderSize = borderSize,
          bold = bold,
          italic = italic,
          textColor = textColor,
          borderColor = borderColor,
          borderStyle = borderStyle,
          shadowOffset = shadowOffset,
          backgroundColor = backgroundColor,
          justification = justification,
        )

      SubtitleMode.Multi ->
        SubtitleStyleProfile(
          font = multiPrimaryFont,
          fontSize = multiPrimaryFontSize,
          borderSize = multiPrimaryBorderSize,
          bold = multiPrimaryBold,
          italic = multiPrimaryItalic,
          textColor = multiPrimaryTextColor,
          borderColor = multiPrimaryBorderColor,
          borderStyle = multiPrimaryBorderStyle,
          shadowOffset = multiPrimaryShadowOffset,
          backgroundColor = multiPrimaryBackgroundColor,
          justification = multiPrimaryJustification,
        )
    }

  fun secondaryProfile(): SubtitleStyleProfile =
    SubtitleStyleProfile(
      font = secondaryFont,
      fontSize = secondaryFontSize,
      borderSize = secondaryBorderSize,
      bold = secondaryBold,
      italic = secondaryItalic,
      textColor = secondaryTextColor,
      borderColor = secondaryBorderColor,
      borderStyle = secondaryBorderStyle,
      shadowOffset = secondaryShadowOffset,
      backgroundColor = secondaryBackgroundColor,
      justification = secondaryJustification,
    )

  fun layoutProfileFor(mode: SubtitleMode): SubtitleLayoutProfile =
    when (mode) {
      SubtitleMode.Single ->
        SubtitleLayoutProfile(
          subScale = subScale,
          subPos = subPos,
          subSpacing = subSpacing,
          overrideAssSubs = overrideAssSubs,
          scaleByWindow = scaleByWindow,
        )

      SubtitleMode.Multi ->
        SubtitleLayoutProfile(
          subScale = multiSubScale,
          subPos = multiSubPos,
          subSpacing = multiSubSpacing,
          overrideAssSubs = multiOverrideAssSubs,
          scaleByWindow = multiScaleByWindow,
        )
    }

  data class SubtitleStyleProfile(
    val font: Preference<String>,
    val fontSize: Preference<Int>,
    val borderSize: Preference<Float>,
    val bold: Preference<Boolean>,
    val italic: Preference<Boolean>,
    val textColor: Preference<Int>,
    val borderColor: Preference<Int>,
    val borderStyle: Preference<SubtitlesBorderStyle>,
    val shadowOffset: Preference<Int>,
    val backgroundColor: Preference<Int>,
    val justification: Preference<SubtitleJustification>,
  )

  data class SubtitleLayoutProfile(
    val subScale: Preference<Float>,
    val subPos: Preference<Float>,
    val subSpacing: Preference<Int>,
    val overrideAssSubs: Preference<SubAssOverride>,
    val scaleByWindow: Preference<Boolean>,
  )
}

enum class SubtitleMode {
  Single,
  Multi,
}

enum class SubAssOverride(
  val value: String,
  @StringRes val titleRes: Int,
) {
  None("no", R.string.player_sheets_sub_ass_override_none),
  Scale("scale", R.string.player_sheets_sub_ass_override_scale),
  Force("force", R.string.player_sheets_sub_ass_override_force),
  Strip("strip", R.string.player_sheets_sub_ass_override_strip),
  ;

  companion object {
    fun fromValue(value: String): SubAssOverride = entries.firstOrNull { it.value == value } ?: None
  }
}

enum class SubtitleJustification(
  val value: String,
  val icon: ImageVector,
) {
  Left("left", Icons.AutoMirrored.Default.FormatAlignLeft),
  Center("center", Icons.Default.FormatAlignCenter),
  Right("right", Icons.AutoMirrored.Default.FormatAlignRight),
  Auto("auto", Icons.Default.FormatAlignJustify),
}
