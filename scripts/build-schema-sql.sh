#!/usr/bin/env bash
#
# Rolls every migration into one file that can be pasted into the Supabase SQL
# Editor. This is the way to build the database without a computer: the CLI
# needs a terminal, the SQL Editor only needs a browser.
#
# Regenerate after adding a migration:
#   scripts/build-schema-sql.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/supabase/schema.sql"

{
  echo "-- JW Garden Services CRM — complete database schema."
  echo "--"
  echo "-- GENERATED FILE. Do not edit by hand: it is every file in"
  echo "-- supabase/migrations/ joined in order by scripts/build-schema-sql.sh."
  echo "--"
  echo "-- Run this once against a brand-new Supabase project: open the SQL Editor"
  echo "-- in the dashboard, paste the whole file, and press Run. It is wrapped in a"
  echo "-- transaction, so if anything fails nothing is left half-applied."
  echo "--"
  echo "-- Already have a database built by the CLI? You do not need this file."
  echo ""
  echo "BEGIN;"
  echo ""

  for file in "$ROOT"/supabase/migrations/*.sql; do
    echo "-- ─────────────────────────────────────────────────────────────────────"
    echo "-- $(basename "$file")"
    echo "-- ─────────────────────────────────────────────────────────────────────"
    echo ""
    cat "$file"
    echo ""
  done

  # Record the same migrations in Supabase's own ledger. Without this the CLI
  # would later believe none had been applied and try to run them all again.
  echo "-- ─────────────────────────────────────────────────────────────────────"
  echo "-- Mark these migrations as applied, so the CLI agrees with reality if it"
  echo "-- is ever used against this project."
  echo "-- ─────────────────────────────────────────────────────────────────────"
  echo ""
  echo "CREATE SCHEMA IF NOT EXISTS supabase_migrations;"
  echo "CREATE TABLE IF NOT EXISTS supabase_migrations.schema_migrations ("
  echo "  version text PRIMARY KEY,"
  echo "  statements text[],"
  echo "  name text"
  echo ");"
  echo ""
  for file in "$ROOT"/supabase/migrations/*.sql; do
    base="$(basename "$file" .sql)"
    version="${base%%_*}"
    name="${base#*_}"
    echo "INSERT INTO supabase_migrations.schema_migrations (version, name)"
    echo "  VALUES ('$version', '$name') ON CONFLICT (version) DO NOTHING;"
  done
  echo ""

  echo "COMMIT;"
} > "$OUT"

echo "Wrote $OUT ($(wc -l < "$OUT") lines)"
