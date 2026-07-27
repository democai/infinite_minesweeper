# T13: game integration

`GameViewModel` restores the viewport-scoped Room snapshot, owns the integrated engine and
lock/wipe mechanic, mirrors engine events for animation, persists live board/viewport changes,
and explicitly flushes the latest snapshot when the activity stops.

`GameScreen` connects the HUD, viewport renderer, tap/long-press mapping, settings route, LOD path,
and lock/resolve/wipe flashes. `GameModule` provides the DataStore-backed binding preference.
`MainActivity` and `AppRoot` remain at the application package root.
