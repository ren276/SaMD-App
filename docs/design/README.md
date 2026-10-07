# Design records

Memos, audits, investigations and read-me notes that record why the code is the way it is. They were
written in `scratchpad/` and are tracked here so that every citation in the code, in `PROGRESS.md`
and in `docs/` resolves in any checkout.

This folder is outside the IEC 62304 controlled set (`docs/quality`, `docs/requirements`), as
`docs/domain/` is. A memo here is a design record, not a controlled document: the controlled
documents cite a memo and carry the decision, and a memo's operator rulings are recorded in the memo
that received them.

Files are flat and keep their original names, so a `scratchpad/<name>` citation became
`docs/design/<name>`. Older memos describe the work session that wrote them and so still say
"scratchpad/" in prose (for example "nothing was written outside scratchpad/"); those are
statements about that session, not paths to follow. A few cite session scratch files that were never
committed (for example `scratchpad/results_cpu.json`); those stay dangling by design.

New design memos go here directly, not in `scratchpad/`.
