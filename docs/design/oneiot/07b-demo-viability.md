Status: RESEARCH, not a design ruling. Sources read: memos `02` to `07`, `07b-runnability-for-demo.md`; SaMD-App BLE code sizes at `4b9ee3c`. Effort figures are INFERRED estimates for one engineer, about plus or minus 50 percent, with no benchmark behind them.

Tag key: a claim with a file:line citation and no other tag is VERIFIED, read in code at the commit named above. A citation to another memo inherits that memo's tag. Anything else is marked INFERRED, DOCS-ONLY or EXTERNAL.

# 07b: Demo viability (R1-3)

`K/` means `app/src/main/java/com/example/iot1/`. This memo answers R1-3. `07b-runnability-for-demo.md` stays as the detail source, because R2-1 and R2-5 cite its sections. R1-4's planning assumption was replaced by R2-3 (staged replacement). Table (b) is the clean rebuild R1-3 asked for, and the P1, P2, P3 comparison belongs to memo `10`.

## a. Patching the old app

| Demo need | Old app's state | Evidence | Minimum fix | Days | Fraction of touched file that changes |
|---|---|---|---|---|---|
| Readings from BP, SpO2, Thermometer reach the app | None. No reading exists on either side | `02` 7g, `05` 8 | Request and response messages, hub side reply, inbound parse and state | 4 to 6 | `DisplayThingActivity.kt` about 40 percent; `thing_mobile.py` all, replaced |
| One manifest per device | One fixed `manifest.json`, newest overwrites | `K/ThingDetailsActivity.kt:222`, `K/DisplayThingActivity.kt:83` | Key the file by Thing; pass the id through | 0.5 to 1 | `ThingDetailsActivity.kt` 5 percent; `DisplayThingActivity.kt` 2 percent; `DBHelper.kt` 10 percent |
| Correct labels, read-only sensors | Hardcoded "Temperature", sensors editable, `min` shown as a value | `03` 8, `04` 3 | Labels from keys, section-aware walk, read-only row, action button, empty state | 2 to 3 | Each of 3 generators about 30 percent; `xml1`, `xml2`, `xml3`, `xml5` most of each |
| Wi-Fi | Raw TCP, no framing; ack bugs | `05` 4, 12 | Length-prefixed frames | 1 to 2 | Send and read paths about 15 percent |
| BLE | Absent: Classic RFCOMM only | `05` 12 | New GATT client | 5 to 8 | New file; `BluetoothDisplayThingActivity.kt` retired |
| Wired USB | Absent | `05` 12 | IP over tethering through the Wi-Fi path, untested on Pixel | 1 to 2, plus AOA fallback 5 or more | Small |
| NFC | Absent | `05` 12 | Tap to pair only, needs tag hardware | 2 to 3 | New file |
| No cloud dependency | Add Thing needs `cloud.py` and SQL Server | `07b-runnability-for-demo.md` 3 | Static list (R3), then NSD (R4) | 0.5 to 1, then 1 to 2 | `ThingDetailsActivity.kt` about 25 percent |
| Hand a reading to SaMD-App | Nothing returns a result | Section c | `setResult` and a Send action, plus SaMD side (Phase 3) | 1 to 2 on OneIoT side | `DisplayThingActivity.kt` 5 percent |
| Not listed, but required: crashes and crypto | Empty read kills Pi; manual key placement; 16 KB risk | `06` 4 | Fix crash paths; decide on crypto | 2 to 3 | Pi scripts replaced anyway |
| **Total** | | | | **about 20 to 35** | |

Pi side becomes the S4 adapter in any case, so its effort sits with the first row (see `09` section 3).

## b. Clean OneIoT v2 rebuild

| Demo need | v2 approach | Days | Fraction changed |
|---|---|---|---|
| Readings reach the app | Protocol module with Action, Event, Status; hub adapter | 4 to 6 | All new |
| One manifest per device | Manifest module: model, parser, validator, per-device store | 3 to 4 | All new |
| Correct labels, read-only sensors | Compose renderer on a fixed template | 3 to 5 | All new |
| Wi-Fi | Transport module, framing, NSD | 2 to 3 | All new |
| BLE | Transport module. SaMD-App's BLE client is about 1,750 lines in its `dev` source set, a pattern to follow, not a dependency | 5 to 8 | All new |
| Wired USB | IP over tethering reuses the Wi-Fi module | 1 to 2, plus AOA fallback 5 or more | All new |
| NFC | Tap to pair | 2 to 3 | All new |
| No cloud dependency | By construction | 0 | n/a |
| Hand a reading to SaMD-App | Result contract from day one | 1 to 2 | All new |
| App shell, navigation, permissions at target 37, tests | Required in v2 only | 3 to 5 | All new |
| **Total** | | **about 24 to 38** | Survives from old code: `ac.json` as a fixture, the concept |

## c. Where "Send to SaMD" attaches in the old code

Likely moot under R2-3. Details in `07b-runnability-for-demo.md` section 6.

- **Attach point:** after the row loop in `addUIElements`, `K/DisplayThingActivity.kt:306-308`, once a reading exists. Nothing there can receive one today.
- **Launch for a result:** yes in form, `DisplayThingActivity` is exported with no filter (`app/src/main/AndroidManifest.xml:57-60`). But 0 uses of `setResult` exist, a missing `ip_address` extra crashes it (`K/DisplayThingActivity.kt:80`), and it depends on a manifest saved earlier. No old activity can return a result today.

## Conclusion (evidence, no ruling)

- The two totals overlap: about 20 to 35 days patched, about 24 to 38 days rebuilt. Effort does not separate them.
- Patch work lands on code with no tests, three copies of the generator, and the crypto and cloud layers still attached. Rebuild work leaves a tested surface at target 37 that SaMD-App could later consume.
- Build risk is lower than feared: the old code compiles (`07` section 10). Runtime on the Pixel is unproven.
- Staged replacement (R2-3) is a third shape, assessed in memo `10` with per-file surviving lines.
