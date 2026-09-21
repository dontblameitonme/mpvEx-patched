package app.marlboroadvance.mpvex.presentation.components

import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RepeatingTonalButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  val currentClickListener by rememberUpdatedState(onClick)
  var pressed by remember { mutableStateOf(false) }

  Surface(
    modifier = modifier.pointerInteropFilter {
      if (!enabled) return@pointerInteropFilter false
      pressed = when (it.action) {
        MotionEvent.ACTION_DOWN -> true
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
        else -> pressed
      }
      true
    },
    shape = MaterialTheme.shapes.medium,
    color = if (pressed) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    tonalElevation = if (pressed) 4.dp else 1.dp,
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 12.dp),
      contentAlignment = Alignment.Center,
    ) {
      content()
    }
  }

  LaunchedEffect(pressed, enabled) {
    var currentDelayMillis = 280L
    while (enabled && pressed) {
      currentClickListener()
      delay(currentDelayMillis)
      currentDelayMillis = (currentDelayMillis - (currentDelayMillis * 0.25f)).toLong().coerceAtLeast(35L)
    }
  }
}

/**
 * 0.1 precision fine-tuning dialog for subtitle positions and spacing.
 * Offers direct keyboard input, repeating -1.0, -0.1, +0.1, +1.0 steppers,
 * and quick +/- toggle for negative values.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FineTuneDialog(
  title: String,
  value: Float,
  min: Float,
  max: Float,
  onValueChange: (Float) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  defaultValue: Float? = null,
  stepSmall: Float = 0.1f,
  stepLarge: Float = 1.0f,
) {
  val focusManager = LocalFocusManager.current
  var textValue by remember { mutableStateOf(String.format(Locale.US, "%.1f", value)) }
  var isTextEditing by remember { mutableStateOf(false) }

  // Update text field when value changes externally unless user is actively typing
  LaunchedEffect(value) {
    if (!isTextEditing) {
      textValue = String.format(Locale.US, "%.1f", value)
    }
  }

  val commitOffset: (Float) -> Unit = { delta ->
    val raw = value + delta
    val rounded = (raw * 10f).roundToInt() / 10f
    val clamped = rounded.coerceIn(min, max)
    onValueChange(clamped)
    textValue = String.format(Locale.US, "%.1f", clamped)
    isTextEditing = false
    focusManager.clearFocus()
  }

  BasicAlertDialog(
    onDismissRequest = onDismissRequest,
    modifier = modifier,
  ) {
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = AlertDialogDefaults.containerColor,
      tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
      Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Title row with close button
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column {
            Text(
              text = title,
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              color = AlertDialogDefaults.titleContentColor,
            )
            Text(
              text = stringResource(R.string.fine_tune_range, min, max),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          IconButton(onClick = onDismissRequest) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.generic_cancel))
          }
        }

        // Direct numeric input with +/- sign toggle
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (min < 0f) {
            RepeatingTonalButton(
              onClick = {
                val negated = (-value).coerceIn(min, max)
                val rounded = (negated * 10f).roundToInt() / 10f
                onValueChange(rounded)
                textValue = String.format(Locale.US, "%.1f", rounded)
              },
              modifier = Modifier.width(48.dp),
            ) {
              Text(
                text = "±",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
              )
            }
          }

          OutlinedTextField(
            value = textValue,
            onValueChange = { input ->
              textValue = input
              isTextEditing = true
              val parsed = input.trim().toFloatOrNull()
              if (parsed != null && parsed in min..max) {
                val rounded = (parsed * 10f).roundToInt() / 10f
                onValueChange(rounded)
              }
            },
            label = { Text(stringResource(R.string.fine_tune_input_label)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
              keyboardType = if (min < 0f) KeyboardType.Text else KeyboardType.Decimal,
              imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
              onDone = {
                val parsed = textValue.trim().toFloatOrNull()
                if (parsed != null) {
                  val clamped = ((parsed * 10f).roundToInt() / 10f).coerceIn(min, max)
                  onValueChange(clamped)
                  textValue = String.format(Locale.US, "%.1f", clamped)
                }
                isTextEditing = false
                focusManager.clearFocus()
              }
            ),
            textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
          )
        }

        // Repeating Stepper Buttons: [-1.0], [-0.1], [+0.1], [+1.0]
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            RepeatingTonalButton(
              onClick = { commitOffset(-stepLarge) },
              modifier = Modifier.weight(1f),
              enabled = value > min,
            ) {
              Text(
                text = String.format(Locale.US, "-%.1f", stepLarge).replace(".0", ""),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
              )
            }
            RepeatingTonalButton(
              onClick = { commitOffset(-stepSmall) },
              modifier = Modifier.weight(1f),
              enabled = value > min,
            ) {
              Text(
                text = String.format(Locale.US, "-%.1f", stepSmall),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
              )
            }
            RepeatingTonalButton(
              onClick = { commitOffset(stepSmall) },
              modifier = Modifier.weight(1f),
              enabled = value < max,
            ) {
              Text(
                text = String.format(Locale.US, "+%.1f", stepSmall),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
              )
            }
            RepeatingTonalButton(
              onClick = { commitOffset(stepLarge) },
              modifier = Modifier.weight(1f),
              enabled = value < max,
            ) {
              Text(
                text = String.format(Locale.US, "+%.1f", stepLarge).replace(".0", ""),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
              )
            }
          }

          Text(
            text = stringResource(R.string.fine_tune_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
          )
        }

        // Footer buttons: Reset & Confirm
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (defaultValue != null) {
            TextButton(
              onClick = {
                val clamped = (defaultValue * 10f).roundToInt() / 10f
                onValueChange(clamped)
                textValue = String.format(Locale.US, "%.1f", clamped)
                isTextEditing = false
                focusManager.clearFocus()
              },
            ) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(Icons.Default.FormatClear, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.generic_reset))
              }
            }
          } else {
            Spacer(Modifier.width(1.dp))
          }

          TextButton(onClick = onDismissRequest) {
            Text(stringResource(R.string.generic_ok), fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}
