package com.infinite.minesweeper.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.infinite.minesweeper.ui.theme.BoardPalette
import com.infinite.minesweeper.ui.theme.HudTypography
import kotlinx.coroutines.launch

/**
 * Settings screen: tap/long-press binding plus long-press duration.
 */
@Composable
fun SettingsScreen(
    binding: InputBinding,
    longPressDuration: LongPressDuration,
    onBindingChange: (InputBinding) -> Unit,
    onLongPressDurationChange: (LongPressDuration) -> Unit,
    onResetGame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = BoardPalette.Background) {
        Column(modifier = Modifier.padding(24.dp)) {
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
            var showResetConfirm by remember { mutableStateOf(false) }
            Button(
                onClick = { showResetConfirm = true },
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
 * Wires [SettingsScreen] directly to [InputBindingPreferences].
 */
@Composable
fun SettingsRoute(
    preferences: InputBindingPreferences,
    onResetGame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val binding by preferences.binding.collectAsState(initial = InputBinding.Default)
    val longPressDuration by preferences.longPressDuration.collectAsState(
        initial = LongPressDuration.Default,
    )
    SettingsScreen(
        binding = binding,
        longPressDuration = longPressDuration,
        onBindingChange = { updated -> scope.launch { preferences.setBinding(updated) } },
        onLongPressDurationChange = { updated ->
            scope.launch { preferences.setLongPressDuration(updated) }
        },
        onResetGame = onResetGame,
        modifier = modifier,
    )
}
