# Offline sync design (real backend deferred — the queuing pattern is now built)

> **Status: PARTIAL (2026-07-19).** `agent_docs/hardening.md` places real sync (WorkManager) and
> the backend in "explicitly later — no backend to sync to yet" — still true, no change there.
> What's now real: the recommended pattern below (§2, per-record pending/synced state) is built
> for the one place data crosses a boundary today, doctor assignment. Confirming a doctor while
> offline (real network down, or the worker's manual toggle) sets `CaseStatus.PENDING_SYNC`
> instead of `SENT_TO_DOCTOR` — see `CaseRecordRepository.assignDoctor(isOnline)`. `MockSyncStatus`
> refuses to run while offline, and auto-syncs every `PENDING_SYNC` case the instant connectivity
> returns (real network or the manual toggle flipping back on) via a background watcher on
> `ConnectivityController.isOnline` — the worker doesn't have to remember to tap Sync Up, though
> the button still works for "send right now." **Update 2026-08-17:** the generic `syncState`
> convention below (§2 item 1) is now built, `MIGRATION_12_13`, `sync_state`/`server_version`/
> `sync_error_code`/`last_sync_attempt_at`/`local_modified_at` on all 20 syncable entities per
> api-contract.md §6.1's Android handling rule. Schema only, nothing reads or writes it yet: no
> DAO queries over it, no repository sets it, `MockSyncStatus`/`CaseStatus.PENDING_SYNC`/
> `synced_to_cloud_at` untouched. Still not built: `WorkManager`/a real backend, conflict
> resolution, and purge-on-sync minimisation (§2 items 2-5 below). The seam is still the
> `SyncStatus` domain interface.

## 1. Requirement (as described)

- **Online:** local changes sync up to our database.
- **Offline, two cases:**
  1. the last DB "image" with a timestamp is kept as a **backup**, and
  2. new data keeps being captured locally without friction;
- when the network returns, local data **syncs** to the server, and the **old timestamped DB
  backup is deleted** as data minimisation — described as improving **ACID consistency**.

The goals underneath this: (a) never lose field data when offline, (b) never block the worker
on connectivity, (c) reconcile cleanly when back online, (d) keep only what's needed on-device.

## 2. Recommended architecture (achieves the same goals, more robustly)

The whole-DB-image-with-timestamp backup is **not recommended** — it is heavy, scales poorly
as data grows, and works against data-minimisation (it duplicates the entire DB). Room/SQLite
already give **local ACID** via transactions + WAL, so a durable local write is not at risk of
being lost; a separate full-image backup is redundant for that purpose. Use the standard
**offline-first, row-level** pattern instead:

1. **Per-record sync state, DONE 2026-08-17 (schema only, `MIGRATION_12_13`).** Four states,
   not two, per api-contract.md §6.1's Android handling rule: `syncState` is `PENDING` (local
   write, not yet acknowledged), `SYNCED` (server acknowledged `applied`/`stale`/`duplicate`),
   `CONFLICT` (server acknowledged `conflict`, stays queued for review), or `FAILED` (server
   acknowledged `rejected`, stop retrying a row that will never stop being malformed). Every
   syncable entity also gets `serverVersion` (nullable, matches `base_version: null` for a
   record the server has never seen), `syncErrorCode`, `lastSyncAttemptAt`, and
   `localModifiedAt` (sync metadata distinct from any entity's own clinical `updatedAt`, the
   uniform column Phase 6's outbox reads regardless of entity). All pre-existing rows are
   `PENDING` with `serverVersion = NULL`, deliberately: no device has ever pushed anything, so
   the first sync after Phase 6 drains the full local history and must chunk against the
   500-record/5 MB batch limit. Local writes will be immediate and marked `PENDING`; this
   replaces the whole-DB snapshot, since the "backup" is simply that durable, transactional
   rows persist locally until confirmed synced. Nothing sets or reads these columns yet.
2. **Outbox / RemoteMediator.** A background `WorkManager` job (connectivity constraint +
   exponential backoff) pushes `PENDING` records when online, idempotently, and marks them
   `SYNCED` on server ack. Room 3 `RemoteMediator` is the idiomatic fit for the read side.
3. **Conflict resolution.** Start with last-write-wins keyed on `localModifiedAt` (mapped to
   `client_updated_at` on the wire) + `serverVersion`; escalate to field-level merge or vector
   clocks only if real conflicts appear. Sync must be
   **idempotent** (safe to retry after a half-completed round).
4. **Consistency model — name it honestly.** *Local* operations are ACID (SQLite transactions).
   *Device ↔ server* is **eventual consistency** with conflict resolution, not distributed
   ACID. The requirement's "improves ACID consistency" is really "durable local writes +
   reliable eventual reconciliation" — which this delivers.
5. **Data minimisation on success.** After a record is `SYNCED` *and* falls outside the
   day-scope window (see the `observePatientsWithEncounterBetween` cache scoping), purge it
   from the device. This is the correct place for the "delete the old backup" intent — purge
   confirmed-synced, out-of-window rows, not a whole-DB image.

## 3. Why not the whole-DB image backup

- **Scale:** duplicating a growing DB on every offline transition is unbounded work/space.
- **Data minimisation (DPDP):** a full image is the opposite of minimisation; row-level state
  keeps exactly the pending slice.
- **Recovery granularity:** an image can only restore *all-or-nothing*; row state lets each
  record sync/retry independently.
- **Already durable:** WAL + transactions mean an offline write is not lost without an image.

## 4. When built, revisit

- `SyncStatus` implementation swaps `MockSyncStatus` → real engine (interface unchanged).
- Add `ai_kernel_version` to `CaseRecord`/`AuditLogEntity` once the kernel is real and versioned
  (deferred per `agent_docs/hardening.md`).
- Backend + localisation per `docs/regulatory-foundation.md` Phase 2.
