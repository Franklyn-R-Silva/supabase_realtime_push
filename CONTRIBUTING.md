# Contributing

Thanks for helping. Issues and PRs are welcome; a short report beats none.

## Layout

| Path | What |
|---|---|
| `lib/` | The Dart API apps call |
| `android/` | The native service: Realtime client, snapshot fetch, notifications |
| `supabase/` | The SQL your project runs (tables and functions the service talks to) |
| `docs/contract.md` | How the three talk to each other. Change it in the same PR as the code |
| `example/` | A small app that registers the device and starts the service |

## Before you open a PR

```bash
flutter pub get
dart format --output=none --set-exit-if-changed .
flutter analyze --fatal-infos
flutter test
# Kotlin unit tests (from example/android, after `flutter build apk --config-only` in example/)
./gradlew :supabase_realtime_push:testDebugUnitTest
```

CI runs the same checks on every PR.

## Commits and PR titles

[Conventional Commits](https://www.conventionalcommits.org/): `feat: …`,
`fix: …`, `docs: …`. The PR title becomes the squash commit, so it is the one
that matters.

## Behaviour that must not regress

- The device never shows a notification the snapshot does not list, and a
  notification that leaves the snapshot is removed from the device.
- A notification alerts (sound, vibration) once per `revision`. A swipe-away is
  respected until the revision changes.
- Losing the socket loses nothing: every reconnect fetches the snapshot again.
