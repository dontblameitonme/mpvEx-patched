package app.marlboroadvance.mpvex.ui.player.controls.components.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlignVerticalCenter
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.marlboroadvance.mpvex.R
import androidx.compose.runtime.remember
import app.marlboroadvance.mpvex.preferences.SubtitleMode
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.preferences.preference.deleteAndGet
import app.marlboroadvance.mpvex.utils.media.BilingualSubtitleParser
import app.marlboroadvance.mpvex.presentation.components.ExpandableCard
import app.marlboroadvance.mpvex.presentation.components.SliderItem
import app.marlboroadvance.mpvex.ui.player.controls.CARDS_MAX_WIDTH
import app.marlboroadvance.mpvex.ui.player.controls.components.sheets.toFixed
import app.marlboroadvance.mpvex.ui.player.controls.panelCardsColors
import app.marlboroadvance.mpvex.ui.theme.spacing
import `is`.xyz.mpv.MPVLib
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import app.marlboroadvance.mpvex.preferences.SubAssOverride
import kotlin.math.roundToInt
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ListPreferenceType
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SwitchPreference
import app.marlboroadvance.mpvex.presentation.components.FineTuneDialog
import java.util.Locale
import org.koin.compose.koinInject

@Composable
fun SubtitlesMiscellaneousCard(
  modifier: Modifier = Modifier,
  mode: SubtitleMode = SubtitleMode.Single,
) {
  val preferences = koinInject<SubtitlesPreferences>()
  val layout = remember(mode) { preferences.layoutProfileFor(mode) }
  val context = LocalContext.current
  var isExpanded by remember { mutableStateOf(true) }
  ExpandableCard(
    isExpanded,
    title = {
      Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)) {
        Icon(Icons.Default.Tune, null)
        Text(stringResource(R.string.player_sheets_sub_misc_card_title))
      }
    },
    onExpand = { isExpanded = !isExpanded },
    modifier.widthIn(max = CARDS_MAX_WIDTH),
    colors = panelCardsColors(),
  ) {
    ProvidePreferenceLocals {
      Column {
        val overrideAssSubs by layout.overrideAssSubs.collectAsState()
        ListPreference(
          value = overrideAssSubs,
          onValueChange = {
            layout.overrideAssSubs.set(it)
            BilingualSubtitleParser.applySubtitlePreferencesForMode(preferences, mode)
          },
          values = SubAssOverride.entries,
          valueToText = { AnnotatedString(context.getString(it.titleRes)) },
          title = { Text(stringResource(R.string.player_sheets_sub_override_ass)) },
          summary = { Text(stringResource(overrideAssSubs.titleRes)) },
          type = ListPreferenceType.DROPDOWN_MENU,
          icon = { Icon(Icons.Default.Tune, null) },
        )
        val scaleByWindow by layout.scaleByWindow.collectAsState()
        SwitchPreference(
          scaleByWindow,
          onValueChange = {
            layout.scaleByWindow.set(it)
            BilingualSubtitleParser.applySubtitlePreferencesForMode(preferences, mode)
          },
          { Text(stringResource(R.string.player_sheets_sub_scale_by_window)) },
          summary = { Text(stringResource(R.string.player_sheets_sub_scale_by_window_summary)) },
        )
        val subScale by layout.subScale.collectAsState()
        val subPos by layout.subPos.collectAsState()
        var showSubPosFineTune by remember { mutableStateOf(false) }

        SliderItem(
          label = stringResource(R.string.player_sheets_sub_scale),
          value = subScale,
          valueText = subScale.toFixed(2).toString(),
          onChange = {
            layout.subScale.set(it)
            BilingualSubtitleParser.applySubtitlePreferencesForMode(preferences, mode)
          },
          max = 5f,
          icon = {
            Icon(
              Icons.Default.FormatSize,
              null,
            )
          },
        )
        val posLabelRes = if (mode == SubtitleMode.Multi) {
          R.string.player_sheets_sub_position_bilingual_block
        } else {
          R.string.player_sheets_sub_position_single
        }
        SliderItem(
          label = stringResource(posLabelRes),
          value = subPos,
          valueText = String.format(Locale.US, "%.1f", subPos),
          onChange = { rawPos ->
            val newPos = (rawPos * 10f).roundToInt() / 10f
            layout.subPos.set(newPos)
            BilingualSubtitleParser.applySubtitlePreferencesForMode(preferences, mode)
          },
          min = 0f,
          max = 150f,
          steps = 150,
          icon = {
            Icon(
              Icons.Default.AlignVerticalCenter,
              null,
            )
          },
          onLongClick = {
            showSubPosFineTune = true
          },
        )

        if (showSubPosFineTune) {
          FineTuneDialog(
            title = stringResource(posLabelRes),
            value = subPos,
            min = 0f,
            max = 150f,
            defaultValue = if (mode == SubtitleMode.Multi) 96.0f else 90.0f,
            onValueChange = { rawPos ->
              val newPos = (rawPos * 10f).roundToInt() / 10f
              layout.subPos.set(newPos)
              BilingualSubtitleParser.applySubtitlePreferencesForMode(preferences, mode)
            },
            onDismissRequest = { showSubPosFineTune = false },
          )
        }

        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(end = MaterialTheme.spacing.medium, bottom = MaterialTheme.spacing.medium),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(
            onClick = {
              layout.subPos.delete()
              layout.subScale.delete()
              layout.subSpacing.delete()
              layout.overrideAssSubs.delete()
              layout.scaleByWindow.delete()
              BilingualSubtitleParser.applySubtitlePreferencesForMode(preferences, mode)
            },
          ) {
            Row {
              Icon(Icons.Default.EditOff, null)
              Text(stringResource(R.string.generic_reset))
            }
          }
        }
      }
    }
  }
}
