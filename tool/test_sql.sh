#!/usr/bin/env bash
# Runs supabase/test/realtime_push_test.sql on a throwaway Postgres 17 in
# Docker. The container is always removed, pass or fail.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
name="realtime-push-sql-test-$$"

trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT

docker run -d --name "$name" -e POSTGRES_PASSWORD=postgres postgres:17-alpine >/dev/null
# docker cp, not a bind mount: no SELinux relabelling of the checkout.
docker cp "$root/supabase" "$name:/work"

# The image's init runs a temporary server on the socket only; TCP answering
# means the real one is up.
for _ in $(seq 60); do
  if docker exec "$name" pg_isready -q -h 127.0.0.1 -U postgres; then
    break
  fi
  sleep 1
done

docker exec "$name" psql -X -q -h 127.0.0.1 -U postgres -v ON_ERROR_STOP=1 \
  -f /work/test/realtime_push_test.sql
