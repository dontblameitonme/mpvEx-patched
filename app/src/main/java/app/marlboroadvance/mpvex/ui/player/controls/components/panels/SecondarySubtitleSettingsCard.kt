package app.marlboroadvance.mpvex.ui.player.controls.components.panels

import android.annotation.SuppressLint
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlignVerticalCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.BorderStyle
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.SubtitleJustification
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.utils.media.applySecondarySubStyleOverrides
import app.marlboroadvance.mpvex.utils.media.toAssColorString
import app.marlboroadvance.mpvex.preferences.preference.Preference
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.preferences.preference.deleteAndGet
import app.marlboroadvance.mpvex.presentation.components.ExpandableCard
import app.marlboroadvance.mpvex.presentation.components.ExposedTextDropDownMenu
import app.marlboroadvance.mpvex.presentation.components.SliderItem
import app.marlboroadvance.mpvex.ui.player.controls.CARDS_MAX_WIDTH
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.toFixed
import app.marlboroadvance.mpvex.ui.player.controls.panelCardsColors
import app.marlboroadvance.mpvex.ui.theme.spacing
import com.github.k1rakishou.fsaf.FileManager
import com.yubyf.truetypeparser.TTFFile
import kotlin.math.roundToInt
import `is`.xyz.mpv.MPVLib
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ListPreferenceType
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.preferenceTheme
import org.koin.compose.koinInject

enum class SecondarySubColorType(
  @StringRes val titleRes: Int,
  val property: String,
  val preference: (SubtitlesPreferences) -> Preference<Int>,
) {
  Text(
    R.string.player_sheets_subtitles_color_text,
    "secondary-sub-color",
    preference = SubtitlesPreferences::secondaryTextColor,
  ),
  Border(
    R.string.player_sheets_subtitles_color_border,
    "secondary-sub-border-color",
    preference = SubtitlesPreferences::secondaryBorderColor,
  ),
  Background(
    R.string.player_sheets_subtitles_color_background,
    "secondary-sub-back-color",
    preference = SubtitlesPreferences::secondaryBackgroundColor,
  ),
}

@SuppressLint("MutableCollectionMutableState")
@Composable
fun SecondarySubtitleSettingsCard(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val preferences = koinInject<SubtitlesPreferences>()
  val fileManager = koinInject<FileManager>()
  var isExpanded by remember { mutableStateOf(false) }
  val fonts by remember { mutableStateOf(mutableListOf("Default")) }
  var fontsLoadingIndicator: (@Composable () -> Unit)? by remember {
    val indicator: (@Composable () -> Unit) = {
      CircularProgressIndicator(Modifier.size(32.dp))
    }
    mutableStateOf(indicator)
  }

  // Defer font scanning until card is expanded to eliminate cold start and player entry lag
  LaunchedEffect(isExpanded) {
    if (!isExpanded || fonts.size > 1) return@LaunchedEffect
    withContext(Dispatchers.IO) {
      val fontsDir = fileManager.fromPath(context.filesDir.path + "/fonts")
      if (fileManager.exists(fontsDir)) {
        fonts.addAll(
          fileManager
            .listFiles(fontsDir)
            .filter { fileManager.isFile(it) && fileManager.getName(it).lowercase().matches(".*\\.[ot]tf$".toRegex()) }
            .mapNotNull {
              runCatching {
                TTFFile.open(fileManager.getInputStream(it) ?: return@mapNotNull null).families.values.first()
              }.getOrNull()
            }.distinct(),
        )
      }
      fontsLoadingIndicator = null
    }
  }

  ExpandableCard(
    isExpanded = isExpanded,
    onExpand = { isExpanded = !isExpanded },
    title = {
      Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Default.Subtitles, null)
        Text(stringResource(R.string.player_sheets_subtitles_secondary_title))
      }
    },
    modifier = modifier.widthIn(max = CARDS_MAX_WIDTH),
    colors = panelCardsColors(),
  ) {
    Column {
      val secondarySidStr by MPVLib.propString["secondary-sid"].collectAsState(initial = MPVLib.getPropertyString("secondary-sid"))
      val secondarySid = secondarySidStr?.toIntOrNull() ?: 0
      if (secondarySid <= 0) {
        Text(
          text = stringResource(R.string.player_sheets_secondary_sub_inactive_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
        )
      } else {
        Text(
          text = stringResource(R.string.player_sheets_secondary_sub_active_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
        )
      }

      val isBold by preferences.secondaryBold.collectAsState()
      val isItalic by preferences.secondaryItalic.collectAsState()
      val justify by preferences.secondaryJustification.collectAsState()
      val font by preferences.secondaryFont.collectAsState()
      val fontSize by preferences.secondaryFontSize.collectAsState()
      val borderStyle by preferences.secondaryBorderStyle.collectAsState()
      val borderSize by preferences.secondaryBorderSize.collectAsState()
      val shadowOffset by preferences.secondaryShadowOffset.collectAsState()
      val subScale by preferences.secondarySubScale.collectAsState()
      val secondarySpacing by preferences.secondarySubSpacing.collectAsState()

      // Bold, Italic, Alignment, Reset
      Row(
        Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(start = MaterialTheme.spacing.extraSmall, end = MaterialTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconToggleButton(
          checked = isBold == true,
          onCheckedChange = {
            preferences.secondaryBold.set(it)
            applySecondarySubStyleOverrides(preferences)
          },
        ) {
          Icon(Icons.Default.FormatBold, null, modifier = Modifier.size(32.dp))
        }
        IconToggleButton(
          checked = isItalic == true,
          onCheckedChange = {
            preferences.secondaryItalic.set(it)
            applySecondarySubStyleOverrides(preferences)
          },
        ) {
          Icon(Icons.Default.FormatItalic, null, modifier = Modifier.size(32.dp))
        }
        SubtitleJustification.entries.minus(SubtitleJustification.Auto).forEach { justification ->
          IconToggleButton(
            checked = justify == justification,
            onCheckedChange = {
              if (it) {
                preferences.secondaryJustification.set(justification)
              } else {
                preferences.secondaryJustification.set(SubtitleJustification.Auto)
              }
              applySecondarySubStyleOverrides(preferences)
            },
          ) {
            Icon(justification.icon, null)
          }
        }
        Spacer(Modifier.weight(1f))
        TextButton(
          onClick = { resetSecondarySubtitles(preferences) },
        ) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.Default.FormatClear, null)
            Text(stringResource(R.string.generic_reset))
          }
        }
      }

      // Font family selector
      Row(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          painterResource(R.drawable.outline_brand_family_24),
          null,
          modifier = Modifier.size(32.dp),
        )
        ExposedTextDropDownMenu(
          selectedValue = font.ifEmpty { "Default" },
          options = fonts.toImmutableList(),
          label = stringResource(R.string.player_sheets_sub_typography_font),
          onValueChangedEvent = {
            val actualFont = if (it == "Default") "" else it
            preferences.secondaryFont.set(actualFont)
            applySecondarySubStyleOverrides(preferences)
          },
          leadingIcon = fontsLoadingIndicator,
        )
      }

      // Font size slider
      SliderItem(
        label = stringResource(R.string.player_sheets_sub_typography_font_size),
        max = 100,
        min = 1,
        value = fontSize,
        valueText = fontSize.toString(),
        onChange = {
          preferences.secondaryFontSize.set(it)
          applySecondarySubStyleOverrides(preferences)
        },
      ) {
        Icon(Icons.Default.FormatSize, null)
      }

      // Border style dropdown
      ProvidePreferenceLocals(
        theme = preferenceTheme(iconContainerMinWidth = 64.dp),
      ) {
        ListPreference(
          borderStyle,
          onValueChange = {
            preferences.secondaryBorderStyle.set(it)
            applySecondarySubStyleOverrides(preferences)
          },
          title = { Text(stringResource(R.string.player_sheets_subtitles_border_style)) },
          valueToText = { AnnotatedString(context.getString(it.titleRes)) },
          values = SubtitlesBorderStyle.entries,
          type = ListPreferenceType.DROPDOWN_MENU,
          summary = { Text(stringResource(borderStyle.titleRes)) },
          icon = { Icon(Icons.Default.BorderStyle, null) },
        )
      }

      // Border size slider
      SliderItem(
        label = stringResource(R.string.player_sheets_sub_typography_border_size),
        value = borderSize,
        valueText = borderSize.toFixed(1).toString(),
        onChange = {
          preferences.secondaryBorderSize.set(it)
          applySecondarySubStyleOverrides(preferences)
        },
        min = 0f,
        max = 4f,
        steps = 19,
        icon = { Icon(Icons.Default.BorderColor, null) },
      )

      // Shadow offset slider
      SliderItem(
        stringResource(R.string.player_sheets_subtitles_shadow_offset),
        value = shadowOffset,
        valueText = shadowOffset.toString(),
        onChange = {
          preferences.secondaryShadowOffset.set(it)
          applySecondarySubStyleOverrides(preferences)
        },
        max = 100,
        icon = { Icon(painterResource(R.drawable.sharp_shadow_24), null) },
      )

      // Scale slider
      SliderItem(
        label = stringResource(R.string.player_sheets_sub_scale),
        value = subScale,
        valueText = subScale.toFixed(2).toString(),
        onChange = {
          preferences.secondarySubScale.set(it)
          applySecondarySubStyleOverrides(preferences)
        },
        max = 5f,
        icon = { Icon(Icons.Default.FormatSize, null) },
      )

      // Line spacing slider between primary and secondary subtitles (-40.0 to +40.0, step 0.2)
      SliderItem(
        label = stringResource(R.string.player_sheets_secondary_sub_spacing_label),
        value = secondarySpacing,
        valueText = secondarySpacing.toFixed(1).toString(),
        onChange = { rawSpacing ->
          val newSpacing = (rawSpacing * 5f).roundToInt() / 5f
          preferences.secondarySubSpacing.set(newSpacing)
          applySecondarySubStyleOverrides(preferences)
        },
        min = -40f,
        max = 40f,
        steps = 399,
        icon = { Icon(Icons.Default.AlignVerticalCenter, null) },
      )

      // Colors Section
      var currentColorType by remember { mutableStateOf(SecondarySubColorType.Text) }
      var currentColor by remember {
        mutableIntStateOf(getSecondaryMPVColor(currentColorType, preferences))
      }
      LaunchedEffect(currentColorType) {
        currentColor = getSecondaryMPVColor(currentColorType, preferences)
      }

      Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier =
          Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = MaterialTheme.spacing.extraSmall, end = MaterialTheme.spacing.medium),
      ) {
        SecondarySubColorType.entries.forEach { type ->
          IconToggleButton(
            checked = currentColorType == type,
            onCheckedChange = { currentColorType = type },
          ) {
            Icon(
              when (type) {
                SecondarySubColorType.Text -> Icons.Default.FormatColorText
                SecondarySubColorType.Border -> Icons.Default.BorderColor
                SecondarySubColorType.Background -> Icons.Default.FormatColorFill
              },
              null,
            )
          }
        }
        Text(stringResource(currentColorType.titleRes))
        Spacer(Modifier.weight(1f))
        TextButton(
          onClick = {
            resetSecondaryColors(preferences, currentColorType)
            currentColor = getSecondaryMPVColor(currentColorType, preferences)
          },
        ) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.Default.FormatColorReset, null)
            Text(stringResource(R.string.generic_reset))
          }
        }
      }

      SubtitlesColorPicker(
        currentColor,
        onColorChange = {
          currentColor = it
          currentColorType.preference(preferences).set(it)
          applySecondarySubStyleOverrides(preferences)
        },
      )
    }
  }
}

private fun getSecondaryMPVColor(
  type: SecondarySubColorType,
  preferences: SubtitlesPreferences,
): Int {
  return type.preference(preferences).get()
}

private fun resetSecondaryColors(
  preferences: SubtitlesPreferences,
  type: SecondarySubColorType,
) {
  type.preference(preferences).delete()
  applySecondarySubStyleOverrides(preferences)
}

private fun resetSecondarySubtitles(preferences: SubtitlesPreferences) {
  preferences.secondaryBold.delete()
  preferences.secondaryItalic.delete()
  preferences.secondaryJustification.delete()
  preferences.secondaryFont.delete()
  preferences.secondaryFontSize.delete()
  preferences.secondaryBorderSize.delete()
  preferences.secondaryShadowOffset.delete()
  preferences.secondaryBorderStyle.delete()
  preferences.secondarySubScale.delete()
  preferences.secondarySubSpacing.delete()
  preferences.secondarySubPos.delete()
  preferences.secondaryTextColor.delete()
  preferences.secondaryBorderColor.delete()
  preferences.secondaryBackgroundColor.delete()
  applySecondarySubStyleOverrides(preferences)
}
