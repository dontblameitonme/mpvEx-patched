package app.marlboroadvance.mpvex.ui.player.controls.components.panels

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.SubtitleMode
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.utils.media.BilingualSubtitleParser
import app.marlboroadvance.mpvex.presentation.components.ExpandableCard
import app.marlboroadvance.mpvex.presentation.components.TintedSliderItem
import app.marlboroadvance.mpvex.ui.player.controls.CARDS_MAX_WIDTH
import app.marlboroadvance.mpvex.ui.player.controls.panelCardsColors
import app.marlboroadvance.mpvex.ui.theme.spacing
import org.koin.compose.koinInject

@Composable
fun SubtitleSettingsColorsCard(
  modifier: Modifier = Modifier,
  mode: SubtitleMode = SubtitleMode.Single,
) {
  val preferences = koinInject<SubtitlesPreferences>()
  val profile = remember(mode) { preferences.primaryProfileFor(mode) }
  var isExpanded by remember { mutableStateOf(true) }
  ExpandableCard(
    isExpanded = isExpanded,
    onExpand = { isExpanded = !isExpanded },
    title = {
      Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
      ) {
        Icon(Icons.Default.Palette, null)
        Text(
          stringResource(
            if (mode == SubtitleMode.Multi) {
              R.string.player_sheets_sub_primary_colors_title
            } else {
              R.string.player_sheets_sub_colors_card_title
            },
          ),
        )
      }
    },
    modifier = modifier.widthIn(max = CARDS_MAX_WIDTH),
    colors = panelCardsColors(),
  ) {
    Column {
      var currentColorType by remember { mutableStateOf(SubColorType.Text) }
      val activePref = remember(profile, currentColorType) {
        when (currentColorType) {
          SubColorType.Text -> profile.textColor
          SubColorType.Border -> profile.borderColor
          SubColorType.Background -> profile.backgroundColor
        }
      }
      var currentColor by remember(mode, currentColorType) { mutableIntStateOf(activePref.get()) }
      LaunchedEffect(currentColorType, mode) {
        currentColor = activePref.get()
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
        SubColorType.entries.forEach { type ->
          IconToggleButton(
            checked = currentColorType == type,
            onCheckedChange = { currentColorType = type },
          ) {
            Icon(
              when (type) {
                SubColorType.Text -> Icons.Default.FormatColorText
                SubColorType.Border -> Icons.Default.BorderColor
                SubColorType.Background -> Icons.Default.FormatColorFill
              },
              null,
            )
          }
        }
        Text(stringResource(currentColorType.titleRes))
        Spacer(Modifier.weight(1f))
        TextButton(
          onClick = {
            activePref.delete()
            currentColor = activePref.get()
            BilingualSubtitleParser.applySubtitlePreferencesForMode(preferences, mode)
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
          activePref.set(it)
          BilingualSubtitleParser.applySubtitlePreferencesForMode(preferences, mode)
        },
      )
    }
  }
}

fun Int.copyAsArgb(
  alpha: Int = this.alpha,
  red: Int = this.red,
  green: Int = this.green,
  blue: Int = this.blue,
) = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

@OptIn(ExperimentalStdlibApi::class)
fun Int.toColorHexString() = "#" + this.toHexString().uppercase()

enum class SubColorType(
  @StringRes val titleRes: Int,
) {
  Text(R.string.player_sheets_subtitles_color_text),
  Border(R.string.player_sheets_subtitles_color_border),
  Background(R.string.player_sheets_subtitles_color_background),
}

@Composable
fun SubtitlesColorPicker(
  color: Int,
  onColorChange: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier) {
    TintedSliderItem(
      stringResource(R.string.player_sheets_sub_color_red),
      color.red,
      color.red.toString(),
      onChange = { onColorChange(color.copyAsArgb(red = it)) },
      max = 255,
      tint = Color.Red,
    )

    TintedSliderItem(
      stringResource(R.string.player_sheets_sub_color_green),
      color.green,
      color.green.toString(),
      onChange = { onColorChange(color.copyAsArgb(green = it)) },
      max = 255,
      tint = Color.Green,
    )

    TintedSliderItem(
      stringResource(R.string.player_sheets_sub_color_blue),
      color.blue,
      color.blue.toString(),
      onChange = { onColorChange(color.copyAsArgb(blue = it)) },
      max = 255,
      tint = Color.Blue,
    )

    TintedSliderItem(
      stringResource(R.string.player_sheets_sub_color_alpha),
      color.alpha,
      color.alpha.toString(),
      onChange = { onColorChange(color.copyAsArgb(alpha = it)) },
      max = 255,
      tint = Color.White,
    )
  }
}
