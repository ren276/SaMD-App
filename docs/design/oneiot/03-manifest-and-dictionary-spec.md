Status: RESEARCH, not a design ruling. Sources read: `grammar.txt`, `android_grammer_rules`, `ac.json`, `grammar_parser.py`, `output.py`, `fetch.py`, `kotlintopython.py`, `K/DisplayThingActivity.kt`, `K/BluetoothDisplayThingActivity.kt`, `K/Adhoc.kt`, `Untitled Document 1`, paper pages 11 to 19.

# 03: Manifest and dictionary specification

Paths are relative to `/media/sandesh/extra-ssd/AndroidWork/OneIOTApp`. `K/` means `app/src/main/java/com/example/iot1/`.

Three different things go by "the spec". Keep them apart:

| Layer | Where | Runs? |
|---|---|---|
| A. Paper grammar | Paper p.13 to 15, `grammar.txt` | Never |
| B. Kotlin grammar map | `android_grammer_rules` | Never, cannot compile (`02` section 7a) |
| C. Runtime classifier | `classifyValue` and `addUIElements` in 3 activities | Yes, this is the real behaviour |

## 1. Grammar as written

`S -> {START}`, then `START -> OPTIONAL,START | SENSOR | ACTUATOR | SENSOR,ACTUATOR` (`grammar.txt:12-13`). So a manifest has zero or more optional sections, then a sensor section, an actuator section, or both. Productions print to JSON by Algorithm 1: when a `:` is a leftmost terminal, print the parent non-terminal name as the key (paper p.15).

## 2. Every manifest field (layer A, the dictionary)

Paper text sets the meaning. "Runtime" says what layer C does with the key. Line numbers are in `grammar.txt`.

### Sections

| Key | Line | JSON type | Required | Meaning | Runtime |
|---|---|---|---|---|---|
| `DEVICE` | 15 | object | optional | Identity of the device | Walked, nothing shown |
| `OEM` | 18 | object | optional | Maker and its server | Walked, nothing shown |
| `MODE` | 21 | object | optional | Named presets of actuator values | Top-level only: spinner of names |
| `LOCATION` | 29 | array of string | optional, mainly SMF | Where the device is | `Unknown Category`, nothing shown |
| `SENSOR` | 31 | object | one of `SENSOR`, `ACTUATOR` is required | Read-only values | Not recognised: its subtree is treated like any other |
| `ACTUATOR` | 75 | object | same | User-settable controls | Same: not recognised as a section |

### `DEVICE` and `OEM` members

| Key | Line | Type | Meaning | Runtime |
|---|---|---|---|---|
| `NAME` | 17, 19 | string | Device name or OEM name | Category `String`, nothing shown |
| `DOMAIN` | 17 | string | For example smart home, medical | Category `String`, nothing shown |
| `INSTALLATION` | 17 | string | Fixed or portable | Category `String`, nothing shown |
| `WARRANTY` | 17 | string | Warranty text | Category `String`, nothing shown |
| `SW_VERSION`, `HW_VERSION` | 17 | string | Versions. `grammar.txt` spells them with a space, the paper with `_` | Category `String`, nothing shown |
| `SOCKET` | 20 | `:str:num` | OEM host and port. Not expressible as a JSON value as written | `Unknown Category` |

### `MODE` members

| Symbol | Line | Shape | Meaning |
|---|---|---|---|
| `M` | 22 | `"<mode name>": { ... }` | One mode |
| `ACTUATOR_INFO` | 25 | `"<actuator name>": DATA` | One preset value |
| `DATA` | 26 | `[VALUE]` or `[VALUE, UNIT_VAL]` | Value, with optional unit |
| `VALUE` | 27 | string, number or array | Terminal `array` has no definition |

Runtime uses only the mode names, never the preset values (`K/DisplayThingActivity.kt:225,243-244`).

### `SENSOR` and `ACTUATOR` members

| Symbol | Line | Shape | Meaning | Runtime |
|---|---|---|---|---|
| `X`, `Y` | 32, 76 | `"<name>": {DESC?, TYPE}` | One sensor or actuator | Name is ignored except the substring test `Power` |
| `DESC` | 33 | string | Description | `Unknown Category` |
| `STRUCT_X`, `STRUCT_Y` | 35, 78 | object of 2 or more children | Grouping | Walked as plain nesting |
| `BATTERY` | 36 | `{}` | Battery level sensor | `Unknown Category` |
| `GRAPH` | 37 | `{PIE or BAR}` | Chart | `Unknown Category` |
| `PIE`, `BAR` | 39 | `[CORD1, CORD2]` | Axis values: num, str, bool, time | `Unknown Category` |

### Data types

| Key | Line | Shape | Meaning | Runtime |
|---|---|---|---|---|
| `BOOLEAN` | 43 | `[str, str]` | Two named states | Exactly 2 strings: `xml3` or `xml5` |
| `NUMERIC` | 44 | `{RANGE}` or `{TUPLE, UNIT}` | Number | Wrapper only, walked through |
| `RANGE` | 45 | RVAL or list of RVAL | Allowed values | Array of 5 or 6: `xml1`. Array of 4 dropped. Others invalid |
| RVAL | 47 | `[MIN, MAX, STEP, OPERATION, UNIT_VAL?, EXCEPT?]` | Positional | Reads index 0, 1, 2, 4 only |
| `OPERATION` | 52 | `+`, `*`, `-1` | How STEP applies | Never read, step is always additive |
| `EXCEPT` | 53 | TUPLE or sub-range | Excluded values | Never read |
| `TUPLE` | 54 | array of numbers | Discrete values | `Unknown Category`, nothing shown |
| `UNIT` | 56 | string | Unit with TUPLE | `Unknown Category`, nothing shown |
| `STRING` | 57 | `{OPTION}` | Text choice | Wrapper only |
| `OPTION` | 58 | array of string | Choices | All strings: `xml2`. Otherwise dropped |
| `IMAGE` | 60 | `{SIZE?, IMAGE_ATTRIB?}` | Image | `Unknown Category` |
| `SIZE` | 61 | `[LENGTH, WIDTH, UNIT_VAL]` | Dimensions | `Unknown Category` |
| `IMAGE_ATTRIB` | 63 | `[SRC, jpeg or png or gif or raw]` | Source and type | `Unknown Category` |
| `AUDIO`, `VIDEO` | 66, 70 | `{SIZE?, PLAYER_ATTRIB?}` | Media | `Unknown Category` |
| `PLAYER_ATTRIB` | 67 | `[SRC, type, LOOP, MUTE, ACTUATOR, AUTOPLAY]` | Player setup | `Unknown Category` |
| `MAP`, `COLOR`, `DATE`, `TIME` | 71 to 74 | `{}` or `{SIZE}` | Widget kinds | `Unknown Category` |

Support is thin: of 6 sections and 11 data types, the runtime draws widgets for `MODE`, `RANGE`, `OPTION` and `BOOLEAN` only.

## 3. Example manifests

### 3.1 `ac.json`, the only manifest file (VERIFIED, quoted in full)

```json
{
        "DEVICE" : {
                  "NAME":"Air conditioner",
                  "DOMAIN":"Smart home",
                  "INSTALLATION":"Fixed"
                },
        "MODE" : {
                   "COOL" : {"Temperature" : [16,"C"],
                              "Fan" : ["medium"]
                             },
                   "DRY" : {"Temperature" : [25,"C"],
                            "Fan" : ["high"]
                           }
                },
        "CONTROL" : {
                  "Temperature" : {
                                   "NUMERIC":{
                                             "RANGE":[14,30,0.5,"+","C"]
                                             }
                                  },
                  "Fan" : {
                           "STRING":{"OPTION":["low","medium","high"]}
                         },
                  "Swing" : {
                            "BOOLEAN":["up","down"]
                           },
                  "Power" : {
                            "BOOLEAN":["on","off"]
                           }
                }
        }
```

Annotations:
- `DEVICE`: never shown on screen.
- `MODE`: gives a spinner of `COOL`, `DRY`. Preset values are unused.
- `CONTROL` (line 15): not in the grammar. The paper's version says `ACTUATOR` (paper p.19). Runtime does not care, because it does not check section names.
- `RANGE`: min 14, max 30, step 0.5, operation `+`, unit `C`. Unit is free text, not UCUM.
- `Power`: `xml3` is chosen because the key contains `Power`, not because of its type.

### 3.2 Inline variants in code (VERIFIED)

| File | Lines | Difference from `ac.json` |
|---|---|---|
| `output.py` | 67-101 | `CONTROL`, `Fan` adds `very high` |
| `fetch.py` | 2-18 | `ACTUATOR` instead of `CONTROL` |
| `kotlintopython.py` | 51-67 | `ACTUATOR` instead of `CONTROL` |

No other device's manifest exists in this folder.

## 4. Validation: enforced vs not

| Rule | Enforced? | Where | Behaviour |
|---|---|---|---|
| Valid JSON | Partly | `K/DisplayThingActivity.kt:94` | Malformed text throws uncaught from `onCreate`: app crash (INFERRED) |
| Non-empty file | In 2 of 3 copies | `K/DisplayThingActivity.kt:84-87`, `K/Adhoc.kt:50-53` | Blank screen. `K/BluetoothDisplayThingActivity.kt:92-99` has no check and would throw |
| Section names | No | `:142` only filters a log loop | Any key accepted |
| One of `SENSOR`, `ACTUATOR` required | No | none | A file with neither gives an almost empty screen |
| `RANGE` is 4, 5 or 6 long | Yes | `:169` | Others give `Invalid RANGE Category`, no widget |
| `RANGE` length 4 | Accepted, then dropped | `:169,254` | Grammar allows it, runtime draws nothing: silent loss |
| `RANGE` items are numbers | No | `:333-335` | Non-numeric throws uncaught `JSONException`: crash (INFERRED) |
| `min < max`, `step > 0` | No | `:347` | `step` 0 gives an infinite ratio; no error raised (INFERRED) |
| `OPTION` all strings | Yes | `:175` | Otherwise no widget, log only |
| `BOOLEAN` is 2 strings | Yes | `:182` | Otherwise no widget, log only |
| Unknown key | Tolerated | `:188` | Silently ignored, no user signal |
| Size limit | No | `:103-118` | Whole file read into memory |
| Signature, hash, schema version | No | none | See `06` |
| Unit, range plausibility | No | none | Free text, trusted |
| Manifest contains text `ssh-rsa` | Mishandled | `K/ThingDetailsActivity.kt:239-250` | Manifest truncated at that point and the rest is treated as a key |

Python prototypes (`fetch.py`, `output.py`, `kotlintopython.py`) run no validation on real manifests.

## 5. Defects in the grammar itself

Anyone rebuilding from the grammar should know these. All VERIFIED by reading `grammar.txt`.

| Line | Defect |
|---|---|
| 1, 2 | `V` is written with `|` inside and spells `S | START`; symbols with spaces in this file, underscores in the paper |
| 3, 6 | `UNIT VAL` listed twice in V; `ACTUATOR` listed twice (line 1 and 7) |
| 7, 67, 69 vs 75 | `ACTUATOR` is both a top-level section and a boolean player attribute, with conflicting productions |
| 16 | `D -> ... | |D,D` contains an empty alternative |
| 16, 22, 30, 32, 46, 55, 59, 76 | `D,D`, `M,M`, `X,X` and similar make the grammar ambiguous; no ordering or uniqueness |
| 13 | `SENSOR` and `ACTUATOR` must follow all optional sections, so JSON key order would matter under a strict parser |
| 20 | `SOCKET -> :str:num` cannot be a JSON value |
| 27 | Terminal `array` has no element type |
| 34 | `SENSOR TYPE -> ... | {}` has no colon, unlike every sibling |
| 45 | `RANGE -> :[R,R]` nests arrays, which runtime rejects by length |
| 67 | `AUTOPLAY` is used but missing from `V` |
| 68 | `PLAYER TYPE` separated by commas, not `|`, so it reads as a sequence |

These also apply to the paper's copy (paper p.13 to 15).

## 6. Versioning

- **Schema version:** none. `SW_VERSION` and `HW_VERSION` describe the device, not the format.
- **Manifest identity:** none. No id, hash, or serial. Device identity is the Thing ID typed by the user, outside the manifest.
- **"Unique per machine":** in practice per model at best. All units running `send.py` serve the same fixed file (`send.py:72`).
- **Update:** the manifest is fetched once, when a Thing is added (`K/ThingDetailsActivity.kt:138`). Nothing re-fetches or compares it.
- **Storage:** one path, `manifest.json`, overwritten by each new Thing (`K/ThingDetailsActivity.kt:222`). Mode and SMF storage do not exist.

## 7. What a manifest can and cannot express

| Need | Grammar | Runtime today |
|---|---|---|
| Read-only numeric value with range and unit | Yes: `SENSOR` plus `NUMERIC` | No. Drawn as an editable slider labelled Temperature, showing `min` as its value |
| Discrete numeric values | Yes: `TUPLE` plus `UNIT` | No widget |
| Grouped values (systolic with diastolic) | Partly: `STRUCT_X` | No grouping, flattened |
| Trigger action (start measure) | No. Only a two-state `BOOLEAN` | Radio or switch with no listener |
| Result event, completion, error | No | No |
| Measurement status (inflating, ready) | Not as a type; a `STRING` would imply a settable choice | No |
| Timestamp on a value | `TIME` is a widget kind, not a field on a reading | No |
| Quality flags | No | No |
| Coded unit (UCUM), coded observation (LOINC, MDC) | No, free text | No |
| Clinical bounds separate from device range | No, one range | No |
| Device serial, manifest version | No | No |
| Localised labels | No | No |
| Waveform or live stream | `GRAPH` is pie or bar only | No |

## 8. Feasibility probe: DRAFT BP manifest

**DRAFT.** Exists only in this memo. Written in the exact paper grammar to test expressibility. Ranges are placeholders, not clinical bounds and not yet matched to SaMDPi (Phase 2 Appendix A does that).

```json
{
  "DEVICE": {
    "NAME": "Blood pressure monitor",
    "DOMAIN": "Medical",
    "INSTALLATION": "Portable",
    "SW_VERSION": "DRAFT",
    "HW_VERSION": "DRAFT"
  },
  "SENSOR": {
    "Systolic":  {"DESC": "Systolic pressure",  "NUMERIC": {"RANGE": [0, 300, 1, "+", "mmHg"]}},
    "Diastolic": {"DESC": "Diastolic pressure", "NUMERIC": {"RANGE": [0, 300, 1, "+", "mmHg"]}},
    "Pulse":     {"DESC": "Pulse rate",         "NUMERIC": {"RANGE": [0, 250, 1, "+", "bpm"]}},
    "Cuff battery": {"BATTERY": {}}
  },
  "ACTUATOR": {
    "Measure": {"DESC": "Cuff cycle", "BOOLEAN": ["start", "stop"]}
  }
}
```

### Probe result against the grammar

| Vital need | Fits today's grammar? | Note |
|---|---|---|
| Numeric reading with unit | Yes | `mmHg` and `bpm` are plain strings |
| Start measure | Only by misuse | `BOOLEAN` means two states, and a measurement is a momentary action |
| Result event | No | Nothing can say "reading complete" or "failed" |
| Systolic and diastolic as one reading | Weak | `STRUCT_X` could group them; nothing makes the pair atomic |

### Probe result against the current app (traced by reading, not run)

| Manifest part | What appears | Why | Evidence |
|---|---|---|---|
| `DEVICE` | Nothing | Category `String` has no branch | `K/DisplayThingActivity.kt:167,220-291` |
| Systolic, Diastolic, Pulse | 3 sliders labelled **Temperature**, showing `0.0 mmHg`, `0.0 mmHg`, `0.0 bpm` | `xml1` hardcodes the label and starts at `min`. Sensor names are ignored | `app/src/main/res/layout/xml1.xml:10`, `K/DisplayThingActivity.kt:348-349` |
| + or - on any of them | Sends `Data:1.0+IP:...` to the device | Same send path as an actuator; the value has no name | `K/DisplayThingActivity.kt:360-367,497` |
| `Cuff battery` | Nothing | `Unknown Category` | `:188` |
| `Measure` | Radio **Swing**, **Up**, **Down**, sends nothing | Key lacks `Power`, so `xml5`; strings ignored, no listener | `:285`, `app/src/main/res/layout/xml5.xml:10,27,34` |

**Answer to R0-6h.** A vitals device is partly expressible in the grammar and not supported by the app. Worse than absent: the app would show a wrong label and a made-up value (`0.0 mmHg`) as if it were a live reading. Grammar changes are needed for an action, a result event, status, timestamps and quality. Runtime needs replacing for everything else.

## 9. How to extend without breaking existing manifests

Facts for a later design, no recommendation yet (Phase 3).

- The runtime already ignores unknown keys, so new keys would not crash old clients. They would be silently dropped, which is a safety problem if the dropped key mattered.
- Positional RVAL arrays cannot be extended safely: index 5 is `EXCEPT` and length chooses the category (`K/DisplayThingActivity.kt:169`). New fields need named keys.
- Any new type key must be added in all three generator copies today.
- Adding a section is free at runtime but not valid under the grammar, as `CONTROL` shows.
- With no schema version, a client cannot tell "unknown to me" from "typo". Adding a version field is the prerequisite for any fail-closed rule (`12`).
- Existing manifests in the wild: one AC example, no fleet. Breaking compatibility costs little (INFERRED).

## Appendix A: INFERRED JSON Schema of the paper grammar

INFERRED. Not in the repo. It describes grammar intent, with defects from section 5 resolved in the most obvious way. `ac.json` fails it because of `CONTROL`.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "OneIoT main manifest (inferred)",
  "type": "object",
  "properties": {
    "DEVICE": {"type": "object", "additionalProperties": false, "minProperties": 1, "properties": {
      "NAME": {"type": "string"}, "DOMAIN": {"type": "string"}, "INSTALLATION": {"type": "string"},
      "WARRANTY": {"type": "string"}, "SW_VERSION": {"type": "string"}, "HW_VERSION": {"type": "string"}}},
    "OEM": {"type": "object", "additionalProperties": false, "minProperties": 1, "properties": {
      "NAME": {"type": "string"}, "SOCKET": {"type": "string", "description": "host:port, grammar form is not valid JSON"}}},
    "MODE": {"type": "object", "minProperties": 1, "additionalProperties": {
      "type": "object", "minProperties": 1, "additionalProperties": {
        "type": "array", "minItems": 1, "maxItems": 2,
        "prefixItems": [{"type": ["string", "number", "array"]}, {"type": "string"}]}}},
    "LOCATION": {"type": "array", "minItems": 1, "items": {"type": "string"}},
    "SENSOR": {"$ref": "#/$defs/members"},
    "ACTUATOR": {"$ref": "#/$defs/members"}
  },
  "anyOf": [{"required": ["SENSOR"]}, {"required": ["ACTUATOR"]}],
  "additionalProperties": false,
  "$defs": {
    "members": {"type": "object", "minProperties": 1, "additionalProperties": {"$ref": "#/$defs/member"}},
    "member": {"type": "object", "properties": {"DESC": {"type": "string"}},
      "oneOf": [
        {"required": ["BOOLEAN"], "properties": {"BOOLEAN": {"type": "array", "items": {"type": "string"}, "minItems": 2, "maxItems": 2}}},
        {"required": ["NUMERIC"], "properties": {"NUMERIC": {"$ref": "#/$defs/numeric"}}},
        {"required": ["STRING"], "properties": {"STRING": {"type": "object", "required": ["OPTION"], "properties": {"OPTION": {"type": "array", "minItems": 1, "items": {"type": "string"}}}}}},
        {"required": ["BATTERY"]}, {"required": ["GRAPH"]}, {"required": ["IMAGE"]}, {"required": ["AUDIO"]},
        {"required": ["VIDEO"]}, {"required": ["MAP"]}, {"required": ["COLOR"]}, {"required": ["DATE"]}, {"required": ["TIME"]},
        {"required": ["STRUCT"], "properties": {"STRUCT": {"$ref": "#/$defs/members"}}}
      ]},
    "numeric": {"type": "object", "oneOf": [
      {"required": ["RANGE"], "properties": {"RANGE": {"$ref": "#/$defs/rval"}}},
      {"required": ["TUPLE", "UNIT"], "properties": {"TUPLE": {"type": "array", "items": {"type": "number"}}, "UNIT": {"type": "string"}}}]},
    "rval": {"type": "array", "minItems": 4, "maxItems": 6, "prefixItems": [
      {"type": "number"}, {"type": "number"}, {"type": "number", "exclusiveMinimum": 0},
      {"enum": ["+", "*", "-1"]}, {"type": "string"}, {"type": "array"}]}
  }
}
```

## Appendix B: grammar audit (addendum A4, additions only)

### B.1 `grammar.txt`, transcribed in full

```text
V->{S | START, OPTIONAL, SENSOR, ACTUATOR, DEVICE, OEM, MODE, LOCATION, D, NAME,
DOMAIN, WARRANTY, INSTALLATION, SW VERSION, HW VERSION, O, SOCKET, M, NAME VAL, Z,
DATA, ACTUATOR INFO, VALUE, UNIT VAL, AREA, X, DESC, SENSOR TYPE, STRUCT X, BATTERY,
GRAPH, DATA TYPE, PIE, BAR, GRAPH TYPE, CORD1, CORD2, BOOLEAN, NUMERIC, STRING, IMAGE,
AUDIO, VIDEO, MAP, COLOR, DATE, TIME, RANGE, TUPLE, UNIT, R, RVAL, MIN, MAX, STEP,
UNIT VAL, OPERATION, EXCEPT, TVAL, OPTION, OVAL, SIZE, IMAGE ATTRIB, LENGTH, WIDTH,
SRC, IMAGE TYPE, PLAYER TYPE, PLAYER ATTRIB, LOOP, MUTE, ACTUATOR, Y,
ACTUATOR TYPE, STRUCT Y }
T->{num, pos num, str, bool, array, jpeg, png, gif, raw, time, [, ], {, }, :, ’,’}
S->{S}
P is defined as below:
S ->{START}
START -> OPTIONAL,START |SENSOR | ACTUATOR | SENSOR,ACTUATOR
OPTIONAL -> DEVICE | OEM | MODE | LOCATION
DEVICE ->:{D}
D -> NAME | DOMAIN | INSTALLATION | WARRANTY | SW VERSION | HW VERSION | |D,D
NAME, DOMAIN, INSTALLATION, WARRANTY, SW VERSION, HW VERSION -> :str
OEM -> :{O}
O -> NAME | SOCKET | NAME,SOCKET
SOCKET -> :str:num
MODE -> :{M}
M -> M,M | NAME VAL:{Z}
NAME VAL -> str
Z -> ACTUATOR INFO | ACTUATOR INFO,Z
ACTUATOR INFO -> NAME VAL : DATA
DATA -> [VALUE] | [VALUE,UNIT VAL]
VALUE -> str | num | array
UNIT VAL -> str
LOCATION -> :[AREA]
AREA -> str | AREA,AREA
SENSOR -> :{X}
X -> X,X | NAME VAL:{DESC, SENSOR TYPE} | NAME VAL:{SENSOR TYPE}
DESC -> :str
SENSOR TYPE -> STRUCT X | BATTERY | GRAPH | DATA TYPE | {}
STRUCT X -> :{X, X}
BATTERY -> :{}
GRAPH -> :{GRAPH TYPE}
GRAPH TYPE -> PIE | BAR
PIE, BAR -> :[CORD1,CORD2]
CORD1, CORD2 -> num | str | bool | time
DATA TYPE -> BOOLEAN | NUMERIC | STRING | IMAGE | AUDIO | VIDEO | MAP | COLOR |
DATE | TIME
BOOLEAN -> :[str, str]
NUMERIC -> :{RANGE} | :{TUPLE, UNIT}
RANGE -> :[R,R] | :RVAL
R -> RVAL | R,R
RVAL -> [MIN,MAX,STEP,OPERATION] |[MIN,MAX,STEP,OPERATION,UNIT VAL]
|[MIN,MAX,STEP,OPERATION,UNIT VAL,EXCEPT]
MIN,MAX -> num
STEP -> pos num
UNIT VAL -> str
OPERATION -> + | * | -1
EXCEPT -> [TUPLE] | [MIN,MAX,STEP,OPERATION]
TUPLE -> :[TVAL]
TVAL -> TVAL,TVAL | num
UNIT -> :str
STRING -> :{OPTION}
OPTION -> :[OVAL]
OVAL -> str | OVAL,OVAL
IMAGE -> :{SIZE, IMAGE ATTRIB} | :{SIZE} | :{IMAGE ATTRIB} | :{}
SIZE -> :[LENGTH, WIDTH, UNIT VAL]
LENGTH, WIDTH -> pos num
IMAGE ATTRIB -> :[SRC, IMAGE TYPE]
SRC -> str
IMAGE TYPE -> jpeg | png | gif | raw
AUDIO -> :{SIZE, PLAYER ATTRIB} | :{SIZE} | :{PLAYER ATTRIB} | :{}
PLAYER ATTRIB -> :[SRC, PLAYER TYPE, LOOP, MUTE, ACTUATOR, AUTOPLAY]
PLAYER TYPE -> mp4, mp3, ogg, mov, wmv, avi, mpeg, webm
LOOP, MUTE, ACTUATOR, AUTOPLAY -> bool
VIDEO -> :{SIZE, PLAYER ATTRIB} | :{SIZE} | :{PLAYER ATTRIB} | :{}
MAP -> :{SIZE} | :{}
COLOR -> :{}
DATE -> :{}
TIME -> :{}
ACTUATOR -> :{Y}
Y -> Y,Y | NAME VAL:{DESC, ACTUATOR TYPE} | NAME VAL:{ACTUATOR TYPE}
ACTUATOR TYPE -> DATA TYPE | STRUCT Y
STRUCT Y -> :{Y, Y}
```

### B.2 Rule-by-rule comparison with the paper

Method: both texts normalised (multi-word names joined by `_`, whitespace removed, Algorithm 1 and page furniture dropped), then diffed line by line. Paper text is p.13 to 15 via `pdftotext`.

| Difference | `grammar.txt` | Paper | Weight |
|---|---|---|---|
| V opening | `{S | START,` (line 1) | `{S, START,` | Typo in repo copy |
| Name style | `SW VERSION`, `UNIT VAL` and so on | `SW_VERSION`, `UNIT_VAL` | Paper's underscores match runtime `DisplayThingActivity`; spaces match `Adhoc` and `BluetoothDisplayThingActivity` |
| Quote mark in T | `’,’` | `′,′` | Typesetting |

Every production is otherwise identical (VERIFIED by the diff). So the defects below are in the published grammar, not just this copy. Fig 12 of the paper uses `ACTUATOR` where `ac.json:15` uses `CONTROL`; the two files otherwise have the same 31-line shape (VERIFIED).

### B.3 Flags

Line numbers are in `grammar.txt`. All VERIFIED by reading.

| Flag | Lines | Issue |
|---|---|---|
| Multi-LHS rules | 17, 39, 40, 49, 62, 69 | `NAME, DOMAIN, ... -> :str`, `PIE, BAR`, `CORD1, CORD2`, `MIN,MAX`, `LENGTH, WIDTH`, `LOOP, MUTE, ...`. Shorthand, not valid CFG form. A parser generator needs them expanded |
| Empty alternative in D | 16 | `HW VERSION | |D,D`: an empty alternative, so `"DEVICE":{}` and `,,` become derivable |
| Duplicates in V | 3, 6 and 1, 7 | `UNIT VAL`; `ACTUATOR` |
| Duplicate production | 28, 51 | `UNIT VAL -> str` twice |
| `ACTUATOR` reused | 67, 69 vs 75 | Section symbol reused as a player boolean, with conflicting expansions (`bool` vs `:{Y}`), so a media attribute could expand to a whole actuator section |
| `AUTOPLAY` undeclared | 67, 69 | Used on both sides, missing from V |
| Missing from T | 52, 68 | `+`, `*`, `-1`, and `mp4`, `mp3`, `ogg`, `mov`, `wmv`, `avi`, `mpeg`, `webm` are not terminals. Also `-` alone is not defined; `-1` is treated as one token |
| `PLAYER TYPE` form | 68 | Comma separated, which elsewhere means sequence, not choice |
| Undefined terminals | 9 | `array`, `time`, `num`, `pos num`, `str`, `bool` have no lexical definition |
| `:` placement: invalid JSON | 20 | `SOCKET -> :str:num` emits `"SOCKET":"host":8080` |
| `:` placement: invalid JSON | 53, 54 | `EXCEPT -> [TUPLE]` with `TUPLE -> :[TVAL]` emits `["TUPLE":[...]]`, a key inside an array |
| `:` placement: invalid JSON | 32, 34 | `SENSOR TYPE -> {}` inside `NAME VAL:{SENSOR TYPE}` emits `"x":{{}}` |
| `:` placement: inconsistent | 22, 25, 32 vs 15, 31 | Sometimes a colon starts the RHS and relies on Algorithm 1 to print the parent; sometimes it sits mid-rule after `NAME VAL` |
| Key vs positional | 47, 67 | RVAL and `PLAYER ATTRIB` are positional arrays, so new attributes cannot be added safely |
| Unbounded `OPTIONAL` | 13 | `START -> OPTIONAL,START` allows `DEVICE` repeated any number of times, which produces duplicate JSON keys |
| Order dependence | 13 | Optional sections must precede `SENSOR` and `ACTUATOR`, and `SENSOR` must precede `ACTUATOR`. JSON objects carry no such order, so a CFG over characters and a JSON parser disagree |
| Ambiguity | 16, 22, 30, 32, 46, 55, 59, 76 | `D,D`, `M,M`, `X,X` forms have many parse trees |
| Struct arity | 35, 78 | `STRUCT X -> :{X, X}` plus `X -> X,X` gives no way to read a struct's fields apart |
| Two meanings of `UNIT` | 44, 56, 26 | `UNIT` key for TUPLE, `UNIT VAL` positional for RANGE |

### B.4 What `OPERATION` and `EXCEPT` do in code

**Nothing.** `configureRangeElements` reads indexes 0, 1, 2 and 4 (`K/DisplayThingActivity.kt:333-336`), and always steps by addition (`:351-352,361-362,371`). Index 3 (`OPERATION`) and index 5 (`EXCEPT`) are never read. `EXCEPT` affects only array length, which selects the same widget (`:169`). The Python prototypes do not read them either (`output.py:53`, `fetch.py:41`). VERIFIED, all three copies.

### B.5 Does any code validate against the grammar?

**No.** No code parses a manifest against `grammar.txt` (`02` section 7a and 7b).

| Role | Exists? | Evidence |
|---|---|---|
| Validate a manifest against the grammar | No | Search over file list |
| Generate JSON from a parse tree (Algorithm 1, the paper's tool) | No | `ac.json` looks hand-written; DOCS-ONLY for the tool |
| Consume JSON | Yes, with ad hoc shape checks | `K/DisplayThingActivity.kt:165-190` |
| Prototype consumers | Yes: dead scripts | `output.py`, `fetch.py`, `kotlintopython.py` |
