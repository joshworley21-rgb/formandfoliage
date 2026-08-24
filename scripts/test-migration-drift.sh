#!/usr/bin/env bash
#
# Exercises scripts/check-migrations-applied.py against the situations that
# actually occur, including the one that went unnoticed for two weeks.
#
# Needs nothing but python3 — the ledger is a fixture rather than a database.
#
# Usage: scripts/test-migration-drift.sh
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/.."

TMP=$(mktemp -d)
DIR="$TMP/migrations"
mkdir -p "$DIR"
touch "$DIR/20260815142242_initial.sql" \
      "$DIR/20260822120000_quotes.sql" \
      "$DIR/20260823150000_business_details.sql" \
      "$DIR/20260824100000_email_delivery_events.sql"

pass=0; fail=0
check() {
  local name="$1" expected_code="$2" expected_text="$3" input="$4"
  local out code
  out=$(printf '%s' "$input" | python3 scripts/check-migrations-applied.py "$DIR" 2>&1)
  code=$?
  if [ "$code" = "$expected_code" ] && printf '%s' "$out" | grep -q "$expected_text"; then
    echo "  ok    $name"; pass=$((pass+1))
  else
    echo "  FAIL  $name (exit $code, expected $expected_code)"; echo "$out" | sed 's/^/          /'; fail=$((fail+1))
  fi
}

check "everything applied" 0 "all 4 migration(s) applied" \
  '20260815142242
20260822120000
20260823150000
20260824100000'

# The real case: business_details merged but never dispatched.
check "the two-week drift" 1 "20260823150000_business_details.sql" \
  '20260815142242
20260822120000'

check "drift names every missing file" 1 "20260824100000_email_delivery_events.sql" \
  '20260815142242
20260822120000'

check "fresh database with nothing applied" 1 "4 migration(s) in this repository have not been applied" ''

# A migration removed from the repo but still recorded remotely is not drift.
check "extra versions in the database are fine" 0 "up to date" \
  '20260815142242
20260822120000
20260823150000
20260824100000
20260101000000'

check "tells you how to fix it" 1 "Apply database migrations" \
  '20260815142242'

check "blank lines in psql output are ignored" 0 "up to date" \
  '20260815142242

20260822120000
20260823150000

20260824100000
'

echo
if [ "$fail" = 0 ]; then echo "All $pass checks passed."; else echo "$fail failed, $pass passed."; fi
rm -rf "$TMP"
[ "$fail" = 0 ]
