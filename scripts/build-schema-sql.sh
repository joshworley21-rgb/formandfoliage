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

# Also split it into pieces small enough to paste on a phone. The SQL Editor is
# a full code editor and a 30KB paste can lock up a mobile browser, so each part
# is kept under ~7KB and never splits a migration down the middle.
PARTS="$ROOT/supabase/schema-parts"
rm -rf "$PARTS"
mkdir -p "$PARTS"

python3 - "$ROOT" <<'PYEOF'
import os, sys, glob

root = sys.argv[1]
parts_dir = os.path.join(root, "supabase", "schema-parts")
files = sorted(glob.glob(os.path.join(root, "supabase", "migrations", "*.sql")))

LIMIT = 7000
batches, current, size = [], [], 0
for path in files:
    body = open(path).read()
    if current and size + len(body) > LIMIT:
        batches.append(current)
        current, size = [], 0
    current.append((path, body))
    size += len(body)
if current:
    batches.append(current)

total = len(batches)
for index, batch in enumerate(batches, start=1):
    name = os.path.join(parts_dir, "%02d-of-%02d.sql" % (index, total))
    with open(name, "w") as out:
        out.write("-- JW Garden Services CRM schema — part %d of %d.\n" % (index, total))
        out.write("--\n")
        out.write("-- GENERATED FILE. Run the parts in order, one at a time, in the Supabase\n")
        out.write("-- SQL Editor. Each part is its own transaction: if one fails, nothing in\n")
        out.write("-- that part is applied and you can fix it and run the same part again.\n")
        out.write("--\n")
        out.write("-- Only needed if you are building the database by hand. The\n")
        out.write('-- "Set up Supabase" GitHub Action does all of this without any pasting.\n')
        out.write("\nBEGIN;\n\n")
        for path, body in batch:
            base = os.path.basename(path)
            out.write("-- ---------- %s ----------\n\n" % base)
            out.write(body)
            if not body.endswith("\n"):
                out.write("\n")
            out.write("\n")
        out.write("CREATE SCHEMA IF NOT EXISTS supabase_migrations;\n")
        out.write("CREATE TABLE IF NOT EXISTS supabase_migrations.schema_migrations (\n")
        out.write("  version text PRIMARY KEY,\n  statements text[],\n  name text\n);\n\n")
        for path, _ in batch:
            base = os.path.basename(path)[:-4]
            version, _, migname = base.partition("_")
            out.write("INSERT INTO supabase_migrations.schema_migrations (version, name)\n")
            out.write("  VALUES ('%s', '%s') ON CONFLICT (version) DO NOTHING;\n" % (version, migname))
        out.write("\nCOMMIT;\n")
    print("  %s (%d bytes)" % (os.path.basename(name), os.path.getsize(name)))

print("Wrote %d parts to supabase/schema-parts/" % total)
PYEOF
