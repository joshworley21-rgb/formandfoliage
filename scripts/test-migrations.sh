#!/usr/bin/env bash
#
# Builds a database from nothing but supabase/migrations/, then checks it
# behaves. Catches the class of bug where the live database is fine because
# something was once done by hand in a dashboard, while a fresh project comes up
# broken — which is exactly what happened with the storage buckets and the
# enquiry key.
#
# Needs a local PostgreSQL (no Supabase project, no Docker):
#   sudo apt-get install postgresql
#
# Usage: scripts/test-migrations.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PGBIN="${PGBIN:-$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1)}"
PGDATA="${PGDATA:-/var/tmp/formandfoliage-testdb}"
PGPORT="${PGPORT:-55432}"
PGHOST=/tmp
DB=migration_test

if [ ! -x "$PGBIN/initdb" ]; then
  echo "PostgreSQL not found. Set PGBIN to the directory holding initdb." >&2
  exit 1
fi

# Postgres refuses to run as root, so use the postgres system user when we are.
if [ "$(id -u)" = "0" ]; then AS_PG=(su postgres -c); else AS_PG=(bash -c); fi
run_pg() { "${AS_PG[@]}" "$1"; }

cleanup() {
  run_pg "$PGBIN/pg_ctl -D $PGDATA stop -m immediate" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> Starting a throwaway PostgreSQL on port $PGPORT"
rm -rf "$PGDATA"
mkdir -p "$PGDATA"
[ "$(id -u)" = "0" ] && chown postgres:postgres "$PGDATA"
chmod 700 "$PGDATA"
run_pg "$PGBIN/initdb -D $PGDATA -A trust -U postgres" >/dev/null
run_pg "$PGBIN/pg_ctl -D $PGDATA -o '-p $PGPORT -k /tmp' -l $PGDATA/server.log start" >/dev/null

psql -h "$PGHOST" -p "$PGPORT" -U postgres -q -c "CREATE DATABASE $DB;"
PSQL=(psql -h "$PGHOST" -p "$PGPORT" -U postgres -d "$DB" -v ON_ERROR_STOP=1 -q)

echo "==> Installing the Supabase stand-in"
"${PSQL[@]}" -f "$ROOT/supabase/tests/harness.sql" >/dev/null

echo "==> Applying migrations"
for file in "$ROOT"/supabase/migrations/*.sql; do
  printf '    %s ... ' "$(basename "$file")"
  if "${PSQL[@]}" -f "$file" >/dev/null 2>"$PGDATA/err.log"; then
    echo "ok"
  else
    echo "FAILED"
    sed 's/^/        /' "$PGDATA/err.log" >&2
    exit 1
  fi
done

echo "==> Running schema checks"
"${PSQL[@]}" -f "$ROOT/supabase/tests/schema.test.sql"

echo
echo "Migrations build a working database from empty."
