#!/usr/bin/env bash
#
# Takes a backup of the local HealthCloud database (Phase 11 slice 6 — backup & restore).
#
# Dumps the `healthcloud` database from the docker-compose PostgreSQL to a timestamped,
# custom-format (`-Fc`) file under var/backups/ (git-ignored). Custom format is compressed
# and lets `pg_restore` recreate the full schema + data (and restore selectively / in parallel).
# The pg tools run INSIDE the postgres container, so no host-installed psql is required.
#
# Usage:  ./scripts/db-backup.sh
# Output: prints the path of the dump file it wrote (the restore drill parses this).
#
# Local-only, $0. The production equivalent (RDS automated backups + point-in-time recovery +
# snapshot restore) is documented in docs/runbooks/backup-and-restore.md — an on-demand,
# go-ahead-gated step, per the AWS-cost boundary.
set -euo pipefail

cd "$(dirname "$0")/.."

DB_NAME="${HEALTHCLOUD_DB_NAME:-healthcloud}"
DB_USER="${HEALTHCLOUD_DB_USER:-healthcloud}"
BACKUP_DIR="var/backups"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
DUMP_FILE="${BACKUP_DIR}/${DB_NAME}-${TIMESTAMP}.dump"

mkdir -p "${BACKUP_DIR}"

# Fail early with a clear message if the database container is not running.
if ! docker compose ps postgres --status running >/dev/null 2>&1 \
    || [ -z "$(docker compose ps -q postgres)" ]; then
  echo "ERROR: the 'postgres' service is not running. Start it with: docker compose up -d postgres" >&2
  exit 1
fi

echo "==> Backing up database '${DB_NAME}' to ${DUMP_FILE} ..." >&2
# -T: no TTY (we redirect stdout to a file). pg_dump streams the custom-format archive to stdout.
docker compose exec -T postgres pg_dump -Fc -U "${DB_USER}" -d "${DB_NAME}" > "${DUMP_FILE}"

SIZE="$(du -h "${DUMP_FILE}" | cut -f1)"
echo "==> Backup complete (${SIZE})." >&2

# The dump path is the ONLY thing on stdout, so callers (the restore drill) can capture it.
echo "${DUMP_FILE}"
