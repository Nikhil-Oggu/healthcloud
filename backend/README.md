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

## Run as a container image (Phase 10 slice 1)
The backend also ships as a production container image — the artifact AWS ECS Fargate will run
later. A multi-stage `Dockerfile` packages the jar on a full JDK, then runs it on a slim JRE as a
non-root `spring` user, with a `/actuator/health` HEALTHCHECK.

Build the image:
```bash
docker build -t healthcloud-backend:local ./backend
```
Run it against the Postgres container (from the repo root — the `backend` service is behind the
opt-in `full` profile, so everyday `docker compose up -d postgres kafka` is unaffected):
```bash
docker compose --profile full up -d postgres backend
curl http://localhost:8080/actuator/health      # expect {"status":"UP", ... "db":{"status":"UP"} ...}
docker compose --profile full down
```
This runs with the **default** Spring profile (production shape): Flyway migrates and the app serves
traffic, but there is **no demo seed and no dev-login**. To get the seeded, clickable app in the
container, add `SPRING_PROFILES_ACTIVE: local` under the `backend` service's `environment` in
`docker-compose.yml`. The outbox relay is disabled for the containerized run (cross-container Kafka
networking is a later slice); the app makes no broker connection at startup regardless.

## Notes
- Schema is owned by **Flyway** (`src/main/resources/db/migration`). Hibernate never auto-creates tables (`ddl-auto: validate`).
- DB connection defaults to `healthcloud/healthcloud` on `localhost:5432` (override via `HEALTHCLOUD_DB_USER` / `HEALTHCLOUD_DB_PASSWORD`).
- All data is **synthetic only**.
