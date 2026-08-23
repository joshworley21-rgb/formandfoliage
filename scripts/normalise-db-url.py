#!/usr/bin/env python3
"""Make a Postgres connection string safe to hand to a client.

A database password is copied out of a dashboard as plain text, but it sits in
a URL, where characters like @ : / ? # and spaces have their own meaning. Paste
one in unescaped and the client cannot parse the URL at all — the error names
the whole string and not the real culprit, which is a miserable thing to debug.

Reads the URL on stdin, writes a corrected one to stdout, and explains any
refusal on stderr. Percent-decoding before re-encoding keeps this idempotent,
so a password that was already escaped correctly is left as it was.

Usage:  echo "$DB_URL" | scripts/normalise-db-url.py
"""

import re
import sys
from urllib.parse import quote, unquote

# The host part never contains @, so the last @ ends the password.
PATTERN = re.compile(r"^(postgres(?:ql)?://)([^:@/]+):(.*)@([^@]+)$", re.DOTALL)


def fail(message: str) -> "NoReturn":  # noqa: F821
    sys.stderr.write(f"{message}\n")
    raise SystemExit(1)


def main() -> None:
    raw = sys.stdin.read().strip()
    if not raw:
        fail("The database URL is empty.")

    match = PATTERN.match(raw)
    if not match:
        fail(
            "This does not look like a database connection string. Expected\n"
            "  postgresql://user:password@host:port/database\n"
            "Copy it from Project Settings -> Database -> Connection string ->\n"
            "Session pooler."
        )

    scheme, user, password, rest = match.groups()

    if not password:
        fail("The connection string has no password in it.")

    if "[" in password or "]" in password:
        fail(
            "The password is still the placeholder from the dashboard.\n"
            "Replace the [YOUR-PASSWORD] part with your real database password\n"
            "(reset it under Project Settings -> Database if you do not have it)."
        )

    encoded = quote(unquote(password), safe="")
    sys.stdout.write(f"{scheme}{user}:{encoded}@{rest}")

    # Describe the target without revealing anything secret.
    host = rest.split("/")[0]
    database = rest.split("/", 1)[1] if "/" in rest else "(none)"
    changed = "yes" if encoded != password else "no (already correct)"
    sys.stderr.write(
        f"Connection target: {host}\n"
        f"Database: {database}\n"
        f"Password re-encoded for the URL: {changed}\n"
    )


if __name__ == "__main__":
    main()
