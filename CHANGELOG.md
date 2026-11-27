# Changelog

## 0.0.1 - Unreleased

- Project scaffold and the Dart ↔ Android ↔ database contract.
- Android service that keeps the Realtime connection and shows the snapshot as notifications.
- CI scans every PR for leaked secrets (gitleaks) and grades the repository
  with OpenSSF Scorecard.
- Dart API: `device`, `start`, `stop`, `status`, battery exemption,
  notification permission, `launchTap` and the `taps` stream.
- README from setup to the first notification, and an example app that signs
  in, registers the device and starts the service.
- `supabase/realtime_push.sql`: device registration, the snapshot, the
  "changed" signal and a ready-made inbox, tested on Postgres 17.
