Status: RESEARCH, not a design ruling. Sources read: `K/DisplayThingActivity.kt`, `K/BluetoothDisplayThingActivity.kt`, `K/Adhoc.kt`, `app/src/main/res/layout/xml1.xml` to `xml6.xml`, `activity_display_thing.xml`, `activity_bluetooth_display_thing.xml`, `activity_adhoc.xml`, `Untitled Document 1`. Nothing was run: behaviours are traced by reading.

# 04: UI generator

Paths are relative to `/media/sandesh/extra-ssd/AndroidWork/OneIOTApp`. `K/` means `app/src/main/java/com/example/iot1/`. Line numbers refer to `K/DisplayThingActivity.kt` unless another file is named. `K/BluetoothDisplayThingActivity.kt` and `K/Adhoc.kt` are near copies.

## 1. Algorithm

1. Read the file into one string, dropping newlines (`:103-118`).
2. Parse with Gson, convert back to `org.json` (`:93-101`).
3. `extractAndClassify` walks the tree depth-first and records `(fullKey, value, category)` for every key. Full keys look like `CONTROL - Temperature - NUMERIC - RANGE` (`:119-139`).
4. `classifyValue` sets the category from the **key name** and value shape (`:165-190`).
5. `addUIElements` lists categories in first-seen order, then handles each group (`:207-292`).
6. Each view goes in a vertical `LinearLayout` with `height=0` and a weight (`:298-306`).
7. `populateSpinnerOptions` fills spinners (`:308,310-331`).

## 2. Widget mapping

| Key and shape | Category string | Layout | Widgets | Label source | Reads from manifest | Sends to device? |
|---|---|---|---|---|---|---|
| Top-level `MODE` object | `MODE` | `xml6` | Spinner | Blank (`xml6.xml:11`) | Mode names | Yes, on selection (`:243-244`) |
| `RANGE`, array length 5 or 6 | `RANGE Category: MIN, MAX, STEP, OPERATION, UNIT VAL...` | `xml1` | TextView, -, SeekBar, + | **Hardcoded "Temperature"** (`xml1.xml:10`) | Index 0, 1, 2, 4 | + and - only (`:350-368`). Dragging the SeekBar never sends (`:369-376`) |
| `RANGE`, length 4 | `RANGE Category: MIN, MAX, STEP, OPERATION` | none | none | n/a | n/a | Silent drop: inner test needs `UNIT VAL` (`:254`) |
| `RANGE`, other length | `Invalid RANGE Category` | none | none | n/a | n/a | Log only |
| `OPTION`, all strings | `Option valid N strings` | `xml2` | Spinner | **Hardcoded "Actuator"** (`xml2.xml:11`) | Strings | No, no listener |
| `BOOLEAN`, 2 strings, full key contains `Power` | `[String, String] - BOOLEAN` | `xml3` | Switch | **Hardcoded "Power"**, "Power On/Off" (`xml3.xml:10,19`) | Nothing | No, no listener |
| `BOOLEAN`, 2 strings, other key | same | `xml5` | RadioGroup | **Hardcoded "Swing", "Up", "Down"** (`xml5.xml:10,27,34`) | Nothing | No, no listener |
| `NAME`, `DOMAIN`, `INSTALLATION`, `WARRANTY`, `SW_VERSION`, `HW_VERSION` | `String` | none | none | n/a | n/a | No |
| Anything else | `Unknown Category` | none | none | n/a | n/a | No |
| Always, at the end | n/a | `xml4` | Empty spacer, weight 40 (`:294-295`) | n/a | n/a | No |

So a "generated" screen takes its structure from the manifest and most of its text from a fixed AC layout. Unit is the only per-control text that comes through, into the value display (`:336,349`).

## 3. Defects that matter for any reuse

| # | Defect | Evidence | Consequence |
|---|---|---|---|
| 1 | Label never set from manifest | No code writes `txt_xml1`. The only label writes, for `xml3` and `xml5`, are commented out (`K/BluetoothDisplayThingActivity.kt:342-345`) | Every numeric field reads "Temperature" |
| 2 | `SENSOR` and `ACTUATOR` not distinguished | Walk ignores section names (`:122-136`) | A read-only sensor becomes an editable control |
| 3 | Shows `min` as if it were the value | `var currentTemperature = min` (`:348`) | A number with no device behind it appears as current state |
| 4 | Value not read from device | No GET anywhere | Display and device can disagree silently |
| 5 | Only first spinner filled | `findViewById(R.id.fan_spinner)` in a loop (`:319`) | With 2 `OPTION` lists, the first spinner shows the last list, others stay empty |
| 6 | Most controls are dead | No listeners on Spinner, Switch, RadioGroup outside `MODE` | Fan, Swing and Power do nothing, unlike the older code (`Untitled Document 1:80-108`) |
| 7 | Wire value has no control name | `Data:$data` (`:497`) | Device cannot tell which control changed |
| 8 | Mode sent on open | Spinner listeners fire on first layout (Android behaviour, INFERRED) | A command is sent with no user action |
| 9 | No scrolling | Root is a plain `LinearLayout` (`activity_display_thing.xml:2-8`) | Many controls get squashed into weighted rows, content can clip (INFERRED) |
| 10 | Rows grouped by category, not manifest order | `:207-214` | Manifest author cannot control order |
| 11 | Three drifting copies | `:62`, `K/BluetoothDisplayThingActivity.kt:61`, `K/Adhoc.kt:32`; weights 18 vs 15 (`:248`, `K/Adhoc.kt:208`) | Fixes must be made three times |
| 12 | Adhoc container id missing | `findViewById(R.id.main2)` (`K/Adhoc.kt:161`), but `activity_adhoc.xml:5` declares `main` | Null container: crash if reached with a manifest (INFERRED) |

## 4. State model

| Aspect | Today | Evidence |
|---|---|---|
| Control state | Local `var` captured by click listeners | `:348` |
| Survives rotation or process death | No. `onCreate` re-reads the file and reconnects; no saved state | `:76-102` |
| ViewModel, flow | None | whole app |
| Connection state in UI | None. No indicator | `:378-393` |
| Send result in UI | None. Log lines only | `:513,530,538` |
| Threading | Raw `Thread`; a Bluetooth connect runs on the main thread from a posted runnable | `:380,493`; `K/BluetoothDisplayThingActivity.kt:82,127,146` |
| Toast from a worker thread | `setupBluetooth` toasts inside `sendToRpi`'s thread when Bluetooth is off | `:493,547,644`. Likely crash (INFERRED) |

## 5. Commands back to the device

Bidirectional in the sense of command and ACK. The phone sends a value string; the device replies with an ACK that carries no data. There is no start or stop measure, no status, no telemetry (`05`).

## 6. Limits: what a manifest cannot express to this generator

- Any text label, title or device name.
- Read-only values, status lines, or results.
- Actions (buttons with no state).
- Control order, grouping, visibility or dependency between controls.
- Input validation messages, confirmation for risky actions.
- Multiple spinners with their own options.
- Images, media, graphs, maps, dates, times (grammar only).
- A second device on the same phone: the path is fixed (`:83`).

## 7. Accessibility

| Item | Status | Evidence |
|---|---|---|
| Content descriptions | 1 layout file of 16 has one, none in `xml1` to `xml6` | search over `res/layout` |
| Touch targets | - and + are 48 dp, which meets the common minimum | `xml1.xml:40-41,59-60` |
| Colour | Hardcoded black and cyan, `#09F2F6` on white; contrast unmeasured | `xml2.xml:21`, `xml3.xml:21` |
| Screen reader meaning | "Temperature" read out for every numeric field, whatever it measures | `xml1.xml:10` |
| Font scaling | Fixed heights such as `50dp` spinners may clip (INFERRED) | `xml2.xml:20` |

## 8. Localization

No locale resources: `values-night` is the only qualified folder (VERIFIED). Labels are hardcoded in layouts and Kotlin rather than `strings.xml`. `strings.xml` defines mode and fan arrays that no code uses (`app/src/main/res/values/strings.xml:7-16`). Manifest strings have no language field. Hindi, needed for PHC staff in Madhya Pradesh, is not addressed anywhere (INFERRED from absence).
