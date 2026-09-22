package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.ui.player.TrackNode
import app.marlboroadvance.mpvex.ui.theme.spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import app.marlboroadvance.mpvex.preferences.SubtitleMode

sealed class SubtitleItem {
  data class Track(val node: TrackNode) : SubtitleItem()
  data class Header(val title: String) : SubtitleItem()
  object Divider : SubtitleItem()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitlesSheet(
  tracks: ImmutableList<TrackNode>,
  subtitleMode: SubtitleMode,
  onSubtitleModeChange: (SubtitleMode) -> Unit,
  onToggleSubtitle: (Int) -> Unit,
  isSubtitleSelected: (Int) -> Boolean,
  getSubtitleRole: (Int) -> String?,
  onAddSubtitle: () -> Unit,
  onOpenSubtitleSettings: () -> Unit,
  onOpenSubtitleDelay: () -> Unit,
  onRemoveSubtitle: (Int) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val items = remember(tracks) {
    val list = mutableListOf<SubtitleItem>()
    
    // Internal/Local tracks section
    val internal = tracks.filter { it.external != true }
    val external = tracks.filter { it.external == true }
    
    if (internal.isNotEmpty() || external.isNotEmpty()) {
        list.add(SubtitleItem.Header(if (internal.isNotEmpty()) "Embedded Subtitles" else "Local Subtitles"))
        list.addAll(internal.map { SubtitleItem.Track(it) })
        if (internal.isNotEmpty() && external.isNotEmpty()) {
          list.add(SubtitleItem.Header("External Subtitles"))
        }
        list.addAll(external.map { SubtitleItem.Track(it) })
    }

    list.toImmutableList()
  }

  GenericTracksSheet(
    tracks = items,
    onDismissRequest = onDismissRequest,
    header = {
      Column {
        AddTrackRow(
          stringResource(R.string.player_sheets_add_ext_sub),
          onAddSubtitle,
          actions = {
            IconButton(onClick = onOpenSubtitleSettings) {
              Icon(Icons.Default.Palette, null)
            }
            IconButton(onClick = onOpenSubtitleDelay) {
              Icon(Icons.Default.MoreTime, null)
            }
          },
        )
        SingleChoiceSegmentedButtonRow(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
        ) {
          SegmentedButton(
            selected = subtitleMode == SubtitleMode.Single,
            onClick = { onSubtitleModeChange(SubtitleMode.Single) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
          ) {
            Text(stringResource(R.string.player_sheets_sub_mode_single))
          }
          SegmentedButton(
            selected = subtitleMode == SubtitleMode.Multi,
            onClick = { onSubtitleModeChange(SubtitleMode.Multi) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
          ) {
            Text(stringResource(R.string.player_sheets_sub_mode_multi))
          }
        }
      }
    },
    track = { item ->
      when (item) {
        is SubtitleItem.Track -> {
          val track = item.node
          SubtitleTrackRow(
            title = getTrackTitle(track),
            isSelected = isSubtitleSelected(track.id),
            isExternal = track.external == true,
            isSingleMode = subtitleMode == SubtitleMode.Single,
            roleBadge = getSubtitleRole(track.id),
            onToggle = { onToggleSubtitle(track.id) },
            onRemove = { onRemoveSubtitle(track.id) },
          )
        }
        is SubtitleItem.Header -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        SubtitleItem.Divider -> {
            HorizontalDivider(
              modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small),
              color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
      }
    },
    footer = {
      Spacer(modifier = Modifier.height(MaterialTheme.spacing.medium))
    },
    modifier = modifier,
  )
}

@Composable
fun SubtitleTrackRow(
  title: String,
  isSelected: Boolean,
  isExternal: Boolean,
  isSingleMode: Boolean = false,
  roleBadge: String? = null,
  onToggle: () -> Unit,
  onRemove: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
  ) {
    if (isSingleMode) {
      RadioButton(selected = isSelected, onClick = onToggle)
    } else {
      Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
    }
    Text(title, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
    if (roleBadge != null && isSelected) {
      Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.padding(end = 4.dp),
      ) {
        Text(
          roleBadge,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
      }
    }
    if (isExternal) {
      IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = null) }
    }
  }
}
