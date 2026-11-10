Status: RESEARCH, not a design ruling. **All hazard text here is PROPOSED, for sign-off. Nothing is written into `docs/quality/`.** Sources read: memos `03` to `12`; `docs/quality/risk-management-file.md` (hazard rows touching vitals, hub, acquisition, BLE, read only); `docs/design/samdpi-two-hub-memo.md` sections 10, 14.4 to 14.6; SaMD-App at `4b9ee3c`; SaMDPi at `bba2c6b`.

Tag key: a claim with a file:line citation and no other tag is VERIFIED, read in code at the commit named above. A citation to another memo inherits that memo's tag. Anything else is marked INFERRED, DOCS-ONLY or EXTERNAL.

# 13: Risk and regulatory

`A/` is `app/src/main/java/com/example/samdapp/`. `D/` is `app/src/dev/java/com/example/samdapp/data/vitalssource/`. `E/` is SaMDPi `emulator/`.

## 1. Numbering

The register holds no IDs for this. H-37 to H-45 are reserved for PR-C, and H-46 is F1 (`docs/quality/risk-management-file.md:150`). So labels below are provisional, `H-OI-nn`, the same way `H-PI-nn` were before PR-C. Severity and probability are not assigned: the operator's call.

## 2. Device boundary

| Question | Finding | Tag |
|---|---|---|
| Today | Pi is an accessory outside the boundary while RC-1 to RC-4 hold, PROPOSED. BLE client dev only | `docs/design/samdpi-two-hub-memo.md:1099-1110` |
| Does D1 change that? | No for staging and prod: dev flavour and flag only, no main Bluetooth. OneIoT is a separate dev tool, synthetic data only | INFERRED |
| RC-1 | Holds, strengthened: request id becomes the nonce | `10` section 1.2 |
| RC-2 | Holds. Same mapper | `D/PiMeasurementMapper.kt:39-41` |
| RC-3 | Holds in SaMD's form. OneIoT's read-only sensors do not contradict it: editing stays in the record | `12` section 8 |
| RC-4 | Holds: a bridge result only fills the form | `A/presentation/compounder/CompounderViewModel.kt:515-519` |
| Boundary rationale 3, no autonomous ingestion | Holds under D1, **fails under D2** | `10` section 1.1 |
| **T1 or any release path** | Modules, `ble-ktx`, generated UI enter the software item tree. Reverses G-B11b. Needs production security first. Needs a fresh boundary determination | `docs/design/samdpi-two-hub-memo.md:1108-1110` |

Disclaimer on record: the POC must not be connected to clinical forms or vitals gates (`POC:17`). SaMD's dev path already does this for synthetic data with labels. Under D1 the same stands; it is a conflict in wording to resolve when PR-C is written (OQ-20).

## 3. Candidate hazards (PROPOSED)

| ID | Hazard | Sequence | Controls proposed | Evidence or residual |
|---|---|---|---|---|
| H-OI-01 | Wrong unit from a manifest or hub | Manifest says `kPa` or `Cel` on systolic; value written to a mmHg field | SaMD expected-unit table, reject on mismatch; canonicalise in SaMD; golden files | Mapper ignores unit today (`08` section 6). **Blocking prerequisite** |
| H-OI-02 | Wrong label | Generated screen labels a reading wrongly | `LABEL` required; fixed template; manifest allowlist; SaMD writes by field name, not label | Old app: "Temperature" on everything (`03` section 8) |
| H-OI-03 | Fabricated value shown | Default or `min` appears as a reading | No value until a reading; contract forbids values on non-OK | Old app `K/DisplayThingActivity.kt:348`; composite G-A |
| H-OI-04 | Sensor editable | User changes a value on OneIoT screen, then sends | `SENSOR` has no input widget | Old app sends slider values |
| H-OI-05 | Reading bound to wrong patient or session | Result arrives for another patient | Request id from open screen; one result per launch; bridge in saved state; ViewModel binds patient | Test recents switching |
| H-OI-06 | Stale reading as fresh | Old reading returned; GET keeps last reading after completion | Nonce echo, session check. Clock advisory | `E/transports/wifi/server.py:157,174-176`. `measured_at` not gated |
| H-OI-07 | Manifest tampered or swapped | UI or binding altered | Allowlist, sha256, NFC pin; fail closed | Cleartext delivery on Wi-Fi remains. Hash catches it |
| H-OI-08 | Generated UI hides a field | Too many rows, unknown type dropped | Unknown type rejected; scrollable; fixed fields per `EVENT` | Old app silently dropped (`03` section 4) |
| H-OI-09 | Duplicate reading | Double Send, retry | Request id, `measurement_id`; one result | Old app resends commands (`06` section 4) |
| H-OI-10 | Silent transport loss | Link drops; UI says connected | No standing "Connected"; per-action state; caps | `11` section 4; H-44 sibling |
| H-OI-11 | Version skew | Old SaMD with new reading, or the reverse | Version fields, refuse; v1 unchanged | `12` section 4 |
| H-OI-12 | Spoofed OneIoT app | Another app returns a crafted result | Explicit component, certificate check before launch, `signature` permission | Dev signing keys, OQ-11 |
| H-OI-13 | Emulated value in a real record | Synthetic number believed real | Existing labels, `synthetic` in audit and payload | **Residual as H-43: no persisted marker** |
| H-OI-14 | Non-OK reading treated as measurement | 0.0 fault values | RC-2; contract: non-OK has no values | `E/generator/profiles.py:33-37` |
| H-OI-15 | Fault during simulated lifecycle mis-shown | `ERROR` after latency appears as done | Terminal states from manifest; reading only on `DONE` | New code |
| H-OI-16 | Hub held by other app | SaMD and OneIoT contend; capture fails or wrong app receives indications | Route decides one holder; release before Send | Same phone: no hub-side enforcement (`10` section 1.6) |
| H-OI-17 | v2 hub change breaks SaMD's path | A1 shares a process | Byte compatibility tests | Blast radius, `10` section 4 |
| H-OI-18 | Wired listener silently dead | Replug leaves listener on dead index, looks healthy | Restart on replug, `/health` from off-box | `DEPLOY.md:147-149` |
| H-OI-19 | NFC tag swapped | Tag points to another hub | Allowlist, `hub_id`, human check. **Residual** | `11b` section 4.5 |
| H-OI-20 | Lost capture at process death | Worker believes value was saved | Value only in form until Continue; visible empty form | Deliberate: `docs/quality/risk-management-file.md:279-285` |
| H-OI-21 | Emergency check skipped on entry (b) | Quick check-up saves SpO2 of 85 with no override | Same check on any save path | `10` section 7; only if entry (b) is built |
| H-OI-22 | Old OneIoT build used | Old APK with RSA, cloud, fake value | Delete old screens at Stage 2d; do not distribute `app/py311` | Repo excludes it (R3-0) |

Superseded by (2026-11-10, CodeRabbit on PR #85): R4-K withdrew `LABEL` and `EVENT`, so the H-OI-02 and H-OI-08 controls that need them cannot be met. Replacement controls, all in ren276/OneIoT `spec/oneiot-protocol-v1.md`: H-OI-02, the label is the exact member name or a translation keyed by it (section 3.4, REQ-43) on a fixed template, and SaMD-App maps values by exact member name per allowlisted SHA-256 (plan 1b-4); H-OI-08, the vitals profile admits only `NUMERIC` sensors plus `Status` and `Measure` (section 3.2), so an unknown member type fails validation and no screen is shown (REQ-41). Residual risk: a wrong member name in an allowlisted manifest; controlled by the golden SHA-256 tests in both repositories (REQ-03) and review of every allowlist change.

Existing rows these touch: H-01, H-13; PROPOSED H-37 to H-45, especially H-37, H-41, H-42, H-43, H-44.

## 4. Link security, production residuals (R3-1f)

Demo: identity and correlation only, on synthetic data, dev flavour. **Cleartext and unauthenticated by design** (`09` section 4).

| Residual, PROPOSED | Why it matters | Production control | Evidence |
|---|---|---|---|
| Wi-Fi: reading can be read and altered on the LAN | No TLS | mTLS with device certificates | `POC:388` |
| BLE: no bonding, no link encryption | Any central can connect first; values readable | LE Secure Connections with authenticated pairing, level 4 | `POC:392-393` |
| Manifest hash pins content, not the reading | Allowlist gives no reading authenticity | Signed readings or authenticated channel | `12` section 7 |
| NFC tag not authenticated | Swappable | Tamper-evident placement or signed payload | |
| Hub clock advisory | No trusted time | Secure time source | `E/envelope.py:12` |
| Build check is drift detection only | "Not a defence against someone who can edit both" | Secure boot, root of trust | `E/hubctx.py:21` |
| Real patient device on this path | **All of the above become mandatory** | Forced by promotion | `docs/design/samdpi-two-hub-memo.md:1108-1110` |

Key material rule stands: the old key pair and scheme are never reused in SaMD (R0-3).

## 5. SaMD-side items, independent of OneIoT (R3-1g)

**Separate SaMD items, valid even if OneIoT is dropped.** Each deserves its own memo and risk review under the project workflow.

| ID | Finding | Evidence | Consequence | Recommendation |
|---|---|---|---|---|
| SAMD-1 | **No plausibility bounds.** 999 mmHg saves | `A/presentation/compounder/CompounderViewModel.kt:668-671`, `clinical.py:40-47` | Mistyped or device-wrong value reaches kernel and doctor. Only 3 fields have emergency thresholds, flagged "a starting point for clinical review" | Clinical owner to set a table. **The operator decides values and who reviews them** (OQ-15) |
| SAMD-2 | **`PiMeasurementMapper` ignores unit** | `D/PiMeasurementMapper.kt:39-53` | Wrong unit passes today, in dev | **Must precede the bridge** |
| SAMD-3 | **`observations.device_id` null on save** | `CompounderViewModel.kt:665-682` | Record cannot name its device; only audit can | Small; wire contract already carries it |
| SAMD-4 | **Capture-method enum needs a backend value for any new method** | `backend/core/app/models/enums.py:172-177`, `clinical.py:44`, `A/domain/model/Observation.kt:26` | New value breaks sync without a coordinated change. Method is also the worker's attestation | Avoid for the demo (`10` section 1.3) |
| SAMD-5 | D4 stale session in failure audit | `CompounderViewModel.kt:563` | Audit names the wrong session | Fix before the bridge |
| SAMD-6 | F2 UNREACHABLE vs HUB_MISMATCH | `D/ble/BleAcquisition.kt:205-208` | False advice | Filed |
| SAMD-7 | `measured_at` not gated; `STALE_TIMESTAMP` accepted | `09` section 1 | Old reading passes if session id matches | Decide with clinical owner |
| SAMD-8 | Units in Room are not UCUM | `A/domain/model/Observation.kt:6-15` | Export needs mapping | With FHIR or ABDM work |

**Recommendation:** the OneIoT-to-SaMD bridge must not ship before unit checking and canonicalisation exist on the path it uses (SAMD-2). SAMD-5 should be fixed first. SAMD-1 is the most serious on its own, but needs clinical input and should not wait on this track.

## 6. IEC 62304 and configuration items

| Item | D1 demo | T1 target |
|---|---|---|
| SaMD software items | Bridge in dev flavour: outside released configurations, same logic as `ble-ktx` (`docs/design/samdpi-two-hub-memo.md:1102-1105`) | In the tree |
| OneIoT app | Dev tool, separate repo | Absorbed |
| New SOUP in OneIoT | Nordic, Compose, OkHttp: dev tool, not SaMD SOUP | Enter SOUP list |
| SOUP removed by dropping old stack | Chaquopy, `cryptography`, JDBC drivers | n/a |
| Hub SOUP | `dbus-fast`, recorded in the interface spec, not SOUP list (`docs/design/samdpi-two-hub-memo.md:1114-1115`) | Same unless the boundary moves |
| Manifests | CI: generator commit plus golden output, version, sha256, approval | Controlled |
| Reading payload, manifest schema | Interface specification entries | Controlled |
| Allowlist | CI in the app | CI |
| Controlled docs | **None edited by this study.** PR-C pattern when ready | |

Safety class is not assessed here: the operator's decision.

## 7. Test strategy, risk view

| Control | Proof |
|---|---|
| Every schema rule | Mutation, as `12` section 10 |
| Unit check | Wrong unit per field, red |
| Bridge | Fake OneIoT signed differently; crafted result; wrong request id; double delivery; recents switch; process death |
| RC-1 to RC-4 | Existing tests stay green unchanged |
| v1 compatibility | SaMD testDev suite and hub tests unchanged |
| Generated UI | Compose test: no number before a reading, label from manifest, sensor not editable |
| Project rule | A guard written against a pattern, not the artifact, has the defect it exists to catch |

## 8. Docs vs code

| Docs say | Code does | Result |
|---|---|---|
| POC must never reach clinical forms or vitals gates (`POC:17`) | SaMD's dev flavour fills the vitals form from a hub, synthetic and labelled (`A/presentation/compounder/CompounderViewModel.kt:515-519`, `D/PiMeasurementMapper.kt:39-53`) | Code wins as the fact. Wording conflict goes to OQ-20 |
| The envelope's own header comment calls the phone's receive time authoritative (`E/envelope.py:18`) | That holds on BLE. On Wi-Fi the hub fills `received_at` itself (`E/generator/instruments.py:119`) | Code wins. The contract makes the receiver overwrite it (`12` section 5.1) |

## 9. Amendments at STOP 3 (R4-4, R4-K, R4-R; operator 2026-11-08; additions only)

**R4-4.** Section 5 items become separate SaMD PRs. SAMD-2 (unit check) and canonicalisation must merge before the bridge. SAMD-1 is split into two layers; no values are chosen in this study:

| Layer | Behaviour | Who sets values |
|---|---|---|
| 1. Physical-impossibility hard limits | Reject the value; nothing saves | Operator, from physiology; PROPOSED |
| 2. Soft confirm ranges | Save only after the clinician confirms | Clinician sign-off; each range becomes a PROPOSED hazard row |

New candidate hazards, PROPOSED, provisional labels as in section 1:

| Label | Hazard | Cause | Proposed control |
|---|---|---|---|
| H-OI-23 | Value saved under the wrong vital | SaMD maps MMF sensor names to codes (R4-K); a manifest with a renamed or swapped sensor maps to nothing or to the wrong field | Exact-name map per allowlisted manifest sha256; unknown name refuses the whole reading |
| H-OI-24 | Reading accepted with a unit the manifest did not declare | BLE SIG unit flag and MMF `UNIT_VAL` disagree | Refuse on mismatch (`11b` section 8.3) and SAMD-2 on SaMD side |
| H-OI-25 | Capture blocked or misdirected by the Registration host | Host down, or stale address from heartbeat | Address used only to reach kernelhub1; NSD lookup as fallback; host holds no clinical data (`10` section 9.3) |
