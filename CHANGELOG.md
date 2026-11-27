# Changelog

## 0.0.1 - Unreleased

- Project scaffold and the Dart ↔ Android ↔ database contract.
- CI scans every PR for leaked secrets (gitleaks) and grades the repository
  with OpenSSF Scorecard.
- `supabase/realtime_push.sql`: device registration, the snapshot, the
  "changed" signal and a ready-made inbox, tested on Postgres 17.
