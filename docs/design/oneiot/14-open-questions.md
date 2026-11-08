Status: RESEARCH, not a design ruling. Sources read: memos `00` to `13`; operator rulings R0 to R3, A1 to A7, S1 to S6, T-0 to T-4.

Tag key: a claim with a file:line citation and no other tag is VERIFIED, read in code at the commit named above. A citation to another memo inherits that memo's tag. Anything else is marked INFERRED, DOCS-ONLY or EXTERNAL.

# 14: Open questions

Each is for the operator unless noted. "Blocks" says what cannot start or finish until answered.

## Rulings needed from Phase 3

| OQ | Question | Why it matters | Blocks | Recommendation |
|---|---|---|---|---|
| OQ-1 | Confirm D1 as the demo bridge? | Sets SaMD's work and the boundary argument | Stage 2f | Yes (`10` section 1) |
| OQ-2 | Same phone or second device for the demo? (S5) | Same phone gives D1. Second device gives D3 and 8 to 12 days | Stage 2f | Same phone |
| OQ-3 | **Does a third Pi exist?** (R3-1e, R3-2) | kernelhub3 depends on it | Thermometer, wired | If not: second process on kernelhub1 (`10` section 5) |
| OQ-4 | Must the demo show the OEM cloud Registration step? (S5) | If yes, R3 and R4 do not apply; needs a stub | Stage 2 | No (R2-4a) |
| OQ-5 | targetSdk 34 or 37? (S5) | 37 needs local-network handling; 34's grant is temporary | Stage 1 | 37 in the new shell (`10` section 3) |
| OQ-6 | P1, P2 or P3? And accept P2's two deviations: new shell first, one-step removal? | Sets effort and order | Stage 1 | P2 with deviations (`10` section 1.7) |
| OQ-7 | Logical envelope with a per-bearer mapping, rather than JSON on BLE? | R1-6 and R3-1a conflict | `oneiot-protocol` | Logical envelope (`12` sections 1, 4) |
| OQ-8 | BLE manifest paging by CP write, then read? | BP is 1,664 bytes; limit is 512 | Hub v2 | Yes; test long reads first |
| OQ-9 | A1 confirmed? | Hub design | Stage 2c | Yes (`10` section 4) |
| OQ-10 | No capture-method value for OneIoT, contrary to S5? | Avoids a synced enum change and keeps the method an attestation | Stage 2f | No new value (`10` section 1.3) |
| OQ-11 | Signing: same dev key for SaMD and OneIoT, or `knownSigner`? | Certificate check depends on it. Keys never shown to the study | Stage 2f | Operator decides; digest only |
| OQ-12 | New audit action, or new keys on the existing action? | New action means a backend mirror | Stage 2f | New action, per S5 |
| OQ-13 | Should a clinical manifest require `CODES`? | One MDC number unverified | Schema | Required once verified |

## Product and clinical

| OQ | Question | Why it matters | Blocks |
|---|---|---|---|
| OQ-14 | Entry point (b) data model: Q1, Q2 or Q3? Consent? Doctor visibility? Emergency check? | No home for vitals without an encounter (`10` section 7) | Entry (b) |
| OQ-15 | **Who owns plausibility bounds, and what are the values?** | None exist (SAMD-1) | SAMD-1; clinical sign-off |
| OQ-16 | Gate on `measured_at` age? What window? | Stale accepted today; hub clock is advisory | SAMD-7 |
| OQ-17 | The operator's input says primary use is triage. Must an instrument value trigger the emergency override on every entry point? | Entry (a) has the check; (b) has no save path yet | Entry (b) |
| OQ-18 | Should simulated lifecycle splits mimic a real cuff? | Placeholder split only | Hub v2 |
| OQ-19 | Should device `RANGE` be defined by anyone? Emulator has none | Placeholder scenario bands in manifests | Manifest v2 |

## Regulatory

| OQ | Question | Why it matters | Blocks |
|---|---|---|---|
| OQ-20 | POC disclaimer says no connection to clinical forms (`POC:17`), yet SaMD's dev path does so with synthetic data. Reword when? | Wording conflict in a claimed control | PR-C |
| OQ-21 | Severity, probability, signature for `H-OI-*` | PROPOSED only | Register entries |
| OQ-22 | Final IDs for `H-OI-*` | H-37 to H-46 are taken or reserved | Register entries |
| OQ-23 | Does a release path ever want BLE in main? | Reverses G-B11b, needs production security | T1 |
| OQ-24 | Is keeping a OneIoT identity needed for the TiH grant or the lab? | Not knowable from the repo | T1 naming, shell |
| OQ-25 | Is "OpenIoT-HealthSaRK" related to OneIoT organisationally? | Only name similarity found (`08a`) | Docs naming |

## Facts to measure or obtain (R3-2)

| OQ | Question | Why it matters | Blocks | Who |
|---|---|---|---|---|
| OQ-26 | **Pixel `PAGE_SIZE`.** Operator runs it when the phone is on this machine; the study does not run adb | Chaquopy 16.0.0 predates 16 KB support | Running old build; Stage 1 | Operator |
| OQ-27 | `docs/adr/` cited by `CLAUDE.md`, absent at `origin/master`, and `CLAUDE.md` is dirty in the main checkout | No ADR could be checked for conflicts | Trust in "locked architecture" checks | Operator |
| OQ-28 | 19 vs 23 synced tables: brief says 19, `LocalClinicalData.kt` has 23 `EntityScope` entries | May be different lists | Q2 option | Next memo |
| OQ-29 | Unverified: `MDC_PULS_RATE_NON_INV` number; LOINC 8889-8; ABDM and NRCeS FHIR bindings; `/min`, `mm[Hg]` from UCUM's own table | Manifest `CODES` | `CODES` required rule | Next research |
| OQ-30 | Related papers not in the repo: Banda, Chaitanya, Mohan 2015 SITIS; Banda et al. 2017 IJITST; demo paper "Demonstration of an Application Layer Omni-frequency IoT Protocol" (A6) | Protocol history; may explain gaps | None | Request from Prof. Banda |
| OQ-31 | Is the paper's public repo (github.com/nayancyiiti/IoT) this code, and did it ever hold the key files? (R0-3, A5) | Exposure assessment | None | Operator; A5 optional |
| OQ-32 | Do the DB credentials at `cloud.py:16-17` and `ipverify.py:28-29` still work? | Rotation | Operator's commit of those files | Operator |

## Transport tests before design is final

| OQ | Question | Why it matters | Blocks |
|---|---|---|---|
| OQ-33 | **Can Pixel apps open sockets to a tethered Pi?** | Wired hypothesis lives or dies | Wired |
| OQ-34 | **How can the app learn the tethered Pi's address?** | Top wired risk | Wired |
| OQ-35 | Does long read work at MTU 517 and 23 on these hubs? | Filed, untested (`docs/design/samdpi-two-hub-memo.md:1724`) | Probe on BLE |
| OQ-36 | Same phone: two apps on one hub, what does the hub see? | Single-central rule has no hub-side enforcement | D1 on BLE |
| OQ-37 | Does each app need its own CDM association? | Worker steps | D1 on BLE |
| OQ-38 | Which NFC tag hardware, and when does it arrive? | Passive sticker recommended | Stage 2h |
| OQ-39 | Wi-Fi: two clients overwrite each other's session. Refuse with BUSY? | Single-central gap (`11b` section 2.3) | Hub v2 |
| OQ-40 | Does SaMD's client tolerate an added `envelope` object, and added characteristics? | INFERRED only | Hub v2 |

## Housekeeping

| OQ | Question | Why it matters | Blocks |
|---|---|---|---|
| OQ-41 | Where will modernized OneIoT live? (S5) **Answered in part:** `ren276/OneIOTApp`, private, `main`, commit `25952a2`. Open: modules layout, and how SaMD consumes the payload classes | Copy or publish | Stage 2f |
| OQ-42 | Commit these study memos? | Uncommitted on `docs/oneiot-study` | Handoff | Operator |
| OQ-43 | `.txt` copies of Gradle properties in the OneIoT repo: delete? Copy made to fix Studio sync, and committed | Noise | None |
| OQ-44 | Did Android Studio sync succeed after the fix? | Reported failing, fix unverified on the operator's side | Running old build |
| OQ-45 | Confirm R3-0's check: 10 committed files matched the credential-word scan, and all were judged non-secret by file role | The commit is on a private remote; the operator has not confirmed this judgement | Nothing, unless a file is wrong |
| OQ-46 | OkHttp `pingInterval` failure behaviour and `getCallingActivity` semantics were not confirmed from primary docs | Affects D3 and T3 liveness, and D1's caller check | D3, T3; Stage 2f |

## Answered at STOP 3 (R4, operator 2026-11-08)

| OQ | Answer |
|---|---|
| OQ-1, OQ-2 | D1, same phone (R4-1) |
| OQ-6 | **P3**: new app, new repo by the operator (R4-0) |
| OQ-7 | Logical envelope with per-bearer mapping, matching the paper's message structure (R4-1, R4-K) |
| OQ-8 | Paging mandatory; long read is no longer a gate (R4-2). OQ-35 no longer blocks |
| OQ-9 | A1 (R4-1) |
| OQ-10 | No new capture-method value; provenance in audit (R4-1) |
| OQ-11 | Same debug signer, `checkSignatures` (R4-6) |
| OQ-13 | Withdrawn: codes are never in the manifest (R4-K) |
| OQ-41 | New repo created by the operator (R4-0); legacy repo archived |

## New at STOP 3

| OQ | Question | Recommendation |
|---|---|---|
| OQ-47 | `STRUCT_X` and `STRUCT_Y` JSON key spelling: Algorithm 1 prints the parent symbol, which gives `STRUCT_X` and `STRUCT_Y`, but no example exists | Avoid in vitals; spell `STRUCT_X`, `STRUCT_Y` if ever needed |
| OQ-48 | Underscore (paper p.13 to 15) or spaced (`grammar.txt`) keyword spelling | Underscore, the published spec |
| OQ-49 | Message-level spellings and the paper versus TIH conflicts (`15` section 3.2) | As in `15` section 3.2 |
| OQ-50 | `SOCKET` means the OEM server address in the paper (p.11), not the device address. Use it for our Registration host? | Yes; device address comes from Registration (Fig 7 step 7) or Discovery |
| OQ-51 | Must the demo show all five phases? (R4-R) | Yes if R4-R is adopted: all five fit (`15` section 7) |
| OQ-52 | Does the kernelhub2 Pi have any IP uplink for a separate unit? | Operator to check; ownership only either way |
| OQ-53 | Registry login inside OneIoT: its own user, or something reused from SaMD? | Own user, dev only |
| OQ-54 | Configuration content for kernelhub2 | None for the demo |
| OQ-55 | One Invoke GET naming several sensors returns one measurement (filled gap). Accept? | Yes |
| OQ-56 | Encryption type values beyond `none` | Defer to production |
| OQ-57 | Instrument identity for SaMD routing comes from the allowlist entry for the sha256, not from a manifest field. Accept? | Yes |
| OQ-58 | Should OneIoT also check its caller is SaMD (reverse `checkSignatures`)? | Yes |
| OQ-59 | Production signing key and package rename (deferred by R4-6) | Before any release |
| OQ-60 | MTU below 513: fail closed with `MTU_TOO_SMALL`, rather than long reads? | Fail closed |
| OQ-61 | Emulator scenario selection from OneIoT (dev argument), or always default | Default only, scenario stays a SaMD dev control |
| OQ-62 | Registration host location: laptop service (recommended), kernelhub1, or none | Final ruling at STOP 4 (R4-R) |
