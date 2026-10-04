package com.infinite.minesweeper.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.infinite.minesweeper.core.cache.DEFAULT_RETENTION_MARGIN_CHUNKS
import com.infinite.minesweeper.core.coords.cellToChunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.GameEvent
import com.infinite.minesweeper.ui.board.BoardEffect
import com.infinite.minesweeper.ui.board.ChunkBounds
import com.infinite.minesweeper.ui.board.ViewportBoardCanvas
import com.infinite.minesweeper.ui.board.LodRenderer
import com.infinite.minesweeper.ui.board.computeMinZoomFromExploredBounds
import com.infinite.minesweeper.ui.board.rememberViewportState
import com.infinite.minesweeper.ui.hud.GameHud
import com.infinite.minesweeper.ui.settings.LongPressDuration
import com.infinite.minesweeper.ui.settings.SettingsRoute
import com.infinite.minesweeper.ui.settings.TapKind
import com.infinite.minesweeper.ui.theme.BoardDimens
import com.infinite.minesweeper.ui.theme.BoardPalette
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    var showSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSettings) { showSettings = false }

    val density = LocalDensity.current
    val baseCellSizePx = with(density) { BoardDimens.BaseCellSizeDp.dp.toPx() }.toDouble()

    // The board stays undrawn until the saved viewport has been applied and the chunks under it
    // are hydrated, so a cold start opens straight onto the last played position and zoom instead
    // of flashing an empty board that only fills in on the first tap. The ViewModel outlives
    // configuration changes but this ViewportState does not, hence the per-composition marker.
    val sessionId by viewModel.sessionId.collectAsStateWithLifecycle()
    var appliedSessionId by remember { mutableIntStateOf(0) }
    val boardReady = sessionId > 0 && appliedSessionId == sessionId
    var lastSynced by remember { mutableStateOf<Pair<ChunkBounds, Boolean>?>(null) }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(sessionId) {
        if (sessionId == 0) return@LaunchedEffect
        // The board canvas may not be laid out yet (or at all, if settings was restored open), so
        // size the viewport from the window and let the canvas refine it later.
        val size = snapshotFlow { windowInfo.containerSize }
            .first { it.width > 0 && it.height > 0 }
        viewportState.updateViewportSize(size.width.toDouble(), size.height.toDouble())
        val saved = viewModel.currentViewport()
        val restoredMeta = viewModel.state.value.meta
        // Raise the zoom-out floor first: moveTo clamps to it, and the explored extent is what
        // allows the saved (zoomed-out) level in the first place.
        viewportState.updateMinZoom(
            computeMinZoomFromExploredBounds(
                viewportWidthPx = viewportState.viewportWidthPx,
                viewportHeightPx = viewportState.viewportHeightPx,
                baseCellSizePx = baseCellSizePx,
                hasExploredBounds = restoredMeta.hasExploredBounds,
                exploredMinCx = restoredMeta.exploredMinCx,
                exploredMaxCx = restoredMeta.exploredMaxCx,
                exploredMinCy = restoredMeta.exploredMinCy,
                exploredMaxCy = restoredMeta.exploredMaxCy,
            ),
        )
        viewportState.moveTo(
            centerX = saved.centerX.toDouble(),
            centerY = saved.centerY.toDouble(),
            zoom = saved.zoom.toDouble(),
        )
        val bounds = viewportState.visibleChunkBounds(
            baseCellSizePx = baseCellSizePx,
            renderMarginChunks = DEFAULT_RETENTION_MARGIN_CHUNKS,
        )
        if (bounds != null) {
            val useLod = LodRenderer.shouldUseLod(
                BoardDimens.BaseCellSizeDp * viewportState.zoom.toFloat(),
            )
            lastSynced = bounds to useLod
            val sync = if (useLod) {
                viewModel.syncOverviewWindow(bounds)
            } else {
                viewModel.syncVisibleWindow(bounds.toSet())
            }
            sync?.join()
        }
        appliedSessionId = sessionId
        viewModel.markBoardReady()
    }
    LaunchedEffect(viewportState, boardReady) {
        if (!boardReady) return@LaunchedEffect
        snapshotFlow { Triple(viewportState.centerX, viewportState.centerY, viewportState.zoom) }
            .collect { (x, y, zoom) -> viewModel.updateViewport(x, y, zoom) }
    }

    LaunchedEffect(viewportState, baseCellSizePx, boardReady) {
        if (!boardReady) return@LaunchedEffect
        snapshotFlow {
            val bounds = viewportState.visibleChunkBounds(
                baseCellSizePx = baseCellSizePx,
                renderMarginChunks = DEFAULT_RETENTION_MARGIN_CHUNKS,
            )
            bounds?.let {
                val cellSizeDp = BoardDimens.BaseCellSizeDp * viewportState.zoom.toFloat()
                it to LodRenderer.shouldUseLod(cellSizeDp)
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { key ->
                // The restore above already synced this exact window.
                if (key == lastSynced) {
                    lastSynced = null
                    return@collect
                }
                val (bounds, useLod) = key
                if (useLod) {
                    viewModel.syncOverviewWindow(bounds)
                } else {
                    viewModel.syncVisibleWindow(bounds.toSet())
                }
            }
    }
    val viewportWidthPx = viewportState.viewportWidthPx
    val viewportHeightPx = viewportState.viewportHeightPx
    val meta = state.meta
    LaunchedEffect(
        viewportWidthPx,
        viewportHeightPx,
        baseCellSizePx,
        meta.hasExploredBounds,
        meta.exploredMinCx,
        meta.exploredMaxCx,
        meta.exploredMinCy,
        meta.exploredMaxCy,
    ) {
        viewportState.updateMinZoom(
            computeMinZoomFromExploredBounds(
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                baseCellSizePx = baseCellSizePx,
                hasExploredBounds = meta.hasExploredBounds,
                exploredMinCx = meta.exploredMinCx,
                exploredMaxCx = meta.exploredMaxCx,
                exploredMinCy = meta.exploredMinCy,
                exploredMaxCy = meta.exploredMaxCy,
            ),
        )
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

    var transferDialog by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.saveTransferMessages.collect { message ->
            transferDialog = message.text to (message is SaveTransferMessage.Failure)
        }
    }
    transferDialog?.let { (text, isError) ->
        AlertDialog(
            onDismissRequest = { transferDialog = null },
            title = { Text(if (isError) "Save transfer failed" else "Save transfer") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { transferDialog = null }) {
                    Text("OK", color = BoardPalette.AccentGold)
                }
            },
        )
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
                    showSettings = false
                },
                onExportSave = { viewModel.exportSave() },
                onImportSave = { bytes -> viewModel.importSave(bytes) },
                onImportApplied = {
                    showSettings = false
                },
                onTransferMessage = { text, isError ->
                    if (isError) {
                        viewModel.reportSaveTransferFailure(text)
                    } else {
                        viewModel.reportSaveTransferSuccess(text)
                    }
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

    var selectorMenu by remember { mutableStateOf<Pair<ChunkCoord, Offset>?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        ViewportBoardCanvas(
            chunks = if (boardReady) state.chunks.values else emptyList(),
            viewportState = viewportState,
            modifier = Modifier.fillMaxSize(),
            onTap = { if (boardReady) viewModel.dispatch(TapKind.TAP, it) },
            onLongPress = { cell, position ->
                if (!boardReady) return@ViewportBoardCanvas
                if (viewModel.shouldOpenHintMenu(cell)) {
                    selectorMenu = cellToChunk(cell) to position
                } else {
                    viewModel.dispatch(TapKind.LONG_PRESS, cell)
                }
            },
            longPressTimeoutMs = longPressDuration.timeoutMs,
            onSolvedSelectorLongPress = { coord, position ->
                if (state.chunks[coord]?.isSolved == true) selectorMenu = coord to position
            },
            effect = effectChunk?.let {
                BoardEffect(chunk = it, color = effectColor, alpha = effectAlpha.value)
            },
        )
        selectorMenu?.let { (coord, position) ->
            val isSolved = state.chunks[coord]?.isSolved == true
            DropdownMenu(
                expanded = true,
                onDismissRequest = { selectorMenu = null },
                offset = with(density) {
                    DpOffset(x = position.x.toDp(), y = position.y.toDp())
                },
            ) {
                if (isSolved) {
                    DropdownMenuItem(
                        text = { Text("Reset selector") },
                        onClick = {
                            viewModel.resetSelector(coord)
                            selectorMenu = null
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Hint") },
                        onClick = {
                            viewModel.hint(coord)
                            selectorMenu = null
                        },
                    )
                }
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
