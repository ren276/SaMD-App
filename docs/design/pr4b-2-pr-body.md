# PR 4b-2: prove no off-device ASR path remains, then flip VOICE_FIELD_IMPACT_ENABLED

Second half of PR 4b. 4b-1 (#34) shipped the Open Source Licences screen and discharged the
CC BY 4.0 attribution obligation. This PR produces the egress evidence, flips the flag on the
strength of it, and updates the controlled documents to describe the device that now exists.

Two flags, one moved:

- `VOICE_FIELD_IMPACT_ENABLED`: **false to true**. The voice confirmation gate on
  `impactOnDailyActivities` only, a narrative field read by a human.
- `VOICE_INPUT_ENABLED`: **stays false**. It gates `chiefComplaint` voice and the audio
  attachment, the High-severity H-15 paths that reach `/api/v1/evaluate` under no confirmation
  gate. The egress evidence is orthogonal to that and does not move it.

## The gate, and why it is checkable from `git log`

The flag does not flip on a claim. Its own KDoc said so before this PR. Commits 1 to 3 are the
evidence, commit 4 is hygiene, and the flip is commit 5, so the ordering is readable from the log
rather than from anyone's account of it.

```
9a33d51 test(asr): source-level absence scan for the platform recognizer
3f349a1 test(asr): assert no network dependency reaches the transcription path
a801679 test(asr): on-device egress assertion for a full transcription
3c5a9bc docs(asr): correct two residual CI overclaims and one stale comment
30917b5 feat(asr): flip VOICE_FIELD_IMPACT_ENABLED
d063a82 docs(asr): record arm64 iQOO operator witness, L3.4 + L3.5 closed
884fec0 feat(asr): flag-on gate tests and the permission-denial dead end
9d9bdf1 docs(quality): H-15 residual re-evaluation, intended-use, SOUP validation record
```

## Evidence: transmission

| Layer | What it asserts | Where | Result |
|---|---|---|---|
| L1 | No platform recognizer API mentioned anywhere in `app/src/main` | `NoPlatformRecognizerSourceScanTest` | green |
| L2.1 / L2.2 | No network dependency reaches the transcription path in the graph | `TranscriptionPathHasNoNetworkDependencyTest` | green |
| L2.3 | No platform recognizer class reachable in the built bytecode (reflection scan) | `AsrEgressTest` | green |
| L3.1 | A real transcript is produced by the local engine | `AsrEgressTest` | green |
| L3.2 | **`txDelta = 0 B`, `rxDelta = 0 B`** across a full decode | `AsrEgressTest` | green |
| L3.3 | `StrictMode` records no network call on the transcription path | `AsrEgressTest` | green |
| L3.4 | A capture writes no file | operator run, **iQOO I2302 (arm64-v8a)** | **PASS 2026-09-02** |
| L3.5 | Airplane-mode transmission witness: transcription completes with no network reachable | operator run, **iQOO I2302 (arm64-v8a)** | **PASS 2026-09-02** |

L2.3 and L3.1 to L3.3 ran on the x86_64 emulator. L3.4 and L3.5 ran on the arm64 device with a
real microphone, which is why they were relocated there: the emulator has no usable mic input, a
device limitation rather than a code failure. **The x86_64-not-arm64 residue named in the memo is
therefore CLOSED**: the egress witness has run on the shipped ABI.

What this evidence does not say: nothing here speaks to transcription accuracy. It closes the
transmission question only.

## Evidence: the tests are non-vacuous

Mutation checks, each exercised then reverted:

- **M1**: broke the expected transcript in the decode assertion, test went red. L3.1 is reading
  real engine output.
- **M2**: introduced a network call on the transcription path, `StrictMode` and the byte-delta
  assertions both went red. L3.2 and L3.3 are watching the right process.
- **M3**, the one that mattered: the source scan reported **green on a tree it had never read**.
  The test reads source files off disk, and Gradle's up-to-date checking had no reason to know
  that, so it skipped the task and a "passed" was indistinguishable from "did not run". Fixed by
  declaring `src/main` as an input on the `Test` task; the scan then went red under mutation as it
  should.

That finding generalises and is recorded as standing discipline in `PROGRESS.md`: **any test that
reads files off disk must declare them as Gradle `Test` task inputs.**

## Latency, reported not asserted

First capture in a process pays the 622 MiB model load, which before the flip never happened in a
shipped build. Measured on the x86_64 emulator: **2612 ms cold, 613 ms warm**. The "Listening..."
state covers the cold-load window. No threshold is asserted, because none has been agreed. Cold
load is also visible on the iQOO (seconds, not milliseconds, on the first mic tap).

## What else is in the flip commits

- `ConsultationVoiceGateUiTest`: the flag-off absence assertion is **inverted**, not deleted, and a
  new test taps the mic entry point and asserts `onRecordImpactVoice` fires. Both address the entry
  point by test tag, never by shape. The three direct `ImpactVoiceSuggestionSurface` tests are
  flag-independent and unchanged.
- **Permission denial was a silent dead end.** `rememberPermissionAction` called `onGranted` on
  grant and did nothing on denial, at all four call sites. With the flag on, a worker who declines
  the microphone prompt taps the mic and sees nothing happen, forever. Fixed in the shared helper
  (one optional `onDenied`, defaulting to no-op); the consultation screen passes the existing
  `errorMessage` path. The other three call sites keep the default.

## Controlled documents

- **`risk-management-file.md` H-15: residual rewritten from scratch, wording operator-approved.**
  Every earlier update rested on "the residual risk to a user is unchanged, because the control is
  not user-reachable". The flip makes that false. Severity **Medium** for the newly-reachable path;
  probability **not established** for the deployment population. **The row does not mark the risk
  accepted.** Two open actions recorded rather than closed: the accented-speech evaluation of the
  shipped int8 artifact (blocks **clinical deployment**, not this dev flip), and the fact that
  nobody reads the `dwellMs` rubber-stamp breadcrumbs (operational gap, not counted as mitigation).
- **`intended-use-statement.md` section i amended** and **`docs/quality/soup-validation-record.md`
  added** (five model-companion components, arm64 airplane-mode witness as runtime validation
  evidence, indexed from the DHF). Both **PROPOSED, AWAITING OPERATOR SIGN-OFF**, not marked
  approved.

## Test results

- `testDevDebugUnitTest`: **307 passed, 0 failed**.
- `ConsultationVoiceGateUiTest`: **6/6 passed** on emulator-5554 (Pixel 9 Pro AVD).
- `compileDevDebugKotlin`, `compileDevDebugUnitTestKotlin`, `compileDevDebugAndroidTestKotlin`:
  green.
- Device discipline: `adb shell am kill-all` before any instrumented ASR run. The 622 MiB resident
  model plus a 4 GB AVD hits the memory ceiling otherwise, and it presents as a code failure when
  it is not one. Also recorded in `PROGRESS.md`.

## Also in this PR, by operator decision: the mic entry point becomes a trailing icon

Kept in this PR rather than split out, at the operator's call. `ConsultationScreen.kt`: the mic
was a full-width `OutlinedButton` above the field it dictates into, and is now the trailing icon of
the impact `OutlinedTextField`, so the control sits on the thing it fills. Adds
`androidx.compose.material:material-icons-extended` for `Icons.Default.Mic`.

Behaviour and the safety surface are unchanged: same handler, same
`VOICE_FIELD_IMPACT_ENABLED` guard (hidden, not disabled, when off), same
`impact_voice_mic_button` test tag. The gate tests address the entry point by tag rather than by
shape, which is exactly the property that lets this ride here: the 6/6 instrumented run was against
an APK built with this change in place.

## Pre-distribution gate list

| Item | Status |
|---|---|
| L3.4 capture writes no file, arm64 | **discharged**, iQOO I2302, 2026-09-02 |
| L3.5 airplane-mode transmission witness, arm64 | **discharged**, iQOO I2302, 2026-09-02 |
| CC BY 4.0 attribution for the Parakeet weights | **discharged** by PR 4b-1 (#34) |

The gate list is fully discharged. Still open, and deliberately not on this gate: the
accented-speech evaluation of the shipped int8 artifact, which is a precondition of clinical
deployment and not of a dev-only flag flip.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01HSzNmdfa6BZBvKEEQC6zmw
