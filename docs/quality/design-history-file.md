# Design History File (DHF) — index (scaffold)

> **Scaffold.** ISO 13485 §7.3 requires a DHF: the compiled record of the design & development
> of the device. This is the *index* pointing at each record. Records marked **TODO** don't
> exist yet. To be maintained by QA/RA.

| Design record | Location | Status |
|---------------|----------|--------|
| Regulatory strategy & classification | `docs/regulatory-foundation.md` | started (provisional Class B) |
| Software safety classification (formal) | TODO | **open** — must be decided by risk analysis |
| User needs / intended use | TODO | open — draft from `docs/regulatory-foundation.md` §1 |
| Software requirements (SRS) | `docs/requirements/software-requirements.md` | started |
| Requirements traceability matrix | `docs/requirements/traceability-matrix.md` | started |
| Architecture description | code (`presentation/` → `domain/` → `data/`); formal doc TODO | partial |
| Detailed design | code + KDoc; formal doc TODO | partial |
| Risk management file | `docs/quality/risk-management-file.md` | started |
| Verification & validation records | TODO (see roadmap #4: tests + CI) | **open** |
| Usability engineering file (IEC 62366) | TODO | open |
| Cybersecurity / threat model | partial (SQLCipher, Keystore); doc TODO | partial |
| Release records | TODO | open |
| Change history | git commit history of this repository | available |

## Notes
- The clean layered architecture and pinned dependency versions give the DHF a real
  configuration baseline today (git history + `libs.versions.toml`).
- The biggest open DHF gaps are **V&V records** (blocker #4) and the **formal safety
  classification** (blocker #2) — both gate a credible submission.
