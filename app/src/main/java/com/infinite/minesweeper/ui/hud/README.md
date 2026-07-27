# T11: HUD

`HudUiState.kt` derives a presentation-ready snapshot (coordinate readout, flags placed, selectors
cleared/locked/wiped) from a live `GameState` and the viewport's world-space center. It is a pure
mapper, JVM-testable without Compose. `GameHud.kt` renders that snapshot as the top bar (plan §7);
callers re-derive `HudUiState` whenever the engine state or viewport center changes and pass it in,
keeping the composable a stateless renderer.
