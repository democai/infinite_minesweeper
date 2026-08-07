package com.infinite.minesweeper.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.infinite.minesweeper.ui.theme.BoardPalette
import com.infinite.minesweeper.ui.theme.HudTypography
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen: input binding, long-press duration, cascade behavior, and save backup.
 */
@Composable
fun SettingsScreen(
    binding: InputBinding,
    longPressDuration: LongPressDuration,
    limitCascadeToSelector: Boolean,
    onBindingChange: (InputBinding) -> Unit,
    onLongPressDurationChange: (LongPressDuration) -> Unit,
    onLimitCascadeToSelectorChange: (Boolean) -> Unit,
    onResetGame: () -> Unit,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
    transferBusy: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = BoardPalette.Background) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(text = "Settings", style = HudTypography.titleMedium, color = BoardPalette.AccentGold)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Tap and long-press binding",
                style = HudTypography.labelMedium,
                color = BoardPalette.OnSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (binding == InputBinding.TAP_REVEAL_LONG_PRESS_FLAG) {
                        "Tap reveals, long-press flags"
                    } else {
                        "Tap flags, long-press reveals"
                    },
                    style = HudTypography.labelMedium,
                    color = BoardPalette.OnSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = binding == InputBinding.TAP_FLAG_LONG_PRESS_REVEAL,
                    onCheckedChange = { inverted ->
                        onBindingChange(
                            if (inverted) {
                                InputBinding.TAP_FLAG_LONG_PRESS_REVEAL
                            } else {
                                InputBinding.TAP_REVEAL_LONG_PRESS_FLAG
                            },
                        )
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = BoardPalette.AccentGold),
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "Zero cascade",
                style = HudTypography.labelMedium,
                color = BoardPalette.OnSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Limit 0-cascade to current selector",
                    style = HudTypography.labelMedium,
                    color = BoardPalette.OnSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = limitCascadeToSelector,
                    onCheckedChange = onLimitCascadeToSelectorChange,
                    colors = SwitchDefaults.colors(checkedTrackColor = BoardPalette.AccentGold),
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "Long-press duration",
                style = HudTypography.labelMedium,
                color = BoardPalette.OnSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.selectableGroup()) {
                LongPressDuration.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == longPressDuration,
                                onClick = { onLongPressDurationChange(option) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == longPressDuration,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = BoardPalette.AccentGold),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (option) {
                                LongPressDuration.SHORT -> "Short (${option.timeoutMs} ms)"
                                LongPressDuration.MEDIUM -> "Medium (${option.timeoutMs} ms)"
                                LongPressDuration.LONG -> "Long (${option.timeoutMs} ms)"
                            },
                            style = HudTypography.labelMedium,
                            color = BoardPalette.OnSurface,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "Save backup",
                style = HudTypography.labelMedium,
                color = BoardPalette.OnSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Export your board progress to a file, or import a previous backup after reinstall.",
                style = HudTypography.labelMedium,
                color = BoardPalette.OnSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onExportClick,
                enabled = !transferBusy,
                colors = ButtonDefaults.buttonColors(containerColor = BoardPalette.AccentGold),
            ) {
                Text("Export save", color = BoardPalette.Background)
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onImportClick,
                enabled = !transferBusy,
            ) {
                Text("Import save", color = BoardPalette.AccentGold)
            }

            Spacer(modifier = Modifier.height(28.dp))
            var showResetConfirm by remember { mutableStateOf(false) }
            Button(
                onClick = { showResetConfirm = true },
                enabled = !transferBusy,
                colors = ButtonDefaults.buttonColors(containerColor = BoardPalette.MineExploded),
            ) {
                Text("Reset Game")
            }
            if (showResetConfirm) {
                AlertDialog(
                    onDismissRequest = { showResetConfirm = false },
                    title = { Text("Reset Game?") },
                    text = {
                        Text(
                            "This permanently wipes your entire explored board, flags, and " +
                                "stats, and starts a brand-new world. This can't be undone.",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showResetConfirm = false
                                onResetGame()
                            },
                        ) {
                            Text("Reset", color = BoardPalette.MineExploded)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetConfirm = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }
        }
    }
}

/**
 * Wires [SettingsScreen] to [InputBindingPreferences] and SAF export/import.
 */
@Composable
fun SettingsRoute(
    preferences: InputBindingPreferences,
    onResetGame: () -> Unit,
    onExportSave: suspend () -> ByteArray,
    onImportSave: suspend (ByteArray) -> Unit,
    onImportApplied: () -> Unit,
    onTransferMessage: (String, isError: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val binding by preferences.binding.collectAsState(initial = InputBinding.Default)
    val longPressDuration by preferences.longPressDuration.collectAsState(
        initial = LongPressDuration.Default,
    )
    val limitCascadeToSelector by preferences.limitCascadeToSelector.collectAsState(initial = false)

    var transferBusy by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            transferBusy = true
            try {
                val bytes = onExportSave()
                writeUri(context, uri, bytes)
                onTransferMessage("Save exported.", false)
            } catch (e: Exception) {
                onTransferMessage(e.message ?: "Export failed.", true)
            } finally {
                transferBusy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingImportUri = uri
        showImportConfirm = true
    }

    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = {
                if (!transferBusy) {
                    showImportConfirm = false
                    pendingImportUri = null
                }
            },
            title = { Text("Import save?") },
            text = {
                Text(
                    "This replaces your current board, flags, and stats with the chosen backup. " +
                        "Your current progress will be lost.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingImportUri ?: return@TextButton
                        showImportConfirm = false
                        pendingImportUri = null
                        scope.launch {
                            transferBusy = true
                            try {
                                val bytes = readUri(context, uri)
                                onImportSave(bytes)
                                onTransferMessage("Save imported.", false)
                                onImportApplied()
                            } catch (e: Exception) {
                                onTransferMessage(e.message ?: "Import failed.", true)
                            } finally {
                                transferBusy = false
                            }
                        }
                    },
                    enabled = !transferBusy,
                ) {
                    Text("Import", color = BoardPalette.MineExploded)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportConfirm = false
                        pendingImportUri = null
                    },
                    enabled = !transferBusy,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    SettingsScreen(
        binding = binding,
        longPressDuration = longPressDuration,
        limitCascadeToSelector = limitCascadeToSelector,
        onBindingChange = { updated -> scope.launch { preferences.setBinding(updated) } },
        onLongPressDurationChange = { updated ->
            scope.launch { preferences.setLongPressDuration(updated) }
        },
        onLimitCascadeToSelectorChange = { enabled ->
            scope.launch { preferences.setLimitCascadeToSelector(enabled) }
        },
        onResetGame = onResetGame,
        onExportClick = {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
            exportLauncher.launch("infinite-minesweeper-$stamp.imsave")
        },
        onImportClick = {
            importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        },
        transferBusy = transferBusy,
        modifier = modifier,
    )
}

private suspend fun writeUri(context: Context, uri: Uri, bytes: ByteArray) {
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(bytes)
            stream.flush()
        } ?: error("Could not open export destination for writing")
    }
}

private suspend fun readUri(context: Context, uri: Uri): ByteArray =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not open save file for reading")
    }
