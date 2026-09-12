#!/usr/bin/env bash
#
# Wipes the local database (including its data volume) and starts a fresh PostgreSQL.
# The next backend start with the `local` profile re-runs all Flyway migrations and
# reseeds the identical NorthCare / Green Valley demo scenario.
#
# Usage:  ./scripts/db-reset.sh
set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> Stopping containers and removing the database volume..."
docker compose down -v

echo "==> Starting a fresh PostgreSQL..."
docker compose up -d postgres

echo ""
echo "Fresh database is up. Reseed by running the backend with the local profile:"
echo "  cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local"
