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
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.preferences.preference.deleteAndGet
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
import app.marlboroadvance.mpvex.utils.media.applySecondarySubStyleOverrides
import me.zhanghai.compose.preference.ListPreference
import kotlin.math.roundToInt
import me.zhanghai.compose.preference.ListPreferenceType
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SwitchPreference
import app.marlboroadvance.mpvex.presentation.components.FineTuneDialog
import app.marlboroadvance.mpvex.utils.media.applySecondarySubStyleOverrides
import java.util.Locale
import org.koin.compose.koinInject

@Composable
fun SubtitlesMiscellaneousCard(modifier: Modifier = Modifier) {
  val preferences = koinInject<SubtitlesPreferences>()
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
        var overrideAssSubs by remember {
          mutableStateOf(preferences.overrideAssSubs.get())
        }
        ListPreference(
          value = overrideAssSubs,
          onValueChange = {
            overrideAssSubs = it
            preferences.overrideAssSubs.set(it)
            MPVLib.setPropertyString("sub-ass-override", it.value)
            MPVLib.setPropertyString("secondary-sub-ass-override", it.value)
            if (it.value == "force" || it.value == "strip") {
              MPVLib.setPropertyBoolean("sub-ass-justify", true)
              MPVLib.setPropertyBoolean("secondary-sub-ass-justify", true)
            }
          },
          values = SubAssOverride.entries,
          valueToText = { AnnotatedString(context.getString(it.titleRes)) },
          title = { Text(stringResource(R.string.player_sheets_sub_override_ass)) },
          summary = { Text(stringResource(overrideAssSubs.titleRes)) },
          type = ListPreferenceType.DROPDOWN_MENU,
          icon = { Icon(Icons.Default.Tune, null) },
        )
        var scaleByWindow by remember {
          mutableStateOf(MPVLib.getPropertyString("sub-scale-by-window") == "yes")
        }
        SwitchPreference(
          scaleByWindow,
          onValueChange = {
            scaleByWindow = it
            preferences.scaleByWindow.set(it)
            val value = if (it) "yes" else "no"
            MPVLib.setPropertyString("sub-scale-by-window", value)
            MPVLib.setPropertyString("sub-use-margins", value)
            MPVLib.setPropertyString("sub-ass-scale-with-window", value)
            MPVLib.setPropertyString("sub-ass-force-margins", value)
            applySecondarySubStyleOverrides(preferences)
          },
          { Text(stringResource(R.string.player_sheets_sub_scale_by_window)) },
          summary = { Text(stringResource(R.string.player_sheets_sub_scale_by_window_summary)) },
        )
        val subScale by preferences.subScale.collectAsState()
        val subPos by preferences.subPos.collectAsState()
        var showSubPosFineTune by remember { mutableStateOf(false) }

        SliderItem(
          label = stringResource(R.string.player_sheets_sub_scale),
          value = subScale,
          valueText = subScale.toFixed(2).toString(),
          onChange = {
            preferences.subScale.set(it)
            MPVLib.setPropertyFloat("sub-scale", it)
          },
          max = 5f,
          icon = {
            Icon(
              Icons.Default.FormatSize,
              null,
            )
          },
        )
        SliderItem(
          label = stringResource(R.string.player_sheets_sub_position_overall),
          value = subPos,
          valueText = String.format(Locale.US, "%.1f", subPos),
          onChange = { rawPos ->
            val newPos = (rawPos * 10f).roundToInt() / 10f
            preferences.subPos.set(newPos)
            MPVLib.setPropertyInt("sub-pos", newPos.roundToInt())
            applySecondarySubStyleOverrides(preferences)
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
            title = stringResource(R.string.player_sheets_sub_position_overall),
            value = subPos,
            min = 0f,
            max = 150f,
            defaultValue = 92.0f,
            onValueChange = { rawPos ->
              val newPos = (rawPos * 10f).roundToInt() / 10f
              preferences.subPos.set(newPos)
              MPVLib.setPropertyInt("sub-pos", newPos.roundToInt())
              applySecondarySubStyleOverrides(preferences)
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
              preferences.subPos.deleteAndGet().let { defaultPos ->
                MPVLib.setPropertyInt("sub-pos", defaultPos.roundToInt())
                applySecondarySubStyleOverrides(preferences)
              }
              preferences.subScale.deleteAndGet().let {
                MPVLib.setPropertyFloat("sub-scale", it)
              }
              val defaultOverride = preferences.overrideAssSubs.deleteAndGet()
              overrideAssSubs = defaultOverride
              MPVLib.setPropertyString("sub-ass-override", defaultOverride.value)
              MPVLib.setPropertyString("secondary-sub-ass-override", defaultOverride.value)
              val defaultScaleByWindow = preferences.scaleByWindow.deleteAndGet()
              scaleByWindow = defaultScaleByWindow
              val scaleValue = if (defaultScaleByWindow) "yes" else "no"
              MPVLib.setPropertyString("sub-scale-by-window", scaleValue)
              MPVLib.setPropertyString("sub-use-margins", scaleValue)
              MPVLib.setPropertyString("sub-ass-scale-with-window", scaleValue)
              MPVLib.setPropertyString("sub-ass-force-margins", scaleValue)
              applySecondarySubStyleOverrides(preferences)
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
