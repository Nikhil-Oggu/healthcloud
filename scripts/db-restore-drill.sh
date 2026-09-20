#!/usr/bin/env bash
#
# Backup & restore DRILL (Phase 11 slice 6 — observability & recovery).
#
# "A backup you have never restored is not a backup." This script rehearses recovery end to end and
# proves the backup is usable, WITHOUT touching the live database:
#
#   1. Take a fresh backup of `healthcloud` (via db-backup.sh).
#   2. Create a scratch database (healthcloud_restore_drill), dropped first if it exists.
#   3. Restore the dump into the scratch DB (full schema + constraints + data).
#   4. Verify fidelity: compare count(*) of every public table in the source vs the restored DB.
#   5. Drop the scratch DB (cleanup) and report PASS/FAIL (non-zero exit on any mismatch).
#
# The pg tools run inside the postgres container, so no host psql is needed. Local-only, $0.
#
# Usage:  ./scripts/db-restore-drill.sh
set -euo pipefail

cd "$(dirname "$0")/.."

DB_NAME="${HEALTHCLOUD_DB_NAME:-healthcloud}"
DB_USER="${HEALTHCLOUD_DB_USER:-healthcloud}"
SCRATCH_DB="healthcloud_restore_drill"

# Run psql inside the container against the maintenance DB `postgres` (so we can create/drop others).
# NOTE: </dev/null on every helper is essential — `docker compose exec` reads stdin, and when a helper
# is called inside the `while read` verify loop it would otherwise swallow the loop's table list (so only
# the first table gets checked). These helpers use -c and need no stdin, so we detach it.
psql_admin() { docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "${DB_USER}" -d postgres "$@" </dev/null; }
# Run psql against a named DB, tuples-only + unaligned, for machine-readable output.
psql_q()     { docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -tA -U "${DB_USER}" -d "$1" -c "$2" </dev/null; }

cleanup() {
  # Best-effort: drop the scratch DB even if the drill fails partway through.
  psql_admin -c "DROP DATABASE IF EXISTS ${SCRATCH_DB};" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> [1/5] Taking a fresh backup ..."
DUMP_FILE="$(./scripts/db-backup.sh)"
echo "    dump: ${DUMP_FILE}"

echo "==> [2/5] Creating scratch database '${SCRATCH_DB}' ..."
psql_admin -c "DROP DATABASE IF EXISTS ${SCRATCH_DB};" >/dev/null
psql_admin -c "CREATE DATABASE ${SCRATCH_DB};" >/dev/null

echo "==> [3/5] Restoring the dump into '${SCRATCH_DB}' ..."
# pg_restore reads the archive from stdin; --no-owner/--no-privileges avoid role-ownership noise.
docker compose exec -T postgres pg_restore --no-owner --no-privileges -U "${DB_USER}" \
  -d "${SCRATCH_DB}" < "${DUMP_FILE}"

echo "==> [4/5] Verifying row counts (source vs restored) ..."
TABLES="$(psql_q "${DB_NAME}" \
  "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;")"

if [ -z "${TABLES}" ]; then
  echo "ERROR: no public tables found in '${DB_NAME}' — nothing to verify. Is the DB seeded?" >&2
  exit 1
fi

fail=0
table_count=0
total_rows=0
while IFS= read -r t; do
  [ -z "$t" ] && continue
  table_count=$((table_count + 1))
  src="$(psql_q "${DB_NAME}"   "SELECT count(*) FROM public.\"${t}\";")"
  res="$(psql_q "${SCRATCH_DB}" "SELECT count(*) FROM public.\"${t}\";")"
  if [ "${src}" != "${res}" ]; then
    printf '    MISMATCH  %-40s source=%s restored=%s\n' "$t" "$src" "$res"
    fail=1
  else
    printf '    ok        %-40s %s rows\n' "$t" "$src"
    total_rows=$((total_rows + src))
  fi
done <<< "${TABLES}"

echo "==> [5/5] Cleaning up scratch database ..."
# (the EXIT trap also drops it; doing it here makes the normal path explicit)
psql_admin -c "DROP DATABASE IF EXISTS ${SCRATCH_DB};" >/dev/null

echo ""
if [ "${fail}" -eq 0 ]; then
  echo "RESTORE DRILL PASSED ✅  — ${table_count} tables, ${total_rows} rows restored identically."
  echo "Backup verified usable: ${DUMP_FILE}"
  exit 0
else
  echo "RESTORE DRILL FAILED ❌  — one or more tables did not match. Do NOT trust this backup." >&2
  exit 1
fi
