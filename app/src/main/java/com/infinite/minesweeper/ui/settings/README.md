# T11: settings and input binding

`InputBinding.kt` is the single v1 preference (plan §6): which gesture reveals versus flags.
`InputBindingPreferences.kt` is the `DataStore<Preferences>` adapter storing it as a preference,
not game state, independent of Room/`GameMeta`. `InputActionMapper.kt` is the pure, dependency-free
mapper from a raw gesture + cell state + binding to a `GameAction` — tapping an already-revealed
cell always chords regardless of binding; this is what the dev tree's JVM test on binding-toggle
dispatch targets. `SettingsScreen.kt` holds the stateless settings composable plus `SettingsRoute`,
a convenience wrapper wiring it straight to `InputBindingPreferences`.
