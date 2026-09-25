# Evidence — Security Boundary (real HTTP transcripts)

> These are **real request/response transcripts** captured against the running app (local `local`
> profile, synthetic seed). They prove the source-of-truth §60 acceptance criteria: the backend is
> the only security boundary, tenant isolation holds, two same-role users get different results, a
> consent-controlled field is withheld by default, and illegal state transitions are refused.
> Captured 2026-09-25. All data is synthetic.

**How captured:** each caller signs in via the local `dev-login` stand-in (email → session cookie),
then makes ordinary API calls. State-changing calls include the CSRF header (`X-XSRF-TOKEN`, read
from the `XSRF-TOKEN` cookie) exactly as the SPA does. Bodies/responses below are verbatim.

---

## Proof 1 — Cross-tenant isolation (secure 404)

Patient `a02106ba…` (`GV-0002`) belongs to **Green Valley**. A **NorthCare** admin asks for it by id.
The row exists — but not in the caller's tenant — so the backend returns a **secure 404**, never a 403
that would confirm the row exists.

```http
GET /api/v1/patients/a02106ba-f0ec-413f-9755-833fca36e104     (as admin@northcare.example.org)
→ HTTP 404
{"code":"NOT_FOUND","message":"The requested resource was not found.",
 "correlationId":"c89daa29-6728-48f2-b257-1011602b3a42"}
```

**Control** — the Green Valley coordinator reads the *same id* in-tenant and succeeds:

```http
GET /api/v1/patients/a02106ba-f0ec-413f-9755-833fca36e104     (as coordinator@greenvalley.example.org)
→ HTTP 200
{"id":"a02106ba-…","medicalRecordNumber":"GV-0002","fullName":"Fern Fixture",
 "dateOfBirth":null,"status":"ACTIVE","version":0,"maskedFields":["dateOfBirth"]}
```

> Same id, opposite outcome, decided entirely by the caller's backend-derived tenant. The browser
> never supplies the tenant.

---

## Proof 2 — Consent field masking + live flip (deny-by-default)

`dateOfBirth` is a consent-controlled field. **Same coordinator, same patient**, before vs after a
consent GRANT — the value is withheld until consent allows it.

**Before** — deny-by-default: the field is `null` and named in `maskedFields`:

```http
GET /api/v1/patients/2f0a03ff-…     (as coordinator@northcare.example.org)
→ HTTP 200
{"id":"2f0a03ff-…","medicalRecordNumber":"NC-0002","fullName":"Fern Fixture",
 "dateOfBirth":null,"status":"ACTIVE","version":0,"maskedFields":["dateOfBirth"]}
```

**Record** an organization-scope GRANT for the purpose+category that governs the field:

```http
POST /api/v1/patients/2f0a03ff-…/consent-directives     (+ X-XSRF-TOKEN)
{"effect":"GRANT","purpose":"CARE_COORDINATION","dataCategory":"DEMOGRAPHICS_CONTACT","scopeType":"ORGANIZATION"}
→ HTTP 201
{"id":"b1d869dc-…","effect":"GRANT","purpose":"CARE_COORDINATION",
 "dataCategory":"DEMOGRAPHICS_CONTACT","scopeType":"ORGANIZATION","status":"ACTIVE","version":1, …}
```

**After** — the same read now returns the value and `maskedFields` is empty:

```http
GET /api/v1/patients/2f0a03ff-…     (as coordinator@northcare.example.org)
→ HTTP 200
{"id":"2f0a03ff-…","medicalRecordNumber":"NC-0002","fullName":"Fern Fixture",
 "dateOfBirth":"1992-11-02","status":"ACTIVE","version":0,"maskedFields":[]}
```

> The masking is enforced on the **backend** — the withheld value is never sent to the client, so it
> can't leak via dev-tools. The SPA merely renders "Restricted" for a `null` masked field.

---

## Proof 3 — Relationship gate: same role, different result (§60 headline)

**Dana** and **Morgan** are *both* `PROVIDER`s in NorthCare. Dana is actively assigned to patient
`2f0a03ff…`; Morgan is not. The object/relationship layer narrows access by *relationship*, not role.

```http
GET /api/v1/patients/2f0a03ff-…     (as provider@northcare.example.org  — Dana, ASSIGNED)
→ HTTP 200   { … "fullName":"Fern Fixture" … }
```
```http
GET /api/v1/patients/2f0a03ff-…     (as provider2@northcare.example.org — Morgan, UNASSIGNED, same role)
→ HTTP 404
{"code":"NOT_FOUND","message":"The requested resource was not found.",
 "correlationId":"cb24e66a-0a41-42b3-83b3-b36a69611e8b"}
```

The same gate shapes the **list** read — each caller sees only what they may:

| Caller | Role | Patients returned by `GET /api/v1/patients` |
|--------|------|--------------------------------------------:|
| NorthCare coordinator | broad role | **3** (whole tenant) |
| Dana | PROVIDER, assigned to 2 | **2** |
| Morgan | PROVIDER, assigned to 0 | **0** |

> Two identical roles, three different result sets — decided by the care relationship, exactly the
> §60 criterion ("two users with the same role get different results").

---

## Proof 4 — Invalid state transitions are refused (409)

State moves are validated on the backend against a pure transition policy. Illegal moves return
**409 `INVALID_STATE_TRANSITION`**, distinct from a stale-version conflict — the change is never
silently applied.

**(a) An engine-owned status can't be reached by a bare status change.** `ADJUDICATED` is produced
only by the adjudication engine command:

```http
PATCH /api/v1/claims/04e36741-…/status     (+ X-XSRF-TOKEN)
{"targetStatus":"ADJUDICATED","expectedVersion":1}
→ HTTP 409
{"code":"INVALID_STATE_TRANSITION",
 "message":"A claim is adjudicated by the adjudication engine, not a status change.",
 "correlationId":"09743923-2ed5-44c7-8147-56208500cd6f"}
```

**(b) A terminal claim can't move backward.** `CLM-6A1A02` is `REJECTED` (terminal):

```http
PATCH /api/v1/claims/04e36741-…/status     (+ X-XSRF-TOKEN)
{"targetStatus":"SUBMITTED","expectedVersion":1}
→ HTTP 409
{"code":"INVALID_STATE_TRANSITION",
 "message":"Cannot change claim status from REJECTED to SUBMITTED.",
 "correlationId":"20e4ba10-cdc4-4eeb-a7a8-cd4bc2a61d83"}
```

---

## What these transcripts demonstrate

- **Backend-enforced tenant isolation** — cross-tenant reads are secure 404s (Proof 1).
- **Layered authorization** — role is *not* sufficient; the relationship + consent layers narrow
  further, so identical roles get different results (Proofs 2, 3).
- **Deny-by-default field masking on the server** — a consent-controlled value is withheld until
  consent grants it, and never sent to the client while masked (Proof 2).
- **Guarded state machines** — illegal transitions (including reaching an engine-owned status)
  are refused with a precise 409, not silently applied (Proof 4).
- **Uniform, safe errors** — every failure is the standard `{code, message, correlationId}` shape
  with no sensitive detail; the correlationId ties a response to its server-side log line.

The same guarantees are covered by automated negative tests in the suite (`TenantIsolation*`,
secure-404, consent-masking, and transition tests) — see [test-results.md](test-results.md). These
transcripts are the live, human-readable counterpart.

## Reproduce

```bash
docker compose up -d postgres
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # synthetic seed + dev-login
# then sign in with dev-login and replay the calls above, e.g.:
curl -s -c j -X POST localhost:8080/api/v1/dev-login --data email=admin@northcare.example.org
curl -s -b j localhost:8080/api/v1/me           # obtains the XSRF-TOKEN cookie
curl -s -b j localhost:8080/api/v1/patients/<a-green-valley-patient-id>   # → 404
```
