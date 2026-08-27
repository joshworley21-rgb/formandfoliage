#!/usr/bin/env python3
"""Report migrations that exist in the repository but not in the database.

Merging a pull request deploys the Edge Functions but deliberately does not
touch the database — a merge should not be able to alter live data on its own.
The cost of that choice is silence: the code moves forward, the database stays
where it was, and nothing says so. A settings column went missing that way for
two weeks and surfaced as an email failure that looked like three unrelated
problems.

So the deploy now asks the question out loud. Given the versions the database
says it has applied, this prints the ones it is missing and exits non-zero.

Usage:
    psql "$DB_URL" -tAc "select version from supabase_migrations.schema_migrations" \\
      | python3 scripts/check-migrations-applied.py supabase/migrations
"""

import os
import sys
from pathlib import Path


def annotate(missing_files: list[str]) -> None:
    """Put the reason on the run's summary, not only in the log.

    stderr reaches the log, which is one tap further in than most people look —
    the Annotations panel showed a bare "Process completed with exit code 1"
    for a failure that already knew exactly what was wrong. A workflow command
    on stdout is what puts it on the front page. Annotations are one line, so
    the detail stays below and only the headline goes here.
    """
    if not os.environ.get("GITHUB_ACTIONS"):
        return
    listed = ", ".join(missing_files)
    print(
        f"::error title=Database is behind the code::{len(missing_files)} migration(s) "
        f"not applied: {listed}. Fix: Actions -> Set up Supabase -> Run workflow, "
        'with "Apply database migrations" ticked.'
    )
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write("### Database is behind the code\n\n")
            for name in missing_files:
                handle.write(f"- `{name}`\n")
            handle.write(
                '\nRun **Set up Supabase** with "Apply database migrations" ticked.\n'
            )


def versions_in(directory: Path) -> list[str]:
    """Migration files are named <version>_<description>.sql."""
    found = []
    for path in sorted(directory.glob("*.sql")):
        version = path.name.split("_", 1)[0]
        if version.isdigit():
            found.append(version)
    return found


def main() -> int:
    if len(sys.argv) != 2:
        sys.stderr.write("Usage: check-migrations-applied.py <migrations-dir>\n")
        return 2

    directory = Path(sys.argv[1])
    if not directory.is_dir():
        sys.stderr.write(f"No such directory: {directory}\n")
        return 2

    applied = {line.strip() for line in sys.stdin if line.strip()}
    local = versions_in(directory)
    missing = [v for v in local if v not in applied]

    if not missing:
        print(f"Database is up to date: all {len(local)} migration(s) applied.")
        return 0

    names = {}
    for path in sorted(directory.glob("*.sql")):
        names[path.name.split("_", 1)[0]] = path.name

    sys.stderr.write(
        "The database is behind the code.\n\n"
        f"{len(missing)} migration(s) in this repository have not been applied:\n"
    )
    for version in missing:
        sys.stderr.write(f"  - {names.get(version, version)}\n")
    sys.stderr.write(
        "\nThe app now expects a database shape it has not got, and the failure\n"
        "that causes will not look like a missing migration when it arrives.\n\n"
        "Fix it: Actions tab -> Set up Supabase -> Run workflow, with\n"
        '"Apply database migrations" ticked.\n'
    )
    annotate([names.get(version, version) for version in missing])
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
