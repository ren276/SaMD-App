# Environments and release: STEP 1 design memo

Status: PROPOSED, awaiting operator gate review. Nothing here is a ruling until the operator gives it.
Date: 2026-11-04. Base: origin/master 7e98cd8. Branch: docs/environments-and-release (uncommitted).
Later amendments to this memo are additions-only.

Amendment A-1 (2026-11-05, operator): scope widened from demos to how a production company ships. The
memo now has two parts. Everything below down to the end of section 7 is PART A (D-1 to D-17,
environments, demo stacks, install slots, bundles), unchanged by this amendment. PART B (D-18 to D-30,
production update safety) is not written yet and starts only after the operator replies at STOP 1.
Superseded by A-1: title line 1 is now "Environments and release: STEP 1 design memo". Branch on line 4
is now docs/environments-and-release; worktree /media/sandesh/extra-ssd/wt-env-release; path
docs/design/environments-and-release-memo.md. Base is still origin/master 7e98cd8 (re-fetched 2026-11-05,
unchanged). Worktree moved and branch renamed in place, so only one copy of this memo exists.

Marking: VERIFIED means read in the repo at 7e98cd8, cited file:line. INFERRED means reasoned or from
memory of tool behavior and not run; every INFERRED item that a design depends on has a live check in
section 6. OPERATOR means stated by the operator and not re-verified here (no docker, adb or gradle was
run for this memo).

## 0. Findings that change the proposal

Read these first. Each one changes a proposed item, and section 2 carries the detail.

1. **A pinned stack can silently rebuild.** With both `image:` and `build:`, Compose builds from the
   current tree when the tag is missing, and tags that build with the pinned name (INFERRED, Compose
   specification). That is this project's characteristic bug: `samd-api:<sha>` reporting satisfied over
   other code. Pinned stacks must run `--no-build --pull never` (D-1).
2. **`*.secrets.env` is not gitignored today.** `.gitignore:33` matches only a file named exactly `.env`
   (VERIFIED). `git add backend/` would commit the demo keys (D-2).
3. **A committed `backend/stacks/demo.env` is unreadable under this project's own rules.** It ends in
   `.env`, which the hard rules forbid an agent to read or edit. Proposed rename: `demo.vars` (D-1, Q-7).
4. **`GIT_SHA` in an env file beats the image's `ENV`.** `.env.example:18` ships `GIT_SHA=local` and
   `env_file` overrides image `ENV` (VERIFIED for the example, INFERRED for precedence), so D-4 as
   proposed could keep reporting `local` (D-4).
5. **Fixed persona ABHA numbers defeat an append-only DB.** `DemoPatientProfile.kt:140,208,284,353,423`
   are constants, `ix_patients_abha_number` is unique (`models/patient.py:73`), and a duplicate is a
   CONFLICT needing a person (`services/sync.py:151-168`). All VERIFIED. Each persona works once per
   database, so the first rehearsal burns the numbers the stage needs (D-8, Q-4). ABDM stub mode has the
   same defect with one fixed number (`abdm_adapter/client.py:303,348`).
6. **A clean-ref APK has no speech model.** ASR weights are gitignored (`.gitignore:58`) and absent from
   this fresh worktree (VERIFIED). The build succeeds and the voice field fails on stage (D-9).
7. **The default dev fallback shows a mock clinical result when the classifier is down.**
   `DevKernelFallbackMode.kt:7-8`: "every failed assessment becomes a mock result" (VERIFIED). A stopped
   classifier at a venue yields a plausible diagnosis on stage (D-9, Q-3).
8. **D-9 commits a path to flipping flags the code says are not flag changes.** `FeatureFlags.kt:49-51`
   and `:81-82` say that `VOICE_FIELD_CHIEF_COMPLAINT_ENABLED` and `VOICE_AUDIO_ATTACHMENT_ENABLED` stay
   false until a gate and provenance are built (VERIFIED). That is a safety-control reversal, risk class
   R3, and the operator's call (Q-2).
9. **D-11's keystore plan risks the wipe it exists to prevent.** Re-signing plain dev with a new shared
   key makes the next `adb install -r` of `com.example.samdapp.dev` fail, and the only way forward wipes
   the Room v23 data. Also, the laptop never builds, so it needs no keystore at all (D-11, Q-8).
10. **D-16's hotfix flow conflicts with repo rules.** Branching from a tag and cherry-picking to master
    contradicts "one branch per change, cut from origin/master" and the merge-commit rule. Stopping on it
    as a conflict, not designing around it (D-16, Q-12).
11. **The bundle as listed would not start offline.** `postgres:16-alpine`, the compose file and samdctl
    itself are missing from D-6's list, and `zstd` is in the Python stdlib only from 3.14 (INFERRED)
    (D-6).

## 1. Problem and verified facts

**Problem.** Every demo freezes development. One stack and one database: a feature-branch migration
upgrades the only DB, and demo and dev builds share one applicationId on the Pixel 11 Pro, where Room v23
cannot downgrade. Goal: run any backend plus app version on demand, from the Linux main machine or the
Windows 11 laptop, possibly with no internet, without pausing dev work.

### 1.1 F-1 to F-11

| Id | Verdict | Evidence and nuance |
|---|---|---|
| F-1 | VERIFIED | Ports `backend/docker-compose.yml:16,42`; volume `:18,58-59`; no top-level `name:` in the file; `build:` with no `image:` `:26-28`; command `:48-50`; env_file `:29-30`; KERNEL_BASE_URL default `:38`; extra_hosts `:55-56`. Live volume name `backend_samd_pgdata`: OPERATOR (F-11). Nuance: `ENVIRONMENT: dev` and `REQUIRE_HTTPS: "false"` are hardcoded at `:34-35`. |
| F-2 | VERIFIED, one nuance | `backend/core/Dockerfile:2-3`, no ARG anywhere in the file; `config.py:41`; `api/v1/health.py:69-78`. Nuance: `core/.env.example:17-18` documents `GIT_SHA=local`, and CMD at `Dockerfile:59` runs no Alembic, so a non-compose orchestrator skips anything put in the compose `command`. |
| F-3 | VERIFIED, one nuance | `config.py:16`, `:147-186`, secret names `:157-162`. Nuance: dev returns early at `:154-155` and compose hardcodes dev, so **`validate_secrets` never runs in any compose stack** and a key missing from an env file silently falls back to the `CHANGE_ME` defaults at `:51-54`. |
| F-4 | VERIFIED | `backend/.dockerignore:2-4`. |
| F-5 | VERIFIED | `app/build.gradle.kts:113-136`, `:193-197`, `:202-205`; only release signing `:215-225,228-229`. Debug using each machine's own keystore is INFERRED (AGP default, no debug config in the file). |
| F-6 | VERIFIED | `app/src/dev/AndroidManifest.xml:14,35,37`; `app/src/main/AndroidManifest.xml:45,60`. Nuance: `:8-12` of the dev manifest states `adb shell am broadcast` cannot land on a locked device. |
| F-7 | VERIFIED | `domain/connectivity/LocalNetworkHost.kt:15` (loopback false), `:29`, `:46-59` (127/8 falls through to false). |
| F-8 | VERIFIED | `tools/dev-connect.sh:1,24,98-100,71-85`. Nuance: it also writes a `KERNEL_BASE_URL` that nothing reads (`build.gradle.kts:117` records its deletion) and it sends no broadcast. |
| F-9 | VERIFIED | `.gitattributes:1` is the only line. `git ls-files --eol`: 1258 files i/lf w/lf and no CRLF blob among the proposed patterns. `gradlew.bat` is also i/lf. |
| F-10 | CHANGED | Six voice flags at origin, not five: chief complaint `FeatureFlags.kt:54` false, audio attachment `:85` false, impact `:104` true, aggravating `:120`, relieving `:126`, relevant history `:132` false. `git status` shows `FeatureFlags.kt` and `DemoPatientProfile.kt` modified in the main checkout; those diffs were not read, per the brief, so which five are true is unknown (Q-2). ABDM: `git status` also shows `backend/docker-compose.yml` modified, and its diff flips the `ABDM_MODE` default to `live`. So the ABDM edit is live in the main checkout, not only in a patch. |
| F-11 | OPERATOR | Docker.raw at `/media/sandesh/oneTB_SSD/docker data/DockerDesktop/Docker.raw`, nvme1n1p1, ext4, 809G free; 32G allocated of a 64G cap; path has a space; fstab by UUID, `nofail`, 10 s timeout; `backend_samd_pgdata` present. Root 8.4G free today (`df`, VERIFIED). extra-ssd 578G free (`df`, VERIFIED). |

Scope note on the reads: two commands touched the main checkout beyond the brief's status and fetch. One
was a read-only `git diff` of `backend/docker-compose.yml` (result in F-10). The other was
`graphify query`, run from the main checkout because a project hook requires it; its output is not cited
as evidence anywhere in this memo. No forbidden file was opened.

### 1.2 New facts the design depends on

| Id | Fact | Mark |
|---|---|---|
| N-1 | `.gitignore:33` is `backend/**/.env`, exact name. Nothing ignores `*.secrets.env`. `.gitignore:16,21-22` do cover `local.properties`, `*.jks`, `*.keystore`. | VERIFIED |
| N-2 | Tests do not run the lifespan: `tests/conftest.py:215` (`ASGITransport`). Schema in tests is `create_all` plus hand-mirrored triggers: `conftest.py:111-170`. | VERIFIED |
| N-3 | Alembic head is 0010; 10 revisions in `alembic/versions/`. CI runs `alembic check`: `backend-ci.yml:89`. Insert-only trigger precedent: `0010_...py:188-193`. | VERIFIED |
| N-4 | PHI encryption is pgcrypto in SQL with the key bound per statement: `db/types.py:50-63`. Blind index: `:82-99`. Case token: `adapters/kernel/pseudonym.py:27-34`. No migration reads a key: no key name in `alembic/` (grep). | VERIFIED |
| N-5 | Blind-indexed columns: `models/patient.py:93` and neighbours (`name_`, `aadhaar_`, `mobile_blind_idx`). `case_token` lives beside the real id in `kernel_call_log`: `models/sync.py:140,146`. | VERIFIED |
| N-6 | A wrong blind key fails silently: lookups miss and duplicates appear. A wrong PHI key errors on read. A wrong JWT key only forces a login. | INFERRED from `db/types.py`, `services/auth.py:131,140` |
| N-7 | No ABHA checksum exists anywhere: backend `schemas/patient.py:19,40` and `models/patient.py:60` are 14 digits only; app `AbhaLoginViewModel.kt:13` is `length == 14`; no `verhoeff` in `app/`, `backend/`, `docs/requirements/`. | VERIFIED |
| N-8 | `versionCode = 1` is fixed: `build.gradle.kts:94`. No `fallbackToDestructiveMigration`: `DatabaseModule.kt` has none (grep). `AppDatabase.kt:78` version 23. | VERIFIED |
| N-9 | `BuildConfig.GEMINI_API_KEY` is baked from local.properties: `build.gradle.kts:107`, read at `GeminiBrandLookupSource.kt:33,40`. Blank means the lookup returns null. An APK built with a key carries it in the clear. | VERIFIED; carrying is INFERRED |
| N-10 | `DevServerConfig.kt:53-56`: a saved URL beats `BuildConfig.BACKEND_BASE_URL` and survives `adb install -r`. `DevDynamicHostInterceptor.kt:42-45` rewrites host and port of any local target. | VERIFIED |
| N-11 | Classifier: not in compose by design (`docker-compose.yml:3-4`). A `samd-classifier` container was recorded on 8000 and 8001 (`docs/design/connectivity-and-assess-ordering-handoff.md:214`), so "demo on 8001" may already be taken. No image name or digest in the repo. | VERIFIED as recorded, current state unknown |
| N-12 | Facility scope: an install claims one facility and BLOCKs any other (`PROGRESS.md`, last section; FS-R). Refresh tokens rotate with reuse detection: `services/auth.py:325-338`. Refresh TTL 7 days: `config.py:62`. | VERIFIED |
| N-13 | `device_id` is ANDROID_ID: `AndroidDeviceInfoProvider.kt:15-16`. It is scoped per signing key on Android 8+ (INFERRED), so a key change also changes the server-side device identity. | VERIFIED; scoping INFERRED |
| N-14 | `TEST_DATABASE_URL` defaults to `localhost:5432/samd_test`: `conftest.py:40-43`. The dev port must stay. | VERIFIED |
| N-15 | Model delivery hardcodes the dev package: `docs/design/on-device-slm-memo.md:782-786`. | VERIFIED |
| N-16 | The main checkout's CLAUDE.md names `docs/adr/`, `docs/agents/engineering-standards.md`, `.claude/rules/` and `.claude/hooks/guard.py`. None exists at origin/master (`git ls-tree` empty); CLAUDE.md at origin is 20 lines. ADR conflicts therefore could not be checked (Q-1). | VERIFIED |

## 2. Rulings D-1 to D-17

These are recommendations for the gate. The operator rules.

### D-1 Pinned stacks: AMEND

Accepted: an environment is image tag, own volume, own port, own Compose project. Dev keeps project
`backend` and port 8080, and no top-level `name:`.

Amendments:

- **`--no-build --pull never` on every pinned `up`.** See finding 1. samdctl adds both for demo and
  preview. Plain `docker compose` from `backend/` stays as today, which is what the rule "`docker compose
  build --no-cache api` from `backend/`" relies on.
- **`samdctl` always passes `-p`.** A plain `docker compose up` from any worktree's `backend/` directory
  resolves to project `backend` and so to the live volume (INFERRED from Compose's directory-name default, F-1; it
  applies to any directory so named). This is how a branch migration reaches the dev DB today. The memo
  does not change that; preview through samdctl is the alternative. Documented in `backend/README.md`.
- **DB published on loopback with a distinct port, not unpublished.** Removing a port in an overlay needs
  `!reset` (Compose 2.24 or later, INFERRED), and interpolation alone cannot drop the mapping. Dev
  `0.0.0.0:5432` unchanged (N-14), demo `127.0.0.1:5433`, preview `127.0.0.1:5434`.
- **API on loopback for demo and preview.** adb reverse reaches localhost, so a venue LAN never sees the
  API. Dev keeps `0.0.0.0:8080`, because a LAN device needs it (`docker-compose.yml:39-40`).
- **`backend/stacks/<name>.vars`, not `.env`.** Finding 3. Compose's `--env-file` takes any name (INFERRED).
- **`ENVIRONMENT` stays `dev` in every compose stack and the `Environment` Literal does not grow.** Staging
  rules demand HTTPS (`config.py:170-171`), and `--no-must-change-pin` needs dev (`seed_accounts.py:152-161`).
  Stack identity is a new `STACK_NAME`, surfaced on `/health` (D-12).
- **Refuse to create a missing volume.** `samdctl up` checks the volume exists, and first creation needs an
  explicit `--init`. Otherwise a wrong engine or an unmounted data drive (section 3, DH-9) yields a new empty
  DB that boots cleanly.

Sketch (dev resolves to today's values):

```yaml
services:
  db:
    ports: ["${DB_BIND:-0.0.0.0}:${DB_PORT:-5432}:5432"]
  api:
    image: samd-api:${API_TAG:-dev}
    build:
      context: .
      dockerfile: core/Dockerfile
      args: { GIT_SHA: "${GIT_SHA:-unknown}" }
    env_file: ["${API_ENV_FILE:-./core/.env}"]
    environment: { STACK_NAME: "${STACK_NAME:-dev}" }   # plus the existing keys
    ports: ["${API_BIND:-0.0.0.0}:${API_PORT:-8080}:8080"]
```

Residual risk: `docker compose up` by hand with `-p samd-demo` bypasses `--no-build`. The label check in
`samdctl status` and the `/health` SHA catch it afterwards, not before. Also, `samd-api:dev` replaces
`backend-api` as the dev image name, which is harmless but visible, and the first plain `up` after merge
rebuilds.

### D-2 Demo secrets: AMEND

Accepted: gitignored, operator-created `backend/stacks/demo.secrets.env`, never read by samdctl.

- `.dockerignore`: VERIFIED covered (`.dockerignore:3`). Add `stacks/` as well, since nothing in the image
  needs it (the Dockerfile copies explicit paths, `Dockerfile:21-24,49-51`).
- `.gitignore`: **VERIFIED not covered**. Add `backend/stacks/*.secrets.env`. Proof by `git check-ignore` in
  the test plan, mutation: remove the line, check goes red.
- Close F-3's hole: when `STACK_NAME` is `demo` or `preview`, `validate_secrets` applies the placeholder and
  length checks and the ABDM-live credential check, and still skips the HTTPS rule. Without this, a
  secrets file missing one key boots on `CHANGE_ME` (`config.py:51-54`) and enrols that as truth at D-3.

Residual risk: operator-held copies drift. A wrong copy is caught by D-3 at boot, including a CRLF-damaged
file written on Windows, since a trailing `\r` is a different key.

### D-3 Key canary: AMEND

Accepted: fail closed at boot on a key mismatch. Amended: first boot must not blindly trust the current
keys; one table also holds D-7's moved marker; rotation is narrower than the proposal implies.

**Table `stack_ledger`**, insert-only, UPDATE and DELETE rejected by triggers as in `0010_...py:188-193`,
and mirrored in `conftest.py` as that file already requires:

| Column | Type | Note |
|---|---|---|
| id | bigint identity PK | latest row by kind wins |
| kind | text CHECK | `enrolled`, `rotated`, `moved`, `reclaimed` |
| phi_check | bytea, `EncryptedText` | fixed non-PHI string, null on moved and reclaimed |
| blind_check, case_check, jwt_check | varchar(64) | `blind_index(s)`, `case_token_for(s, key=...)`, truncated `HMAC-SHA256(jwt_key, s)` |
| image_sha | text | `settings.git_sha` at write time |
| note | text | rotated key name, or bundle id |
| created_at | timestamptz default now() | |

**Migration:** new revision 0011 revising 0010, plus a model on `Base.metadata` so `alembic check` passes.
Downgrade drops the table. Nothing applied is edited.

**Where it runs:** after `alembic upgrade` (the table must exist) and before serving, in `lifespan`, right
after `configure_logging()` at `main.py:60`. Not in the compose `command`, because `Dockerfile:59` runs
uvicorn directly and the same image must be guarded under any orchestrator (F-2). The migration does run
first on a wrong-key boot, which is safe because no migration reads a key (N-4).

**Boot logic:**

1. No row: enrol, under an advisory lock so two replicas cannot both insert. If `patients` already has rows,
   first decrypt up to 20 recent `full_name` values and recompute `name_blind_idx`; if `kernel_call_log` has
   rows, recompute `case_token`. Any mismatch refuses with `key_canary_enroll_conflict`. **This is the
   amendment: without it, the first boot of this code on a DB already holding data under older keys blesses
   the wrong key.** The JWT key has nothing to verify against, and that is accepted (N-6).
2. Row exists: verify all four against the latest `enrolled` or `rotated` row. The name of the failing key
   goes in one `CRITICAL` line, `key_canary_mismatch failed_key=BLIND_INDEX_KEY stack=demo`; never a value.
3. Latest `moved` or `reclaimed` row is `moved`: refuse with `stack_moved` (D-7).
4. Refusal raises out of lifespan, and uvicorn exits nonzero (INFERRED exit code 3, live check). Uvicorn's
   own traceback follows the one clear line.

**Dev placeholder keys:** no exemption. Dev enrols whatever it runs on. A dev who moves from placeholder to
real keys over existing data is exactly the case to catch, and F-3 means nothing else will. No off switch
and no warn mode: a guard with a quiet mode is the characteristic bug. The behavior change to accept: a dev
key change now stops dev boot.

**DB unreachable at boot:** today the service starts and `/health` returns 503 (`health.py:60-66`). With the
check, it exits. Compose has `depends_on: service_healthy` (`docker-compose.yml:43-45`) and has just run
Alembic, so this only bites a separate DB outage at boot under an orchestrator, where a restart is the right
behavior. Flagged for the staging design.

**Rotation:** `python -m app.scripts.stack_ledger rotate --key <NAME> --confirm-stack <stack>`, run by the
operator, appends `rotated`.

| Key | Procedure |
|---|---|
| JWT_SECRET_KEY | Rotate: every device signs in again. |
| CASE_TOKEN_KEY | Rotate: old `kernel_call_log` tokens stop matching new ones, which is acceptable pre-pilot. |
| PHI_ENCRYPTION_KEY, BLIND_INDEX_KEY | **Refused by the tool.** No rekey tool exists. Pre-pilot procedure: new volume on new keys (data is declared non-clinical and reset before deployment, `0010_...py:6-7`). A real rekey belongs with the KMS work (`db/types.py:11-14`) and is filed, not designed here. |

**Mutation proof:** section 6, PR-E1a. A wrong `BLIND_INDEX_KEY` goes RED at an assertion, and the live check
proves the container exits with the DB row unchanged.

Residual risk: `TRUNCATE` skips row triggers (`0010_...py:23-24`), so a reset empties the ledger and the next
boot re-enrols; step 1's sampling covers it if data remains. The canary proves the keys match, not that they
are good keys.

### D-4 GIT_SHA build arg: AMEND

Accepted: bake the SHA into the image. Amended because of finding 4.

- `ARG GIT_SHA=unknown`, then `LABEL org.opencontainers.image.revision=$GIT_SHA` and
  `ENV SAMD_BUILD_SHA=$GIT_SHA`, placed after the last `COPY` in the runtime stage so earlier layers stay
  cached.
- `Settings.git_sha` reads `SAMD_BUILD_SHA` only. `GIT_SHA=local` is removed from `.env.example:17-18`. A
  stale `GIT_SHA` in any operator `.env` then has no effect, and no env file was read to establish this.
- `samdctl status` compares three independent values: pinned tag, image label, `/health`. Any disagreement is
  an error.
- Builds come from `git archive <ref>` into a work directory, never from a working tree, so the SHA cannot
  describe a dirty tree. `--no-cache` is used for demo builds, matching `backend/README.md`'s rule.

Residual risk: plain dev builds report `unknown` unless the operator passes `GIT_SHA`. That is honest. CI's
`docker build` at `backend-ci.yml:109` passes no arg and is unaffected.

### D-5 samdctl: AMEND

Accepted: one stdlib-only Python file, `tools/samdctl.py`. Never reads or writes local.properties or any
secrets file. Ruling on `dev-connect.sh`: **keep it unchanged**, as the Linux-only dev tool. It writes
local.properties (`dev-connect.sh:71-85`), which samdctl must never do, and slot builds ignore that file
anyway (D-9). Its stale `KERNEL_BASE_URL` write and 8000 tunnel are a separate cleanup, and not worth a
change here.

Amendments:

- Commands added: `build-apk <ref> --slot`, `slot install`, `slot push-model`, `slot tunnel [--watch]`.
- **Scrub the child environment.** A shell-exported variable beats `--env-file` in interpolation (INFERRED):
  an exported `ABDM_MODE` or `API_TAG` would silently change the stack. samdctl passes an explicit env with
  every stack variable removed, and sets a work-local `TMPDIR`.
- **Assert the engine.** `docker context show` must match the allowed value in the `.vars` file. Compose
  against another engine sees no volumes (DH-9).
- **`up` is not done until `/health` agrees.** There is no compose healthcheck on `api`, so `up -d` returns
  success even if the container exits next. samdctl polls, checks the exit code, prints the last log line on
  failure.
- **Migration gate.** Before starting `api`: start `db`, read `alembic_version`, run
  `compose run --rm --no-deps api alembic heads`. A difference on a pinned stack refuses unless `--migrate`.
  The compose `command` would otherwise upgrade unasked (`docker-compose.yml:49`).
- **What it can never do.** No `down -v`, no `docker volume rm`, no `adb uninstall`, no `pm clear`, no raw
  `docker compose config` (which prints env_file values, INFERRED). No flag enables these. Destructive steps
  are printed for the operator to run, per CLAUDE.md.
- **`SAMD_WORK_DIR` required**, and refused on Linux when it shares a device with `/`. Paths always passed as
  list arguments, `shell=False`, so the F-11 space is harmless. samdctl never names that path.

Skipped: a config file and subcommand plugins. Add when a third machine exists.

### D-6 Demo bundle: AMEND

Accepted: export and import, hash verify before anything loads, no internet needed.

Added to the list, without which an offline laptop cannot start: **`postgres:16-alpine` image** (tag is
floating, record its digest), **`docker-compose.yml`**, **`samdctl.py`** and the bundle's own `.vars`. Then the
laptop needs no git clone, which also removes most of D-14's CRLF risk.

Changed:

- **gzip, not zstd.** `compression.zstd` arrived in Python 3.14 (INFERRED) and the `zstd` binary is not on a
  stock Windows. `docker load` reads gzip natively (INFERRED). The APK is mostly stored `.onnx`
  (`build.gradle.kts:261`) and will not compress anyway.
- **Dump via a file in the container, then `docker compose cp`**, not an stdout pipe, so no shell handles the
  binary stream on Windows. Then `pg_restore -l` validates the dump before the bundle is finalized.
- **Dump, not volume copy**, also because the restore is then independent of Postgres page format.
- **Verify after load as well as before.** Compare the `org.opencontainers.image.revision` label to the
  manifest, and the architecture to the host. Image IDs may differ across image stores (INFERRED), so IDs are
  not compared.
- **Restore** into a new volume only, with `--exit-on-error --no-owner`, then check `alembic_version` equals the
  manifest. An existing volume refuses (D-7). Triggers are restored post-data, so append-only tables load
  (INFERRED, live check).
- **SaMDPi SHA** is a required `--samdpi-sha` argument. It cannot be derived from this repo.
- Manifest also gets: Room version, architecture, compose and postgres digests, bundle id.

Not in the bundle: demo secrets (carried separately) and the SLM model (already on the phone; the phone travels).

Residual risk: **the APK may carry a Gemini key** (N-9), so a bundle could be sensitive. Recommended: build
without it. The clean worktree has no local.properties, so blank by default (Q-9). The dump holds ciphertext
plus plaintext demographics and ABHA numbers (`models/patient.py:11`); it is synthetic if D-8 is kept to, and
should be treated as sensitive anyway.

### D-7 Single home: AMEND (marker location decided)

Accepted: one home, export stops the source, `up` refuses, `reclaim` to undo.

**The marker lives in the source DB as a `stack_ledger` row of kind `moved`, and the API's own boot check
enforces it.** A file beside samdctl fails three ways: a plain `docker compose up` ignores it, a new clone lacks
it, and deleting a work directory clears it. A row in the DB travels with the thing it protects and stops the
API by any route. Volume deletion is the only bypass, and it is deliberate.

Order on export, chosen so the dump never holds the marker and a failure leaves the source usable:

1. Stop `api`. 2. Dump. 3. Write and re-hash the bundle. 4. Insert `moved` with the bundle id. 5. Stop `db`.

A failure before step 4 means restart and nothing is lost.

`reclaim` appends `reclaimed` after the operator types the bundle id, and warns: only safe if that bundle was
never used elsewhere. Coming home with new data: laptop export, then on main `import` refuses over the existing
volume and prints the volume removal for the operator to run after looking at it.

Residual risk: restoring an **older** bundle to an empty volume recreates the stale-copy state, and nothing can
detect this across machines. `import` prints `created_at` and asks for confirmation; the rule is that only the
newest bundle is imported. Mitigated by keeping the phone with one home. Old bundles stay for reproducibility
(D-16), to be restored only with a matching fresh install.

### D-8 No server reset in v1: AMEND

Accepted: no reset in v1, append-only, seed script.

Why a paired reset is later: a server restore without clearing the phone leaves stale `server_version` values
and revoked tokens, while clearing the phone is the FS-R support procedure (`PROGRESS.md`, last section) and
costs the model re-push (N-15), the CDM association and all grants, which is a rehearsal-day risk. It needs its
own failure design.

**Amended, because append-only collides with fixed ABHA numbers** (finding 5): every registration of a persona
after the first gives a permanent CONFLICT. A seed script does not fix this, because the stage flow registers a
new patient. Recommended (Q-4): under `DEMO_PROFILE` only, `DemoPatientProfile.ABHA_NUMBER` (`:498`) returns a
fresh number per selection, a reserved prefix plus a 9-digit time component. Aadhaar and mobile are not unique
constraints (`sync.py:151-155`), so only ABHA needs this.

**"Checksum-valid" has no referent in this repo** (N-7). Only `^[0-9]{14}$` and uniqueness are enforced. Do not
invent a rule (Q-5). Avoid real numbers by the reserved prefix, chosen by the operator.

**One facility per demo DB,** since an install BLOCKs others (N-12). Seeds use `seed_accounts.py`, whose PIN
printing (`:119`) stays in the operator's terminal; PINs are not committed or logged by anything added here.

ABDM stub also repeats one number, `91756140880001` after dash stripping (`client.py:303`, `mapping.py:23,42`).
Stub enrolment therefore works once per DB (Q-6).

Residual risk: rehearsal data accumulates and shows in patient lists on stage. A clean list needs a new DB and a
fresh install, which is the later paired reset.

### D-9 App install slots: AMEND

Accepted: keep the dev flavor; `-Psamd.slot=demo|preview` gives suffix, label, icon, URL, profile.

**Why not a fourth flavor.** Dev-only code and deps key on the flavor name: `"devImplementation"`
(`build.gradle.kts:329,332`), `src/dev`, `src/testDev`, `src/androidTestDev`, the tests at
`ReadbackFlavorGraphTest.kt:102-104` and `ReportReadbackTest.kt:99-100`, the text read by
`LiteRtLmIsDevOnlyTest.kt:76-77`, the CI matrix (`android-ci.yml:39,42`), and the SBOM classpath
(`build.gradle.kts:363`). A demo flavor would duplicate or re-point all of it to change one id and a few values,
and it would create a new surface for G-B11 and G-B11b. `BuildConfig.FLAVOR` staying `dev` keeps every one of
these true. All VERIFIED.

Amendments:

- **Slot builds come from a clean worktree of a ref, via samdctl.** It removes inherited `local.properties`
  overrides (`BACKEND_BASE_URL`, `PI_GATEWAY_BASE_URL`, `samd.dev.kernelFallback`, `GEMINI_API_KEY`). Gradle fails a
  demo slot when the tree is dirty. Preview allows it, with a `-dirty` suffix on the SHA.
- **Slot URL wins and ignores local.properties**, at `build.gradle.kts:121-123`. Unknown slot value throws,
  following the precedent at `:186-189`. The property is read inside the dev block only, so staging and prod
  cannot get it. `providers.gradleProperty` keeps the configuration cache honest (`gradle.properties` enables it).
- **ASR model is required for slots.** samdctl links the weights into the worktree, and Gradle verifies them
  against the SHA-256s in `docs/sbom/model-soup-2026-09-02-v1.0.json`, failing the build otherwise (finding 6).
- **Flags.** One `DEMO_PROFILE` boolean, false in staging and prod and in unslotted dev, following
  `FeatureFlags.kt:11,171`. Each profile flag becomes `val X = BuildConfig.DEMO_PROFILE`. Not one field per flag.
  **Which flags join is Q-2.** Recommended: exclude `VOICE_FIELD_CHIEF_COMPLAINT_ENABLED` and
  `VOICE_AUDIO_ATTACHMENT_ENABLED` unless the operator rules otherwise, as they are the H-15 paths reaching
  `/api/v1/evaluate` (`FeatureFlags.kt:45-51,64-82`; `risk-management-file.md:47-48,100`). Tests that assert the
  flag-off side (`TranscribeAudioUseCaseTest.kt:26-29`) keep running on unslotted builds and need no edit.
- **Kernel fallback.** Slots bake `DEV_KERNEL_FALLBACK` explicitly. Recommended `none`, for the honest UNAVAILABLE
  card (Q-3). The mock's `inferenceSource = MOCK_FALLBACK` (`MockKernelFallbackSource.kt:252`) is recorded, but
  whether a viewer would notice it on stage is the operator's judgment.
- **`versionCode` from `git rev-list --count` for slots only**, so Android itself refuses an older APK
  (`INSTALL_FAILED_VERSION_DOWNGRADE`) instead of a Room crash on open (N-8). Native control; one line.
- **Icon and label** by a slot res directory added to the dev source set, `src/slot/demo/res`. Not `resValue`, which
  may clash with `strings.xml:2` (INFERRED). `app/src/dev/res` does not exist today, so nothing conflicts.
- **Demo content must be committed.** A clean-ref build cannot contain the uncommitted `DemoPatientProfile.kt`
  edits (Q-17).

**Everything keyed on identity** (grep of `app/src`, `tools`, `scripts`, `.github`; all VERIFIED):

| Site | Keys on | Slot needs |
|---|---|---|
| `build.gradle.kts:91,115,195` | applicationId, suffix | suffix `.dev.demo`, `.dev.preview` |
| `build.gradle.kts:85` | namespace | unchanged, so R and BuildConfig stay in one package |
| `main/AndroidManifest.xml:45,60` | `${applicationId}` | nothing |
| `ReportPdfExporter.kt:53`, `ConsultationScreen.kt:776` | `context.packageName` | nothing |
| Settings deep links: `PinChangeScreen.kt:47`, `HomeScreen.kt:204`, `LoginScreen.kt:103`, `CompounderScreen.kt:339` | package at runtime | nothing |
| `PackageAppPermissions.kt:20,23` | `context.packageName` | nothing |
| `ConsultationVoiceGateUiTest.kt:135` | `context.packageName` | nothing |
| `BuildConfig.FLAVOR`: the two tests above | `dev` | stays `dev` |
| `BuildConfig.ENVIRONMENT` | **zero readers** in `app/src` | stays `dev`; dead field, left alone |
| `BuildConfig.APPLICATION_ID` | zero readers | nothing |
| `DevServerReceiver.kt:17,20,49`, `dev/AndroidManifest.xml:14,35,37` | hardcoded `.dev` | D-10 |
| `scripts/process_death_check.sh:42` | hardcoded `.dev` | accept a package argument, default unchanged |
| `docs/end-to-end-run-guide.md:83` | hardcoded, and its activity path disagrees with `main/AndroidManifest.xml:31` | stale; note only |
| `on-device-slm-memo.md:782-786` | hardcoded | `slot push-model` takes the package; memo gets an addition, not an edit |
| `BlePrepActivity.kt:144` | extra key, not package-bound | nothing |
| `NavBackStackPhiFreeTest.kt:52` | probe string in a test | nothing, unslotted only |

**Per-install state.** All of it is per package, which is the point of the slots.

| State | Where (VERIFIED) | What a slot needs |
|---|---|---|
| Room v23 SQLCipher DB | `AppDatabase.kt:78,107`; `DatabaseModule.kt:103-106` | own file, created fresh; doctors seeded by callback `:72` |
| SQLCipher and document keys | `DatabasePassphraseProvider.kt:110`, `DocumentEncryptionProvider.kt:132` | own Keystore entries, per UID (INFERRED) |
| Tokens, session, outbox DataStore | `DataStoreAuthTokenStore.kt:29`, `MockAuthSession.kt:28`, `InFlightBatchStore.kt:59` | own; sign in once per slot |
| Saved backend URL | `DevServerConfig.kt:47,54` | own; empty unless broadcast (N-10) |
| Facility owner | `device_data_owner` (N-12) | claimed at first sign-in; per slot |
| `files/slm`, `cacheDir/slm` | `LocalSlmModule.kt:52,65`; memo `:332-336` | **own copy per slot, push once**; stage in `/data/local/tmp` to share the one push |
| `files/documents`, `ailment_audio` | `ConsultationDocumentRepositoryImpl.kt:58`, `AndroidAilmentAudioRecorder.kt:27` | own, empty |
| CDM association | `BlePrepActivity.kt:107-124`, `HubAssociations.kt:30` | **own; the system dialog must be accepted once per slot** with kernelhub2 in range; do it at rehearsal |
| Runtime permissions | `main/AndroidManifest.xml:5,6,12`, `dev/AndroidManifest.xml:21`, `NearbyDevices.kt:18` | own; `RECORD_AUDIO`, `CAMERA`, the NEARBY_DEVICES group including `ACCESS_LOCAL_NETWORK` and `BLUETOOTH_CONNECT`. Grant at rehearsal, with `pm grant` where allowed, and live-check |
| WorkManager | `WorkManagerSyncOutboxScheduler.kt:40,48`; `WorkManagerAssessmentScheduler.kt:44` | own; dev's periodic sync keeps running beside demo with no cross effect (INFERRED) |
| Auto Backup | `main/AndroidManifest.xml:22`, `backup_rules.xml` | Tokens and prefs may be backed up per package. A restore into a fresh slot install is INFERRED possible; live check that a first install starts signed out |
| device_id | N-13 | own per signing key, not a collision because DBs are separate |

Residual risk: a slot differs from unslotted dev in flags and fallback, so unit tests on `testDevDebugUnitTest` do
not cover the demo configuration. A rehearsal is the check for that, and running the suite with `-Psamd.slot=demo`
is expected to go red where tests assert flag-off.

### D-10 Manifest identity: AMEND

Accepted: `${applicationId}.permission.SET_BACKEND_URL` and `${applicationId}.SET_BACKEND_URL`. Unslotted dev resolves
to byte-identical names. Hardcoded sites: `dev/AndroidManifest.xml:14,35,37`, `DevServerReceiver.kt:17,20,49`. Others
are in the D-9 table. `ACTION_SET_BACKEND_URL` is a compile-time const at `:49`, so compare against
`"${context.packageName}.SET_BACKEND_URL"`.

Without this, a second install defining the same permission from another key fails with
`INSTALL_FAILED_DUPLICATE_PERMISSION` (INFERRED). That alone justifies the change.

Amended: "broadcasts are sent with `-p`" is a doc change only. The shell cannot hold a signature permission on the
locked Pixel (`dev/AndroidManifest.xml:8-12`), and no tool sends this broadcast (F-8). So the repoint path does not
exist on the demo device and is not a plan B (D-13). Also, the manifest comment at `:8-10` refers to a dev-connect
broadcast that no longer exists; correct it in this PR.

### D-11 Shared signing keystore: AMEND

Accepted: never the release key; the demo APK comes only from the bundle, installed with `adb install -r`, never
rebuilt on the laptop.

Amendments:

- **The laptop does not need a keystore.** The signature travels in the APK. Sharing a keystore is needed only if both
  machines build the same package. They do not. Skip distributing it until they do.
- **Slot builds use a dedicated `samd-slots` key. Unslotted dev keeps its current key.** Slot packages have no install
  yet, so a new key costs nothing, while re-signing `.dev` triggers the failure below. Alternative if the operator
  wants one key everywhere: the shared key must be a copy of the keystore that signed the current dev install. Which
  one that is needs a read-only check (Q-8).
- **Path delivery.** The brief says local.properties, but samdctl's clean worktree has none. Recommended:
  `~/.gradle/gradle.properties` on the build machine, read by name only (Q-8). Slot set with no key configured fails the
  build; no fallback to the machine debug key, as that is how a second identity appears unnoticed.
- **Back the key up off the root disk.** Losing it has the same consequence as a mismatch.
- `signingConfig` set on the debug build type when a slot is set. A flavor-level config may lose to the debug build
  type's default (INFERRED); the live check is `apksigner verify --print-certs`.

**The `INSTALL_FAILED_UPDATE_INCOMPATIBLE` consequence.** `adb install -r` of a package signed by a different key is
refused. Android has no override. The only way forward is uninstall, which destroys: Room v23 data including unsynced
outbox rows, the SQLCipher and document Keystore keys, tokens, documents, audio, `files/slm` (model re-push), CDM
association and grants. Since this is what happens if you are one key off, samdctl stops with a message naming the two
certificates and the words "do not uninstall", and has no uninstall command. Check `slot status` before each install.

Residual risk: key loss. Bounded by the backup, and a demo install is replaceable at the cost of a rehearsal.

### D-12 Pairing check: AMEND

Accepted: compare `BuildConfig.GIT_SHA` with `/health` `git_sha`, banner on mismatch.

- **Slot builds only, not all dev builds.** On plain dev, app and API commits differ during normal work, so the banner
  would always show and get ignored. Unslotted `GIT_SHA` stays empty, which also spares a BuildConfig rebuild every commit.
- **Also compare stack.** Add `stack` to `HealthResponse` (`schemas/health.py:10-23`) and to `api-contract.md:154-190`,
  additive. Otherwise `adb reverse tcp:8180 tcp:8080` puts a demo app on the dev DB, with `environment` reading `dev` on
  both. Expected stack is baked by slot.
- **Wire compatibility: SHA equality is enough for v1.** It is stricter than compatibility, and it means "the exact pair
  that was rehearsed", which is the demo claim. The monorepo builds both from one commit. No app-only or backend-only
  pairing exception: a hotfix rebuilds both. Real N-1 compatibility is D-17.
- `/health` has no authentication and no Android consumer today (`api-contract.md:161-162`). This adds the first. It lives
  behind a seam with a no-op binding in staging and prod. Banner on Login and Home, unreachable shows nothing (existing
  connectivity surfaces cover it), and it never blocks clinical flow. New strings need operator approval.

Residual risk: SHA equality does not prove DB compatibility beyond what the image's migration head enforces. `samdctl up`'s
migration gate (D-5) covers that.

### D-13 Demo transport: AMEND

Accepted: `adb reverse tcp:8180 tcp:8180`, Windows platform-tools, no firewall rule. The app needs only the API port. Hub
port only under a `127.0.0.1` gateway override (`build.gradle.kts:146-155`); classifier and SLM are server-side and need no
tunnel (N-11, `NetworkModule.kt:169,186`). F-7 confirms loopback is not gated.

Wireless adb (all INFERRED, all live-checked at rehearsal):

- It needs a Wi-Fi network the laptop shares, so at a venue the travel router is mandatory, not optional.
- The port changes on each toggle, so `adb connect` needs a value read off the phone's screen. mDNS discovery may fail on
  venue networks.
- "Always allow" is tied to the network identity. A **cloned SSID has a different BSSID** and may re-prompt. Rehearse on the
  actual router.
- Reverse rules die with the transport. `samdctl slot tunnel --watch` re-applies them on reconnect, and `status` lists them.
- Whether Wi-Fi stays usable while the USB-C port carries a hub link depends on how Android routes it (Q-11).

**Plan B, recommended and rehearsed:** the cable goes to the laptop, and the hub connects over Wi-Fi, which is already the
default assignment `SPO2=wifi:kernelhub1,BP=ble:kernelhub2` (`build.gradle.kts:56`). This needs no new code. A LAN-URL slot is
**not** v1: it needs `0.0.0.0` binding, a Windows firewall rule and the local-network grant, and the broadcast route does not
exist (D-10).

When the tunnel drops: the app is offline-first, so capture continues and sync queues, while assessment needs the backend and
hits D-9's fallback. Say so in the rehearsal notes.

Residual risk: a live demo over a radio link. Plan B removes it for the backend, and leaves only the hub on the air.

### D-14 Windows portability: AMEND

Accepted: named volumes, list-args path handling, Windows localhost port publishing (INFERRED, live check).

`.gitattributes` `eol=lf` for `*.env`, `*.vars`, `*.yml`, `*.yaml`, `*.sh`, `*.py`, `Dockerfile`. Adding these is
safe, since no blob needs renormalizing (F-9). `*.bat` stays out of scope: `gradlew.bat` is LF in the index today and the laptop
does not build. Filed, not fixed. With the bundle carrying everything (D-6), the laptop needs no clone, so this mainly protects
future cloning. Operator-written `demo.secrets.env` is untracked, so `.gitattributes` does not cover it; D-3 catches CRLF damage.

samdctl: `pathlib`, invoke as `python tools/samdctl.py`, adb from `PATH` with a `SAMD_ADB` override so a WSL adb is never picked up.
A second adb server is a conflict; `status` reports the version.

Residual risk: nothing here has run on Windows. The first live check on the laptop is the proof, ahead of any demo.

### D-15 Shared externals: AMEND

- **Classifier: inside the stack, no host port.** Rather than `demo on 8001` (possibly taken, N-11), `backend/stacks/classifier.yml`
  is a compose overlay adding a `classifier` service by digest; `.vars` sets `KERNEL_BASE_URL=http://classifier:<port>`. This means
  no port clash, no `host.docker.internal`, and `down` takes it along. `docker-compose.yml:3-4`'s comment still holds for dev, which
  does not load the overlay. Needs image facts (Q-10).
- **ABDM per stack:** `ABDM_MODE` in `.vars` (`docker-compose.yml:36`), which retires the F-10 local edit and patch. `live` needs
  internet, so a venue without it means stub; stub has the fixed number (D-8, Q-6). `/health` reports it (`health.py:76`).
- **SaMDPi freeze:** discipline, not a control. No `deploy_hub.sh` within 48 h. The SHA in the manifest is a record, not a check.
  Accepted, with that stated.
- **Venue Wi-Fi:** travel router cloned with the lab SSID, so kernelhub1 and the phone join with no reprovisioning (INFERRED). Avahi
  on the router's LAN should work where a venue blocks mDNS, because the router is ours. For a literal-IP fallback, `build.gradle.kts:155`
  allows an override: a slot gateway property fixed at build time, with a static DHCP lease for kernelhub1. kernelhub2 is BLE and
  independent of Wi-Fi.
- **SLM service:** not needed in slots. Read-back is on-device in dev (`FeatureFlags.kt:159-162`), and an empty `SLM_SERVICE_TOKEN` is
  a clean 503 (`main.py:85-94`). Leave it unset in `demo.secrets.env`.

Residual risk: the hubs are the one unpinned component in the pair. Rehearse with the exact hardware.

### D-16 Tags: AMEND (one part stopped as a conflict)

Accepted: `demo/YYYY-MM-DD` annotated tag on the demo commit, and old bundles kept, as configuration identification
and release records under IEC 62304 (clauses 8 and 5.8, INFERRED from memory of the standard). Creating and pushing tags is a git
write needing explicit authorization. The DHF entry follows `.claude/rules/controlled-docs.md`, which is not at origin/master (N-16).

**Conflict, reported as the repo rules require:** "a hotfix branches from the tag and is cherry-picked to master" contradicts
"one branch per functionality change, cut from a freshly fetched origin/master" and merge-commit-only. Proposed resolution, for
the operator to rule (Q-12): fix on a normal branch from origin/master, merge by PR; then a `demo-hotfix/<date>` branch from the
tag takes a cherry-pick **of that merged fix**, and is never merged. Master is never written by cherry-pick. This needs an explicit
exemption for the demo branch.

Residual risk: bundle retention consumes disk. Section 4.

### D-17 Later phases: ACCEPT (outline only)

- Private GHCR images, pushed from CI on tags only. Then `samdctl build` becomes optional, and the digest replaces the
  label check.
- N-1 before pilot: `X-App-Version`, minimum-supported-version refusal, expand and contract migrations, old-contract tests. Not needed
  while SHA equality holds, and it must exist before two field versions can coexist.
- Paired reset (D-8), rekey tool with KMS (D-3).

## 3. Hazards on stage and in data

Proof types refer to section 6. R is the PR holding the control.

| # | Hazard | Control | Proof |
|---|---|---|---|
| DH-1 | Wrong stack: demo app on dev DB | Own project, port, volume (D-1); `/health` `stack` compared by the slot banner and samdctl (D-12) | Mutation: compare removed, test red. Live: reverse 8180 to 8080, banner shows. R: E1b, E3b |
| DH-2 | Stale DB copy: forced logout, permanent CONFLICT rows | `moved` ledger row enforced by the API (D-7) | Mutation: skip the moved check, test red on the persisted row. Live: plain `docker compose -p samd-demo up` after export, API exits. R: E1a, E2 |
| DH-3 | Key mismatch: lookups silently miss, PHI unreadable | Canary with sampled enrol (D-3); `STACK_NAME` secret refusal (D-2) | Mutation rows per key. Live: wrong `BLIND_INDEX_KEY`, exit nonzero, one line, row unchanged. R: E1a |
| DH-4 | Signing mismatch: uninstall wipes data | Dedicated slot key; no uninstall in samdctl; stop message (D-11) | Unit: no uninstall argv. Live: wrong-key APK refused, data intact. R: E3a |
| DH-5 | Migration reaches the demo DB | Pinned image, own volume, migration gate (D-1, D-5); older image fails closed on unknown revision (INFERRED) | Unit on the gate, mutation. Live: newer tag refused without `--migrate`. R: E1b |
| DH-6 | Disk full during demo or import | Preflight, work dir off root, retention (section 4) | Unit: preflight refuses. Live: `samdctl status` shows free space on host and in the VM. R: E2 |
| DH-7 | Pinned tag silently rebuilt from the working tree | `--no-build --pull never`; label and `/health` comparison (D-1, D-4) | Mutation: drop the flags, argv test red. Live: remove the tag, `up` must fail, no image appears. R: E1b |
| DH-8 | Plausible mock result on stage | Slot bakes fallback (D-9, Q-3) | Live: stop classifier, assess, UNAVAILABLE card shown. R: E3b |
| DH-9 | Wrong engine or unmounted data drive: fresh empty DB. fstab has `nofail` (F-11), so a missing drive boots; whether Docker Desktop then creates a new disk on root is INFERRED | Context assert; volume must exist or `--init` (D-1, D-5) | Unit: both refuse. Live: wrong context refuses. Operator checks `mountpoint` after any boot where volumes look empty. R: E1b |
| DH-10 | Persona ABHA collisions burn the demo | Per-selection number under the profile (D-8, Q-4) | Unit: two selections differ; 14 digits. Live: register one persona twice, both sync. R: E4 |
| DH-11 | APK without ASR weights | Gradle SHA check against the SBOM (D-9) | Mutation: remove a weight file, build fails. R: E3a |
| DH-12 | Older APK over newer Room: crash, no way back | `versionCode` from commit count (D-9) | Live: install older, `INSTALL_FAILED_VERSION_DOWNGRADE`. R: E3a |
| DH-13 | Demo flags reach staging or prod | `DEMO_PROFILE` is a literal false there; property read only in the dev block | `testStaging` and `testProd` assert false; mutation: set true, red. R: E3b |
| DH-14 | Secrets leak: committed, in image, in bundle, in logs | `.gitignore`, `.dockerignore` (D-2); no raw `compose config`; bundle excludes secrets, APK blank Gemini (D-6) | `git check-ignore` with mutation; test scans bundle file list. Live: `docker history` shows no key. R: E1b, E2 |
| DH-15 | Shell variable silently alters a stack | Scrubbed child env (D-5) | Unit: argv and env recorded with hostile env set. R: E1b |
| DH-16 | Refresh token expires between rehearsal and demo: login needed on stage | Rehearse within 7 days of the demo (`config.py:62`), and have PINs at hand | Checklist item. Access TTL 1 h means a refresh will happen at some point. R: none |
| DH-17 | Tunnel drops mid-demo | `--watch`; Plan B cable (D-13) | Live: toggle Wi-Fi during a flow, tunnel returns. R: E2 |

DH-1 through DH-17 are design-local names, unrelated to the numbered H- rows in `docs/quality/risk-management-file.md`.
Whether the canary or the demo-flag path needs a PROPOSED risk row is Q-13.

## 4. Disk and resources

**Main machine** (F-11, `df` today):

| Disk | Free | Holds | Rule |
|---|---|---|---|
| Root nvme0n1p2 | 8.4G (97 percent used) | OS, home | Nothing from this design. samdctl refuses a work dir on it |
| extra-ssd sda1 | 578G | Worktrees, bundles, `SAMD_WORK_DIR` | `/media/sandesh/extra-ssd/samd-demo/` |
| oneTB_SSD nvme1n1p1 | 809G | Docker.raw, 32G of **64G cap** | Cap is the real limit, not the 809G |

Docker's limit: free space inside the VM, not on the drive. `docker system df` plus `df` inside a container is the check. Raising the
64G cap is an operator setting if needed. Every kept SHA adds its top layers; base layers are shared (INFERRED). Plan: keep the current demo
image, the newest preview and dev, and let bundles on extra-ssd hold history. `samdctl gc` lists candidates, prints the remove commands, and
removes nothing itself.

Any doc naming the Docker.raw path must quote it. samdctl never needs it.

Gradle: `~/.gradle` location is unknown to this memo (Q-16). If it is on root, slot builds add little beyond dev builds, as dependencies are
shared, and the build dir stays in the worktree on extra-ssd.

**Bundle size** (estimate, INFERRED except the ASR figure):

| Part | Size |
|---|---|
| APK | at least 661 MB, `docs/sbom/model-soup-2026-09-02-v1.0.json:174` (VERIFIED), stored and incompressible |
| API image, gzipped | a few hundred MB |
| postgres:16-alpine, gzipped | about 100 MB |
| Classifier image, gzipped | unknown, possibly over 1 GB (Q-10) |
| Dump | small |
| **Total** | **about 2 to 4 GB; measured by first export** |

**Laptop:** Docker Desktop on WSL2 keeps its disk on C: by default, and the VHDX grows without shrinking on its own (INFERRED). Preflight before
import: three times the bundle size free in the work dir, and 10 GB free inside the VM (Q-15). The bundle goes on the laptop's largest disk.

**Pixel:** each slot holds an APK of at least 661 MB plus its own SLM model. The E2B artifact's size is not stated in the on-device memo, only
3.3 GB for a TPU variant (`on-device-slm-memo.md:257`). Budget about 4 to 5 GB per slot, and two extra slots should be checked against free space
with `adb shell df /data` (Q-11).

**CPU and memory:** running dev and demo together doubles Postgres and uvicorn. Low idle cost (INFERRED). Check with the classifier loaded, since
that container is the largest, and Docker Desktop's memory cap applies (Q-15).

## 5. PR split and order

Your suggested four, with E1 and E3 each split because they mix risk classes (CLAUDE.md: highest class wins, or split). One branch each, from
fresh origin/master. All are Linux-buildable; Windows proof is a live check.

| Order | PR | Contents | Risk | Model, effort | Review |
|---|---|---|---|---|---|
| 1 | **E1a** canary | Ledger table, 0011, lifespan check, `stack_ledger` script, `STACK_NAME`, secret refusal, D-4, `.env.example` | R2: migration, new guard, failure path | Opus, high; `edge-case-hunter` after | yes |
| 2 | **E1b** stacks | Compose params, `stacks/*.vars`, ignore rules, `.gitattributes`, samdctl build, up, down, status, classifier overlay | R1, with R2 in the migration gate and `--no-build` | Opus, high, because of the gate and DH-7; a Sonnet split would have too small a part left | yes |
| 3 | **E2** bundle | Export, import, reclaim, moved marker writes, preflight, tunnel, gc | R2: failure and rollback on real data, single-home | Opus, high; `edge-case-hunter` after | yes |
| 4 | **E3a** slots | Gradle slot, suffix, res, clean-worktree build, ASR check, versionCode, signing, D-10, install, push-model | R1 module, with R2 in signing identity | Opus, high | yes |
| 5 | **E3b** profile and pairing | `DEMO_PROFILE`, fallback bake, tests, `/health` stack, banner seam | **R3 if any H-15 flag joins** (safety-control reversal): Opus, xhigh. Otherwise R2 for flags and R1 for banner: Opus, high | per Q-2 | yes |
| 6 | **E4** seed | Seed script, per-selection ABHA, commit demo personas, one facility | R2: touches a uniqueness rule that sync conflict logic cites | Opus, high | yes |

Order reasons: the canary first, so no stack ever holds an unguarded DB. E2 needs E1b. E3a is independent of E2 and could run in parallel if demo
pressure demands. If a demo arrives early, E1a, E1b and E3a alone end the freeze, with no laptop move and no bundle (Q-20).

Baseline to compare against: backend 523 passed with 1 known failure, `test_config.py::test_live_abdm_requires_credentials`; unit suites dev 1279,
staging 944, prod 944 (`PROGRESS.md`, last section, at `f65ff8d`; re-baseline at branch cut).

`graphify update .` after each build phase. After E1a and E1b merges the operator runs `docker compose build --no-cache api` from `backend/`.

## 6. Test plan

Mutation method for all rows: snapshot the file, break the property, show RED at an assertion, restore, confirm the sha256 is identical, show GREEN.
An empty results directory counts as RED, not pass. Backend commands force the test env on the command line. Every test verifying a write asserts the
persisted row, not only a return value (CLAUDE.md, Backend conventions).

Live checks are listed for the operator to run. Nothing below has been run.

### PR-E1a

Unit, `tests/test_stack_ledger.py`, calling the check directly plus one test through `app.router.lifespan_context` (no new dependency), since tests skip the lifespan (N-2):

| Test | Mutation that must turn it RED |
|---|---|
| First boot on empty DB enrols; row read back by SQL | Skip the insert |
| Wrong PHI key refuses, names the key, no row added | Remove the PHI compare |
| **Wrong BLIND_INDEX_KEY refuses at an assertion on the failure name** | **Remove the blind compare** |
| Wrong CASE_TOKEN_KEY refuses | Remove the case compare |
| Wrong JWT key refuses | Remove the jwt compare |
| Enrol over existing patients under other keys refuses, no row | Skip sampling |
| Latest `moved` refuses; then `reclaimed` boots | Skip the moved check |
| UPDATE and DELETE on the table raise | Drop a trigger from migration and conftest |
| Log line carries key name, never the value | Log the value |
| `STACK_NAME=demo` plus placeholder raises; `dev` plus placeholder passes | Remove the branch in `validate_secrets` |
| Rotate on PHI or BLIND refuses; on JWT appends `rotated`, old row intact | Allow PHI |
| `SAMD_BUILD_SHA` sets `git_sha`; a `GIT_SHA` env does not | Restore old field name |

Also `alembic check`, `alembic upgrade head` then `downgrade -1` then `upgrade head`, `ruff check .`, `ruff format --check .`, `mypy app`, `pytest -q`, then `ruff check .` and `pytest -q` from `backend/abdm-adapter`.

Live, operator (on a throwaway project, never the dev volume, until the dev step):

```bash
cd backend
docker compose -p samd-canary-test --env-file stacks/preview.vars up -d
docker compose -p samd-canary-test logs api | grep -c key_canary   # expect 0 on a clean enrol
# stop api; edit only BLIND_INDEX_KEY in that stack's secrets; start api
docker compose -p samd-canary-test logs api | grep key_canary_mismatch   # one line naming the key
docker inspect -f '{{.State.ExitCode}}' samd-canary-test-api-1           # expect nonzero
docker compose -p samd-canary-test exec db psql -U samd -d samd -c 'select count(*) from stack_ledger' # unchanged
# restore key; api boots; then remove the throwaway volume by hand
```

Dev step, a real first enrolment on the live volume (Q-14): `python -m app.scripts.stack_ledger check --dry-run` first via `docker compose run --rm --no-deps api`, which reports sampling results and writes nothing.

### PR-E1b

Unit, `tools/test_samdctl.py`, stdlib `unittest` with a fake runner recording argv and env. Runs with no docker, in CI on Ubuntu and `windows-latest`:

| Assertion | Mutation |
|---|---|
| Pinned `up` has `-p samd-demo --no-build --pull never` | Drop a flag |
| No path produces `down -v`, `volume rm`, `uninstall`, `pm clear` | Add one |
| Hostile env (`API_TAG`, `ABDM_MODE`) is absent from the child env | Pass `os.environ` |
| Missing volume refuses without `--init` | Remove the check |
| Wrong docker context refuses | Remove the check |
| Migration head differs: refuses without `--migrate` | Remove the gate |
| `SAMD_WORK_DIR` on the root device refuses (Linux) | Remove the check |
| Tag, label, `/health` disagreement exits nonzero | Skip the label |

`git check-ignore backend/stacks/demo.secrets.env` in CI; mutation: remove the `.gitignore` line, red.

Live, operator. Dev invariance, printing no env values:

```bash
cd backend
docker compose config --format json | python3 -c 'import json,sys; c=json.load(sys.stdin); print(c["name"], c["services"]["api"]["ports"], c["services"]["db"]["ports"], c["volumes"])'
# expect project backend, 8080, 5432, volume backend_samd_pgdata
docker compose up -d && docker volume ls --format '{{.Name}}' | grep pgdata   # no new dev volume
python3 ../tools/samdctl.py up demo --init    # both up together
curl -s localhost:8080/health; curl -s localhost:8180/health
docker image rm samd-api:<tag>; python3 ../tools/samdctl.py up demo   # must FAIL, no rebuild
```

### PR-E2

Unit, fake runner plus temp files: flip one byte in each artifact, import refuses **before any docker call**; missing artifact refuses; export failing at each of the five steps leaves no `moved` row (fake), or the row present only after step 4; import over an existing volume refuses; preflight refuses on low space; bundle file list contains no `*.secrets.env`.

Live, operator: a full round trip. Export on Linux; `up demo` then refuses; on the Windows laptop with **networking disabled**: import, `up demo`, `curl.exe localhost:8180/health` shows the same SHA; device connects, signs in with no forced logout, unsynced row syncs at the expected `server_version`, and no CONFLICT row appears. Reverse the trip, including the printed volume removal by the operator. Also confirm append-only triggers survive: an UPDATE on `audit_events` in the restored DB fails.

### PR-E3a

Unit: `testDevDebugUnitTest` unchanged and unslotted; `Staging` and `Prod` too. A check that the unslotted merged manifest has the same permission names as today: mutation, change the placeholder, test red. Gradle: `-Psamd.slot=bogus` fails; dirty tree demo fails; missing or altered ASR file fails; slot with no key fails. Each shown by the failure message.

Live, operator (phone free first, Pixel only, `-s 67041FDKX004ZY`):

```bash
adb -s 67041FDKX004ZY shell dumpsys package com.example.samdapp.dev | grep -E 'versionCode|lastUpdateTime'   # before
adb -s 67041FDKX004ZY install -r <demo.apk>     # dev still opens with its data
adb -s 67041FDKX004ZY shell pm list packages | grep samdapp    # both
apksigner verify --print-certs <demo.apk>                       # slot key, not debug
adb -s 67041FDKX004ZY install -r <older-demo.apk>     # INSTALL_FAILED_VERSION_DOWNGRADE
adb -s 67041FDKX004ZY install -r <other-key-demo.apk> # INSTALL_FAILED_UPDATE_INCOMPATIBLE, data intact
```

Then: first start signed out, sign-in, CDM dialog for kernelhub2, permissions, model push by `run-as` and SHA, voice field works, read-back works. G-B11 checks on staging and prod unchanged (live check 9 of the PR-B addendum).

### PR-E3b

Unit: `testStaging` and `testProd` assert `DEMO_PROFILE` false (mutation: true, red). Banner seam: same SHA and stack shows nothing; either differs shows banner; unreachable shows nothing. No-op binding in staging and prod, per the graph test pattern. Unslotted builds keep `TranscribeAudioUseCaseTest.kt:26-29` green. R3 path if H-15 flags are included: operator-defined additional proofs from the rulings.

Live, operator: stop the classifier, assess, UNAVAILABLE shown (if Q-3 is `none`). `adb reverse tcp:8180 tcp:8080`, banner shows.

### PR-E4

Unit: selection generates distinct 14-digit numbers with the reserved prefix, only under `DEMO_PROFILE`; seed script rerun is idempotent, with row counts read back; seeded rows sync-compatible. Mutation: fixed number returned, test red.

Live, operator: register one persona twice, both reach synced state with no CONFLICT. Sign in to every seeded role in the one facility.

### Pre-demo rehearsal checklist (each demo)

Bundle verified on the demo machine; `status` clean; wireless and Plan B both tried on the actual router; hubs measured; classifier down shows the expected card; sign-in within 7 days of the demo; `df` on host, VM and phone; SaMDPi freeze in force.

## 7. Operator questions

Read-only commands are for you to run; I ran none of them.

**Q-1.** `docs/adr/`, `docs/agents/engineering-standards.md`, `.claude/rules/` and `.claude/hooks/guard.py` do not exist at origin/master (N-16), but the main checkout's CLAUDE.md, modified and uncommitted, cites them. Are they due to land on master before E1a? I could not check this design against any ADR. Is there an ADR it may contradict?

**Q-2.** Which five voice flags are true in your demo setup? Is `VOICE_FIELD_CHIEF_COMPLAINT_ENABLED` or `VOICE_AUDIO_ATTACHMENT_ENABLED` among them? `FeatureFlags.kt:49-51,81-82` say those are not flag changes, so committing them under a demo profile is a safety-control reversal on your ruling (R3). Recommended: leave both out.

**Q-3.** Demo slot when the classifier fails: `none` (honest UNAVAILABLE card, recommended) or `mock` (plausible result)?

**Q-4.** Persona ABHA: per-selection generated numbers under the profile (recommended), or another approach? Which reserved prefix?

**Q-5.** D-8 says "checksum-valid", and no checksum rule exists in the repo (N-7). Which rule did you mean, and where is its source? If none, drop the word.

**Q-6.** ABDM at demos: stub, live or per venue? Live needs internet. Stub's fixed number means stub enrolment works once per DB; is a demo of enrolment planned? Should the stub vary its number in non-prod?

**Q-7.** Accept `backend/stacks/<name>.vars` in place of `.env`, so agents can read committed non-secret files under your hard rules?

**Q-8.** Which key signs the current dev install? `adb -s 67041FDKX004ZY shell dumpsys package com.example.samdapp.dev | grep -iA3 signatures`. Accept a dedicated slot key with unslotted dev unchanged? Key path in `~/.gradle/gradle.properties`, since clean worktrees have no local.properties? You create the key and set the file; I never read it.

**Q-9.** Is the Gemini brand lookup needed in the demo? Blank (recommended) keeps the bundle free of that key, and the lookup returns null.

**Q-10.** Classifier: image name and digest, listen port, and how it is built: `docker ps --filter name=samd-classifier --format '{{.Image}} {{.Ports}}'` and `docker image inspect <image> --format '{{.Id}} {{.Size}}'`. Is 8001 bound today? Accept the compose overlay with no host port?

**Q-11.** The wired-transport plan is not in the four hub and network memos I searched (`samdpi-two-hub-memo.md`, `samdpi-pr-b-client-memo.md`, `local-network-permission-memo.md`, `pi-relay-reconciliation-memo.md`). How does kernelhub1 attach over USB-C (tether, USB Ethernet), and what address does the phone use? Is Plan B (cable to laptop, hub over Wi-Fi) acceptable on stage? Phone free space: `adb -s 67041FDKX004ZY shell df /data`.

**Q-12.** Hotfix: accept the exemption in D-16, fix via normal PR and then a never-merged `demo-hotfix/` branch from the tag taking a cherry-pick of the merged fix?

**Q-13.** Do the canary and the demo profile get PROPOSED rows in `docs/quality/risk-management-file.md` and a DHF entry? Does the canary apply in staging and prod from day one (recommended, same image)?

**Q-14.** Could the live dev DB hold rows written under older keys? The dry run in section 6 will tell. If it reports a conflict, what do you want done with that data? The rule stays fail closed.

**Q-15.** Laptop: free space on C: and the largest disk, Docker Desktop and Compose versions, Python version, RAM, and whether the context reads `desktop-linux`: `docker context show`, `docker compose version`, `py --version`, `wsl -l -v`.

**Q-16.** Where is your Gradle user home? This is in the host rules, which I could not read here (N-16).

**Q-17.** Commit the demo personas to master? They live in `src/main` and are not gated in `RegisterScreen.kt:134-142` (no flavor check in that file, by grep; a gate at a caller is not excluded, INFERRED). Staging and prod reachability looks worth filing separately. Should the persona picker move behind the dev flavor or the profile?

**Q-18.** Keep the preview stack and slot in v1? It is the same mechanism at near-zero extra code, and it is what lets an unmerged branch run. Recommended yes.

**Q-19.** Demo facility id and name, and which roles to seed. You run the seed, and PINs stay in your terminal.

**Q-20.** When is the next demo? If soon, E1a, E1b and E3a end the freeze on the main machine, and E2 and E4 follow.

## 8. Gate rulings, 2026-11-05

Part A gate review. Sections 0 to 7 are not edited by this section, apart from the title and Branch field the
operator ordered changed. Each ruling is verbatim, followed by one line saying what it changes.

### 8.1 Rulings, verbatim

- D-1 to D-16: ACCEPT the memo's AMEND text as written, including --no-build --pull never, .vars files, loopback binds, refuse a missing volume without --init, STACK_NAME, the stack_ledger canary with sampled enrolment, SAMD_BUILD_SHA plus image label, samdctl's never-do list, gzip bundles with postgres image plus compose plus samdctl, the moved row in the DB, per-selection persona ABHA under DEMO_PROFILE, slot builds from a clean worktree with ASR SHA check, versionCode from commit count for slots, the dedicated samd-slots signing key with unslotted dev unchanged, banner on slot builds comparing SHA and stack, Plan B cable-to-laptop, the classifier overlay with no host port, dev-connect.sh unchanged. D-17 ACCEPT; PART B below supersedes its outline.
  - **Changes:** section 2 verdicts D-1 to D-17 become rulings, with no text change. Section 5 PRs E1a to E4 are approved in scope. Section 6 stands. D-17's outline yields to section 9.
- Q-2: DEMO_PROFILE excludes VOICE_FIELD_CHIEF_COMPLAINT_ENABLED and VOICE_AUDIO_ATTACHMENT_ENABLED. The operator will list his five demo flags separately; until then E3b plans for the other four candidate flags only, so E3b stays R2, not R3.
  - **Changes:** D-9's flag list narrows to `VOICE_FIELD_IMPACT_ENABLED`, `VOICE_FIELD_AGGRAVATING_ENABLED`, `VOICE_FIELD_RELIEVING_ENABLED`, `VOICE_FIELD_RELEVANT_HISTORY_ENABLED` (`FeatureFlags.kt:104,120,126,132`). Section 5's E3b row reads R2 and R1 at Opus high, and its xhigh branch is dead. Section 6's PR-E3b "R3 path" sentence no longer applies. DH-13 gains a test: both excluded flags stay false under `-Psamd.slot=demo`.
- Q-3: slot DEV_KERNEL_FALLBACK = none (honest UNAVAILABLE card).
  - **Changes:** D-9's fallback bullet is settled at `none`. DH-8 and section 6's PR-E3b live check apply as written.
- Q-5: drop "checksum-valid"; only ^[0-9]{14}$ plus uniqueness. Reserved prefix is pending (Q-4); propose one that cannot collide with issued ABHA numbers and mark the collision claim INFERRED.
  - **Changes:** D-8's checksum paragraph is closed. Section 6's PR-E4 tests assert the regex and distinct numbers only. **Proposed prefix: `00990`**, then a 9-digit time component, 14 digits in all. Every ABHA-shaped number in this repo starts with 91 (`DemoPatientProfile.kt:140`, `abdm_adapter/client.py:303`), and the docs' sample is a placeholder (`healthIdNumber` `1111-1111-1111-11`, `abha api docs/`). The claim that ABDM never issues a number starting `00` is INFERRED from those samples only, and no issuance rule is in the repo. Q-4 stays open for the operator.
- Q-7: accept backend/stacks/<name>.vars.
  - **Changes:** none beyond D-1 and D-14 as written. E1b proceeds with `.vars`.
- Q-9: slots build with a blank GEMINI_API_KEY.
  - **Changes:** D-6's Gemini residual risk closes for slot bundles. D-9's clean-worktree bullet is settled. Section 6's PR-E3a gains one check: the slot's `BuildConfig.GEMINI_API_KEY` is blank (mutation: inject a value, test red).
- Q-11: no wired-transport memo exists because that design was never started (tasklist item, Phase 0 pending). Plan B is accepted for stage.
  - **Changes:** D-13's USB-C and Wi-Fi coexistence bullet leaves v1. DH-17's fallback is the accepted stage plan. Phone free space remains a rehearsal check (section 4).
- Q-12: accept the hotfix exemption exactly as D-16 proposes (normal PR to master first, then a never-merged demo-hotfix/<date> branch from the tag cherry-picking the merged fix).
  - **Changes:** D-16's stopped conflict is resolved for `demo-hotfix/<date>` only. Release branches are a separate question (Q-26, section 9).
- Q-13: canary and demo profile get PROPOSED rows for the next sign-off batch; the canary runs in staging and prod from day one.
  - **Changes:** section 3 and E1a gain a PROPOSED row in `docs/quality/risk-management-file.md`, and E3b gains one, with no controlled-doc edit outside that batch. E1a has no environment exemption, which matches D-3.
- Q-16: GRADLE_USER_HOME is /media/sandesh/extra-ssd/.gradle (~/.gradle is a symlink to it); ANDROID_HOME is /media/sandesh/extra-ssd/Android/Sdk. The slot key path therefore goes in /media/sandesh/extra-ssd/.gradle/gradle.properties, which the operator writes and nobody else reads. The project's own gradle.properties is never edited.
  - **Changes:** D-11's path bullet is settled. Section 4's Gradle paragraph is answered: Gradle's cache is off the root disk. E3a reads the key path by property name through Gradle, and no tool or agent opens that file.
- Q-17: commit the demo personas, gated behind DEMO_PROFILE. File separately, HIGH: the persona picker in src/main is not flavor-gated at RegisterScreen.kt:134-142; establish whether staging and prod builds can reach it (fake patients in a production build).
  - **Changes:** E4 commits the personas behind `DEMO_PROFILE`. **Filed, HIGH, outside this design:** picker reachability in staging and prod. It is unresolved here and no PR below closes it. Section 6's PR-E4 gains a `testProd` assertion once that item defines the seam.
- Q-18: keep preview stack and slot in v1.
  - **Changes:** D-1 and D-9 keep preview. Section 9.4 depends on it as the local N-1 test.
- Pending from the operator, do not block on them: Q-1, the actual five-flag list for Q-2, Q-4 prefix, Q-6, Q-8, Q-10, Q-14, Q-15, Q-19, Q-20.
  - **Changes:** none. Each remains an open dependency of the PR that needs it: Q-8 and Q-15 for E3a and E2, Q-10 for E1b's overlay, Q-14 for E1a's dev step, Q-19 for E4.
- Add hazard DH-18: any worktree's backend/ directory resolves to Compose project "backend", so a plain docker compose up from a feature worktree runs that branch's migrations on the live dev volume (memo D-1, INFERRED). Controls for v1: documented in backend/README.md, samdctl preview as the supported route. PART B must propose a technical guard.
  - **Changes:** hazard row added in 8.2. The technical guard is proposed under D-20 in section 9.2, in PR U1.
- Add a note under D-9: the preview slot's commit-count versionCode can refuse a branch with fewer commits (INSTALL_FAILED_VERSION_DOWNGRADE); that refusal is correct; the remedy is the operator clearing the preview slot's data, never samdctl.
  - **Changes:** note added in 8.3. Section 6's PR-E3a live check for a downgrade also covers preview.

### 8.2 Hazard added to section 3

| # | Hazard | Control | Proof |
|---|---|---|---|
| DH-18 | Any worktree's `backend/` directory resolves to Compose project `backend`, so a plain `docker compose up` from a feature worktree runs that branch's migrations on the live dev volume (D-1, INFERRED) | v1: documented in `backend/README.md`; `samdctl` preview is the supported route. Technical guard: D-20 in section 9.2 | Docs only for v1. Guard: mutation in section 9.6, PR U1. R: E1b for the docs, U1 for the guard |

### 8.3 Note added under D-9

The preview slot's commit-count `versionCode` can refuse a branch with fewer commits
(`INSTALL_FAILED_VERSION_DOWNGRADE`). That refusal is correct, because Room cannot downgrade. The remedy is the operator
clearing the preview slot's data, never samdctl, which D-5 bars from `pm clear` and uninstall.

## 9. PART B

Production update safety. Status: PROPOSED, awaiting operator gate review. Base still origin/master 7e98cd8. Goal: any
update to the backend, the database, the app, a model or a config must not break the versions already running, including
field phones that stay offline for days and update late.

Marking as in the header. Nothing here was run: no docker, adb, gradle or psql. Superseded by this section: the A-1 note's
"PART B is not written yet". Step 0's rename was already done under A-1, so only the title and Branch edits were made.

### 9.0 Findings that change the proposal

Read these first.

1. **F-12's consequence is wrong for the path that matters most, and the truth is worse than a 422.** `records` is
   `list[dict[str, Any]]` by design (`schemas/sync.py:3-7,40`), so `extra="forbid"` stops at the envelope. An unknown
   field inside a record is a per-record `rejected`, `TERMINAL` (`services/sync.py:425-440`), and the app maps that to
   FAILED (`SyncAckMapping.kt:55-57`), which no drain re-collects. The code comment states this: a device shipped ahead
   of the backend "would also land here and be destroyed" (`:429-433`). A 422 waits and retries. This does not.
2. **D-22's choice rests on a queue that does not exist.** There is no stored outbox payload. `collectPendingRecords`
   reads live domain rows and maps them at drain time (`RoomSyncOutboxRepository.kt:66-93`), and the in-flight store
   keeps only ids and revisions (`SyncOutboxDrainer.kt:202-207`). So a row queued under N-1 and pushed after an update is
   already serialized by app N.
3. **F-17 is wrong, and so is this memo's N-13.** The token-bound `device_id` is a random UUID in DataStore
   (`DataStoreAuthTokenStore.kt:85-91`), deliberately not ANDROID_ID (`AuthTokenStore.kt:30-31`, `models/user.py:89-91`).
   ANDROID_ID reaches only report metadata (`GenerateKernelReportUseCase.kt:331,408`). The signing identity decides
   whether an update installs, and so whether unsynced data survives. It does not decide device identity.
4. **An OpenAPI snapshot cannot see the main wire contract.** Records are untyped, and the accepted fields are derived from
   SQLAlchemy columns (`services/sync.py:425-426`). `/api/v1/evaluate` is a verbatim passthrough
   (`api-contract.md:54-58`). A snapshot guard alone would report green while the sync contract breaks, which is this
   project's characteristic bug (D-23).
5. **A backend column change is a wire change.** Because of finding 4, dropping or renaming a synced column TERMINAL-rejects
   every record from an app still sending it, and a new NOT NULL column does the same through sqlstate 23502
   (`services/sync.py:163-169`). D-19's expand and contract must cover the wire, not only the running code.
6. **The 24-hour idempotency TTL must not be implemented.** `sync_log.batch_id` cascades from `sync_batches`
   (`models/sync.py:99-100`), and `uq_sync_log_audit_log_record_id` is the real audit dedup (`models/sync.py:83-95`).
   Purging a batch deletes that dedup and lets a re-push duplicate device audit rows, against REQ-AUD-02. It also breaks
   crash-resume for any phone offline past 24 hours (`SyncOutboxDrainer.kt:27-34`) (D-24).
7. **D-28's OTA contradicts a controlled-document control.** `soup-validation-record.md:33` (S-3) says the component set
   changes only by shipping an app release, precisely to avoid engaging the ACP gap, and `qms-overview.md:45` lists the ACP
   as TODO. No OTA code exists (grep for `OTA` in `backend/core/app` and `app/src/main`: none). Reported as a conflict (D-28, Q-30).
8. **The release workflow cannot produce a usable field build today.** `android-release.yml:38-44` assembles an APK, not a
   bundle; it has no step to fetch the gitignored ASR weights (`.gitignore:58`); `versionCode` is fixed at 1
   (`build.gradle.kts:94`); and no `v*` tag has ever been cut (`git tag`: two archive tags only) (D-25).
9. **The first pilot build is the last chance to add identity.** A phone without the version header can never be refused,
   counted or told to update, and only an update can fix that. Three things are not retrofittable: the header, the
   signing identity and the update channel (9.5).
10. **The contract says the opposite of D-21.** `api-contract.md:33`: "There is no version header, no content negotiation."
    It also promises `/api/v2/` for breaking changes. Reported as a conflict with a proposed amendment (D-21, Q-27).
11. **`/health` is one endpoint doing two jobs**, and the app role in `backend/README.md:141-145` is granted point in time,
    so a new table is invisible to `samd_app` in staging and prod while every test passes as owner (INFERRED grant behavior,
    D-20, PH-9).
12. **A production restore loses data silently.** Phones do not re-push SYNCED rows and no pull route exists
    (`api/v1/sync.py:29` is the only route). D-30's restore drill would pass while this stays true (PH-28, Q-34).

### 9.1 Facts F-12 to F-17

| Id | Verdict | Evidence and nuance |
|---|---|---|
| F-12 | VERIFIED text, CHANGED consequence | `schemas/common.py:19-27`; `schemas/kernel.py:7`, `schemas/patient.py:97`. A 422 holds for the envelope and typed routes, mapped to `SAMD-PAT-3003` whatever the route (`main.py:200-209`, `errors.py:53,152`). Records are the exception: `schemas/sync.py:3-7,40`, then TERMINAL (`services/sync.py:425-440`). Finding 1. |
| F-13 | VERIFIED, two nuances | No per-request header in `app/src/main` (grep). `schemas/auth.py:28`, `api/v1/auth.py:111`, `config.py:42`. Nuance 1: **the app never sends `app_version`**: `LoginRequestDto` has only worker_id, pin, device_id (`AuthDtos.kt:7-11`), so `devices.app_version` (`models/user.py:103`, `services/auth.py:305-306`) is never set by this client (INFERRED from the only client DTO). Nuance 2: `envelope_version` does exist, for the hub reading envelope only (`PiGatewayApi.kt:66`, `HubEnvelope.kt:57`), and `api_version` is a response meta constant (`schemas/common.py:16,33`). No sync protocol version anywhere. |
| F-14 | VERIFIED | `backend/docker-compose.yml:48-50`, `Dockerfile:59`. |
| F-15 | VERIFIED, with changes per item, below | |
| F-16 | VERIFIED | `build.gradle.kts:94-95`. The Play requirement is INFERRED. |
| F-17 | CHANGED | See finding 3. `AUTH_DEVICE_MISMATCH` is `errors.py:36`, raised at `services/auth.py:340-341`, and compares the UUID. |

F-15 items:

| Rule | Verdict | Evidence |
|---|---|---|
| Ship together | OPERATOR | Not written in the repo: grep of `docs/`, `backend/`, `CLAUDE.md` for "ship together", "lockstep" finds no such rule. Dev practice, per the brief. |
| Room forward-only | VERIFIED | No destructive fallback (N-8); `DatabaseModule.kt:105` registers migrations. |
| Audit insert-only, hash-chained | VERIFIED | Triggers `0001_...py:185-194`, chain `services/audit.py:46-71`, per-facility lock `:115-117`, `verify_chain` `:169`. |
| Idempotency no TTL vs contract's 24 h | VERIFIED | `sync-resync-fix-memo.md:176,284`; `api-contract.md:140,1310`; `models/sync.py:48`. The contract's `§7.1` reference at `:141` is stale: section 6.1 holds the text. |
| Classifier carries `model_sha256` | CHANGED | Only `/v1/assess`. `/evaluate` has no `model_metadata` block (`services/kernel.py:334-345`). The proxy copies it and cannot verify it (`0010_...py:15-16`). |
| CDSCO ACP to be incorporated | VERIFIED | `docs/Guidance document on Medical Device Software under MDR-2017.md` (below, `Guidance...md`) `:1790-1824`; TODO at `qms-overview.md:45`. Note `:2330-2333`: a notification or request goes to the authority for any major or minor change, "including software version update". Q-32. |
| Room tests from v12 upward | CHANGED | Stepwise tests are 12To13 to 22To23, and `MigrationTest.kt:35,53,80,112` also runs full chains, up to 1 to 23. Android CI runs none of them (`android-ci.yml`, grep), so they run only on the Pixel by hand. |

**Corrections to Part A, additions only.** Superseded by finding 3: N-13's "a key change also changes the server-side device
identity", and the `device_id` row in D-9's per-install table ("own per signing key"). A slot's bound `device_id` is its own
because DataStore is per package. D-3's "No rekey tool exists" and D-17's outline stand, extended by D-27 and section 9.

### 9.2 Rulings D-18 to D-30

Recommendations for the gate. The operator rules.

#### D-18 Release order: AMEND

Accepted: backend before app. `extra="forbid"` stays. New request fields enter as optional, one release before any app sends
them. The "ship together" rule retires beyond dev.

**What replaces it, the Compatibility Rule (proposed text for `api-contract.md` 0.1, Q-27):**

> A wire change is two PRs. The backend PR stands alone: it accepts both the old and the new shape, and the frozen fixtures of
> every supported app version stay green. The app PR that starts sending merges after that backend is deployed in the target
> environment. Nothing is removed from the wire until the support floor is above every sender. Responses change additively only.
> SHA-equal pairing remains the rule for demo stacks only (D-12).

Amendments:

- **The rule must cover record fields, not only `StrictModel`.** Findings 1 and 5.
- **Technical backstop for an app that ships early:** the envelope's `protocol_version` is refused whole, before any
  per-record apply (D-22), so a too-new app waits instead of TERMINAL-stamping rows. An old backend without that gate answers
  a 422 whole-batch for the new field, which also waits, because the drainer keeps the batch (`SyncOutboxDrainer.kt:231-235`).
- **Same-protocol unknown field stays TERMINAL.** It is then a real defect, not skew. The comment invites an overrule (`services/sync.py:433`, Q-38).

Residual risk: "deployed before the app" is a release checklist item, not a check. The gate protects sync, while typed routes
would answer 422 until the backend catches up.

#### D-19 Expand and contract: AMEND

Accepted in full as policy. Amendments:

- **Alembic wiring.** `env.py:56-59` wraps all pending revisions in one transaction. Set `transaction_per_migration=True`, so a
  failure leaves earlier expand steps applied and `alembic_version` true. `CREATE INDEX CONCURRENTLY` needs
  `op.get_context().autocommit_block()` (INFERRED). Session `lock_timeout=5s`, `statement_timeout=60s`, set in `_run`; a slow
  step raises its own value in its own revision, visibly.
- **The wire rule (finding 5).** A synced column is added nullable or with a server default. A retired one goes on a new
  `TableSpec.retired` set, accepted and dropped, following the `aliases` precedent (`services/sync.py:202`), until the floor is
  above its last sender. Only then does contract run.
- **Backfills cannot touch insert-only tables:** `audit_events`, `kernel_derivation_checks`, `stack_ledger`.
- **Static gate, stdlib `ast`, in pytest:** from a `BASELINE_REVISION` (the first pilot tag's head), a revision using `drop_column`,
  `drop_table`, `rename_table`, `alter_column` with `nullable=False` or `type_`, a non-concurrent `create_index` on an
  existing table, or `TRUNCATE` or `DISABLE TRIGGER` must carry a `CONTRACT:` docstring line and append the epoch row (D-20).
  Earlier revisions are exempt: pre-pilot data is declared non-clinical (`0010_...py:6-7`).
- **The N-1 test gate cannot run as proposed.** `conftest.py:105-111` rebuilds the schema from `Base.metadata` for each test,
  so the old suite would test the old schema, not the new one, and go green regardless. It needs a `SAMD_TEST_SCHEMA=migrated`
  mode: schema once by `alembic upgrade head` from the **new** tree, TRUNCATE between tests. That mode must exist in the **old**
  tag, and no release tag exists yet, so this gate goes live one release after the first tag. Until then: the static gate plus
  frozen fixtures (D-23).

Residual risk: a semantic change (same column, new meaning) passes every gate. Review and fixtures reduce this, and nothing removes it.

#### D-20 Migration job and startup check: AMEND

Accepted: one-shot job before rollout, advisory lock, startup range check. F-14 means this is also needed for staging to work:
the README's `samd_app` role (`backend/README.md:134-147`) has no DDL rights, so the compose command would fail there.

**Lock.** Session-level `pg_try_advisory_lock` in a bounded poll, inside `env.py`, so a raw `alembic upgrade` is covered too. It
survives `autocommit_block` where an xact lock would not (INFERRED). Timing out exits nonzero, naming the holder's pid.

**Startup check**, run in lifespan immediately before the D-3 canary, reading `alembic_version`. Revisions are linear
four-digit numbers (`0010_...py:34`; a test pins this and a single head). The code's own head comes from the image's Alembic scripts, so there is no constant to drift.

| DB state | Result |
|---|---|
| DB below this code's head | Refuse, `schema_behind` |
| DB at or above head, epoch at or below `SCHEMA_EPOCH` | Start |
| DB's latest `contract` ledger row above `SCHEMA_EPOCH` | Refuse, `schema_contracted` |

A contract revision appends a `stack_ledger` row of new kind `contract` and bumps `SCHEMA_EPOCH` in the same PR. That is how old
code can tell expand from contract, which a revision number alone cannot do. Rollback to the N-1 image then starts by itself after an expand.

**How dev compose keeps its convenience, visibly:**

```yaml
services:
  migrate:
    # same image and build stanza as api, via a YAML anchor
    command: ["python", "-m", "app.scripts.migrate"]
    environment:
      SAMD_MIGRATE_MODE: ${SAMD_MIGRATE_MODE:-apply}   # .vars for demo and preview set check
      SAMD_CHECKOUT_DIR: ${PWD:-unset}
    depends_on: { db: { condition: service_healthy } }
  api:
    # no command: the image CMD runs uvicorn, the same as every other environment
    depends_on:
      migrate: { condition: service_completed_successfully }
```

`docker compose up` still migrates, and its output is separate (`docker compose logs migrate`). `check` mode exits nonzero
when anything is pending, and that replaces D-5's `alembic heads` step: `samdctl up --migrate` is what sets `apply`.

**Technical guard for DH-18.** `app.scripts.migrate`, only when `STACK_NAME=dev` and revisions are pending, compares
`SAMD_CHECKOUT_DIR` to the latest `stack_ledger` row of new kind `dev_home`.

| Case | Result |
|---|---|
| No row | Enrol this directory |
| Same directory | Apply |
| Different, or `unset` | Refuse: nothing applied, one line naming both directories and `samdctl up preview`. `api` never starts, because of `depends_on` |
| Different, with `SAMD_MIGRATE_ADOPT_HOME=1` | Append a new `dev_home` row, then apply |

This lives in the script, not in samdctl, because the hazard is the plain `docker compose up` that bypasses samdctl.

Amendments for production: the job runs as owner, the app as `samd_app`. The job must run `ALTER DEFAULT PRIVILEGES`, and revoke
UPDATE and DELETE on each new insert-only table, or `stack_ledger` is unusable or mutable under the app role (PH-9).
`env.py:29` loads full settings, so the job needs every secret in staging (F-3). Accepted for now.

Residual risk, all known: `-f` pointing at another worktree's compose file from the home directory passes the guard. A branch with
no migrations still runs against the dev DB. Raw `alembic upgrade` from a host shell bypasses it, deliberately. Revision 0012, which adds the new kinds, itself runs unguarded.

#### D-21 Client identity and support window: AMEND

**Conflict reported:** `api-contract.md:33` says no version header exists, and the proposal adds one (finding 10). Proposed
amendment: Q-27. The contract's `/api/v2/` rule stays for endpoint breaks.

Accepted: identity on every request, server-side floors, one code for refusal, no blocking of local work, no dropped outbox.

**Wire.** `X-App-Version-Code` (integer) and `X-App-Build-Sha` (omitted when empty, because D-12 keeps unslotted dev empty). They carry
no device id. Added in a new interceptor on both backend builders (`NetworkModule.kt:122-132,153-162`). The SLM client inherits it
(`SlmNetworkModule.kt:127-128`). Gemini and hub clients must **not** get it (`GeminiNetworkModule.kt:51`, `PiGatewayNetworkModule.kt:98`). A forged
header passes: this is compatibility, not security.

**Server.** Middleware following `HttpsEnforcementMiddleware` (`middleware/request_id.py:113`), innermost so audit sees the
status (`main.py:3-8`). `/health`, its split routes and `/admin` (`api/admin.py:24`) are exempt. **`SAMD-SYS-9008`, HTTP 426,** the next free code
(`errors.py:121-127`). A missing header is refused in staging and prod and allowed in dev. Floors default to 0 in dev, which keeps `versionCode = 1` working.

**Amended: two floors, not one.**

| Floor | Refuses | Raised when |
|---|---|---|
| `MIN_SYNC_VERSION_CODE` | Sync push, login, typed writes | The window passes **and** `devices` shows none below it (D-29) |
| `MIN_ASSESS_VERSION_CODE` | `/api/v1/assess` and `/api/v1/evaluate` only (`api/v1/kernel.py:22,29-30,57-58`) | Window, or at once for a clinical recall |

Reason: a recall of a defective assessment path must not also stop recorded clinical data from reaching the server (Q-23).

**App.** `SAMD-SYS-9008` becomes a new `DrainFailure.UPDATE_REQUIRED` in `drainFailureFor` (`DrainOutcomeStore.kt:49-59`). The worker returns
`failure()`, not `retry()`, like `OWNER_MISMATCH` (`SyncPushWorker.kt:44-47`), so no battery is spent in backoff up to 5 h. The in-flight
batch is kept (`SyncOutboxDrainer.kt:231-235`), and no row changes. The code stays out of the `SAMD-AUTH-` prefix (`DrainOutcomeStore.kt:53`) and
`TokenAuthenticator` already keeps the session on it (`TokenAuthenticator.kt:101-104`). A test pins both. Caption on Home next to the existing ones
(`strings.xml:148-150`), new string for operator approval. Assessment falls to the existing UNAVAILABLE card.

**Amended: store deep link first, no Play in-app update library yet.** No Play dependency exists (`build.gradle.kts`, grep), it would be new SOUP, and
it needs Play and a network, while the channel is undecided (Q-21). An `ACTION_VIEW` intent is native and needs nothing.

**Support window: 180 days, proposed.** A release stays supported for 180 days after its successor reaches 100 percent. Why, all INFERRED, because
the repo states no offline duration (grep of `docs/backend`, `docs/requirements`, `docs/quality`, `docs/sync-design.md`) and no pilot data exists:

- Update needs a network and a working Play session, which a rural PHC gets rarely; phones update late even once back online.
- Staged rollout alone can take about 4 weeks (D-25), which eats into any shorter window.
- A phone sitting in a drawer or awaiting repair holds real clinical records. Refusing it costs delay, but losing a supported path costs far more.
- Cost is bounded: at monthly releases, 6 fixture sets and an upcast table, not parallel handlers (D-22).
- It is much longer than the 7-day refresh TTL (`config.py:62`), so a long-offline phone re-signs in first, and that path also carries the header.

Revisit with measured `devices.last_seen_at` by version after the first pilot months. The floor is only ever raised by hand, after the query. No time-based automation.

Residual risk: a phone past the floor with no network holds data until someone brings it online. Accepted: it is never lost, and that is the design goal.

#### D-22 Sync protocol versioning: AMEND

**Rows are not stored payloads (finding 2), so the choice as posed mostly dissolves. Ruling: Room migration transforms (the first option), because
that is already the architecture, and version-stamped stored payloads are rejected.**

| Option | Failure modes |
|---|---|
| **Room migration transforms; N's mapper serializes (recommended, exists today)** | A migration that fails bricks the phone until roll-forward (PH-17). A migration that bumps `localModifiedAt` makes the ack's guarded UPDATE match nothing, and the row resends (`SyncOutboxDrainer.kt:85-89,189-194`), which is bounded but wasteful. A value that old rows lack must have a server default. |
| Stored version-stamped payloads | Needs a new outbox table holding PHI twice. Edits after queuing need a merge rule. The server keeps real old handlers for the whole window. The old mapper's bugs are frozen and replayed. Worst: it replaces a working row-state design for no field problem. |

**`protocol_version` is still worth having, for a different reason:** it stamps the wire shape of *this push*, and it lets the server refuse a batch whole
before any TERMINAL stamp (finding 1). Added as `protocol_version: int = 1` on `SyncPushEnvelope` (`schemas/sync.py:32-40`), backend first under D-18.

**One apply path plus an upcast table, not parallel handlers.** `{version: record -> record}` normalizes to current and then the one `_apply_generic` runs. Per-version handlers would fork
the conflict and audit logic, which is the riskiest code in the service.

**Check position in `push()`:** after the idempotency lookup, before step 2 (`services/sync.py:859-882`). A known `batch_id` is answered verbatim, so a device can always clear its in-flight state.

| Case | Result |
|---|---|
| Version below supported | `SAMD-SYS-9008`, whole batch, caption and update |
| Version above supported | New `SAMD-SYNC-6008`, whole batch. Must not tell a worker to update: the backend is what is behind. `SERVER_REFUSED` with normal retry |

**Passed-through strings stay as written:** `payload_json` on evaluate reports (`SyncPayloadDto.kt:207-213`) and device audit rows. Readers tolerate old shapes. Audit rows are never transformed, since they are chained.

**Replay across an update:** the stored `response_json` is N-1-era JSON read by app N. It is safe under additive responses, and unknown ack statuses are already skipped safely (`SyncAckMapping.kt:30-51`). Fixture in U6.

Residual risk: a Room migration cannot be undone on a phone. D-25's staged rollout and U7's chain test are the controls, and they reduce rather than remove this.

#### D-23 Contract guard: AMEND

Accepted: snapshot, breaking diff, old-contract fixtures, path-aware, asserting persisted rows.

**Three snapshots, because OpenAPI sees one (finding 4):**

| Snapshot | Source | Guards |
|---|---|---|
| `openapi.json` | `app.openapi()`, works with docs URLs off (`main.py:168-170`, INFERRED) | Typed routes, with `oasdiff breaking`, a pinned CI-only binary |
| `sync-records.json` | `{table: sorted(_attr_map(spec))}` for all of `TABLE_REGISTRY`, plus required columns, plus `_AUDIT_LOG_ALLOWED_KEYS` (`services/sync.py:80,195`) | Field removed without `retired`, or a new required column: fails |
| `kernel-shapes.json` | Field paths the app reads from `/v1/assess` and evaluate | A classifier response change reaching an old app (`api-contract.md:54-58`) |

"Versioned" means: a new `/api/v2/` path, or a `protocol_version` bump with its upcast and fixture in the same PR.

**Fixtures come from the real mapper, then freeze.** A JVM test runs `toSyncRecord()` (`SyncRecordMappers.kt`) on seeded entities with `SyncGson` and writes `backend/core/tests/fixtures/wire/<versionCode>/`.
Generated once, at the first pilot tag and each release after. Hand-written fixtures would have the characteristic bug. **Frozen by CI:** any modified or deleted file under an existing version directory fails, unless the floor rose past it. Otherwise someone edits the fixture to go green.

Backend test: each fixture goes through `POST /api/v1/sync/push` with a real token, asserts `applied`, and reads the row back by raw SQL, per the project's backend convention.

Residual risk: fixtures hold what the seeds exercise, so a null-only path may go untested. Seeds cover nulls and populated values per table.

#### D-24 Backend rollout: AMEND

**Readiness and liveness, reconciled with D-3.** Three endpoints. `/health` is unchanged for its contract and D-12.

| Endpoint | Checks | Failure means |
|---|---|---|
| `/livez` | Nothing, not even the DB | Process wedged: restart |
| `/readyz` | DB ping (`health.py:36-48`) | Stop routing, **do not restart** |
| Boot, lifespan | Schema, then canary | See below |

Boot: DB unreachable retries for a bounded 30 s, then exits nonzero. This answers D-3's flagged question: mismatch exits at once with its own line, unreachable waits. After boot a DB outage never exits the process, because the check is boot-only and `/livez` skips the DB, so an outage cannot become a restart storm. A wrong key on a new task stops the rollout while old tasks keep serving.

**Graceful shutdown.** `--timeout-graceful-shutdown 60`, above the longest upstream read, SLM's 50 s (`config.py:75,89`). Orchestrator stop grace at least 70 s; the docker default of 10 s and ECS default of 30 s are too short (INFERRED). `Dockerfile:59` is exec form, so uvicorn gets SIGTERM. The compose `sh -c` wrapper likely does not forward it (INFERRED), and D-20 removes it. A killed push is safe anyway: one transaction (`services/sync.py:847-850`), and the device resends the same `batch_id`.

**Rolling, not blue-green.** D-19 makes old and new safe together, so blue-green adds infrastructure and no safety with one shared DB. Target settled by the AWS session (`backend-prd.md:681-682` leaves ECS or EC2 open).

**Idempotency TTL: REJECT the 24 hours; correct the contract instead (finding 6, Q-28).** Proposed text: "retained at least for the support window; replays return the original response". Keep rows. Size is small: one row per batch, ids and codes with no values (`services/sync.py:403`, INFERRED no PHI in `response_json`). A later purge must (a) keep `sync_log`, (b) not delete the batch row, and (c) change `services/sync.py:874-875`, which raises on a null response and would 500 forever.

Residual risk: the table grows without bound until a retention design exists.

#### D-25 App rollout: AMEND

Accepted: internal, closed, staged with halt. Roll forward only. Amendments:

**The pipeline first (finding 8).**

- Bundle, not APK, for Play (INFERRED requirement).
- **Size.** The APK is at least 661 MB (`model-soup-2026-09-02-v1.0.json:174`). This exceeds Play's base module limit, so the ASR model needs install-time asset delivery or a first-run download (limits INFERRED from memory; confirm in Play Console, Q-25). Install-time keeps it working offline at first run.
- **ASR in CI:** the workflow must fetch weights from a controlled store and verify against the SBOM, as D-9 does for slots. Otherwise the release build has no speech model (INFERRED from finding 6).

**`versionCode` for every flavor (F-16).**

| Build | `versionCode` | Why |
|---|---|---|
| prod, staging release | `X*1000000 + Y*1000 + Z` from tag `vX.Y.Z`, passed as `-Psamd.versionCode`; Gradle throws for a release variant without it | Strictly increasing, readable, under Android's 2100000000 cap (INFERRED) |
| Slots | Commit count (D-9, ruled) | Separate packages, so no conflict |
| Unslotted dev | Stays 1 | `adb install -r` across branches keeps working. Floors are 0 in dev |

A `testProdRelease` assertion, which `android-release.yml:36` already runs, fails if the code is 1.

**Signing and device identity (F-17 corrected).** Play App Signing does not change `device_id`, a DataStore UUID that survives any update and rotates only on uninstall or clear (`models/user.py:89-91`). What matters: a phone holding a build signed by a different key than the store's refuses the update, and the only way forward wipes unsynced data (D-11). So: **before the first phone, pick one app-signing key and one channel, and never sideload a CI-signed build onto a phone that will update from Play** (Q-24). ANDROID_ID in report metadata does vary by signing key (INFERRED), which is cosmetic unless a report consumer keys on it.

**Fixes.** Roll forward from `release/X.Y`, cut at the tag. This is the same conflict as D-16, so the same exemption is proposed: fix on master by normal PR, cherry-pick the merged fix to the release branch, never merge that branch back (Q-26). Halting a stage leaves users on N, so N+1 must migrate from both N and N-1.

**Room.** U7: a chain test from every supported schema to head, with populated PENDING rows, not schema only. Versions below the first pilot schema have no field install and can leave the supported set. Android CI does not run instrumented tests, so this is a Pixel release checklist item (`am instrument -e class`, per CLAUDE.md).

**ACP e) asks for a rollback plan** (`Guidance...md:1821-1822`). "No APK rollback" must be written as that plan: triggers (halt thresholds, D-29), recovery (raise the assess floor, roll forward), user communication (caption). PROPOSED for the ACP.

Residual risk: a migration bug reaches the first stage. Keep the first stage small and on phones whose data is synced.

#### D-26 Flags and remote config: AMEND

Accepted: the separation, and clinical changes as design changes. **Amended: REJECT device remote config for v1.** No device-side config channel: it adds a new path to change device behavior offline-unsafe by default, in order to solve a problem the server already solves.

**Operational kill switches are server-side and exist today.** They work on every app version, with no new code: SLM off by empty token, a clean 503 (`main.py:81-94`); `ABDM_MODE` (`docker-compose.yml:36`); circuit breakers (`main.py:76-79`). D-21's assess floor adds one. Each change is logged at startup (`main.py:61-67`) and gets a DHF line.

**Classification, enforced by test.** Every flag in `FeatureFlags.kt` must be in a registry with a class, or the test fails, so a new flag cannot escape. Proposed, for the operator to rule (Q-31):

| Class | Flags (`FeatureFlags.kt`) | Rule |
|---|---|---|
| Clinical function or safety control | `VOICE_*` `:54,85,104,120,126,132`; `PRESCRIPTION_APPROVAL_GATE_ENABLED` `:150`; `SLM_READBACK_ENABLED` `:171` | Build-time only. Change is a design change: memo, risk row, ACP assessment, app release |
| Security control | `SCREEN_SECURITY_ENABLED` `:11`, `IDLE_LOCK_ENABLED` `:15` | Build-time only, per flavor |
| Operational | `PATIENT_AUDIT_ENABLED` `:19`, `RESUME_DRAFT_ENABLED` `:24`, `DEVICE_RESOURCE_CHECK_ENABLED` `:29` | Build-time now; a remote switch only with a named need |

**H-15 link.** Section 8's Q-2 ruling keeps `VOICE_FIELD_CHIEF_COMPLAINT_ENABLED` and `VOICE_AUDIO_ATTACHMENT_ENABLED` out of `DEMO_PROFILE`. The same principle at the release level: neither can ever move to a remote source, and `DEMO_PROFILE` is literal false outside dev (DH-13).

Under the guidance, "version change which affects ... safety and/or effectiveness" is major (`Guidance...md:2297-2298`), and a clinical flag flip in a release fits. That classification call is the operator's and regulatory, not this memo's.

Residual risk: a defective non-clinical device feature can only be fixed by release. Accepted for the pilot.

#### D-27 Key rotation: AMEND

**JWT `kid`: ACCEPT, before pilot.** `services/auth.py:131,138-141` use one key with no `kid`. Settings hold a map of `kid` to secret plus a current id; sign with current, verify by header `kid`, absent means the legacy key. Retire an old key 7 days after the switch, the refresh TTL (`config.py:61-62`). `validate_secrets` covers each key. A phone offline longer simply signs in again.

**PHI, blind index, case token: AMEND, design with KMS, do not build a per-row version column first.**

- `EncryptedText.column_expression` receives only its own column (`db/types.py:57-58`), so a sibling `key_version` cannot steer decryption without rewriting every query. A self-describing ciphertext prefix might (INFERRED). KMS envelope encryption, which `db/types.py:11-14` already names as the answer, changes this whole mechanism: rotating a wrapping key touches no row (INFERRED).
- Blind index: dual lookup needs `IN (old, new)` at each site and a rewrite of `patients` (`db/types.py:82-99`, N-5). A partial rewrite without dual lookup silently misses patients and creates duplicates (N-6), so ordering is the whole risk.
- Columns in scope: `models/patient.py:83-87`, `models/abha.py:82,144-146`.
- Case token: new key for new rows only, old tokens not rewritten (D-3).

**`stack_ledger` link.** New kinds `rotation_started` and `rotation_finished`. While a rotation is open the canary verifies old and new checks. D-3's tool keeps refusing PHI and BLIND until U14 lands. No code path gains a silent mode.

Residual risk: **until U14, a compromised PHI key has no procedure on a database with real patients.** Pre-pilot it means a new volume. So U14 belongs before real PHI (Q-37).

#### D-28 Model updates: AMEND, OTA rejected for now

**Conflict reported (finding 7).** `soup-validation-record.md:33` keeps model changes inside app releases to avoid the ACP gap (`qms-overview.md:45`). Out-of-band OTA would reverse a recorded control. Not designed here; the operator can reopen it once an ACP exists (Q-30).

What exists, and the rule for each:

| Model | Today | Update route |
|---|---|---|
| ASR | Bundled, SBOM (`model-soup-2026-09-02-v1.0.json`) | App release only |
| SLM | Dev flavor only, sideloaded, identity pinned in the signed APK (`LocalSlmEngine.kt:41,375-384`), file named by SHA so an older one can sit beside it | App release changes the pin. The signed APK plus pin is the signature, so no separate signing needed |
| Classifier | Server side, not in this repo (`docker-compose.yml:3-4`) | Server deployment. This **is** "a post-deployment update to the kernel model", so the ACP comes first (`qms-overview.md:45`) |

Amendments for the classifier:

- **Identity is reported, not enforced.** The proxy copies `model_sha256` and cannot verify it (`0010_...py:15-16`, `services/kernel.py:344-350`), and a malformed value is stored as null. `/evaluate` has none (`:334-338`). A silently replaced model satisfies every current check. Add `KERNEL_ALLOWED_MODEL_SHA256`: a mismatch on assess refuses to UNAVAILABLE, or flags only, as a clinical call (Q-29).
- **Compatibility rule.** A classifier release must accept every token vocabulary of every supported app version and keep `kernel-shapes.json` fields. It cannot be CI-tested here, because the classifier is elsewhere (N-11): the frozen request fixtures are exported and run in that repo (Q-30).
- **Rollback:** previous classifier image kept and pinned by digest. This is cheap, and it answers ACP e).
- **`DERIVATION_RULE_VERSION` already separates rule from model** (`domain/kernel_derivation.py:21-28`). Keep it.

Not read: the operator's `classifier-target-architecture-memo.md`, which is untracked in the main checkout and outside this worktree. It may affect this ruling (Q-30).

Residual risk: `/evaluate` results remain without model identity.

#### D-29 Observability: AMEND

Accepted: per-version visibility, smoke test, thresholds, tagged notes. **Amended: SQL and structured logs first, no metrics stack before AWS.** The file header already sets the principle: "a SQL query rather than a support ticket" (`models/sync.py:4-6`).

- `sync_batches` gains nullable `client_version_code`, `protocol_version`, `backend_sha`. Per-record rejects by version are then a join to `sync_log`.
- **`devices.app_version` gets set from the header** on sync and login (F-13: never set today), with `last_seen_at` (`models/user.py:103,110-112`). This answers "who is below the floor", the precondition for D-19's contract and D-21's floor raise.
- Request log line gains code and SHA. Never bodies (`backend/README.md:151`).
- **Smoke, `samdctl smoke <url>`:** `/health` SHA equals expected, `/readyz` is 200, `/livez` is 200, unauthenticated push gets 401. No PHI, no seeded account in prod.
- Thresholds, proposed and INFERRED, tune after pilot:

| Signal | Threshold | Action |
|---|---|---|
| 5xx by backend SHA | Over 1 percent in 15 min | Stop rollout |
| `unexpected field` TERMINAL rejects | **Any** | Investigate today: signature of skew, data stuck on a phone (PH-1) |
| Sync rejects by app version | Over 2 percent, or twice the prior version | Halt the Play stage |
| `SAMD-SYS-9008` by version | Any unexpected version | Check the floor |
| Devices below floor | Reported, no alert | Blocks a contract |

- Release notes: annotated `vX.Y.Z` tag, one DHF row, listing migrations, protocol and model changes. Tag creation needs explicit authorization.

Residual risk: a sole operator is the alert route (Q-36), and a phone that never reconnects is invisible except by absence.

#### D-30 Backups and integrity: AMEND

Accepted: snapshot before each production migration, PITR on RDS, restore drill, proof for the audit log. Amendments:

**Proof that audit survives, in CI, using the existing scratch-schema harness (`conftest.py:38`).** Start at the baseline revision, write a chain with `audit.append`, record a digest of every row, upgrade to head, then assert: digests equal, `verify_chain` passes, UPDATE and DELETE still raise. Plus one **golden row with a literal hash**, because `compute_entry_hash` includes `to_iso` output (`services/audit.py:46-71,130-131`), and a harmless-looking format change breaks the whole chain with **no migration at all**. The static gate bans `TRUNCATE` and `DISABLE TRIGGER` on insert-only tables, since TRUNCATE skips row triggers (`0010_...py:23-24`). `samd_app` holds no TRUNCATE (`backend/README.md:143`). `verify_chain` caps at 100,000 rows (`services/audit.py:39`), so the drill pages it.

**A restore is not just a technical event (finding 12).** After a point-in-time restore, every write after the restore point is gone from the server. Phones hold those rows as SYNCED and never re-push them, and no pull exists. The `sync_log` audit dedup rewinds as well. A drill measuring "the DB came back and the chain verifies" would pass while clinical data stays missing. The drill must count that gap. A device re-push procedure needs its own memo, with the failure design D-8 already asked for in its paired reset (Q-34).

Ordering for each production migration: snapshot, job, smoke, rollout. Backup retention and DPDP duties are operator and regulatory calls (Q-34).

Residual risk: until that memo exists, restore means known data loss with no list of what was lost.

### 9.3 Hazards

PH names are design-local, unrelated to `risk-management-file.md` rows. Whether they become PROPOSED rows follows Q-13's batch. Proof types refer to 9.6.

| # | Hazard: who or what breaks | Control | Proof |
|---|---|---|---|
| PH-1 | App ahead of backend, new record field: rows go TERMINAL and FAILED on that phone | `protocol_version` whole-batch refusal; alert on `unexpected field` | Mutation: remove gate, fixture row reads FAILED-class. Live: 9.4 row 2. U3b |
| PH-2 | Backend drops or renames a synced column: every older app's rows go TERMINAL | `retired` set, `sync-records.json` | Mutation: remove a column, snapshot and fixture red. U2, U6 |
| PH-3 | New NOT NULL synced column: older apps get 23502 TERMINAL | Static gate, fixtures | Mutation: add one, red on persisted row. U1, U6 |
| PH-4 | New required envelope field: 422, all of that phone's sync halts | Optional-first rule, oasdiff | Mutation: make it required, red. U2 |
| PH-5 | Migration locks a hot table during field sync | `lock_timeout`, concurrent indexes | Unit: a held lock makes the migration exit nonzero, no partial state. U1 |
| PH-6 | Two runners migrate at once | Advisory lock | Unit: second runner times out, `alembic_version` read back. Mutation: skip lock, red. U1 |
| PH-7 | Feature worktree migrates dev DB (DH-18) | `dev_home` guard | Mutation below. Live: `up` from a worktree refuses. U1 |
| PH-8 | Old replica on a contracted schema: 500s on a missing column | Epoch refusal; contract only after floor | Unit, mutation: ignore epoch, red. U1 |
| PH-9 | New table invisible to `samd_app`, or insert-only mutable under it: prod-only 500s, tests green as owner | Default privileges in job; smoke under app role | CI job runs the suite as a restricted role. Mutation: drop the grant step, red. U1 |
| PH-10 | Refused app signs out and loses session | Code outside `SAMD-AUTH-`; authenticator unchanged | Mutation: rename code with that prefix, red. U4 |
| PH-11 | Refusal blocks local capture or drops outbox | Network layer only, rows untouched | Test: 9008 leaves every row and the in-flight batch unchanged. Live: refused phone registers a patient. U4 |
| PH-12 | Too-old phone burns battery in backoff | Worker `failure()` | Unit on `syncPushWorkResult`, mutation. U4 |
| PH-13 | A build without identity reaches the field: never refusable or countable | Must-land list (9.5); missing header refused in prod | Unit. Release checklist. U3a, U4 |
| PH-14 | Second store upload refused, or field phone cannot update | `versionCode` scheme | `testProdRelease` assertion, mutation. U5 |
| PH-15 | CI release lacks ASR weights: voice fails in the field | SBOM check in workflow | Mutation: remove a weight, build fails. U5 |
| PH-16 | Signing or channel mismatch: update refused, the way forward wipes unsynced data | One key, one channel, decided before first phone | Live: mismatch refused, data intact. Q-24 |
| PH-17 | Room migration fails on real rows: phone unusable until roll-forward | U7 chain with rows; staged rollout | Mutation: break a migration, red on the Pixel. U7 |
| PH-18 | Migration bumps `localModifiedAt`: acks miss, rows resend | Rule plus chain assertion | Mutation: bump it, red. U7 |
| PH-19 | 24 h TTL implemented as the contract says: duplicate audit rows, lost resume | No purge; contract corrected | Test: aged batch replays verbatim, `audit_events` count read back. Mutation: delete the row, red. U8 |
| PH-20 | Deploy kills an in-flight push | One transaction; same `batch_id`; grace above 50 s | Live: stop API mid-push, resend, no duplicate rows. U8 |
| PH-21 | DB outage becomes a restart storm | `/livez` skips DB; canary at boot only | Unit, mutation: add DB to `/livez`, red. Live: stop db, process stays. U8 |
| PH-22 | Cloud backup holds refresh token and `device_id` (`AndroidManifest.xml:22`; rules exclude only one file, `backup_rules.xml:9`, `data_extraction_rules.xml:11,14`). A restore on a second phone shares identity, and reuse detection then revokes (`services/auth.py:325-338`). All INFERRED | Exclude `auth_session`, or backup off for prod (Q-33) | Unit on the rules XML, mutation. Live: restore test. U5 |
| PH-23 | Clinical flag changed outside a release | No remote config; registry test | Mutation: unclassified flag, red. U12 |
| PH-24 | Classifier change breaks an older app through passthrough, or rejects its vocabulary | `kernel-shapes.json`, exported fixtures, ACP gate | Mutation: drop a field, red. Live: Q-30. U2, U13 |
| PH-25 | Model silently replaced, under the same report identity | Allowed SHA list | Unit: other SHA gives the ruled result, row read back. U13 |
| PH-26 | Partial blind rekey: lookups miss, duplicate patients | Tool refuses until U14; dual lookup | Mutation in U14's own memo |
| PH-27 | Migration or `to_iso` change breaks the audit chain | D-30 test, golden hash | Mutation: change `to_iso`, red. U10 |
| PH-28 | Restore loses post-restore writes, unknown to anyone | Drill counts them; memo (Q-34) | Live: drill. **Open: no control exists yet.** U10 |
| PH-29 | Contract while stale phones remain | Devices-below-floor query blocks it | Checklist; query tested. U9 |
| PH-30 | Replay returns an old-format response to a newer app | Additive responses; stored-JSON fixture | Unit with frozen old `response_json`. U6 |

### 9.4 How PART A and PART B fit

Part A's machinery is how Part B gets tested without a pilot.

| Local test | Setup, using Part A | Proves |
|---|---|---|
| 1. Old app, new backend (N-1) | `samdctl build-apk <old tag> --slot preview`; `samdctl up preview` at the new tag with `--migrate` | Support window, frozen-fixture truth on a real phone. Banner shows the SHA mismatch, as it should (D-12) |
| 2. New app, old backend | Preview slot at the new ref, preview stack at the old tag | PH-1 gate: batch waits, no row FAILED |
| 3. Update with queued rows | Preview slot at old tag, airplane mode, create rows, then `slot install` at new ref (same package, higher commit count) | Room chain plus push: the exact late-updating field phone |
| 4. Refused client | Preview `.vars` sets a floor above the slot's commit count | PH-10, PH-11, PH-12: caption, session kept, data intact |
| 5. Migration safety | Preview stack, `SAMD_MIGRATE_MODE=check`, then apply | D-20's modes against a real volume, never dev |
| 6. Key failure in rollout | D-3 wrong-key check on a throwaway project | Boot refusal |

Join points:

- **`stack_ledger` is one mechanism:** canary, moved, plus `dev_home`, `contract`, `rotation_*`. U1 widens the CHECK in a new revision.
- **D-5's migration gate** becomes `SAMD_MIGRATE_MODE`. Same refusal, one implementation. If E1b lands before U1, that is a small rework.
- **D-12 versus D-18:** SHA equality for demo slots only. Everywhere else the Compatibility Rule. The banner is compiled out of release builds (D-12's no-op seam).
- **D-3's open DB-unreachable note** is settled by D-24.
- **D-4's `SAMD_BUILD_SHA`** feeds `/health`, `samdctl smoke` and `sync_batches.backend_sha`.
- **Tags:** `demo/YYYY-MM-DD` (D-16) does not match `android-release.yml:4-6`'s `v*.*.*`, so a demo tag cannot start a store release.
- **Kept bundles** (D-16) are reproducible N-1 backends.
- **Slot keys and `versionCode`s** never touch the store (D-11, D-25).
- **D-17's outline** is replaced: `X-App-Version` by D-21's headers; "not needed while SHA equality holds" still holds for demos, and no longer for anything else.

### 9.5 PR split and order

One branch each from fresh origin/master, per CLAUDE.md. Risk classes per CLAUDE.md, for the operator to confirm at the gate. U numbers are in merge order.

**Must land before any pilot phone leaves the lab: "Yes" below.** The **Hard** ones cannot be retrofitted, because the phone must already carry them.

| Seq | PR | Contents | Depends on | Risk, reason | Model, effort | Review | Before pilot |
|---|---|---|---|---|---|---|---|
| 1 | E1a | Section 5 | none | R2 | Opus, high | yes | Yes |
| 2 | **U1** schema safety | Migrate job, `env.py` lock and timeouts, startup check, epoch, `dev_home` guard, compose `migrate`, static gate, role grants | E1a | **R3**: migration, new guard, concurrency, boot ordering at once | Opus, xhigh; `edge-case-hunter` | yes | Yes |
| 3 | E1b | Section 5, on U1's `migrate` | E1a, U1 | R1 with R2 | Opus, high | yes | No |
| 4 | E3a | Section 5 | none | R1 with R2 | Opus, high | yes | No |
| 5 | E3b | Section 5, per section 8 | E3a | R2 | Opus, high | yes | No |
| 6 | E2 | Section 5 | E1b | R2 | Opus, high | yes | No |
| 7 | E4 | Section 5 | E3a | R2 | Opus, high | yes | No |
| 8 | **U2** wire guard | Three snapshots, `retired`, oasdiff CI | none | R2: wire contracts, a new guard, in one seam | Opus, high | yes | Yes |
| 9 | **U3a** identity gate | Middleware, floors, `SAMD-SYS-9008`, contract text PROPOSED | U2 | R2: wire, new guard | Opus, high | yes | Yes |
| 10 | **U3b** protocol gate | Envelope field, upcast table, `SAMD-SYNC-6008`, `retired` handling | U3a | R2: sync rules, wire | Opus, high | yes | Yes |
| 11 | **U4** app identity | Interceptor, `protocol_version`, `UPDATE_REQUIRED`, caption, deep link | U3a, U3b **deployed** | **R3**: wire, sync failure path, worker and session interplay | Opus, xhigh; `edge-case-hunter` | yes | **Hard** |
| 12 | **U5** release pipeline | `versionCode`, bundle, ASR in CI, backup rules, signing per Q-24 | Q-21, Q-24, Q-25, Q-33 | R1 with R2: signing identity, token backup | Opus, high | yes | **Hard** |
| 13 | **U6** frozen contract | Fixture export, replay tests, freeze check, `migrated` test mode | U2, U4 | R2: wire contract proof | Opus, high | yes | Yes; freeze at first tag |
| 14 | **U7** Room chain | Baseline to head with rows; `localModifiedAt` rule | U4 | R2: migrations, on-device | Opus, high | yes | Yes |
| 15 | **U8** rollout | `/livez`, `/readyz`, graceful, contract text on idempotency | U1 | R2: failure paths | Opus, high | yes | Yes |
| 16 | **U9** visibility | `sync_batches` columns, `devices` writes, smoke, queries | U3a, U4 | R2: migration; rest R1 | Opus, high | yes | Yes: columns and smoke |
| 17 | **U10** audit and restore | Survives-migration test, golden hash, drill script | U1 | R2: audit chain | Opus, high | yes | Yes: test. Drill with AWS |
| 18 | **U11** JWT `kid` | Key map, verify by `kid` | none | R2: auth | Opus, high | yes | Yes |
| 19 | **U12** flag registry, ACP | Registry test; PROPOSED ACP, risk rows | Q-31, Q-32 | R1: test and docs, near hazard-cited flags | Sonnet, high; `edge-case-hunter` | yes | Yes |
| 20 | **U13** model pin | Allowed SHA, per Q-29 | Q-29, Q-30 | R2 flag-only; **R3** if refusing (assessment logic plus new guard) | Opus, high or xhigh per Q-29 | yes | Before any model update |
| 21 | **U14** rekey with KMS | Own STEP 1 memo first, with the AWS session | D-27 | **R3**: encryption, migration, ordering | Opus, xhigh, memo says why | yes | Before real PHI (Q-37) |

Order reasons: U1 goes straight after E1a because E1b would otherwise build its gate twice. The two tracks are independent after U1, except where listed. If a demo is close (Q-20): E1a, E1b, E3a first, and accept U1's small rework. If a pilot is closer: E1a, U1, then U2 onward, and E tracks wait. U4 is the one strict deployment dependency: **U3a and U3b must be running in the target environment first,** which is the Compatibility Rule applied to itself.

Not PRs: AWS deployment, which already has its own session (`backend-prd.md:782`), and the restore re-push memo (Q-34). Filed elsewhere: persona picker (section 8).

### 9.6 Test plan per PR

Mutation method as section 6: snapshot, break, RED at an assertion, restore, sha256 identical, GREEN. Empty results directory counts as RED. Any test of a write reads back the persisted row. Nothing below has been run. Live checks are for the operator. Baseline: re-measure at branch cut.

**U1.** `tests/test_migrate.py`, using the scratch-schema harness (`conftest.py:38`).

| Test | Mutation that must go RED |
|---|---|
| Two runners: one applies, one exits nonzero; `alembic_version` read back | Skip the lock |
| Held table lock: migration exits nonzero within `lock_timeout`, no partial revision | Remove `lock_timeout` |
| DB below head: boot refuses `schema_behind` | Skip the check |
| DB above head, same epoch: boots | Treat newer as refusal |
| Epoch above code: refuses `schema_contracted` | Ignore epoch |
| **Other checkout, pending: refused, `alembic_version` unchanged, no ledger row** | **Remove the compare** |
| `unset`: refused | Treat `unset` as a match |
| Adopt env: new `dev_home` row, prior row intact | Update in place |
| `check` mode with pending: exits nonzero, applies nothing | Apply anyway |
| Static gate: `drop_column` after baseline without `CONTRACT:` fails, as do `TRUNCATE` on `audit_events` and a new NOT NULL synced column | Remove each rule |
| Suite under a restricted role, CI job: new table usable, insert-only UPDATE refused | Drop the default-privileges step |

Also: `alembic check`, upgrade then `downgrade -1` then upgrade, `ruff`, `mypy`, `pytest -q` from both packages.

Live, operator: from `backend/` in a throwaway worktree holding a new revision: `docker compose up`, `docker compose logs migrate` shows the refusal naming both directories, `api` not started, `select version_num from alembic_version` unchanged. From the main checkout: normal `up`, with dev volume and ports the same as section 6's invariance check. `docker stop` on `api` exits by SIGTERM, not 137.

**U2.** Field removed from `TABLE_REGISTRY` model: red. Added to `retired`: green. Required request field: `oasdiff` red (proves the binary really runs, since a skipped step would show green). `kernel-shapes.json` field removed: red. Snapshot regeneration on an unchanged tree: byte-identical, in CI.

**U3a.** With floor 100: code 99 gets 426 with `SAMD-SYS-9008` on push, and no `sync_batches` row by raw SQL (mutation: `<=`). 100 passes. Missing header in staging settings refused, dev allowed (mutation: invert). `/health` and `/livez` exempt. Assess floor refuses `/api/v1/assess` and `/api/v1/evaluate` and leaves push untouched: read the pushed row back (mutation: one floor for both). Middleware order: audit row shows 426.

**U3b.** Too-new version: `SAMD-SYNC-6008`, no record applied, no TERMINAL result, no `sync_log` row (mutation: move the gate after apply, **fixture row goes TERMINAL**). Replay of a known `batch_id` with an unsupported version returns the stored response (mutation: gate before lookup). Upcast `v1` record ends as the same persisted row as current. `retired` field accepted, not persisted, not TERMINAL.

**U4.** `testDevDebugUnitTest` and Staging, Prod. Fake web server asserts headers on every call of the backend client, the ABHA client and the derived SLM client, and their absence on the Gemini and hub clients (mutation: add to one, remove from one). `drainFailureFor` maps 9008 to `UPDATE_REQUIRED` (mutation: fall to `SERVER_REFUSED`). `syncPushWorkResult` is `failure()`. Refresh answered 426: session retained in `TokenAuthenticatorTest` (mutation: treat 426 as session-ending). Drain on 426: in-flight batch intact, all rows PENDING, next drain after a 200 uses the same `batch_id` (mutation: clear the batch). `git grep` proof in test that the code is not `SAMD-AUTH-` prefixed.

Live, operator (phone free first): 9.4 rows 1, 3, 4. Refused phone: registers a patient, sees caption, deep link opens, stays signed in; after lowering the floor, syncs with `applied` and no CONFLICT.

**U5.** Release variant without the property fails Gradle (message shown). `testProdRelease` fails at code 1. Workflow with one ASR file altered fails against the SBOM. Backup rules test: `auth_session` excluded (mutation: remove the line), per Q-33. Live: first bundle size in Play Console; `apksigner verify --print-certs` against the store's key; internal track install, then update, data intact.

**U6.** Each frozen fixture: pushed, `applied`, raw SQL read-back per table. Mutations: rename a column, drop a `retired` entry, add a NOT NULL column: each red at the persisted assertion or the TERMINAL result. Edit a frozen file: CI freeze check red. Old stored `response_json` parses through the app's `SyncResultDto` (JVM). N-1 gate: at the second tag, run the first tag's suite in `migrated` mode. Prove with a contract migration that it goes red.

**U7.** Pixel only, `am instrument -e class`, one class. Baseline schema with rows in each syncable table, PENDING, RETRYABLE and FAILED, plus an in-flight batch: migrate to head; assert counts, states, `localModifiedAt`, and `toSyncRecord()` output parses. Mutations: migration bumps `localModifiedAt`, red; deletes a row, red. Added to the release checklist, as CI has no device.

**U8.** `/livez` 200 with DB down (mutation: add a DB call). `/readyz` 503 with DB down. Boot with DB down exits nonzero after the bound, not before (fake clock). Aged `sync_batches` row replays verbatim, audit count read back, and a deleted row shows why it must stay. Live: stop `db`, process keeps running, recovers with no restart; `docker stop` during a slow SLM call finishes within grace; a push killed mid-flight resends with no duplicates, `audit_events` count read back.

**U9.** Push writes the three columns, raw SQL read-back (mutation: skip one). `devices.app_version` set from header. Below-floor query returns expected devices on seeded rows. Smoke fails on a wrong SHA (mutation: skip the compare). No body in logs.

**U10.** D-30's test: digests equal, `verify_chain` passes, triggers still raise. Golden hash literal. Mutations: change `to_iso` format, red; a migration with an UPDATE on `audit_events`, red; a dropped trigger, red. Live, operator: restore a dump into a throwaway volume; `verify_chain` by pages; row count gap reported against a phone's SYNCED count.

**U11.** Token signed with old `kid` verifies during overlap; retired `kid` fails; no `kid` uses legacy (mutation: skip the lookup); unknown `kid` refused, never falling back (mutation: fall back). Canary follows the current key. Refresh under rotation: device stays signed in, row read back.

**U12.** Unclassified flag: test red. Clinical flag marked remote or non-build-time: red. `VOICE_FIELD_CHIEF_COMPLAINT_ENABLED` and `VOICE_AUDIO_ATTACHMENT_ENABLED` false in every variant: red on flip. Controlled docs per the sign-off batch.

**U13.** Allowed SHA passes. Other SHA gives the ruled result, with `kernel_call_log` read back. Null SHA is treated as a mismatch (mutation: pass on null, which is the characteristic bug). Live: Q-30.

**U14.** Own memo.

### 9.7 Operator questions

Read-only commands are for you to run. Pending Part A questions stay open (section 8).

**Q-21.** How do field phones get the app: public Play, private app on managed Google Play, MDM, or sideload? Do PHC phones have Google accounts, and who administers them? This decides D-21's update path, D-25's stages and PH-16. It must be settled before U5.

**Q-22.** Accept a 180-day support window? Do you have any real figure for the longest a phone stays offline? The repo states none.

**Q-23.** Accept two floors, sync and assess? Who may raise the assess floor for a recall, and what does a worker see?

**Q-24.** Play App Signing: Google-generated app key, or upload the existing `release.jks`? Has any phone ever received a build signed with that key? You check this; I read no keystore. Accept "never sideload a CI-signed build to a phone that will update from Play"?

**Q-25.** The ASR model is at least 661 MB. Install-time asset pack (recommended, works offline from first run), first-run download, or a smaller model, which would be its own design change? Please confirm Play's current limits in the console.

**Q-26.** Accept for `release/X.Y` the same exemption as Q-12: fix on master first, cherry-pick the merged fix, never merge back?

**Q-27.** Approve the direction of these `docs/backend/api-contract.md` amendments, as PROPOSED docs: section 0.1 (version headers, the Compatibility Rule), 0.8 and 6.1 (idempotency retention), section 1 (`/livez`, `/readyz`), section 6.1 (`protocol_version`, `SAMD-SYNC-6008`), new `SAMD-SYS-9008`.

**Q-28.** Idempotency: agree the 24-hour TTL is dropped? Keep rows indefinitely until a retention design exists? Does any DPDP or clinical retention duty set a period?

**Q-29.** Clinical call: on a classifier `model_sha256` outside the allowed list, refuse to the UNAVAILABLE card (recommended, R3) or record and flag only (R2)? What about a null SHA?

**Q-30.** Confirm S-3 stands, so no out-of-band model update until an ACP exists? Where does the classifier repo live, who deploys it, and can it run exported request fixtures? `classifier-target-architecture-memo.md` is untracked in your main checkout and I did not read it; does it change D-28?

**Q-31.** Rule the flag classes in D-26. In particular: is `PATIENT_AUDIT_ENABLED` operational or a compliance control, and is `RESUME_DRAFT_ENABLED` purely operational?

**Q-32.** Regulatory: the guidance sends a notification or request to the authority for any major or minor change "including software version update" (`Guidance...md:2330-2338`). Who files it, and with what lead time? That may set the real release cadence. Who owns the ACP, and by when, given `qms-overview.md:45`? The classification call on any change stays yours.

**Q-33.** HIGH, from PH-22: exclude `auth_session` from backup and transfer, or set `allowBackup=false` for prod (recommended for prod, pending a look at what else is backed up)? Worth filing separately from this design?

**Q-34.** Recovery targets: how much data loss and downtime are acceptable, and how long are backups kept? Must the device re-push memo exist before the pilot? I recommend yes, because until then a restore loses data nobody can list.

**Q-35.** DH-18 guard: the first run after U1 enrols whichever directory runs it. Will you run it first from the main checkout? Do you ever deliberately migrate the dev DB from a feature worktree? If so, is `SAMD_MIGRATE_ADOPT_HOME=1` acceptable friction?

**Q-36.** Alerts: you are the only responder. Email, phone, or a daily report for the pilot? Are D-29's thresholds acceptable as a starting point?

**Q-37.** When does the first pilot phone leave the lab, and when is the AWS session? Will the pilot hold real PHI from day one? This sets U14 and the merge order.

**Q-38.** `services/sync.py:429-433` says "Owner may overrule" on an unknown record field being TERMINAL. With the protocol gate, should same-protocol unknown fields stay TERMINAL (recommended, a true defect), or become RETRYABLE, which keeps data recoverable at the cost of retries capped by `MAX_SYNC_ATTEMPTS` (`SyncRetryPolicy.kt:26`)?

## 10. Gate rulings for PART B, 2026-11-05

Operator rulings on section 9, recorded verbatim in 10.1. Sections 0 to 9 are not edited. Where a ruling replaces text there,
10.4 says which. Status: PART A (section 8) and PART B rulings recorded; the items in 10.6 are still pending.

### 10.1 Rulings, verbatim

- D-18 to D-30: ACCEPT the memo's AMEND text as written, except where a ruling below says otherwise.
- Q-38: scope is only an unknown field name inside a same-protocol sync record (app and backend versions out of step, services/sync.py:427-440). That case becomes RETRYABLE, not TERMINAL, so a backend hotfix can recover the rows; the D-29 "any occurrence" alert stays. Before the U3b design is final, state in this section what happens to a row when MAX_SYNC_ATTEMPTS (SyncRetryPolicy.kt:26) is exhausted, with file:line; if exhaustion turns it FAILED, mark that as an open item for U3b. Duplicate-ABHA CONFLICT (sync.py:151-155), FK RETRYABLE and classifier unavailability are out of Q-38's scope and unchanged by this ruling.
- Q-31: PATIENT_AUDIT_ENABLED is a compliance control (build-time only, never off in staging or prod). RESUME_DRAFT_ENABLED is operational. DEVICE_RESOURCE_CHECK_ENABLED is a safety control if it guards free space for clinical writes; verify with file:line and record the result.
- Q-30: S-3 stands. No out-of-band model update until an ACP exists. The operator's classifier target architecture (bundled on-device classifier with OTA updates) therefore depends on the ACP; record that as a dependency of that track, not a rejection of it. The classifier repo is SaMDClassifier at /media/sandesh/extra-ssd/AndroidWork/SaMDClassifier, deployed by the operator.
- Q-21: pilot channel is a private Play closed testing track on facility Google accounts; managed Google Play with an EMM is a later option. Final confirmation is the operator's before U5.
- Q-22: accept the 180-day support window. Q-23: accept two floors; the operator raises the assess floor, with Prof. Banda's sign-off for a clinical recall; a worker sees the UNAVAILABLE card plus the update caption.
- Q-24: Google-generated app signing key with release.jks as the upload key, conditional on the operator confirming no phone has ever installed a release.jks-signed build. Never sideload a CI-signed build to a phone that will update from Play.
- Q-25: ASR model as an install-time asset pack; Play limits to be confirmed by the operator in Play Console.
- Q-26: accept the release/X.Y exemption, same as Q-12.
- Q-27: approve the direction of the api-contract.md amendments, as PROPOSED docs in their PRs.
- Q-28: drop the 24-hour TTL; keep rows until a retention design exists.
- Q-29: on assess, a classifier model_sha256 outside KERNEL_ALLOWED_MODEL_SHA256 refuses to the UNAVAILABLE card; a null SHA counts as a mismatch. /evaluate carries no model identity, so flag only there until it does. U13 is R3, Opus xhigh.
- Q-33: allowBackup=false for staging and prod; filed separately as HIGH, outside this design.
- Q-34: the device re-push memo must exist before the pilot.
- Q-35: the first post-U1 start runs from the main checkout; SAMD_MIGRATE_ADOPT_HOME=1 is acceptable friction.
- Pending from the operator: Q-32, Q-36, Q-37, Q-20, and the Part A pending list in section 8.
- Build order, provisional until Q-20 is answered: E1a, U1, E1b, E3a, then E3b, E2, E4, then U2 onward. If a demo falls within two weeks: E1a, E1b, E3a first.
- Filed, HIGH, before pilot and before U3b: a separate STEP 1 memo "patient identity and dedupe" (R3, Opus xhigh). Operator requirement, verbatim: the same person must never be registered twice; every consultation must attach to the one existing profile; data must never mismatch between patients. Facts found: no local lookup by ABHA or Aadhaar before registration (PatientDao.kt) and no local unique index; no server endpoint to find a patient by identifier (api/v1/patients.py); duplicate ABHA is a CONFLICT that dead-ends on the device; Aadhaar has only a non-unique blind index (models/patient.py:75). Direction for that memo: prevent at registration (local plus server lookup when online); an offline duplicate resolves at sync to the existing patient by a link done in one transaction on server and device, with an appended audit event; never auto-merge on weak signals (Aadhaar typed by hand, mobile, name plus DOB), which need a person to confirm; auto-link only on ABHA; legal check on Aadhaar storage and use as a key. Supersedes the tasklist item "Patient dedupe and merge".

The Q-38 text above is the operator's later, narrower wording. It contains the earlier one (RETRYABLE, the exhaustion statement, the open item).

### 10.2 Facts the rulings asked for

**Q-38: what happens to a row when `MAX_SYNC_ATTEMPTS` is exhausted.** All VERIFIED, read in the repo.

- A row acked `rejected` with `retry_class = RETRYABLE` becomes `RETRYABLE`, not terminal (`SyncAckMapping.kt:55-57`). It is collected again
  after at least `RETRY_MIN_INTERVAL`, 5 minutes (`SyncRetryPolicy.kt:41`), and at most `MAX_SYNC_ATTEMPTS = 5` times (`SyncRetryPolicy.kt:26`,
  `SyncState.kt:24-29`).
- On the attempt that reaches the cap, the same UPDATE that counts the attempt sets `syncState = 'FAILED'` and `syncErrorCode` to
  `SAMD-SYNC-RETRY-EXHAUSTED` (`PatientDao.kt:24-34`, taking `maxAttempts` and `retryExhaustedCode`; the code is `SyncRetryPolicy.kt:53`; the
  repository imports both, `RoomSyncOutboxRepository.kt:27-28`). The comment says the same: `SyncAckMapping.kt:25-28`. The count rises on every
  acked result that is not SYNCED (`PatientDao.kt:30`), so a phone that is offline spends none.
- **Exhaustion turns the row FAILED.** FAILED stops the outbox retrying it and leaves only by human action (`SyncState.kt:32-37`). The route out is
  "Send again": `requeueFailed` resets the count and clears the code, guarded on FAILED (`PatientDao.kt:36-49`). The app shows it as
  `RETRIES_EXHAUSTED`, "the ONE cause where pressing a button can genuinely change the outcome" (`SyncFailureReason.kt:39-42`).
- Timescale: the cap was sized for a parent row landing on the next drain, with five attempts carrying a row for at least an hour
  (`SyncRetryPolicy.kt:18-24`). INFERRED: a backend hotfix that lands more than about an hour after an online phone's first RETRYABLE ack finds the
  row already FAILED, and a worker has to press Send again.
- **OPEN ITEM for U3b, not resolved here:** the Q-38 recovery works only inside the retry budget. U3b's design must say what happens to an unknown-field
  row when the hotfix takes longer than the budget. Nothing is designed in this section.

**Q-31: what the three flags guard.**

- `DEVICE_RESOURCE_CHECK_ENABLED` is read at two sites only: the Register button on Home (`HomeScreen.kt:234`) and the Consultation button on Patient
  summary (`PatientSummaryScreen.kt:160`). The check warns below 15 percent battery or below 200 MB free on `filesDir`
  (`DeviceResourceCheck.kt:11-12,23-29`). The dialog always offers "Continue anyway" (`:32-33,40`). The file calls it "a courtesy nudge, not a safety gate",
  and it runs only before a new patient or consultation, never mid-consultation (`:14-19`). **Result: it does not guard free space for clinical writes,
  so by the operator's own test it is operational, not a safety control.** I did not search whether any write path checks free space. If none does, that is a
  separate item for the operator, and no flag covers it.
- `PATIENT_AUDIT_ENABLED` has one read site, the patient-facing "Who has seen your file" button (`FeatureFlags.kt:16-19`,
  `PatientSummaryScreen.kt:151`). It gates that entry point and not audit writing. Ruled a compliance control regardless.
- `RESUME_DRAFT_ENABLED` has one read site, the resume prompt on Home (`FeatureFlags.kt:21-24`, `HomeScreen.kt:148`). Operational, as ruled.

### 10.3 Filed items, outside this design

| Item | Priority | Gate | Note |
|---|---|---|---|
| `allowBackup=false` for staging and prod (Q-33) | HIGH | Separate item | Evidence is section 9.3 PH-22: `AndroidManifest.xml:22`, `backup_rules.xml:9`, `data_extraction_rules.xml:11,14`. Its grants and restore behavior were INFERRED there and stay so |
| Persona picker reachable in staging or prod (section 8, Q-17) | HIGH | Separate item | `RegisterScreen.kt:134-142`, unresolved |
| STEP 1 memo "patient identity and dedupe", R3, Opus xhigh | HIGH | Before pilot and before U3b | Operator text in 10.1. Facts below |
| STEP 1 memo "device re-push after a restore" (Q-34) | HIGH | Before pilot | Section 9.2 D-30, PH-28 |

Facts for the identity memo, each VERIFIED:

- No local lookup by ABHA or Aadhaar: `PatientDao.kt` has no query naming either (grep of the file: no match). No local unique index: `PatientEntity.kt:9`
  declares `@Entity(tableName = "patients")` with no `indices` (grep for `indices` and `@Index` in the file: none).
- No server endpoint to find a patient by identifier: the routes are create, roster, by id and patch (`api/v1/patients.py:60,98,155,175`).
- Duplicate ABHA: `ix_patients_abha_number` is unique (`models/patient.py:73`), the sqlstate maps to CONFLICT (`services/sync.py:151-155,168`), and a CONFLICT row
  has no "Send again" and leaves only by a local clinical edit (`SyncState.kt:14-22`).
  - **Superseded by (correction, same day):** the evidence in the line above describes `SyncState.CONFLICT`, which is the base-version conflict (the server
    acked `conflict`), a different path. A duplicate ABHA is acked `rejected` with `retry_class` CONFLICT and the device maps that to FAILED
    (`SyncAckMapping.kt:55-57`). Its reason is `DUPLICATE_RECORD` (`SyncFailureReason.kt:27-31,126-131`), whose only action is `TELL_SUPERVISOR`, with no
    button (`SyncFailureReason.kt:70,80-82`), because "there is no screen on this device that can do it" (`:29-30`). The operator's statement that it dead-ends
    on the device holds. The cited range `SyncState.kt:14-22` was also two lines wide: that doc comment is lines 16-21.
- Aadhaar has only a non-unique blind index (`models/patient.py:75`).
- OPERATOR, not checked: the tasklist item "Patient dedupe and merge". No tasklist file is tracked in this worktree.

### 10.4 What these rulings change in section 9

| Ruling | Where in section 9 | Effect |
|---|---|---|
| D-18 to D-30 ACCEPT | 9.2 | Verdicts become rulings, with the exceptions in this table. Section 9's text is unchanged |
| Q-38 | 9.2 D-18 bullet "Same-protocol unknown field stays TERMINAL"; 9.2 D-29 table row "`unexpected field` TERMINAL rejects" | Superseded: that case is RETRYABLE (`services/sync.py:434-440`, U3b). The alert still fires on any occurrence and now keys on the rejection message, not its class. U3b's test plan gains one row: an unknown field in a same-protocol record acks `rejected` with `retry_class` RETRYABLE (mutation: TERMINAL, red). Open item in 10.2 |
| Q-31 | 9.2 D-26 table | `PATIENT_AUDIT_ENABLED` moves from Operational to a compliance control: build-time only, never off in staging or prod. `RESUME_DRAFT_ENABLED` and `DEVICE_RESOURCE_CHECK_ENABLED` stay Operational, the second per 10.2. U12's registry needs a compliance class. Q-31 closed |
| Q-30 | 9.2 D-28 | S-3 stands, no OTA before an ACP exists. The on-device classifier with OTA is a track that depends on the ACP (`qms-overview.md:45`), recorded as a dependency and not a rejection. Classifier repo and deployer are now known. Still unanswered: whether SaMDClassifier can run the exported request fixtures, and whether the untracked classifier memo changes D-28. That repo and memo were not read |
| Q-21, Q-24, Q-25 | 9.2 D-21, D-25; PH-16; U5 | Channel: private Play closed testing track on facility Google accounts. Signing: Google-generated app key, `release.jks` as upload key, conditional on no phone ever having installed a `release.jks`-signed build. ASR: install-time asset pack. Operator confirmations before U5: the channel (Q-21), the no-prior-install fact (Q-24), Play limits (Q-25) |
| Q-22 | 9.2 D-21 | 180-day window accepted |
| Q-23 | 9.2 D-21 | Two floors accepted. The operator raises the assess floor, with Prof. Banda's sign-off for a clinical recall. A worker sees the UNAVAILABLE card plus the update caption |
| Q-26 | 9.2 D-25 | `release/X.Y` exemption accepted as Q-12 |
| Q-27 | 9.2 D-18, D-21, D-22, D-24 | The `api-contract.md` amendments are approved in direction and arrive as PROPOSED docs inside their own PRs |
| Q-28 | 9.2 D-24; U8 | 24-hour TTL dropped, rows kept until a retention design exists. U8 carries the corrected contract text |
| Q-29 | 9.2 D-28; 9.5 row 20 | Assess refuses to UNAVAILABLE on a SHA outside `KERNEL_ALLOWED_MODEL_SHA256`, and a null SHA counts as a mismatch. `/evaluate` is flag only. U13 is R3, Opus xhigh, replacing "R2 flag-only; R3 if refusing" |
| Q-33 | 9.3 PH-22; 9.5 U5; 9.6 U5 | `allowBackup=false` for staging and prod moves to the separate HIGH item in 10.3. U5 no longer carries the backup-rules content, its Q-33 dependency, or its backup-rules test. PH-22's control is that item |
| Q-34 | 9.2 D-30; 9.5 "Not PRs" | The device re-push memo is a pilot gate |
| Q-35 | 9.7 Q-35 | The first post-U1 start runs from the main checkout and enrols that directory. `SAMD_MIGRATE_ADOPT_HOME=1` is accepted friction. Closed |
| Identity memo | 9.5 U3b | New gate before U3b in 10.5. U3b's design may change when that memo lands |

### 10.5 Build order, provisional until Q-20

If a demo falls within two weeks: E1a, E1b, E3a first, accepting U1's small rework (section 9.5), then the order below.

| Seq | PR | Model, effort | Note |
|---|---|---|---|
| 1 | E1a canary | Opus, high | |
| 2 | U1 schema safety, DH-18 guard | Opus, xhigh | R3 |
| 3 | E1b stacks | Opus, high | |
| 4 | E3a slots | Opus, high | |
| 5 | E3b profile and pairing | Opus, high | R2 per section 8 |
| 6 | E2 bundle | Opus, high | |
| 7 | E4 seed | Opus, high | |
| 8 | U2 wire guard | Opus, high | |
| 9 | U3a identity gate | Opus, high | |
| gate | Memo "patient identity and dedupe" | Opus, xhigh | STEP 1 memo, R3, before U3b |
| 10 | U3b protocol gate | Opus, high | Open item in 10.2 |
| 11 | U4 app identity and refusal | Opus, xhigh | R3. U3a and U3b deployed first |
| 12 | U5 release pipeline | Opus, high | After the Q-21, Q-24, Q-25 confirmations |
| 13 | U6 frozen contract | Opus, high | |
| 14 | U7 Room chain test | Opus, high | |
| 15 | U8 liveness, readiness, graceful | Opus, high | Carries the idempotency contract text |
| 16 | U9 per-version visibility | Opus, high | |
| 17 | U10 audit and restore | Opus, high | |
| 18 | U11 JWT `kid` | Opus, high | |
| 19 | U12 flag registry, ACP | Sonnet, high, plus `edge-case-hunter` | Compliance class added |
| 20 | U13 model pin | Opus, xhigh | R3 per Q-29 |
| 21 | U14 rekey with KMS | Opus, xhigh | Own memo first. Before real PHI |

Before the pilot, in addition to the 9.5 "Before pilot" column: the identity memo and its build, and the device re-push memo (Q-34).

### 10.6 Still pending from the operator

- Q-32, Q-36, Q-37, Q-20.
- Part A list in section 8: Q-1, the actual five-flag list for Q-2, the Q-4 prefix, Q-6, Q-8, Q-10, Q-14, Q-15, Q-19, Q-20.
- Confirmations named in 10.4: Q-21 channel and Q-24 no-prior-install fact before U5, Play limits (Q-25).
