# HealthCloud — Architecture

> Diagrams are written as [Mermaid](https://mermaid.js.org/) so they render natively on GitHub and stay
> version-controlled as text. They reflect the system as actually built (Phases 0–11); where something is
> a deliberate simplification for cost or scope, it is called out honestly.

- [1. Deployment / system topology (AWS, on-demand)](#1-deployment--system-topology-aws-on-demand)
- [2. Module map (the modular monolith)](#2-module-map-the-modular-monolith)
- [3. Request lifecycle & authorization pipeline](#3-request-lifecycle--authorization-pipeline)
- [4. Event-driven flow (outbox → Kafka → replay)](#4-event-driven-flow-outbox--kafka--replay)

---

## 1. Deployment / system topology (AWS, on-demand)

The cloud environment is stood up with Terraform, used to capture evidence, then destroyed (`apply → demo →
destroy`) to keep cost near zero — so there is no always-on public URL. When up, the shape is:

```mermaid
flowchart TB
    user([User browser])

    subgraph AWS["AWS (us-east-1)"]
      CF["CloudFront<br/>HTTPS, redirect-to-http→https<br/>CachingDisabled + AllViewer"]
      COG["Amazon Cognito<br/>user pool + hosted UI (OIDC)"]
      SM["Secrets Manager<br/>RDS + Cognito secrets"]

      subgraph VPC["VPC 10.0.0.0/16 · 2 AZs · no NAT"]
        subgraph PUB["Public subnets"]
          ALB["Application Load Balancer<br/>HTTP :80 (SG locked to CloudFront)"]
          subgraph TASK["ECS Fargate task (ARM64) — one task, two containers"]
            NGINX["nginx :8080<br/>serves SPA + proxies /api,/actuator,/oauth2"]
            BE["Spring Boot :8081<br/>modular monolith"]
          end
        end
        subgraph PRIV["Private subnets"]
          RDS[("RDS PostgreSQL 17<br/>encrypted, not public")]
        end
      end
    end

    user -->|HTTPS| CF --> ALB --> NGINX -->|localhost| BE
    BE --> RDS
    user -.->|OIDC login redirect| COG
    BE <-->|auth-code + token| COG
    BE -.->|reads at startup| SM
```

**Notes.**
- **One Fargate task holds both containers** (nginx + backend) talking over `localhost` — the cheapest shape;
  the images run unchanged (only env vars differ from local). Because awsvpc containers share a network
  namespace and nginx is fixed to 8080, the backend runs on **8081**.
- **No NAT Gateway** (a deliberate ~$32/mo saving): Fargate reaches the internet via the public subnet; RDS
  in the private subnets needs no egress.
- **CloudFront terminates HTTPS** in front of the ALB (a free `*.cloudfront.net` cert), which is what lets the
  Cognito OIDC login complete over HTTPS.
- **Secrets never live in code or Terraform state** — the RDS master password and the Cognito client secret are
  injected from Secrets Manager at runtime.
- **No MSK.** The event-driven system (below) is proven locally with Kafka; managed Kafka was omitted from the
  cloud deploy on cost grounds.

---

## 2. Module map (the modular monolith)

The backend is a single deployable (a **modular monolith**) organized into ~30 domain packages under
`com.healthcloud`, over a set of cross-cutting layers. Business logic lives in services and pure policy
classes; controllers stay thin.

```mermaid
flowchart TB
    subgraph API["Thin controllers (REST /api/v1)"]
      C["one controller per resource"]
    end

    subgraph DOMAINS["Domain modules (services + pure policy classes)"]
      direction LR
      ID["identity / organization<br/>users · roles · facilities"]
      CARE["care coordination<br/>patient · relationship · request · document"]
      CONS["consent / clinical<br/>consent engine · clinical summary · coding"]
      CLM["claims & coverage<br/>claim · coverage · adjudication"]
      ADV["advanced claims<br/>priorauth · referral · appeal · claimreview · anomaly · reprocessing"]
      SEC["security & governance<br/>audit · breakglass · retention"]
      EVT["event-driven<br/>outbox · notification · deadletter"]
    end

    subgraph CROSS["Cross-cutting layers"]
      direction LR
      AUTH["auth<br/>SecurityConfig · Cognito BFF · CSRF"]
      CTX["context<br/>UserContext (tenant/roles from DB)"]
      GUARD["PatientAccessGuard<br/>the single relationship choke-point"]
      ERR["error<br/>ApiError · correlationId"]
      COMMON["common<br/>PageResponse · SearchTerms · Csv"]
    end

    DB[("PostgreSQL 17<br/>Flyway · 50 app tables")]

    C --> DOMAINS
    DOMAINS --> CROSS
    DOMAINS --> DB
    AUTH --> CTX --> GUARD
```

**Notes.**
- **Thin controllers.** Authorization, state transitions, claim math, audit creation, and event creation live
  in services/domain components — never in controllers.
- **Pure policy classes** hold decision logic with no Spring/DB dependency and are unit-tested in isolation: six
  state machines (request/claim/prior-auth/referral/appeal/claim-review), the consent evaluator, the anomaly
  detector, and the audit hash-chain math.
- **One relationship choke-point.** Every patient-scoped read routes through `PatientAccessGuard`, so the access
  gate can't be bypassed by adding a new endpoint.
- **Tenant context is derived on the backend** (`UserContext`) from the session; roles/org are resolved fresh
  from the DB by email on each request — never trusted from the client.

---

## 3. Request lifecycle & authorization pipeline

Every protected read passes through independent backend layers **in order**. Each layer can only *narrow*
access. A relationship/consent denial is a **secure 404** (never a 403 that would confirm the row exists). An
important write additionally commits its history + audit + outbox rows in the **same transaction**.

```mermaid
flowchart TD
    REQ[HTTP request + session cookie] --> FILT["CorrelationId + UserContext filters<br/>(resolve user, org, roles from DB)"]
    FILT --> CSRF{"State-changing?<br/>CSRF token valid?"}
    CSRF -- no / missing --> R403[403]
    CSRF -- ok / read --> TEN{"Tenant<br/>row in caller's org?"}
    TEN -- no --> S404[Secure 404]
    TEN -- yes --> ROLE{"Role / function<br/>permission?"}
    ROLE -- no --> R403
    ROLE -- yes --> REL{"PatientAccessGuard<br/>assigned / own / break-glass?"}
    REL -- no --> S404
    REL -- yes --> CONS{"Consent + purpose<br/>for each field?"}
    CONS -- deny --> MASK["field → null,<br/>listed in maskedFields"]
    CONS -- grant --> SHOW[field visible]
    MASK --> DONE
    SHOW --> DONE{Response}

    subgraph WRITE["If it is an important state change (one @Transactional)"]
      direction LR
      D1[domain row] --- D2[status/version history] --- D3[audit event] --- D4[outbox event]
    end
    DONE -. writes .-> WRITE
    WRITE -->|after commit| KAFKA[[Kafka via outbox relay]]
```

**Notes.**
- **Backend is the only security boundary.** The SPA may hide/disable UI, but every check above runs
  server-side, gated off the caller's *actual* roles from `UserContext`.
- **Field masking is deny-by-default** and the read's *purpose* is fixed per action on the backend, not chosen
  by the client. Masked values are `null` and named in a `maskedFields` list — and never leak into logs/exports.
- **Optimistic locking** (a client-supplied `expectedVersion` vs the row's `@Version`) guards concurrent state
  changes; financial accumulators use row locks.

---

## 4. Event-driven flow (outbox → Kafka → replay)

A Kafka publish cannot join a database transaction (the dual-write problem). HealthCloud uses the
**transactional outbox** pattern: the event row is written inside the domain transaction, and a relay publishes
it to Kafka only *after* commit. The consumer is idempotent, and failures are retried, dead-lettered, and
replayable.

```mermaid
flowchart LR
    SVC["Domain service<br/>e.g. AdjudicationService"] -->|"same tx"| OBX[("outbox_event<br/>published_at = null")]
    OBX -->|"@Scheduled relay,<br/>after commit"| K[["Kafka topic<br/>claim.adjudicated"]]
    OBX -. stamps .-> PUB["published_at set"]
    K --> CONS["idempotent consumer<br/>dedupe on event_id"]
    CONS -->|success| FEED[("claim_adjudication_notification")]
    CONS -->|"retry exhausted /<br/>poison message"| DLT[["claim.adjudicated.DLT"]]
    DLT --> DRAIN["drainer"] --> DLE[("dead_letter_event")]
    DLE -->|"ORG_ADMIN replay<br/>POST .../replay"| K
```

**Notes.**
- **At-least-once** delivery, made safe by an **idempotent** consumer (`UNIQUE(event_id)` backstop) — a
  redelivery or a double-click replay is harmless.
- **Structural failures** (bad header, malformed payload) skip retries and go straight to the DLT so a poison
  record never blocks the partition.
- **Single-instance relay** today (a scheduled poller); multi-instance would need
  `SELECT … FOR UPDATE SKIP LOCKED`.
- Event payloads are minimum-necessary and **PHI-free** (coded ids + amounts, no clinical data).

---

See also the **[entity-relationship diagram](../er-diagram/er-diagram.md)** for the data model behind these
flows, and the main **[README](../../README.md)** for the project overview.
