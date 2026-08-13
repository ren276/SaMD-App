# Quality Management System — Overview (scaffold)

> **Scaffold, not a compliant QMS.** This is a starting index for the ISO 13485 quality system
> a production SaMD requires. It must be owned, populated, approved, and version-controlled by
> qualified QA/RA personnel. Items marked **TODO** are organisational deliverables, not code.
> See `docs/regulatory-foundation.md` for the regulatory context and why this must start now.

## Scope
Quality system for **PHC Patient Care**, a Software as a Medical Device (SaMD) intended for
CDSCO-regulated deployment in India. Software developed under **IEC 62304**; risk managed under
**ISO 14971**; quality system per **ISO 13485:2016**; usability per **IEC 62366-1**; CDSCO
submission guided by the **CDSCO Guidance Document on Medical Device Software (Doc No.
CDSCO/MD/GD/MDSW/01/2026)** — supersedes the October-2025 draft and the older MD-5/MD-9
shorthand this project previously referenced. Full text tracked at
`docs/Guidance document on Medical Device Software under MDR-2017.md`; classification analysis
in `docs/regulatory-foundation.md` §2.3.

## Roles & responsibilities (TODO — assign named owners)
| Role | Responsibility | Owner |
|------|----------------|-------|
| Top management | QMS resourcing, management review | TODO |
| Quality/Regulatory (QA/RA) | QMS, DHF, risk file, CDSCO submission | TODO |
| Software lead | IEC 62304 processes, architecture, V&V | TODO |
| Clinical lead | clinical requirements, kernel validation, clinical risk | TODO |
| Data Protection Officer | DPDP Act 2023 compliance | TODO |

## Required procedures (SOPs) — ISO 13485 / IEC 62304 (status: mostly TODO)
| SOP | Standard basis | Status |
|-----|----------------|--------|
| Document & record control | ISO 13485 §4.2 | **partial** — `docs/` tracked in git; formal control TODO |
| Software development lifecycle plan | IEC 62304 §5.1 | TODO |
| Requirements management | IEC 62304 §5.2 | **started** — `docs/requirements/software-requirements.md` |
| Architecture & design | IEC 62304 §5.3–5.4 | **implicit in code** — Clean Architecture; formal design docs TODO |
| Implementation & unit verification | IEC 62304 §5.5 | **partial** — code exists; unit tests TODO (see roadmap #4) |
| Integration & system testing | IEC 62304 §5.6–5.7 | TODO |
| Software release | IEC 62304 §5.8 | TODO |
| Risk management | ISO 14971 | **started** — `docs/quality/risk-management-file.md` |
| Configuration management | IEC 62304 §8 | **partial** — git + pinned `libs.versions.toml`; formal CM plan TODO |
| Problem resolution | IEC 62304 §9 | TODO (issue tracker + CAPA) |
| Usability engineering | IEC 62366-1 | TODO |
| Design controls & Design History File | ISO 13485 §7.3 | **started** — `docs/quality/design-history-file.md` |
| Cybersecurity | IEC 81001-5-1 / AAMI TIR57 | **partial** — SQLCipher, Keystore; threat model TODO |
| Post-market surveillance | ISO 13485 §8, MDR 2017 | TODO |
| Data protection | DPDP Act 2023 | **partial** — encryption + data minimisation; consent/DPO TODO |
| Algorithm Change Protocol (ACP) | CDSCO/MD/GD/MDSW/01/2026 §9.0 | TODO — required before any post-deployment update to the kernel model (`/v1/assess`, `/api/v1/evaluate`); no version-gating exists yet (`ai_kernel_version` gap, see `agent_docs/hardening.md`) |
| Continuous performance assurance / drift monitoring | CDSCO/MD/GD/MDSW/01/2026 §9.0 | TODO — production monitoring for clinically significant performance degradation and algorithm drift; nothing exists post-deployment today, only pre-release scenario testing |
| AI risk management | IS/ISO/IEC 23894 | TODO — distinct from the general ISO 14971 hazard register; AI-specific risks (dataset bias, drift, out-of-distribution inputs) not yet separately tracked |
| AI management system | IS/ISO/IEC 42001 | TODO — organisational AI governance layer, separate from the software QMS above |

## Management review & CAPA
TODO — establish periodic management review and a Corrective/Preventive Action process before
production. Link to the issue tracker once problem-resolution SOP exists.

## Where the records live
- Tracked controlled docs: `docs/`
- Source & config management: this git repository (pinned dependency versions)
- Local working/agent notes (not controlled records): `agent_docs/` (gitignored)
