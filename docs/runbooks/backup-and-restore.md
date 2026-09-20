# Runbook — Database backup & restore

> Phase 11 slice 6 (observability & recovery). How HealthCloud's database is backed up, how we **prove**
> a backup is usable (the restore drill), how to restore for real, and the production (AWS RDS) equivalent.
> All data is **synthetic**. The local drill is **$0** and touches no AWS.

## Principle

**A backup you have never restored is not a backup.** Backups are only trustworthy once a restore has been
rehearsed, so this project ships an automated *restore drill* alongside the backup script — not just a `pg_dump`.

## What backs the database

- **Local (dev):** PostgreSQL 17 in docker-compose (`healthcloud-postgres`, database/user `healthcloud`),
  schema owned by Flyway. Data lives in the `healthcloud-pgdata` volume.
- **Production (AWS):** RDS PostgreSQL 17 (`infrastructure/terraform/rds.tf`) — see *Production DR* below.

## Local backup

```bash
./scripts/db-backup.sh
```

- Writes a compressed, custom-format (`pg_dump -Fc`) archive to `var/backups/healthcloud-<timestamp>.dump`
  (the `var/` directory is git-ignored, so dumps are never committed).
- The pg tools run **inside** the postgres container (`docker compose exec`), so no host-installed `psql`/
  `pg_dump` is required.
- Custom format lets `pg_restore` recreate the full schema + constraints + data, and supports selective/parallel
  restore. The dump includes `flyway_schema_history`, so a restored database passes the app's
  `spring.jpa.hibernate.ddl-auto: validate` unchanged.
- Prints the dump path on stdout (the restore drill parses this).

## Restore drill (prove the backup works)

```bash
./scripts/db-restore-drill.sh
```

Non-destructive to the live database. It:

1. Takes a fresh backup (`db-backup.sh`).
2. Creates a scratch database `healthcloud_restore_drill` (dropped first if present).
3. Restores the dump into it (`pg_restore --no-owner --no-privileges`).
4. **Verifies fidelity**: compares `count(*)` of every `public` table in the source vs the restored DB.
5. Drops the scratch database and reports **PASS/FAIL** (non-zero exit on any mismatch).

**Expected output (PASS):** a per-table `ok <table> <n> rows` list ending with
`RESTORE DRILL PASSED ✅ — N tables, M rows restored identically.` (Last verified locally: 51 tables, 283 rows.)

**If it FAILS** (`RESTORE DRILL FAILED ❌`): a table's row count differed between source and restored. Do **not**
trust that dump. Investigate: re-run the drill (transient container issue?), check `pg_restore` output for
errors, confirm the dump completed (non-zero file size), and verify the postgres container is healthy
(`docker compose ps postgres`). A mismatch usually means a truncated/corrupt dump or a restore error — take a new
backup and re-drill before relying on it.

> Implementation note: every in-container `psql` helper in the drill detaches stdin (`</dev/null`). Without that,
> `docker compose exec` inside the `while read` verify loop swallows the loop's table list and only the first
> table gets checked — the bug this drill was fixed for.

## Restore for real (deliberate, destructive)

There is intentionally **no one-command "restore over the live DB" script** — that would be a foot-gun. Restoring
over real data is a conscious operation. To restore a dump into a **fresh** local database:

```bash
# 1. Stop the app so nothing writes during the restore.
# 2. Reset to an empty database (drops the data volume):
./scripts/db-reset.sh          # brings up a clean, empty postgres

# 3. Restore a chosen dump into it (custom-format archive via stdin):
docker compose exec -T postgres psql -U healthcloud -d postgres -c \
  "DROP DATABASE IF EXISTS healthcloud; CREATE DATABASE healthcloud;"
docker compose exec -T postgres pg_restore --no-owner --no-privileges -U healthcloud \
  -d healthcloud < var/backups/healthcloud-<timestamp>.dump

# 4. Start the backend; ddl-auto=validate confirms the restored schema matches the entities.
```

To roll forward from a restore, restart the app **without** the `local` profile's reseed if you want to keep only
the restored data (the `local`/`demo` seeder is idempotent-ish but adds demo rows on an empty DB).

## Production DR (AWS RDS) — on-demand follow-up

The production database is RDS PostgreSQL 17 (`rds.tf`). The AWS-native equivalents of the above are:

- **Automated backups + point-in-time recovery (PITR):** set `backup_retention_period` > 0 (currently **0** in
  `rds.tf` for cheap, clean teardown of the on-demand demo stack) and RDS keeps daily snapshots + transaction
  logs, enabling restore to any second in the window. Restoring creates a **new** RDS instance
  (`aws rds restore-db-instance-to-point-in-time` / `restore-db-instance-from-db-snapshot`); you then repoint the
  app (the ECS task's DB endpoint) at it.
- **Manual snapshots:** `aws rds create-db-snapshot` before a risky change; restore as above.

⚠️ Enabling retention/PITR and running an RDS restore **creates AWS resources and incurs cost**, so it is an
**on-demand, explicitly-approved** step per the AWS-cost boundary in `CLAUDE.md` — not wired on by default. The
local drill above is the routinely-runnable, $0 rehearsal of the same recover-and-verify discipline.
