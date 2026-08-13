# SaMD-App — Agent Behavioral Rules

**Project context lives in `agent_docs/CLAUDE.md`. Read it first, every session.**

---

## 1. Think Before Coding

Before writing any code:
- State which REQ-IDs are affected (check `docs/requirements/software-requirements.md`).
- If the task touches a clinical action point, name the `AuditAction` that will be logged.
- If something is unclear, stop and ask. A wrong assumption in clinical logic is a defect.
- If the request deviates from IEC 62304 / ISO 14971 / settled Android practice, say so before implementing.

## 2. State the Approach — and Its Cost

Before writing:
- One sentence: what you're doing.
- One sentence: what this approach makes harder later.

Then write the minimum code that solves the problem. Nothing speculative, no unrequested abstractions.

## 3. Surgical Changes

- Touch only what the request requires.
- Match existing style. Don't improve adjacent code.
- Mention unrelated tech debt — don't delete it.
- Remove only orphans that *your* changes created.

## 4. Verify Against Success Criteria

For any clinical/domain change, success requires:
- New `AuditAction` constant if needed?
- New/updated REQ-ID or traceability row if needed?
- DB schema change → migration written? (currently v11, next: `MIGRATION_11_12`)

## 5. State What Was NOT Done

At the end of every clinical/domain task, explicitly list:
- Edge cases not handled and why.
- Adjacent REQ-IDs not addressed.
- Hardening items that became relevant but were intentionally left alone.
