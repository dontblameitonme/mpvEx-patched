package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.presentation.components.PlayerSheet
import app.marlboroadvance.mpvex.ui.player.VideoAspect
import app.marlboroadvance.mpvex.ui.theme.spacing

data class AspectRatio(
  val label: String,
  val ratio: Double,
  val isCustom: Boolean = false,
)

@Composable
fun AspectRatioSheet(
  currentRatio: Double?,
  customRatios: List<AspectRatio>,
  customCropRatio: Double?,
  customCropText: String,
  currentVideoAspect: VideoAspect,
  onSelectRatio: (Double) -> Unit,
  onAddCustomRatio: (String, Double) -> Unit,
  onDeleteCustomRatio: (AspectRatio) -> Unit,
  onApplyCropRatio: (Double, String) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val presetRatios =
    listOf(
      AspectRatio("Default", -1.0),
      AspectRatio("4:3", 4.0 / 3.0),
      AspectRatio("16:9", 16.0 / 9.0),
      AspectRatio("16:10", 16.0 / 10.0),
      AspectRatio("21:9", 21.0 / 9.0),
      AspectRatio("32:9", 32.0 / 9.0),
      AspectRatio("1:1", 1.0),
      AspectRatio("2.35:1", 2.35),
      AspectRatio("2.39:1", 2.39),
    )

  PlayerSheet(onDismissRequest) {
    Column(
      modifier =
        modifier
          .verticalScroll(rememberScrollState())
          .padding(vertical = MaterialTheme.spacing.medium),
    ) {
      Text(
        text = "Aspect Ratio",
        style = MaterialTheme.typography.headlineSmall,
        modifier =
          Modifier
            .padding(horizontal = MaterialTheme.spacing.medium)
            .padding(bottom = MaterialTheme.spacing.small),
      )

      // ==================== Crop Mode (裁切模式) ====================
      CropModeSection(
        customCropRatio = customCropRatio,
        customCropText = customCropText,
        isCropActive = currentVideoAspect == VideoAspect.Custom,
        onApplyCrop = onApplyCropRatio,
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
      )

      HorizontalDivider(
        modifier = Modifier.padding(vertical = MaterialTheme.spacing.medium),
      )

      // ==================== Stretch Presets (拉伸预设) ====================
      Text(
        text = stringResource(R.string.player_sheets_aspect_stretch_presets),
        style = MaterialTheme.typography.titleSmall,
        modifier =
          Modifier
            .padding(horizontal = MaterialTheme.spacing.medium)
            .padding(top = MaterialTheme.spacing.extraSmall),
      )

      LazyRow(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
      ) {
        items(presetRatios, key = { it.label }) { ratio ->
          val isSelected =
            currentVideoAspect != VideoAspect.Custom &&
              (currentRatio?.let { abs(it - ratio.ratio) < 0.01 } ?: (ratio.ratio == -1.0))
          InputChip(
            selected = isSelected,
            onClick = { onSelectRatio(ratio.ratio) },
            label = { Text(ratio.label) },
            modifier = Modifier.animateItem(),
            leadingIcon = null,
          )
        }
      }

      // ==================== Custom Stretch ratios ====================
      if (customRatios.isNotEmpty()) {
        Text(
          text = stringResource(R.string.player_sheets_aspect_custom_stretch),
          style = MaterialTheme.typography.titleSmall,
          modifier =
            Modifier
              .padding(horizontal = MaterialTheme.spacing.medium)
              .padding(top = MaterialTheme.spacing.medium),
        )

        LazyRow(
          modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
          horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
          items(customRatios, key = { it.label }) { ratio ->
            val isSelected =
              currentVideoAspect != VideoAspect.Custom &&
                (currentRatio?.let { abs(it - ratio.ratio) < 0.01 } ?: false)
            InputChip(
              selected = isSelected,
              onClick = { onSelectRatio(ratio.ratio) },
              label = { Text(ratio.label) },
              leadingIcon = null,
              trailingIcon = {
                Icon(
                  Icons.Default.Close,
                  null,
                  modifier = Modifier.clickable { onDeleteCustomRatio(ratio) },
                )
              },
              modifier = Modifier.animateItem(),
            )
          }
        }
      }

      // Add custom stretch ratio
      AddCustomRatioRow(
        onAdd = onAddCustomRatio,
        modifier = Modifier.padding(top = MaterialTheme.spacing.medium),
      )
    }
  }
}

@Composable
private fun CropModeSection(
  customCropRatio: Double?,
  customCropText: String,
  isCropActive: Boolean,
  onApplyCrop: (Double, String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val initialParts = customCropText.split(":")
  var widthText by remember(customCropText) {
    mutableStateOf(if (initialParts.isNotEmpty() && initialParts[0].isNotBlank()) initialParts[0] else "")
  }
  var heightText by remember(customCropText) {
    mutableStateOf(if (initialParts.size > 1 && initialParts[1].isNotBlank()) initialParts[1] else "")
  }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  val keyboardController = LocalSoftwareKeyboardController.current

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.player_sheets_aspect_crop_mode),
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          text = stringResource(R.string.player_sheets_aspect_crop_mode_summary),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (isCropActive && customCropRatio != null && customCropRatio > 0) {
        SuggestionChip(
          onClick = {},
          label = { Text("Active") },
          icon = { Icon(Icons.Default.Check, null) },
        )
      }
    }

    Spacer(Modifier.height(MaterialTheme.spacing.small))

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
      modifier = Modifier.fillMaxWidth(),
    ) {
      // Width input
      OutlinedTextField(
        value = widthText,
        onValueChange = {
          widthText = it.filter { char -> char.isDigit() || char == '.' }
          errorMessage = null
        },
        label = { Text("Width (e.g. 17.5)") },
        isError = errorMessage != null,
        keyboardOptions =
          KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next,
          ),
        modifier = Modifier.weight(1f),
        singleLine = true,
      )

      // Colon separator
      Text(
        text = ":",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.extraSmall),
      )

      // Height input
      OutlinedTextField(
        value = heightText,
        onValueChange = {
          heightText = it.filter { char -> char.isDigit() || char == '.' }
          errorMessage = null
        },
        label = { Text("Height (e.g. 9)") },
        isError = errorMessage != null,
        keyboardOptions =
          KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
          ),
        keyboardActions =
          KeyboardActions(
            onDone = {
              val result = calculateRatio(widthText, heightText)
              if (result != null) {
                onApplyCrop(result, "$widthText:$heightText")
                keyboardController?.hide()
              } else {
                errorMessage = "Invalid ratio"
              }
            },
          ),
        modifier = Modifier.weight(1f),
        singleLine = true,
      )
    }

    Spacer(Modifier.height(MaterialTheme.spacing.small))

    Button(
      onClick = {
        val result = calculateRatio(widthText, heightText)
        if (result != null) {
          onApplyCrop(result, "$widthText:$heightText")
          keyboardController?.hide()
        } else {
          errorMessage = "Invalid ratio"
        }
      },
      modifier = Modifier.fillMaxWidth(),
      colors = ButtonDefaults.buttonColors(
        containerColor = if (isCropActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isCropActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
      ),
    ) {
      Icon(
        painter = painterResource(R.drawable.ic_crop_custom_24),
        contentDescription = null,
        modifier = Modifier.padding(end = MaterialTheme.spacing.small),
      )
      Text(stringResource(R.string.player_sheets_aspect_apply_crop))
    }

    errorMessage?.let { msg ->
      Text(
        text = msg,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = MaterialTheme.spacing.small, top = 4.dp),
      )
    }
  }
}

@Composable
private fun AddCustomRatioRow(
  onAdd: (String, Double) -> Unit,
  modifier: Modifier = Modifier,
) {
  var widthText by remember { mutableStateOf("") }
  var heightText by remember { mutableStateOf("") }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  val keyboardController = LocalSoftwareKeyboardController.current

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = MaterialTheme.spacing.medium),
  ) {
    Text(
      text = "Add Custom Stretch Ratio (e.g. 16:9)",
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier.padding(bottom = MaterialTheme.spacing.small),
    )

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
      modifier = Modifier.fillMaxWidth(),
    ) {
      // Width input
      OutlinedTextField(
        value = widthText,
        onValueChange = {
          widthText = it.filter { char -> char.isDigit() || char == '.' }
          errorMessage = null
        },
        label = { Text("Width") },
        isError = errorMessage != null,
        keyboardOptions =
          KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next,
          ),
        modifier = Modifier.weight(1f),
        singleLine = true,
      )

      // Colon separator
      Text(
        text = ":",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.extraSmall),
      )

      // Height input
      OutlinedTextField(
        value = heightText,
        onValueChange = {
          heightText = it.filter { char -> char.isDigit() || char == '.' }
          errorMessage = null
        },
        label = { Text("Height") },
        isError = errorMessage != null,
        keyboardOptions =
          KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
          ),
        keyboardActions =
          KeyboardActions(
            onDone = {
              val result = calculateRatio(widthText, heightText)
              if (result != null) {
                onAdd("$widthText:$heightText", result)
                widthText = ""
                heightText = ""
                keyboardController?.hide()
              } else {
                errorMessage = "Invalid"
              }
            },
          ),
        modifier = Modifier.weight(1f),
        singleLine = true,
      )

      // Add button
      FilledTonalIconButton(
        onClick = {
          val result = calculateRatio(widthText, heightText)
          if (result != null) {
            onAdd("$widthText:$heightText", result)
            widthText = ""
            heightText = ""
            keyboardController?.hide()
          } else {
            errorMessage = "Invalid"
          }
        },
      ) {
        Icon(Icons.Default.Add, contentDescription = "Add")
      }
    }

    errorMessage?.let { msg ->
      Text(
        text = msg,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = MaterialTheme.spacing.small, top = 4.dp),
      )
    }
  }
}

private fun calculateRatio(
  widthStr: String,
  heightStr: String,
): Double? {
  if (widthStr.isEmpty() || heightStr.isEmpty()) return null

  return try {
    val width = widthStr.toDouble()
    val height = heightStr.toDouble()
    if (width > 0 && height > 0) width / height else null
  } catch (_: NumberFormatException) {
    null
  }
}

private fun abs(value: Double): Double = if (value < 0) -value else value
