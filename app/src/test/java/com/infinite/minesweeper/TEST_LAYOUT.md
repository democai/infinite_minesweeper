# JVM test layout

Mirror the production package for each test:

```text
core/coords/          T2 coordinate and transform tests
core/codec/           T2 codec property/round-trip tests
data/db/              T3 Room and write-behind tests
core/generation/      T4 generator tests
core/cache/           T7 cache and viewport-policy tests
core/engine/          T8 engine tests
core/engine/lock/     T9 lock/wipe tests
data/persistence/     T10 restore tests
ui/settings/          T11 input-mapper tests
ui/board/             T7 viewport math and T12 bitmap bake tests
```

Create test classes in these packages as their production tasks are implemented. UI behavior that
requires Compose or a device belongs in the corresponding package under `src/androidTest`.
