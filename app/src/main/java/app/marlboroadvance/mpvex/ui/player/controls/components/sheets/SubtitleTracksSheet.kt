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

import androidx.compose.ui.text.style.TextOverflow
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
  data class CollapsibleHeader(
    val title: String,
    val count: Int,
    val isExpanded: Boolean,
    val selectedTracks: List<TrackNode> = emptyList(),
    val onToggle: () -> Unit,
  ) : SubtitleItem()
  object Divider : SubtitleItem()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitlesSheet(
  tracks: ImmutableList<TrackNode>,
  primarySid: Int,
  secondarySid: Int,
  subtitleMode: SubtitleMode,
  onSubtitleModeChange: (SubtitleMode) -> Unit,
  onToggleSubtitle: (Int) -> Unit,
  onAddSubtitle: () -> Unit,
  onOpenSubtitleSettings: () -> Unit,
  onOpenSubtitleDelay: () -> Unit,
  onRemoveSubtitle: (Int) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var isEmbeddedExpanded by remember { mutableStateOf(false) }

  val embeddedTitle = stringResource(R.string.player_sheets_sub_embedded)
  val externalTitle = stringResource(R.string.player_sheets_sub_external)

  val items = remember(
    tracks,
    isEmbeddedExpanded,
    primarySid,
    secondarySid,
    subtitleMode,
    embeddedTitle,
    externalTitle,
  ) {
    val list = mutableListOf<SubtitleItem>()

    // Internal/Embedded tracks vs External tracks
    val internal = tracks.filter { it.external != true }
    val external = tracks.filter { it.external == true }

    if (internal.isNotEmpty()) {
      val selectedInternalTracks = internal.filter {
        (it.id == primarySid && primarySid > 0) || (it.id == secondarySid && secondarySid > 0)
      }

      list.add(
        SubtitleItem.CollapsibleHeader(
          title = embeddedTitle,
          count = internal.size,
          isExpanded = isEmbeddedExpanded,
          selectedTracks = selectedInternalTracks,
          onToggle = { isEmbeddedExpanded = !isEmbeddedExpanded },
        )
      )

      if (isEmbeddedExpanded) {
        list.addAll(internal.map { SubtitleItem.Track(it) })
      }
    }

    if (external.isNotEmpty()) {
      if (internal.isNotEmpty() && isEmbeddedExpanded) {
        list.add(SubtitleItem.Divider)
      }
      list.add(SubtitleItem.Header(externalTitle))
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
          val isSelected = if (subtitleMode == SubtitleMode.Single) {
            track.id == primarySid && primarySid > 0
          } else {
            (track.id == primarySid && primarySid > 0) || (track.id == secondarySid && secondarySid > 0)
          }
          val roleBadge = if (subtitleMode == SubtitleMode.Single) {
            null
          } else if (track.title?.startsWith("[双语]") == true && track.id == primarySid) {
            stringResource(R.string.player_sheets_sub_role_bilingual)
          } else when (track.id) {
            primarySid -> stringResource(R.string.player_sheets_sub_role_primary)
            secondarySid -> stringResource(R.string.player_sheets_sub_role_secondary)
            else -> null
          }

          SubtitleTrackRow(
            title = getTrackTitle(track),
            isSelected = isSelected,
            isExternal = track.external == true,
            isSingleMode = subtitleMode == SubtitleMode.Single,
            roleBadge = roleBadge,
            onToggle = { onToggleSubtitle(track.id) },
            onRemove = { onRemoveSubtitle(track.id) },
          )
        }
        is SubtitleItem.CollapsibleHeader -> {
          val selectedTrackTitle = when {
            item.selectedTracks.isEmpty() -> null
            item.selectedTracks.size == 1 -> getTrackTitle(item.selectedTracks.first())
            else -> {
              val titles = mutableListOf<String>()
              for (track in item.selectedTracks) {
                titles.add(getTrackTitle(track))
              }
              titles.joinToString(", ")
            }
          }

          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall)
              .clickable(onClick = item.onToggle),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                modifier = Modifier.weight(1f, fill = false),
              ) {
                Icon(
                  imageVector = if (item.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp),
                )
                Text(
                  text = "${item.title} (${item.count})",
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.Bold,
                )
              }
              if (selectedTrackTitle != null) {
                Surface(
                  shape = MaterialTheme.shapes.extraSmall,
                  color = MaterialTheme.colorScheme.secondaryContainer,
                  modifier = Modifier.padding(start = MaterialTheme.spacing.small),
                ) {
                  Text(
                    text = stringResource(R.string.player_sheets_sub_selected_prefix, selectedTrackTitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  )
                }
              }
            }
          }
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
