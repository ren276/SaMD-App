# kernel_reports upsert investigation (STEP 1, read-only)

Date: 2026-08-28. Branch: master @ 600e0e2. Read-only, no code changed.

## Verdict

**Defect ABSENT.** The hypothesis describes the pre-e8bdd73 shape. The fix already
landed in commit `e8bdd73` "Enforce one current assessment per case on kernel_reports
and evaluate_reports", which fixed the kernel path and the evaluate path together.
kernel_reports has the H-14-equivalent protection. Queue work can build on this path.

One residual (not the hypothesised defect, low severity) noted at the end.

## Trace: /assess response to DAO write

1. `SendingViewModel.kt:82` (and `RetryKernelAssessmentUseCase.kt:47`) call
   `GenerateKernelReportUseCase`.
2. `GenerateKernelReportUseCase.kt:104` — the single save point for all three outcome
   shapes: `kernelReportRepository.save(output).map { output }`.
3. `KernelReportRepositoryImpl.kt:29-31` — id resolution and write.
4. `KernelReportDao.kt:14-15` — `@Insert(onConflict = OnConflictStrategy.REPLACE)`.
   Generated SQL confirmed: `INSERT OR REPLACE INTO kernel_reports (...)`
   (`app/build/generated/ksp/devDebug/kotlin/.../KernelReportDao_Impl.kt:43`).

## The id-assignment line

The domain object's id IS a fresh UUID per call, at three sites:

- `GenerateKernelReportUseCase.kt:204` — real-API path (`tryRealApi`).
- `GenerateKernelReportUseCase.kt:251` — `buildUnavailableOutput`.
- `MockKernelFallbackSource.kt:233` — dev-flavor fallback.

That fresh UUID is then **discarded on re-assessment**. The row id actually written is
assigned at:

**`app/src/main/java/com/example/samdapp/data/repository/KernelReportRepositoryImpl.kt:29`**

```kotlin
val id = kernelReportDao.getIdForCase(report.caseRecordId) ?: report.id
```

Keyed on `caseRecordId`, falling back to the fresh UUID only for a genuinely new case.
Backed by `KernelReportDao.getIdForCase` (`KernelReportDao.kt:26-27`).

## UNIQUE index status, and whether the save path honors it

- Entity: `KernelReportEntity.kt:16` —
  `@Entity(tableName = "kernel_reports", indices = [Index("caseRecordId", unique = true)])`.
- Migration: `Migrations.kt:430` `MIGRATION_15_16` — de-dups newest-wins by
  `localModifiedAt DESC, rowid DESC`, drops the old non-unique index, recreates it
  UNIQUE. Applies to both `kernel_reports` and `evaluate_reports`.
- DB version: **16**, `AppDatabase.kt:72`. Registered at `DatabaseModule.kt:96`.
- Exported schema confirms it: `app/schemas/.../16.json:1907` —
  `CREATE UNIQUE INDEX IF NOT EXISTS index_kernel_reports_caseRecordId ...`.
- Migration test exists: `app/src/androidTest/.../MigrationTest15To16.kt:43`.

Does the save path honor it? Yes, twice over, and the two mechanisms are independent:

- `getIdForCase` makes the write target the existing row **by primary key**, so the
  REPLACE is an ordinary PK-conflict replace and the row id stays stable.
- `INSERT OR REPLACE` in SQLite resolves a conflict on **any** uniqueness constraint,
  the `caseRecordId` unique index included. So even if `getIdForCase` were bypassed,
  the write degrades to delete-then-insert rather than an IntegrityError.

The second mechanism is a backstop, not the design. Relying on it alone would churn
the primary key on every save, which would break outbox correlation (`applySyncResult`
matches `WHERE id = :id`).

## Retry outcome

Same `/assess` called twice for one case, sequentially (the WorkManager retry shape):

**One row. Updated in place, primary key unchanged.**

Evidence:
- 1st call: `getIdForCase` returns null, `id = report.id` (fresh UUID), insert.
  (`KernelReportRepositoryImpl.kt:29`)
- 2nd call: `getIdForCase` returns the id written by call 1, so the fresh UUID from
  `GenerateKernelReportUseCase.kt:204` is dropped. `INSERT OR REPLACE` on the same PK
  replaces the row. (`KernelReportRepositoryImpl.kt:29-31`, `KernelReportDao.kt:14`)
- `serverVersion` survives: read back at `KernelReportRepositoryImpl.kt:30` via
  `getServerVersion(id)` on the RESOLVED id and threaded into the replacement entity
  (`:62`). Without the resolved id this read could only ever return null.
- `syncState` correctly resets to PENDING: not by explicit assignment, but because
  REPLACE writes the full default set and `KernelReportEntity.kt:38` defaults to
  `SyncState.PENDING`. Consistent with the syncState-reset policy.
- Unit-test coverage already asserts this:
  `KernelReportRepositoryImplTest.kt:115` `secondSaveForTheSameCase_replacesTheExistingRowInsteadOfInsertingASecondOne`,
  `:146` `secondSaveForTheSameCase_preservesServerVersionAcrossTheRetry`,
  `:170` `firstSaveForANewCase_hasNoServerVersionToPreserve`.

Note the tests are against a `FakeKernelReportDao` (`:27`), not real Room, so they
assert the repository's id-resolution logic rather than the index. The index itself is
covered separately by `MigrationTest15To16`.

## Comparison against the H-14 fix in EvaluateReportRepositoryImpl.save()

They are the same fix, shipped in the same commit. Side by side:

| | Evaluate (`EvaluateReportRepositoryImpl.kt`) | Kernel (`KernelReportRepositoryImpl.kt`) |
|---|---|---|
| resolve id by case | `:49-50` | `:29` |
| read back serverVersion on resolved id | `:51` | `:30` |
| upsert REPLACE | `:52` | `:31` |
| unique index on caseRecordId | yes (MIGRATION_15_16) | yes (MIGRATION_15_16) |

Kernel is NOT in the pre-H-14 shape. The only structural difference is that evaluate
carries a `failureCode` column and a `saveFailure` path (`:66-85`, itself id-resolved
the same way); kernel expresses the same idea as an in-band
`InferenceSource.UNAVAILABLE` row rather than a separate failure column. That is a
deliberate difference, not a gap.

## Fix warranted?

For the hypothesised defect: **no**. Nothing to build.

One residual worth a decision before the queue lands, distinct from the brief:

**Non-atomic read-modify-write.** `getIdForCase` / `getServerVersion` / `upsert` are
three separate DAO calls with no `@Transaction` anywhere in `KernelReportDao` or
`EvaluateReportDao`. Two concurrent saves for the same case can both read null from
`getIdForCase`, mint different UUIDs, and both insert. Outcome is still one row (the
caseRecordId unique index makes the second an INSERT OR REPLACE), but the surviving
row's **primary key is the loser of the race**, and any outbox entry already holding
the first id would then target a row that no longer exists.

- Reachable today? Only if two assessments for one case overlap in time. The current
  UI path is sequential, so this is latent.
- A WorkManager retry queue raises it: a queued retry firing while a foreground
  assessment is in flight is exactly the overlap.
- Scope if you take it: wrap the resolve-plus-upsert in a `@Transaction` DAO method
  (both repositories, for symmetry). **No migration needed** — schema is already
  correct, this is write-path atomicity only.
- Classification: **Sonnet-mechanical**, small and well-bounded, IF the decision to do
  it is already made. The decision itself (does the queue actually create the overlap,
  or does queue design make overlap impossible) is a design question that belongs with
  the queue work, not ahead of it.

## Audit chain / traceability

Nothing found that widens this beyond the brief. `InferenceSource` is carried on every
row and survives replace; a retry superseding an `UNAVAILABLE` row is the intended
behavior and is documented at `KernelReportEntity.kt:49-53`. The one-current-assessment
-per-case contract means the DB holds the current assessment, not a history of
attempts, which is the already-recorded decision from the MIGRATION_15_16 session.
No wider Opus design pass flagged.
