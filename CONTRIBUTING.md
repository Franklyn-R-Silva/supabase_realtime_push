# Contributing

Thanks for helping. Issues and PRs are welcome; a short report beats none.

## Layout

| Path | What |
|---|---|
| `lib/` | The Dart API apps call |
| `android/` | The native service: Realtime client, snapshot fetch, notifications |
| `supabase/` | The SQL your project runs (tables and functions the service talks to) |
| `doc/contract.md` | How the three talk to each other. Change it in the same PR as the code |
| `example/` | A small app that registers the device and starts the service |

## Before you open a PR

```bash
flutter pub get
dart format --output=none --set-exit-if-changed .
flutter analyze --fatal-infos
flutter test
# Kotlin unit tests (from example/android, after `flutter build apk --config-only` in example/)
./gradlew :supabase_realtime_push:testDebugUnitTest
# SQL tests on a throwaway Postgres (needs Docker)
tool/test_sql.sh
```

CI runs the same checks on every PR.

## Tests

Every change in behaviour comes with a test that fails without it: Dart in
`test/`, Kotlin in `android/src/test/`, SQL in `supabase/test/`. A bug fix
starts with the test that shows the bug.

## Before a release

The service's lifecycle runs only on a real Android, so check it by hand on
the example app, against a real project, before tagging:

| Check | Android 8 | 12 | 14 | 15+ |
|---|---|---|---|---|
| Send arrives with the app closed, screen on (exempt, then not exempt) | | | | |
| Screen off, `adb shell dumpsys deviceidle force-idle`: arrives at the next watch | | | | |
| Reboot, then send: arrives without opening the app | | | | |
| Install an update (`flutter install`), then send | | | | |
| Airplane mode on, send, airplane mode off: arrives | | | | |
| Swipe away, then send the same revision: stays away | | | | |
| Tap: the app gets `{id, data}`; reopen from recents: no second tap | | | | |
| `realtime_push_unregister` from SQL: the shade clears, status `rejected` | | | | |
| Sign out: the shade clears, status `stopped` | | | | |

## Releasing

1. In one PR, titled `chore: release x.y.z`, set `version:` in `pubspec.yaml`,
   date the top `CHANGELOG.md` section (`## x.y.z - YYYY-MM-DD`) and update the
   README's `supabase_realtime_push: ^x.y.z`. `flutter pub publish --dry-run`
   must report 0 warnings.
2. Merge it, then tag the merge commit and push the tag:

   ```sh
   git tag -a vx.y.z -m "x.y.z" <merge sha>
   git push origin vx.y.z
   ```

3. The tag runs [`release.yaml`](.github/workflows/release.yaml): analyze and
   tests, a check that the tag matches `pubspec.yaml`, the upload to pub.dev,
   then the GitHub Release with the CHANGELOG section. pub.dev only accepts the
   upload from this repository, for a `v{{version}}` tag, from the `pub.dev`
   environment, which only `v*` tags can use. Its OIDC token lives for the
   run, so no publish secret is stored.

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
