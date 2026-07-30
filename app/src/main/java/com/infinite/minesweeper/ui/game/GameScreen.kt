package com.infinite.minesweeper.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.infinite.minesweeper.core.cache.DEFAULT_RETENTION_MARGIN_CHUNKS
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.GameEvent
import com.infinite.minesweeper.ui.board.BoardEffect
import com.infinite.minesweeper.ui.board.ViewportBoardCanvas
import com.infinite.minesweeper.ui.board.rememberViewportState
import com.infinite.minesweeper.ui.hud.GameHud
import com.infinite.minesweeper.ui.settings.LongPressDuration
import com.infinite.minesweeper.ui.settings.SettingsRoute
import com.infinite.minesweeper.ui.settings.TapKind
import com.infinite.minesweeper.ui.theme.BoardDimens
import com.infinite.minesweeper.ui.theme.BoardPalette
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

@Composable
fun GameScreen(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val longPressDuration by viewModel.inputBindingPreferences.longPressDuration
        .collectAsStateWithLifecycle(initialValue = LongPressDuration.Default)
    val viewportState = rememberViewportState()
    var viewportRestored by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSettings) { showSettings = false }

    LaunchedEffect(state.isProcessing, viewportRestored) {
        if (!state.isProcessing && !viewportRestored) {
            viewportState.moveTo(
                centerX = state.meta.viewportX.toDouble(),
                centerY = state.meta.viewportY.toDouble(),
                zoom = state.meta.zoom.toDouble(),
            )
            viewportRestored = true
        }
    }
    LaunchedEffect(viewportState) {
        snapshotFlow { Triple(viewportState.centerX, viewportState.centerY, viewportState.zoom) }
            .collect { (x, y, zoom) -> viewModel.updateViewport(x, y, zoom) }
    }

    val density = LocalDensity.current
    LaunchedEffect(viewportState, density) {
        val baseCellSizePx = with(density) { BoardDimens.BaseCellSizeDp.dp.toPx() }.toDouble()
        snapshotFlow {
            viewportState.visibleChunkBounds(
                baseCellSizePx = baseCellSizePx,
                renderMarginChunks = DEFAULT_RETENTION_MARGIN_CHUNKS,
            )
        }
            .filterNotNull()
            .map { it.toSet() }
            .distinctUntilChanged()
            .collect { window -> viewModel.syncVisibleWindow(window) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                runBlocking { viewModel.flushNow() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showSettings) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            TextButton(onClick = { showSettings = false }) {
                Text("Back to game", color = BoardPalette.AccentGold)
            }
            SettingsRoute(
                preferences = viewModel.inputBindingPreferences,
                onResetGame = {
                    viewModel.resetGame()
                    viewportRestored = false
                    showSettings = false
                },
                modifier = Modifier.weight(1f),
            )
        }
        return
    }

    val effectAlpha = remember { Animatable(0f) }
    var effectChunk by remember { mutableStateOf<ChunkCoord?>(null) }
    var effectColor by remember { mutableStateOf(Color.Transparent) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val visual = when (event) {
                is GameEvent.ChunkLocked -> Triple(event.chunk, BoardPalette.MineExploded, 0.45f)
                is GameEvent.ChunkSoftResolved -> Triple(event.chunk, BoardPalette.AccentGold, 0.35f)
                is GameEvent.ChunkWiped -> Triple(event.chunk, BoardPalette.WipeFlash, 0.85f)
                is GameEvent.ChunkCleared -> null
            } ?: return@collect
            effectChunk = visual.first
            effectColor = visual.second
            effectAlpha.snapTo(visual.third)
            effectAlpha.animateTo(0f, animationSpec = tween(durationMillis = 420))
        }
    }

    var resetChunkPrompt by remember { mutableStateOf<Pair<ChunkCoord, Offset>?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        ViewportBoardCanvas(
            chunks = state.chunks.values,
            viewportState = viewportState,
            modifier = Modifier.fillMaxSize(),
            onTap = { viewModel.dispatch(TapKind.TAP, it) },
            onLongPress = { viewModel.dispatch(TapKind.LONG_PRESS, it) },
            longPressTimeoutMs = longPressDuration.timeoutMs,
            onSolvedSelectorLongPress = { coord, position ->
                if (state.chunks[coord]?.isSolved == true) resetChunkPrompt = coord to position
            },
            effect = effectChunk?.let {
                BoardEffect(chunk = it, color = effectColor, alpha = effectAlpha.value)
            },
        )
        resetChunkPrompt?.let { (coord, position) ->
            DropdownMenu(
                expanded = true,
                onDismissRequest = { resetChunkPrompt = null },
                offset = with(density) {
                    DpOffset(x = position.x.toDp(), y = position.y.toDp())
                },
            ) {
                DropdownMenuItem(
                    text = { Text("Reset selector") },
                    onClick = {
                        viewModel.resetSelector(coord)
                        resetChunkPrompt = null
                    },
                )
            }
        }
        GameHud(
            state = state,
            viewportCenterX = viewportState.centerX,
            viewportCenterY = viewportState.centerY,
            modifier = Modifier
                .statusBarsPadding()
                .zIndex(1f),
            onSettingsClick = { showSettings = true },
        )
    }
}
