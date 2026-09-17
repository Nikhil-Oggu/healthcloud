# HealthCloud — frontend

React 19 + TypeScript + Vite SPA (Material UI, React Router, TanStack Query). Auth is a session
cookie only — the SPA never holds tokens; login state = whether `/api/v1/me` returns 200.

## Run locally (dev)
Start the backend first (see `../backend/README.md`), then:
```bash
npm install      # once
npm run dev      # Vite on http://localhost:5173, proxies /api + /actuator -> :8080
```
Checks: `npm run typecheck`, `npm test` (Vitest), `npm run build`.

## Run as a container image (Phase 10 slice 3)
The frontend also ships as a production image: a multi-stage build compiles the SPA with Node, then a
**non-root nginx** serves the static files and **reverse-proxies `/api` + `/actuator` to the backend**
(same-origin — the production mirror of the Vite dev proxy, so the session + CSRF cookies stay
first-party, no CORS).

Build the image:
```bash
docker build -t healthcloud-frontend:local ./frontend
```
Run the whole app as containers (from the repo root — the `frontend` + `backend` services are behind the
opt-in `full` profile, so everyday `docker compose up -d postgres kafka` is unaffected):
```bash
docker compose --profile full up -d postgres backend frontend
curl http://localhost:8081/                  # the SPA (index.html)
curl http://localhost:8081/actuator/health   # proxied to the backend -> {"status":"UP", ...}
docker compose --profile full down
```
Browse the app at **http://localhost:8081**. The `BACKEND_UPSTREAM` env var (default
`http://backend:8080`) points the `/api` + `/actuator` proxy at the backend, so the same image works in
compose and in the cloud with no rebuild. For a seeded, logged-in click-through, run the backend on its
`local` profile (add `SPRING_PROFILES_ACTIVE: local` to the `backend` service in `docker-compose.yml`).

## Notes
- All data is **synthetic only**.
- Known follow-up: the production bundle is a single ~940 kB (270 kB gzipped) chunk — Vite suggests
  code-splitting via dynamic `import()`. Functional, not yet optimized.
