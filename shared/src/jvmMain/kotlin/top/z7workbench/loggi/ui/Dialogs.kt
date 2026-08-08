package top.z7workbench.loggi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import top.z7workbench.loggi.i18n.LocalStrings
import top.z7workbench.loggi.vm.ColorPickerRequest
import top.z7workbench.loggi.vm.FileTab
import top.z7workbench.loggi.vm.FileViewModel

@Composable
fun GoToLineDialog(vm: FileViewModel, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    fun go() {
        val n = text.trim().toLongOrNull()
        if (n == null || n < 1 || n > vm.info.lineCount) {
            error = true
        } else {
            vm.jumpToLine(n - 1)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.goToLineTitle) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = false },
                label = { Text(strings.goToLineLabel) },
                isError = error,
                supportingText = { if (error) Text(strings.invalidLineNumber) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
            )
        },
        confirmButton = { TextButton(onClick = { go() }) { Text(strings.okButton) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancelButton) } },
    )
}

@Composable
fun RenameDialog(tab: FileTab, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    var text by remember { mutableStateOf(tab.displayName ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.renameTitle) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(strings.renameLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    tab.displayName = text.trim().ifEmpty { null }
                    onDismiss()
                }),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                tab.displayName = text.trim().ifEmpty { null }
                onDismiss()
            }) { Text(strings.okButton) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancelButton) } },
    )
}

/** ARGB color picker: three sliders + preview swatch. */
@Composable
fun ColorPickerDialog(request: ColorPickerRequest, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    var r by remember { mutableStateOf(((request.initialArgb shr 16) and 0xFF).toFloat()) }
    var g by remember { mutableStateOf(((request.initialArgb shr 8) and 0xFF).toFloat()) }
    var b by remember { mutableStateOf((request.initialArgb and 0xFF).toFloat()) }
    var a by remember { mutableStateOf(((request.initialArgb shr 24) and 0xFF).toFloat()) }

    fun argb(): Long =
        (a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.customColorTitle) },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(Color(argb()), RoundedCornerShape(4.dp)),
                )
                listOf("R" to r, "G" to g, "B" to b, "A" to a).forEach { (label, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.size(16.dp))
                        Slider(
                            value = value,
                            onValueChange = {
                                when (label) {
                                    "R" -> r = it
                                    "G" -> g = it
                                    "B" -> b = it
                                    else -> a = it
                                }
                            },
                            valueRange = 0f..255f,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                request.onPick(argb())
                onDismiss()
            }) { Text(strings.okButton) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancelButton) } },
    )
}

/** Hosts the app-level color picker dialog; swatches anywhere call this to open it. */
val LocalColorPickerHost = androidx.compose.runtime.compositionLocalOf<(ColorPickerRequest) -> Unit> { {} }
