# HealthCloud frontend — production container image (Phase 10, slice 3).
#
# A multi-stage build: stage 1 builds the SPA to static files with Node; stage 2 serves them with a
# non-root nginx that also reverse-proxies /api + /actuator to the backend — the production mirror of
# the Vite dev proxy (vite.config.ts), so the SESSION + XSRF-TOKEN cookies stay same-origin (no CORS,
# no token in JS). This is the artifact AWS ECS Fargate can run alongside the backend image.
#
# Build:  docker build -t healthcloud-frontend:local ./frontend
# Run:    docker compose --profile full up -d postgres backend frontend   (from the repo root)

# ---- Stage 1: build the SPA -------------------------------------------------------------
FROM node:24-alpine AS build
WORKDIR /app

# Copy the lockfile first and install from it — cached unless dependencies change.
COPY package.json package-lock.json ./
RUN npm ci

# Build the static assets (npm run build = `tsc --noEmit && vite build` → dist/).
COPY . .
RUN npm run build

# ---- Stage 2: serve with a non-root nginx -----------------------------------------------
# nginx-unprivileged runs as uid 101 (non-root) and listens on 8080 — matching the backend image's
# non-root posture. It substitutes ${BACKEND_UPSTREAM} into the config at startup via envsubst.
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

# The server block (SPA fallback + /api,/actuator reverse proxy). The base image's entrypoint runs
# envsubst over /etc/nginx/templates/*.template → /etc/nginx/conf.d/ at container start.
COPY default.conf.template /etc/nginx/templates/default.conf.template

# The built SPA.
COPY --from=build /app/dist /usr/share/nginx/html

# Backend the /api + /actuator proxy points at. Overridden per environment (compose: http://backend:8080;
# ECS: the backend service's address) with no rebuild.
ENV BACKEND_UPSTREAM=http://backend:8080

EXPOSE 8080

# Report health from nginx serving the SPA itself (independent of the backend). busybox wget ships in alpine.
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
    CMD wget -qO- http://localhost:8080/ >/dev/null 2>&1 || exit 1
