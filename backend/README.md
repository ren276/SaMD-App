# SaMD Backend

FastAPI backend for the PHC Patient Care Android app.

- **What it is and why:** `docs/backend/backend-prd.md`
- **Every endpoint, schema, and error code:** `docs/backend/api-contract.md`
- **ABDM/ABHA integration (Phase 5):** `ABHA planning/abha-integration-plan.md`

Phase 1 is complete: health, auth, error envelope, request-ID and audit middleware, structured
logging with PHI redaction, the audit hash chain, and CI. The 20 Room-mirrored clinical tables,
the kernel proxy, sync, and the ABDM adapter are Phases 2 through 5.

## Layout

```
backend/
  core/                 the FastAPI service
    app/
      api/v1/           routers
      db/               engine, session, declarative base
      middleware/       request-id, HTTPS enforcement, audit
      models/           SQLAlchemy 2.0
      schemas/          Pydantic v2, extra="forbid" on every request model
      services/         auth, audit chain
      scripts/          operator tools (account provisioning)
    alembic/            migrations
    tests/
  abdm-adapter/         Phase 5, a package mounted as a router by core
  docker-compose.yml    dev only
```

`abdm-adapter` is a sibling directory and a separate Python package, but it runs **in the same
process** and is mounted under `/api/v1/abha/`. One process, one image, one container. A
consequence worth knowing before Phase 6: `BuildConfig.ABHA_BACKEND_BASE_URL` collapses into
`BACKEND_BASE_URL`, and the separate dev port 8081 is not needed.

## Running it

```bash
cd backend/core
cp .env.example .env          # then edit; .env is gitignored
cd ..
docker compose up --build
```

The API is on `http://localhost:8080`. Interactive docs are at `/docs` in dev only, and are
disabled entirely in staging and production.

Port 8080 matches the dev flavor's `BACKEND_BASE_URL`, so an Android device on the same LAN needs
no Gradle edit. Point it at the host machine's LAN IP, not `localhost`.

The XGBoost kernel is deliberately not a compose service. It stays a separate process on the LAN
in dev; the backend reaches it through `KERNEL_BASE_URL`.

## Provisioning accounts

There is no self-service registration, no self-service PIN reset, and no user-management API
(decision D-3). The facility administrator runs:

```bash
docker compose exec api python -m app.scripts.seed_accounts \
    facility PHC-RJ-0142 "PHC Bagru" --district Jaipur --state Rajasthan

docker compose exec api python -m app.scripts.seed_accounts \
    worker "A. Devi" ASHA_WORKER PHC-RJ-0142
```

The second command prints a one-time PIN. Hand it to the worker in person. Every account is
created with `must_change_pin`, so until the worker changes it, every endpoint except
`/auth/me`, `/auth/change-pin`, and `/auth/logout` returns `SAMD-AUTH-1008`.

`worker_id` is not invented by the script. It is derived exactly as `MockAuthSession.stableUserId`
does on the device, `sha256(name.trim().lowercase() + "|" + ROLE)` truncated to 16 hex characters,
so audit identity is continuous across the cutover from mock auth to real auth. Provision with the
same name and role the worker types into the app.

## Migrations

```bash
cd backend/core
alembic upgrade head
alembic revision --autogenerate -m "add patients"
```

Every schema change is a migration. There is no `create_all` outside the test fixture, and CI
fails the build when `alembic check` still finds a diff.

## Tests

```bash
cd backend/core
pip install -e ".[dev]"
createdb samd_test          # or let docker compose's postgres serve it
TEST_DATABASE_URL=postgresql+asyncpg://samd:samd_dev_only@localhost:5432/samd_test pytest
```

Tests run against a real PostgreSQL 16, not SQLite. The audit chain uses
`pg_advisory_xact_lock`, the schema uses regex CHECK constraints and an Identity column, and the
append-only guarantee is a plpgsql trigger. A suite running on a database the service will never
see verifies the wrong thing.

## Deployment note: the audit log database role

The append-only audit log has three enforcement layers (`app/models/audit.py`). The third is a
database trigger, created by migration 0001, which restrains the table owner as well. In staging
and production, run the application under a role that additionally holds only `SELECT, INSERT` on
`audit_events`:

```sql
CREATE ROLE samd_app LOGIN PASSWORD '...';
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO samd_app;
REVOKE UPDATE, DELETE ON audit_events FROM samd_app;
```

Migrations run as the owner, deliberately and visibly. The application does not.

## Things that are true and easy to get backwards

- **Request and response bodies are never logged**, at any level, in any environment. There is no
  switch for it.
- **`ailments.audio_local_uri` never crosses this boundary** (REQ-AIL-03). Private-ailment audio
  stays on the device. A sync record carrying it is rejected with `SAMD-SYNC-6006`.
- **`visibility = PRIVATE` ailments do sync**, including their clinical text. Private means hidden
  from the worker-facing projection (REQ-AIL-04), not withheld from the clinical record. Only the
  audio is device-local.
- **No patient identity may reach the kernel** (REQ-HAN-06, hazard H-10). Phase 3 rebuilds that
  guarantee server side rather than inheriting it, because the backend holds the full patient row
  in the same process and a careless join is all it would take.
- **The backend adds real foreign keys** even though the Room schema has none. The device's reason
  for omitting them (arbitrary offline insert order, one writer) does not apply to the durable
  copy of a clinical record.
