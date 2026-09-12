# HealthCloud — backend

Spring Boot 4.1 (Java 25) modular-monolith backend/BFF.

## Run locally (Phase 1 slice 1)
From the repo root, start the database:
```bash
docker compose up -d postgres
```
Then run the app (from this `backend/` folder):
```bash
./mvnw spring-boot:run
```
Verify it's up and connected to the database:
```bash
curl http://localhost:8080/actuator/health
```
Expected: `{"status":"UP", ... "db":{"status":"UP"} ...}`.

Stop the app with Ctrl+C. Stop the database with `docker compose down` (add `-v` to also delete data).

## Notes
- Schema is owned by **Flyway** (`src/main/resources/db/migration`). Hibernate never auto-creates tables (`ddl-auto: validate`).
- DB connection defaults to `healthcloud/healthcloud` on `localhost:5432` (override via `HEALTHCLOUD_DB_USER` / `HEALTHCLOUD_DB_PASSWORD`).
- All data is **synthetic only**.
