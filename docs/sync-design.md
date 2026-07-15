# Offline sync design (deferred — no backend yet)

> **Status: not built.** `agent_docs/hardening.md` places real sync (WorkManager) and the
> backend in "explicitly later — no backend to sync to yet." The app currently ships
> `MockSyncStatus` (UI-only: last-synced time + a Sync-now button, no data transport). This
> document captures the intended behaviour and the recommended production architecture so it
> is ready to build when the backend exists. The seam is the `SyncStatus` domain interface.

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

1. **Per-record sync state.** Add to each syncable entity: `syncState` (`PENDING` | `SYNCED`),
   `updatedAt`, and a `serverVersion`/etag. Local writes are immediate and marked `PENDING`.
   This replaces the whole-DB snapshot: the "backup" is simply that durable, transactional
   rows persist locally until confirmed synced.
2. **Outbox / RemoteMediator.** A background `WorkManager` job (connectivity constraint +
   exponential backoff) pushes `PENDING` records when online, idempotently, and marks them
   `SYNCED` on server ack. Room 3 `RemoteMediator` is the idiomatic fit for the read side.
3. **Conflict resolution.** Start with last-write-wins keyed on `updatedAt` + `serverVersion`;
   escalate to field-level merge or vector clocks only if real conflicts appear. Sync must be
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
