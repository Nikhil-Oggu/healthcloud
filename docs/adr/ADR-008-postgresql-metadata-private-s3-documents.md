# ADR-008 — PostgreSQL Metadata + Private S3 Documents
- Status: Accepted
- Date: 2026-09-24 (recorded; in force since Phase 3)

## Context
Patient documents have two very different parts: the **metadata** (who it belongs to, tenant, filename, content
type, scan status, uploader — the data the authorization and audit model must reason about) and the **binary
content** (the bytes themselves). Storing large binaries in the relational database bloats it and mixes concerns;
storing authorization metadata alongside the bytes puts access decisions outside the database where the rest of the
access model lives.

## Decision
Split them: keep **relational authorization/audit metadata in PostgreSQL** (`patient_document` — tenant key,
`patient_id`, `file_name`/`content_type`/`size_bytes`, an opaque `storage_key`, `scan_status`, uploader), and keep
the **binary content behind a `DocumentStorage` abstraction** whose production implementation is **private S3**.
Nothing else in the app knows where bytes live. Access re-authorizes on every read (through `PatientAccessGuard`),
uploads are malware-scanned, and **bytes never enter a DTO, log, or event** — download re-authorizes then streams as
an attachment.

## Consequences
- ✅ Access/audit decisions stay in the database where the whole authorization model lives; the store is pluggable.
- ✅ The DB stays lean; large binaries live in object storage built for them.
- ✅ Content is quarantined until CLEAN; the opaque `storage_key` avoids leaking a path.
- ⚠️ **Honest status:** locally the abstraction uses a `LocalFileSystemDocumentStorage` stand-in (a git-ignored
  `var/` dir); the private-S3 implementation is the target design and is **not** wired into the deployed slice yet —
  a documented follow-up, not a change to this decision.
