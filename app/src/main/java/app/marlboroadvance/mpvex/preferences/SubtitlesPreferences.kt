package app.marlboroadvance.mpvex.preferences

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
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
  val subPos = preferenceStore.getInt("sub_pos", 92)
  val subSpacing = preferenceStore.getInt("sub_spacing", 0)

  // Secondary Subtitle preferences
  val secondaryFont = preferenceStore.getString("secondary_sub_font", "")
  val secondaryFontSize = preferenceStore.getInt("secondary_sub_font_size", 55)
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
  val secondarySubPos = preferenceStore.getInt("secondary_sub_pos", 100)
  val secondarySubSpacing = preferenceStore.getFloat("secondary_sub_spacing", -8.0f)

  val overrideAssSubs =
    preferenceStore.getObject(
      key = "sub_override_ass",
      defaultValue = SubAssOverride.None,
      serializer = { it.value },
      deserializer = { SubAssOverride.fromValue(it) },
    )
  val scaleByWindow = preferenceStore.getBoolean("sub_scale_by_window", true)

  val defaultSubDelay = preferenceStore.getInt("sub_default_delay")
  val defaultSubSpeed = preferenceStore.getFloat("sub_default_speed", 1f)
  
  val pickerPath = preferenceStore.getString("sub_picker_path")
  
  val subtitleSaveFolder = preferenceStore.getString("sub_save_folder", "")
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
