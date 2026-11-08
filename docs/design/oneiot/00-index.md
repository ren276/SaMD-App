Status: RESEARCH, not a design ruling. Sources read: operator brief "ONEIOT STUDY" (2026-11-06), operator replies at STOP-0A.

# OneIoT study: index

Study worktree: `/media/sandesh/extra-ssd/wt-oneiot-study`, branch `docs/oneiot-study`, cut from `origin/master` at `4b9ee3c`.
Scratch: `/media/sandesh/extra-ssd/oneiot-study/`. Memos stay uncommitted.

## Scope rulings from the operator (STOP-0A, 2026-11-06)

| Item | Ruling |
|---|---|
| OneIoT source | `/media/sandesh/extra-ssd/AndroidWork/OneIOTApp` only. `/media/sandesh/extra-ssd/veman/Android-oneIot--main` is excluded. |
| Public key files | May be read. |
| `Untitled Document 1` | Scan first, use if it matters. Scan clean: it is used. |
| Stop script | The brief's script only, for this study. |
| Paper | `A_novel_IoT-device_management_platform_for_on-the- (1) (1).pdf`. Proposed at STOP-0A, no objection. |

The operator's "don't worry about keys" answered a question about public key files. `privateKey.txt` and
`app/google-services.json` remain unopened under brief rule 1 and the project `CLAUDE.md` credential rule.

## Rulings at STOP 0 (operator, 2026-11-06)

| Id | Ruling | Where applied |
|---|---|---|
| R0-1 | Upstream unset on `docs/oneiot-study` | done, `status -sb` shows no upstream |
| R0-2 | No `cat` or broad globs near credential files; list first | all Phase 1 commands |
| R0-3 | Key pair and google-services file treated as compromised; never reuse scheme in SaMD | `06` section 1 |
| R0-4 | Veman comparison table | **dropped**: the operator's message said not to use veman at all |
| R0-5 | APK class and method names only | `02` section 6 |
| R0-6 | Questions a to h | `02` section 7, `03` sections 7 and 8 |
| R0-7 | Hypothesis, evidence only until Phase 3 | `02` section 10, `07` section 7 |
| R0-8 | Log root free space at each STOP | stop messages |

## Addendum, scope change, transports (operator, 2026-11-06)

| Id | Item | Where |
|---|---|---|
| A1 | Figures rendered and transcribed | `05` section 11 |
| A2 | Security stories table, manifest vs RSA limit | `06` section 7 |
| A3 | Further docs-vs-code checks | `02` section 11 |
| A4 | Grammar audit | `03` Appendix B |
| A5 | Upstream check | Optional, waits for operator's clone |
| A6 | Related papers to request | Phase 3, `14` |
| A7 | Heartbeat vs liveness terminology | `02` section 12, `05` section 10 note |
| S1 | Demo uses the OneIoT app as a separate app | `07b`, framing for later phases |
| S2 | Key files: present, unread, operator states rotated | `01` section 11, `06` section 6 |
| S3 | Runnability, reading and Intent hooks, STOP-1B answer | `07b`, `07` section 8 |
| S4 | Thing adapter design | Phase 2, `09` |
| S5 | Demo bridge and target options | Phase 3 |
| T-1 | Bearer inventory | `05` section 12 |
| T-2 | SaMDPi BLE stack, SaMD BLE and CDM, USB and NFC search | Phase 2, `08`, `09` |
| T-3 | Transports memo | Phase 3, `11b` |

## Rulings at STOP 1A (operator, 2026-11-06)

| Id | Item | Where |
|---|---|---|
| R1-1 | STOP-1B no | Superseded by R2-1 |
| R1-2 | 06 amendment, masking tightening | `06` section 1; `cloud.py:10-35` and `ipverify.py:20-45` never printed raw again |
| R1-3 | Demo viability memo | `07b-demo-viability.md`; `07b-runnability-for-demo.md` kept, cited by R2-1, R2-5 |
| R1-4 | New repo planning assumption | Replaced by R2-3 |
| R1-5 | Instrument detail, vitals form | `09` section 1, `08` section 5 |
| R1-6 | Memo 12 scope | Phase 3 |
| R2-1 | Build of a copy | `07` section 10: success |
| R2-2 | POC memo, baseline rule | `08a` |
| R2-3 | Staged replacement in the same app | Planning assumption, Phase 3 memo 10 |
| R2-4 | Recommendations a to d | Phase 3; evidence for b in `09` section 4 |
| R2-5 | Hub OS measured: Debian 13.7 | `07` section 11, `07b-runnability-for-demo.md` section 4 |

## Operator request outside the study: OneIoT private repo

Operator asked in chat for a new private repo with its own commits. This supersedes R2-3's "not the study's job" for repo creation. State: `ren276/OneIOTApp` created, PRIVATE, empty. Local `git init` in the OneIoT folder with `origin` set. `.git/info/exclude` keeps credential files out. **No commit yet**: waits for operator's "authorized".

Superseded by R3-0 (operator, 2026-11-06): commit 1 authorized and pushed, `25952a2` on `main`, 103 files; `cloud.py`, `ipverify.py`, key files, `app/py311/`, `.idea/` excluded by `.gitignore`.

## Rulings at STOP 2 (operator, 2026-11-06)

| Id | Item | Where |
|---|---|---|
| R3-0 | Commit and push OneIoT snapshot | Done, `25952a2` |
| R3-1a | Protocol v2 and reading model extend SaMDPi | `12` sections 4, 5; final ruling at STOP 3 |
| R3-1b | Manifest v2 BINDINGS, generated, served on both bearers | `12` sections 2, 3; `11b` section 1.4 |
| R3-1c | Hub option A1 leading | `10` section 4 |
| R3-1d | Real states, latency constants only | `12` section 4.5 |
| R3-1e | Thermometer and wired | `10` section 5, `11b` section 3, OQ-3 |
| R3-1f | Demo link security | `12` section 7, `13` section 4 |
| R3-1g | SaMD items separate | `13` section 5, `10` section 1.4 |
| R3-1h | Demo bridge defaults | `10` sections 1, 3 |
| R3-1i | BLE single central | `10` section 1.6 |
| R3-1j | P1, P2, P3 | `10` section 1.7 |
| R3-2 | Open items | `14` |

## Sources outside the study worktree

| Repo | Path | State |
|---|---|---|
| OneIoT | `/media/sandesh/extra-ssd/AndroidWork/OneIOTApp` | not a git repo |
| SaMDPi | `/media/sandesh/oneTB_SSD/SaMDPi` | `master` at `bba2c6b`, clean. Not at the path the brief guessed; located via clone remotes. |
| SaMD-App main checkout | `/media/sandesh/extra-ssd/AndroidWork/SaMDApp` | `master` at `7e98cd8`, 9 dirty entries. Never a source of code facts. |

Superseded for OneIoT: it is now a git repo, `ren276/OneIOTApp`, `main` at `25952a2` (R3-0).

## Memos

| File | Phase | Status |
|---|---|---|
| `00-index.md` | 0 | this file |
| `01-inventory.md` | 0 | written |
| `02-what-oneiot-is.md` | 1 | written |
| `03-manifest-and-dictionary-spec.md` | 1 | written |
| `04-ui-generator.md` | 1 | written |
| `05-device-protocol-and-data-output.md` | 1 | written |
| `06-security-and-failure-handling.md` | 1 | written |
| `07-modernization-inventory.md` | 1 | written, build result pending STOP-1B |
| `07b-runnability-for-demo.md` | 1 | written, added by S3a |
| `07b-demo-viability.md` | 1 | written, added by R1-3 |
| `08a-samdpi-openiot-poc.md` | 2 | written, added by R2-2 |
| `08-samd-current-vitals-pipeline.md` | 2 | written |
| `09-mapping-and-gap-analysis.md` | 2 | written |
| `10-integration-options.md` | 3 | written |
| `11-connection-and-liveness.md` | 3 | written |
| `11b-transports.md` | 3 | written, added by T-3 |
| `12-manifest-v2-protocol-v2-and-reading-payload.md` | 3 | written, renamed by R1-6 |
| `13-risk-and-regulatory.md` | 3 | written |
| `14-open-questions.md` | 3 | written |
| `15-handoff-digest.md` | 4 | pending |

Measured for `07`: the build result is in `07` section 10 (success, R2-1).

## Rulings at STOP 3 (operator, 2026-11-08)

| Id | Ruling, in short | Where applied |
|---|---|---|
| R4-0 | Build a NEW OneIoT app from scratch, faithful to the paper's protocol, on current tooling, for vitals. No legacy code copied. `ren276/OneIOTApp` is the legacy reference only; the operator renames and archives it. OQ-6 ruled **P3**, new repo created by the operator | `10` section 9; `15` |
| R4-K | Manifest keywords exactly as published. No new or renamed keywords. BINDINGS, named RANGE keys and CONTROL withdrawn. Metadata lives in Invoke Outputs. Manifest identity is a client-side sha256 of file bytes | `12` section 11; `15` sections 2, 4 |
| R4-F | Faithful means the paper's protocol, not legacy wire formats. Deviations only where the paper is silent or defective, each recorded | `15` section 3 |
| R4-R | Evaluate a minimal Registration host of our own. Final ruling at STOP 4 | `10` section 9.3; `15` section 7 |
| R4-1 | Accepted: D1 same phone; logical envelope; A1; no new capture-method value; R2-4 a to d with a sha256 allowlist until NFC; R3-1 a to j as changed by R4-0, R4-K, R4-F | `10` section 9; `15` |
| R4-2 | BLE manifest paging mandatory; long-read test dropped as a gate | `11b` section 8; `12` section 11.4 |
| R4-3 | Latency opt-in per session by a start-request flag; default instant | `11` section 8; `12` section 11.5 |
| R4-4 | SaMD items are separate SaMD PRs. Unit check and canonicalisation merge before the bridge. SAMD-1 in two layers, no values chosen here | `13` section 9 |
| R4-5 | Demo tiers 1 to 3 with effort bands | `15` section 8 |
| R4-6 | `applicationId` `com.example.oneiot`; no release key until production; dev caller check by `checkSignatures` returning `SIGNATURE_MATCH`; both apps built on the same machine | `10` section 9.2; `15` section 6 |
| R4-7 | Tag key line accepted for memos; digest tags every claim | `15` |
| R4-8 | Phase 4 digest contents, print, git status, STOP 4 | `15-handoff-digest.md` |

`15-handoff-digest.md` status: written in Phase 4 (2026-11-08). Superseded the "pending" row above.

Study closed at STOP 4 (2026-11-08). Paths under /media/sandesh/extra-ssd/oneiot-study/ are untracked scratch evidence. The schemas and reference manifests move to the ren276/OneIoT repo under spec/ (Phase C), which becomes the source for implementation.
