package com.infinite.minesweeper.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.infinite.minesweeper.ui.theme.BoardPalette
import com.infinite.minesweeper.ui.theme.HudTypography
import kotlinx.coroutines.launch

/**
 * Settings screen, stateless per the app's MVVM convention (plan §1): caller supplies the current
 * [binding] and receives change requests through [onBindingChange]. v1 keeps this to the single
 * tap/long-press toggle (plan §6) — no further options.
 */
@Composable
fun SettingsScreen(
    binding: InputBinding,
    onBindingChange: (InputBinding) -> Unit,
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
        }
    }
}

/**
 * Wires [SettingsScreen] directly to [InputBindingPreferences] so callers needing only the
 * standard DataStore-backed behavior don't have to hand-roll the collect/launch plumbing.
 */
@Composable
fun SettingsRoute(
    preferences: InputBindingPreferences,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val binding by preferences.binding.collectAsState(initial = InputBinding.Default)
    SettingsScreen(
        binding = binding,
        onBindingChange = { updated -> scope.launch { preferences.setBinding(updated) } },
        modifier = modifier,
    )
}
