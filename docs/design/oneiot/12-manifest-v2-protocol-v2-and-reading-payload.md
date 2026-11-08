Status: RESEARCH, not a design ruling. Everything here is DRAFT, memo only, for ruling at STOP 3. Sources read: memos `03`, `05`, `06`, `08`, `08a`, `09`; SaMDPi at `bba2c6b`; SaMD-App at `4b9ee3c`; W3C WoT TD 1.1, RFC 8785, RFC 8017, Bluetooth SIG assigned numbers, FHIR R4 vitals (linked where used).

Tag key: a claim with a file:line citation and no other tag is VERIFIED, read in code at the commit named above. A citation to another memo inherits that memo's tag. Anything else is marked INFERRED, DOCS-ONLY or EXTERNAL.

# 12: Manifest v2, protocol v2 and reading payload (R1-6, R3-1)

`E/` is SaMDPi `emulator/`. `D/` is SaMD-App `app/src/dev/java/com/example/samdapp/data/vitalssource/`. `A/` is `app/src/main/java/com/example/samdapp/`.

Rule from R2-2: an implemented reading model exists in SaMDPi, so this extends it. There is no second reading model.

## 1. Conflicts between rulings, and how this draft resolves them

| Conflict | Sources | Draft resolution | Needs ruling? |
|---|---|---|---|
| "Envelope fields on every bearer" vs "protocol v2 = SaMDPi's existing protocol" | R1-6 vs R3-1a | One **logical** envelope with a defined mapping per bearer (section 4). HTTP carries it as JSON, BLE keeps its binary Reading Envelope v1 | Yes |
| "Custom OneIoT service, framed JSON" vs "read characteristic in existing vendor service" | T-3 vs R3-1b | Later ruling wins: existing vendor service | No, recorded |
| Manifest read characteristic vs ATT size | R3-1b vs ATT limit | BP manifest exceeds 512 bytes; needs paging (section 3.5) | Yes |
| Simulated latency vs SaMD's Wi-Fi client | R3-1d vs `D/PiGatewayVitalsSource.kt:77-88` | Opt-in only (section 4.4) | No, forced by compatibility |
| Sha256 pinned by NFC vs NFC hardware not yet here | R3-1f vs T-0 "when it arrives" | Bundled allowlist now, NFC adds a tap (section 7) | Yes |

## 2. Manifest v2: what changes from the paper grammar

| Grammar defect (`03` Appendix B) | v2 |
|---|---|
| Positional RVAL | Named `RANGE` keys |
| Free-text unit | `UNIT.ucum`, plus `UNIT.display` |
| No codes | `CODES.loinc`, `CODES.mdc` |
| No action | `ACTION` |
| No result event | `EVENT` |
| No status | `STATUS` |
| No id, version, serial | `schema_version`, `manifest_id`, `manifest_version`, `device_serial` |
| No way to reach an operation | `BINDINGS` |
| Label from key | `LABEL` required |
| Actuator and sensor not enforced | Schema separates them; UI rule in section 8 |
| Invalid JSON productions | JSON Schema replaces the CFG |

Kept: `DEVICE`, `OEM`, `SENSOR`, `ACTUATOR`, `MODE`, `NUMERIC`, `RANGE`, `OPTION`, `BOOLEAN`, `DESC`.

`RANGE` is the **device's** reporting range. It is never a clinical bound (section 8). The emulator has no device range today, so the instrument files below use the scenario bands from `09` section 1 as placeholders.

### Generation (R3-1b)

Generate from `E/generator/instruments.py:19-80` plus the hub profile, so the two cannot drift. That table lacks labels, codes, ranges and status lists, so it must grow. Assessment: feasible. The manifest is a pure function of table plus profile, needs no new file on the hub, and passes the BUILD check (`E/hubctx.py:93-124`) because no generated file is written. Pin the output with a golden test in both repos, the existing `tests/fixtures/ble_golden_vectors.json` pattern (`docs/design/samdpi-two-hub-memo.md:1775`).

`emulator_build` must stay out of the manifest: it changes each deploy and would break any pinned hash. It already travels on the reading.

### Bindings, in the spirit of WoT

W3C WoT TD 1.1 describes a Form with `href`, content type and `op`, and calls Forms "serializations of Protocol Bindings" (EXTERNAL: [WoT TD 1.1](https://www.w3.org/TR/wot-thing-description11/)).

| OneIoT v2 op | WoT `op` | IP binding | BLE binding |
|---|---|---|---|
| `probe` | `readproperty` | `GET /api/v1/manifest` | Vendor manifest characteristic, paged |
| `start` | `invokeaction` | `POST /api/v1/session/start` | CP opcode |
| `stop` | `cancelaction` | `POST /api/v1/session/stop` | CP opcode |
| `status` | `readproperty`, `observeproperty` | `GET /api/v1/session/status` | Status characteristic, indicate |
| `reading` | `subscribeevent` | `GET /api/v1/measurement` | Envelope characteristic, indicate |

Not adopting WoT TD itself: its JSON-LD and generality add nothing for 3 instruments, and no client here consumes it (INFERRED).

## 3. Manifest v2 drafts

### 3.1 JSON Schema 2020-12 (block M1)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:oneiot:manifest:2",
  "title": "OneIoT manifest v2 (DRAFT)",
  "type": "object",
  "required": ["schema_version", "manifest_id", "manifest_version", "device_serial", "synthetic", "DEVICE", "BINDINGS"],
  "anyOf": [{"required": ["SENSOR"]}, {"required": ["ACTUATOR"]}],
  "additionalProperties": false,
  "patternProperties": {"^x_[a-z0-9_]+$": {}},
  "properties": {
    "schema_version": {"type": "string", "pattern": "^2\\.[0-9]+$"},
    "manifest_id": {"type": "string", "pattern": "^[a-z0-9-]{3,64}$"},
    "manifest_version": {"type": "integer", "minimum": 1},
    "device_serial": {"type": "string", "minLength": 1, "maxLength": 64},
    "synthetic": {"type": "boolean"},
    "DEVICE": {
      "type": "object",
      "required": ["NAME", "DOMAIN", "DEVICE_TYPE"],
      "additionalProperties": false,
      "properties": {
        "NAME": {"type": "string", "minLength": 1},
        "DOMAIN": {"type": "string", "minLength": 1},
        "DEVICE_TYPE": {"type": "string", "minLength": 1},
        "INSTALLATION": {"type": "string"},
        "WARRANTY": {"type": "string"},
        "SW_VERSION": {"type": "string"},
        "HW_VERSION": {"type": "string"}
      }
    },
    "OEM": {
      "type": "object",
      "required": ["NAME"],
      "additionalProperties": false,
      "properties": {"NAME": {"type": "string", "minLength": 1}}
    },
    "SENSOR": {"$ref": "#/$defs/members"},
    "ACTUATOR": {"$ref": "#/$defs/members"},
    "MODE": {
      "type": "object",
      "minProperties": 1,
      "additionalProperties": {
        "type": "object",
        "required": ["LABEL", "SET"],
        "additionalProperties": false,
        "properties": {
          "LABEL": {"type": "string", "minLength": 1},
          "SET": {"type": "object", "minProperties": 1}
        }
      }
    },
    "ACTION": {
      "type": "object",
      "minProperties": 1,
      "additionalProperties": {
        "type": "object",
        "required": ["LABEL", "op"],
        "additionalProperties": false,
        "properties": {
          "LABEL": {"type": "string", "minLength": 1},
          "op": {"enum": ["start", "stop"]},
          "produces": {"type": "string"}
        }
      }
    },
    "EVENT": {
      "type": "object",
      "minProperties": 1,
      "additionalProperties": {
        "type": "object",
        "required": ["LABEL", "fields"],
        "additionalProperties": false,
        "properties": {
          "LABEL": {"type": "string", "minLength": 1},
          "fields": {"type": "array", "minItems": 1, "items": {"type": "string"}}
        }
      }
    },
    "STATUS": {
      "type": "object",
      "required": ["states", "terminal"],
      "additionalProperties": false,
      "properties": {
        "states": {
          "type": "array",
          "minItems": 2,
          "items": {
            "type": "object",
            "required": ["id", "LABEL"],
            "additionalProperties": false,
            "properties": {"id": {"type": "string", "pattern": "^[A-Z_]+$"}, "LABEL": {"type": "string", "minLength": 1}}
          }
        },
        "terminal": {"type": "array", "minItems": 1, "items": {"type": "string"}}
      }
    },
    "BINDINGS": {
      "type": "object",
      "minProperties": 1,
      "additionalProperties": false,
      "properties": {"ble": {"$ref": "#/$defs/ble"}, "ip": {"$ref": "#/$defs/ip"}}
    }
  },
  "$defs": {
    "members": {"type": "object", "minProperties": 1, "additionalProperties": {"$ref": "#/$defs/member"}},
    "member": {
      "type": "object",
      "required": ["LABEL"],
      "additionalProperties": false,
      "properties": {
        "LABEL": {"type": "string", "minLength": 1},
        "DESC": {"type": "string"},
        "CODES": {
          "type": "object",
          "minProperties": 1,
          "additionalProperties": false,
          "properties": {"loinc": {"type": "string", "pattern": "^[0-9]+-[0-9]$"}, "mdc": {"type": "integer"}}
        },
        "NUMERIC": {
          "type": "object",
          "required": ["RANGE", "UNIT"],
          "additionalProperties": false,
          "properties": {
            "RANGE": {
              "type": "object",
              "required": ["min", "max", "resolution"],
              "additionalProperties": false,
              "properties": {
                "min": {"type": "number"},
                "max": {"type": "number"},
                "resolution": {"type": "number", "exclusiveMinimum": 0}
              }
            },
            "UNIT": {
              "type": "object",
              "required": ["ucum", "display"],
              "additionalProperties": false,
              "properties": {"ucum": {"type": "string", "minLength": 1}, "display": {"type": "string", "minLength": 1}}
            }
          }
        },
        "OPTION": {"type": "array", "minItems": 1, "items": {"type": "string"}},
        "BOOLEAN": {
          "type": "object",
          "required": ["on", "off"],
          "additionalProperties": false,
          "properties": {"on": {"type": "string", "minLength": 1}, "off": {"type": "string", "minLength": 1}}
        }
      },
      "oneOf": [{"required": ["NUMERIC"]}, {"required": ["OPTION"]}, {"required": ["BOOLEAN"]}]
    },
    "uuid": {"type": "string", "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"},
    "short_uuid": {"type": "string", "pattern": "^[0-9a-f]{4}$"},
    "ble": {
      "type": "object",
      "required": ["service", "control_point", "envelope", "manifest", "status", "sig", "ops"],
      "additionalProperties": false,
      "properties": {
        "service": {"$ref": "#/$defs/uuid"},
        "control_point": {"$ref": "#/$defs/uuid"},
        "envelope": {"$ref": "#/$defs/uuid"},
        "manifest": {"$ref": "#/$defs/uuid"},
        "status": {"$ref": "#/$defs/uuid"},
        "sig": {
          "type": "object",
          "required": ["service", "measurement"],
          "additionalProperties": false,
          "properties": {"service": {"$ref": "#/$defs/short_uuid"}, "measurement": {"$ref": "#/$defs/short_uuid"}}
        },
        "ops": {
          "type": "object",
          "required": ["start", "stop", "probe"],
          "additionalProperties": false,
          "properties": {
            "start": {"type": "integer", "minimum": 1, "maximum": 127},
            "stop": {"type": "integer", "minimum": 1, "maximum": 127},
            "probe": {"type": "integer", "minimum": 1, "maximum": 127}
          }
        }
      }
    },
    "ip": {
      "type": "object",
      "required": ["port", "ops"],
      "additionalProperties": false,
      "properties": {
        "port": {"type": "integer", "minimum": 1, "maximum": 65535},
        "nsd": {
          "type": "object",
          "required": ["type", "instance"],
          "additionalProperties": false,
          "properties": {"type": {"type": "string"}, "instance": {"type": "string"}}
        },
        "ops": {
          "type": "object",
          "required": ["probe", "start", "stop", "status", "reading"],
          "additionalProperties": false,
          "properties": {
            "probe": {"$ref": "#/$defs/route"},
            "start": {"$ref": "#/$defs/route"},
            "stop": {"$ref": "#/$defs/route"},
            "status": {"$ref": "#/$defs/route"},
            "reading": {"$ref": "#/$defs/route"}
          }
        }
      }
    },
    "route": {
      "type": "object",
      "required": ["method", "path"],
      "additionalProperties": false,
      "properties": {"method": {"enum": ["GET", "POST"]}, "path": {"type": "string", "pattern": "^/"}}
    }
  }
}
```

### 3.2 `ac.json` in v2, compatibility fixture (block M2)

Proves the schema still expresses the paper's own example. Bindings are illustrative, as the AC has no v2 service.

```json
{
  "schema_version": "2.0",
  "manifest_id": "fixture-air-conditioner",
  "manifest_version": 1,
  "device_serial": "FIXTURE-AC-0001",
  "synthetic": true,
  "DEVICE": {"NAME": "Air conditioner", "DOMAIN": "Smart home", "DEVICE_TYPE": "AIR_CONDITIONER", "INSTALLATION": "Fixed"},
  "ACTUATOR": {
    "temperature": {"LABEL": "Temperature", "NUMERIC": {"RANGE": {"min": 14, "max": 30, "resolution": 0.5}, "UNIT": {"ucum": "Cel", "display": "C"}}},
    "fan": {"LABEL": "Fan", "OPTION": ["low", "medium", "high"]},
    "swing": {"LABEL": "Swing", "BOOLEAN": {"on": "up", "off": "down"}},
    "power": {"LABEL": "Power", "BOOLEAN": {"on": "on", "off": "off"}}
  },
  "MODE": {
    "cool": {"LABEL": "Cool", "SET": {"temperature": 16, "fan": "medium"}},
    "dry": {"LABEL": "Dry", "SET": {"temperature": 25, "fan": "high"}}
  },
  "BINDINGS": {
    "ip": {
      "port": 8090,
      "ops": {
        "probe": {"method": "GET", "path": "/api/v1/manifest"},
        "start": {"method": "POST", "path": "/api/v1/session/start"},
        "stop": {"method": "POST", "path": "/api/v1/session/stop"},
        "status": {"method": "GET", "path": "/api/v1/session/status"},
        "reading": {"method": "GET", "path": "/api/v1/measurement"}
      }
    }
  }
}
```

### 3.3 BP on kernelhub2 (block M3)

```json
{
  "schema_version": "2.0",
  "manifest_id": "samdpi-bp",
  "manifest_version": 1,
  "device_serial": "kernelhub2-BP",
  "synthetic": true,
  "DEVICE": {"NAME": "Blood Pressure Monitor", "DOMAIN": "Medical", "DEVICE_TYPE": "BLOOD_PRESSURE", "INSTALLATION": "Portable", "HW_VERSION": "samdpi-hub"},
  "OEM": {"NAME": "SaMD dev emulator (synthetic)"},
  "SENSOR": {
    "systolic": {"LABEL": "Systolic", "NUMERIC": {"RANGE": {"min": 82, "max": 179, "resolution": 0.1}, "UNIT": {"ucum": "mm[Hg]", "display": "mmHg"}}, "CODES": {"loinc": "8480-6", "mdc": 150021}},
    "diastolic": {"LABEL": "Diastolic", "NUMERIC": {"RANGE": {"min": 53, "max": 108, "resolution": 0.1}, "UNIT": {"ucum": "mm[Hg]", "display": "mmHg"}}, "CODES": {"loinc": "8462-4", "mdc": 150022}},
    "pulse": {"LABEL": "Pulse", "NUMERIC": {"RANGE": {"min": 50, "max": 115, "resolution": 0.1}, "UNIT": {"ucum": "/min", "display": "bpm"}}, "CODES": {"loinc": "8867-4"}}
  },
  "ACTION": {
    "measure": {"LABEL": "Start measurement", "op": "start", "produces": "reading"},
    "cancel": {"LABEL": "Stop", "op": "stop"}
  },
  "EVENT": {"reading": {"LABEL": "Reading complete", "fields": ["systolic", "diastolic", "pulse"]}},
  "STATUS": {
    "states": [
      {"id": "IDLE", "LABEL": "Ready"},
      {"id": "INFLATING", "LABEL": "Cuff inflating"},
      {"id": "DEFLATING", "LABEL": "Measuring"},
      {"id": "DONE", "LABEL": "Done"},
      {"id": "ERROR", "LABEL": "Device error"},
      {"id": "STOPPED", "LABEL": "Stopped"}
    ],
    "terminal": ["DONE", "ERROR", "STOPPED"]
  },
  "BINDINGS": {
    "ble": {
      "service": "f4610001-95ad-4b7d-a8ed-b568b5b0e927",
      "control_point": "f4610002-95ad-4b7d-a8ed-b568b5b0e927",
      "envelope": "f4610003-95ad-4b7d-a8ed-b568b5b0e927",
      "manifest": "f4610004-95ad-4b7d-a8ed-b568b5b0e927",
      "status": "f4610005-95ad-4b7d-a8ed-b568b5b0e927",
      "sig": {"service": "1810", "measurement": "2a35"},
      "ops": {"start": 4, "stop": 2, "probe": 3}
    }
  }
}
```

### 3.4 SpO2 on kernelhub1, and Thermometer on proposed kernelhub3 (blocks M4, M5)

```json
{
  "schema_version": "2.0",
  "manifest_id": "samdpi-spo2",
  "manifest_version": 1,
  "device_serial": "kernelhub1-SPO2",
  "synthetic": true,
  "DEVICE": {"NAME": "Pulse Oximeter", "DOMAIN": "Medical", "DEVICE_TYPE": "PULSE_OXIMETER", "INSTALLATION": "Portable", "HW_VERSION": "samdpi-hub"},
  "OEM": {"NAME": "SaMD dev emulator (synthetic)"},
  "SENSOR": {
    "spo2": {"LABEL": "SpO2", "NUMERIC": {"RANGE": {"min": 86, "max": 100, "resolution": 0.1}, "UNIT": {"ucum": "%", "display": "%"}}, "CODES": {"loinc": "59408-5", "mdc": 150456}},
    "pulse": {"LABEL": "Pulse", "NUMERIC": {"RANGE": {"min": 56, "max": 118, "resolution": 0.1}, "UNIT": {"ucum": "/min", "display": "bpm"}}, "CODES": {"loinc": "8867-4", "mdc": 149530}}
  },
  "ACTION": {
    "measure": {"LABEL": "Start measurement", "op": "start", "produces": "reading"},
    "cancel": {"LABEL": "Stop", "op": "stop"}
  },
  "EVENT": {"reading": {"LABEL": "Reading complete", "fields": ["spo2", "pulse"]}},
  "STATUS": {
    "states": [
      {"id": "IDLE", "LABEL": "Ready"},
      {"id": "SIGNAL_SEARCH", "LABEL": "Finding signal"},
      {"id": "DONE", "LABEL": "Done"},
      {"id": "ERROR", "LABEL": "Device error"},
      {"id": "STOPPED", "LABEL": "Stopped"}
    ],
    "terminal": ["DONE", "ERROR", "STOPPED"]
  },
  "BINDINGS": {
    "ip": {
      "port": 8090,
      "nsd": {"type": "_samd-gw._tcp", "instance": "kernelhub1"},
      "ops": {
        "probe": {"method": "GET", "path": "/api/v1/manifest"},
        "start": {"method": "POST", "path": "/api/v1/session/start"},
        "stop": {"method": "POST", "path": "/api/v1/session/stop"},
        "status": {"method": "GET", "path": "/api/v1/session/status"},
        "reading": {"method": "GET", "path": "/api/v1/measurement"}
      }
    }
  }
}
```

```json
{
  "schema_version": "2.0",
  "manifest_id": "samdpi-thermometer",
  "manifest_version": 1,
  "device_serial": "kernelhub3-THERMOMETER",
  "synthetic": true,
  "DEVICE": {"NAME": "Digital Thermometer", "DOMAIN": "Medical", "DEVICE_TYPE": "THERMOMETER", "INSTALLATION": "Portable", "HW_VERSION": "samdpi-hub"},
  "OEM": {"NAME": "SaMD dev emulator (synthetic)"},
  "SENSOR": {
    "body_temperature": {"LABEL": "Body temperature", "NUMERIC": {"RANGE": {"min": 34.9, "max": 39.5, "resolution": 0.01}, "UNIT": {"ucum": "Cel", "display": "°C"}}, "CODES": {"loinc": "8310-5", "mdc": 150364}}
  },
  "ACTION": {
    "measure": {"LABEL": "Start measurement", "op": "start", "produces": "reading"},
    "cancel": {"LABEL": "Stop", "op": "stop"}
  },
  "EVENT": {"reading": {"LABEL": "Reading complete", "fields": ["body_temperature"]}},
  "STATUS": {
    "states": [
      {"id": "IDLE", "LABEL": "Ready"},
      {"id": "SENSING", "LABEL": "Reading temperature"},
      {"id": "DONE", "LABEL": "Done"},
      {"id": "ERROR", "LABEL": "Device error"},
      {"id": "STOPPED", "LABEL": "Stopped"}
    ],
    "terminal": ["DONE", "ERROR", "STOPPED"]
  },
  "BINDINGS": {
    "ip": {
      "port": 8090,
      "nsd": {"type": "_samd-gw._tcp", "instance": "kernelhub3"},
      "ops": {
        "probe": {"method": "GET", "path": "/api/v1/manifest"},
        "start": {"method": "POST", "path": "/api/v1/session/start"},
        "stop": {"method": "POST", "path": "/api/v1/session/stop"},
        "status": {"method": "GET", "path": "/api/v1/session/status"},
        "reading": {"method": "GET", "path": "/api/v1/measurement"}
      }
    }
  }
}
```

`mdc` is omitted for BP pulse: `MDC_PULS_RATE_NON_INV`'s number is unverified (`09` Appendix B). The schema allows that. Whether `CODES` should be mandatory for a clinical manifest is an OQ.

### 3.5 Serving the manifest

| Bearer | Mechanism | Evidence and assessment |
|---|---|---|
| IP | New `GET /api/v1/manifest` on the existing handler | `E/transports/wifi/server.py:238-285`. Hub `/composite/` rule unaffected. Additive |
| BLE | Read characteristic in vendor service `f4610001-...` | `E/transports/ble/ble_hub.py:348-353`. `ReadValue` already handles `offset` (`:175-179`) |

**BLE size limit.** Maximum characteristic value is 512 bytes, which is why 517 is the common MTU (EXTERNAL, secondary: [Web Bluetooth issue 383](https://github.com/WebBluetoothCG/web-bluetooth/issues/383)). The ATT long-read procedure itself was not confirmed from a primary source here, and needs a hardware test (OQ-35). Android 14 and later set MTU 517 for the first client (EXTERNAL: [requestMtu reference](https://learn.microsoft.com/en-us/dotnet/api/android.bluetooth.bluetoothgatt.requestmtu?view=net-android-37.0)); hardware saw `mtu=517` (`docs/design/samdpi-two-hub-memo.md:1592,1636`). Long reads vs larger MTU is a filed item, never tested (`:1724`).

Measured sizes are in section 10: **the BP manifest does not fit 512 bytes, even gzipped.** So paging is required:

| Option | How | For | Against |
|---|---|---|---|
| Page by CP write, then read (proposed) | Write CP `0x03` with u16 page. Hub answers on CP, characteristic then returns that page: u16 page, u16 total pages, u32 total length, 32-byte sha256, data | Fits read characteristic ruling; stateless reads; owner-gated writes | Reads depend on hub state; state machine grows |
| Indicate pages | CP write, pages indicated | Same confirm-driven flow as envelope | Not a "read characteristic" |
| Shrink under 512 | Move `BINDINGS` and labels out | No paging | Loses R3-1b's content; fragile |
| Compress | gzip compact JSON | Smaller | Likely still near the limit; adds a step |

Phone checks the sha256 over the whole reassembled bytes before parsing. Serve canonical bytes (RFC 8785 JCS: sorted keys, no whitespace, ECMAScript number form, informational status; EXTERNAL: [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785)), so the hash is stable across languages. The hub's Python and the phone's Kotlin then need only compare bytes: the phone hashes what it received and never re-serialises.

## 4. Protocol v2

Extends SaMDPi's protocol (R3-1a). **All v1 calls keep working byte for byte**, because SaMD-App's client must not break.

### 4.1 Phases

| Phase | Today | v2 | Bearer detail |
|---|---|---|---|
| Discovery | NSD, CDM | Same, plus NFC tap | `11b` |
| Probe | None | **New** | Section 3.5 |
| Invoke GET | `GET measurement` | Same | Reading is the only GET for instruments |
| Invoke SET | None for instruments | None in demo | Kept for `ACTUATOR` manifests such as AC |
| Action | `start`, `stop` | Same, opt-in v2 start | Section 4.4 |
| Event | Envelope indication, polled measurement | Same | |
| Status | Thin session status | **Real states** | Section 4.5 |

Paper's Registration and Configuration phases: not used. No cloud (R2-4a).

### 4.2 Logical envelope and its mapping

| Field | HTTP | BLE |
|---|---|---|
| `protocol` | `"OneIoT"` in JSON `envelope` object | Implied by vendor service UUID |
| `version` | `"2"` in `envelope` | `envelope_version` byte, `E/envelope.py:5` |
| `phase` | In `envelope` | Implied by characteristic and opcode |
| `msg_id` | UUID in `envelope` | `(nonce, sequence)`, `E/envelope.py:10-11` |
| `correlation_id` | Client nonce echoed | Nonce echoed |
| `timestamp` | Hub time, advisory | `device_time`, `E/envelope.py:12` |
| `payload` | Existing body fields stay top level, unchanged | SIG bytes plus envelope |

On HTTP, a v1 client ignores the added `envelope` object: its DTO lists fields and Gson drops the rest (`D/PiGatewayApi.kt:62-78`, INFERRED; test it).

Example HTTP measurement (shape only):

```text
{ "envelope": {"protocol":"OneIoT","version":"2","phase":"event","msg_id":"...","correlation_id":"...","timestamp":"..."},
  "hub_id":"kernelhub1", "device_id":"kernelhub1-SPO2", "device_type":"PULSE_OXIMETER", "quality_status":"OK",
  "primary_value":98.1, "secondary_value":71.0, "unit":"%", "provenance":{"session_id":"..."}, ... ,
  "values":[...], "manifest":{...} }
```

### 4.3 Ack and idempotency

| Rule | HTTP | BLE |
|---|---|---|
| Delivery ack | HTTP response | Indication confirm (`E/transports/ble/control_point.py:228-246`) |
| Retry | Client may retry GET safely. Start is not retried: a new start gets a new nonce | No retry: one connect per Start (`D/ble/BleAcquisition.kt:25-29`) |
| Idempotency key | `measurement_id` (UUID), already generated (`E/generator/instruments.py:120`), unused by SaMD today | Add `measurement_id` derived from `(nonce, sequence)` on phone, as BLE has no UUID field |
| Dedupe | Consumer keeps ids per session; same id means same reading | Same |
| Replay | Fresh client nonce, echoed; hub refuses reused nonce (BLE exists: `control_point.py:279`; IP is new) | Exists |

### 4.4 Opt-in v2 start

**Why opt-in is forced.** SaMD's client does one `GET` right after start (`D/PiGatewayVitalsSource.kt:77-88`). A delayed reading returns 404, which maps to `NO_MEASUREMENT` (`:146-147`). BLE's `START` length is fixed at 19 (`control_point.py:58,272-273`), so a longer `START` is `INVALID_PARAMETER`.

| Bearer | v1 start (unchanged) | v2 start |
|---|---|---|
| IP | No new field: reading at once | `"protocol_version": 2, "client_nonce": "<uuid>"`. Reading after latency |
| BLE | Opcode `0x01`: reading at once | New opcode `0x04`, same body. Status indications, then reading after latency |

BLE timing fits the phone's existing caps: envelope wait 10 s, whole exchange 25 s (`D/ble/BleAcquisition.kt:34-37`), and BP latency tops out at 4.5 s (next section). IP needs v2 clients to poll status, then GET.

### 4.5 Lifecycle and latency (R3-1d)

Latency constants only, from `E/generator/multi_worker.py:35-42`. **The composite module is never reused** (G-A, `08` section 7). Move the three constants into `instruments.py`.

| Instrument | States | Latency (from `multi_worker.py`) | Split (INFERRED, placeholder) |
|---|---|---|---|
| BP | `IDLE`, `INFLATING`, `DEFLATING`, then `DONE` or `ERROR` | 2.5 to 4.5 s | Half and half |
| SpO2 | `IDLE`, `SIGNAL_SEARCH`, then `DONE` or `ERROR` | 1.2 to 2.5 s | n/a |
| Thermometer | `IDLE`, `SENSING`, then `DONE` or `ERROR` | 0.8 to 1.8 s | n/a |
| All | `STOPPED` on stop | | |

Implementation notes (assessment only):
- **IP:** a `threading.Timer` as in `E/transports/wifi/server.py:143-145`. Hub path deliberately avoids a timer today (`:298`), so this reverses a stated property for v2 sessions only. Stop must cancel it.
- **BLE:** `HubStateMachine` has "no clock of its own" and is pure (`control_point.py:3`). Add a `Delay` action, executed by the adapter with `loop.call_later`. Status indications join the confirm-ordered queue, a subtle area (`:23-31`, residual at `:195-199`). R2 to R3 risk.
- **Faults** report `ERROR` after latency, with the same quality values.

Intermediate cuff pressure `0x2A36` remains unimplemented: not needed, and it would raise the rate of indications.

### 4.6 Android start nonce from the caller

The bridge's `request_id` becomes the session nonce (`10` section 1.2). BLE already takes a 16-byte nonce (`D/ble/BleAcquisition.kt:51,57-58`), so it fits. IP needs `client_nonce` (section 4.4). That carries SaMD's RC-1 correlation end to end.

## 5. Reading payload contract (extends NormalizedMeasurement)

Shared by the demo bridge and the target. **No patient, encounter or session binding from the device**: SaMD binds on receipt (brief 3.3).

### 5.1 Fields

| Field | Status | Source |
|---|---|---|
| `reading_version` | New, `2` | |
| `measurement_id`, `sequence_number` | Kept | `E/models.py:60-61` |
| `device_id`, `device_type`, `measurement_type` | Kept | `:58-62` |
| `hub_id`, `profile_version`, `emulator_build`, `envelope_version` | Kept | `E/transports/wifi/server.py:202,267-270` |
| `session_id` | Kept in `provenance.session_id`, so RC-1 is unchanged | `D/PiMeasurementMapper.kt:44` |
| `client_nonce` | New, inside `provenance` | Section 4.4 |
| `manifest` | New: `manifest_id`, `manifest_version`, `sha256` | |
| `values[]` | New: `name`, `value`, `ucum`, `display_unit`, `loinc`, `mdc` | |
| `primary_value`, `unit` and siblings | Kept, for v1 clients. On BLE the phone fills them | `E/models.py:63-68` |
| `quality_status` | Kept, same 4 values | `E/models.py:45-49` |
| `measured_at` | Kept. Device, nullable, never fabricated | `E/models.py:69`, `POC:229` |
| `received_at` | Kept. **Phone authoritative**: receiver overwrites what the hub sent | `E/envelope.py:18`; today Wi-Fi hub fills it (`E/generator/instruments.py:119`) |
| `transport`, `protocol`, `synthetic` | Kept | `E/models.py:72-74` |

### 5.2 Schema (block R1) and example (block R2)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:oneiot:reading:2",
  "title": "OneIoT reading payload v2 (DRAFT)",
  "type": "object",
  "required": ["reading_version", "measurement_id", "sequence_number", "device_id", "device_type", "hub_id", "manifest", "values", "quality_status", "received_at", "transport", "protocol", "synthetic", "provenance"],
  "additionalProperties": true,
  "not": {"anyOf": [{"required": ["patient_id"]}, {"required": ["encounter_id"]}, {"required": ["case_record_id"]}]},
  "properties": {
    "reading_version": {"const": 2},
    "measurement_id": {"type": "string", "minLength": 1},
    "sequence_number": {"type": "integer", "minimum": 1},
    "device_id": {"type": "string", "minLength": 1},
    "device_type": {"type": "string", "minLength": 1},
    "hub_id": {"type": "string", "pattern": "^[a-z0-9-]{3,32}$"},
    "manifest": {
      "type": "object",
      "required": ["manifest_id", "manifest_version", "sha256"],
      "additionalProperties": false,
      "properties": {
        "manifest_id": {"type": "string"},
        "manifest_version": {"type": "integer", "minimum": 1},
        "sha256": {"type": "string", "pattern": "^[0-9a-f]{64}$"}
      }
    },
    "values": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["name", "value", "ucum"],
        "additionalProperties": false,
        "properties": {
          "name": {"type": "string", "minLength": 1},
          "value": {"type": "number"},
          "ucum": {"type": "string", "minLength": 1},
          "display_unit": {"type": "string"},
          "loinc": {"type": "string"},
          "mdc": {"type": "integer"}
        }
      }
    },
    "quality_status": {"enum": ["OK", "WARNING", "ERROR", "UNAVAILABLE"]},
    "measured_at": {"type": ["string", "null"]},
    "received_at": {"type": "string", "minLength": 1},
    "transport": {"enum": ["WIFI", "BLE", "USB"]},
    "protocol": {"type": "string"},
    "synthetic": {"type": "boolean"},
    "provenance": {
      "type": "object",
      "required": ["session_id"],
      "properties": {"session_id": {"type": "string", "minLength": 1}, "client_nonce": {"type": "string"}}
    }
  },
  "allOf": [
    {
      "if": {"properties": {"quality_status": {"const": "OK"}}},
      "then": {"properties": {"values": {"minItems": 1}}},
      "else": {"properties": {"values": {"maxItems": 0}}}
    }
  ]
}
```

```json
{
  "reading_version": 2,
  "measurement_id": "7f8d1c2e-3b4a-4c5d-9e6f-001122334455",
  "sequence_number": 1,
  "device_id": "kernelhub2-BP",
  "device_type": "BLOOD_PRESSURE",
  "measurement_type": "BLOOD_PRESSURE",
  "hub_id": "kernelhub2",
  "manifest": {"manifest_id": "samdpi-bp", "manifest_version": 1, "sha256": "0000000000000000000000000000000000000000000000000000000000000000"},
  "values": [
    {"name": "systolic", "value": 120.4, "ucum": "mm[Hg]", "display_unit": "mmHg", "loinc": "8480-6", "mdc": 150021},
    {"name": "diastolic", "value": 80.2, "ucum": "mm[Hg]", "display_unit": "mmHg", "loinc": "8462-4", "mdc": 150022},
    {"name": "pulse", "value": 72.1, "ucum": "/min", "display_unit": "bpm", "loinc": "8867-4"}
  ],
  "quality_status": "OK",
  "measured_at": "2026-11-06T10:15:30Z",
  "received_at": "2026-11-06T10:15:31Z",
  "transport": "BLE",
  "protocol": "GATT_SIG",
  "synthetic": true,
  "provenance": {"session_id": "7f8d1c2e-0000-4000-8000-001122334455", "client_nonce": "7f8d1c2e-0000-4000-8000-001122334455"}
}
```

Two deliberate schema rules: a device reading may not carry patient keys, and a non-OK reading carries **no** values. That second rule fixes today's Wi-Fi 0.0 fault values (`E/generator/profiles.py:33-37`) at the contract level. Hub keeps sending 0.0 on the v1 fields.

### 5.3 Rules for SaMD on receipt (proposal)

Order extends `D/PiMeasurementMapper.kt:39-53`: quality, session, device type, **then new: manifest pin, unit check, canonicalise**, then map.

| Rule | Why |
|---|---|
| Reject when a value's `ucum` is not the one SaMD expects for that field | Mapper ignores units today (`08` section 6) |
| Canonicalise display units in SaMD | `mm[Hg]` vs `mmHg`, `Cel` vs `°C` |
| Bounds are SaMD's, never the manifest's | R1-6 |
| Patient and session bound from the ViewModel | `A/presentation/compounder/CompounderViewModel.kt:666-667` |
| Nothing written until Continue | RC-4 |

## 6. Trust and validation

| Rule | Policy |
|---|---|
| Unknown top-level key | **Fail closed**, except `x_` extensions, which must never affect rendering |
| `schema_version` major other than 2 | Fail closed |
| Unknown type in a `member` | Fail closed: schema rejects it. Old v1 behaviour of silently dropping it (`03` section 4) was the hazard |
| Size | Cap, proposed 16 KB, before parse |
| JSON duplicates, NaN | Reject at parse |
| Sha256 mismatch | Reject; nothing shown |
| `synthetic: true` | Visible label required, H-43 pattern |
| Manifest change | New `manifest_version`, new hash, new CI entry |

Each manifest is a **configuration item**: file or generator input, version, sha256, approval record, listed in the design history. Under R3-1b's generator, the CI is the pair of generator commit and golden output.

## 7. Manifest integrity options (R3-1f)

| Option | What it proves | Cost | Failure | Verdict |
|---|---|---|---|---|
| None | Nothing | 0 | Tampered manifest draws wrong UI | Reject |
| Sha256 allowlist bundled in app | Manifest equals an approved one | Low. New build per manifest change | Offline safe; stale list refuses new manifest | **Use now** |
| NFC tap pins sha256 | Manifest equals what this physical hub's tag says | Tag hardware. Sticker can be swapped by hand | Tag missing: fall back to allowlist | **Add when hardware arrives** |
| Detached JWS or COSE signature | Signed by a key holder | Key management, SOUP, rotation | Key compromise | Production later; contradicts "no new crypto" (R3-1f) |

Recommendation: allowlist and NFC together. NFC alone cannot be the control while no tag exists. And a tap says "this hub", while the allowlist says "this manifest was approved", which are different claims. Pinning is **not** authentication of readings: link security stays as `09` section 4.

## 8. UI rule for clinical use (R1-6)

| Rule | Enforced by | Old behaviour it replaces |
|---|---|---|
| Manifest fills a fixed template | `oneiot-ui` | Layout chosen by key name (`04` section 2) |
| Labels and units come from the manifest | Schema requires `LABEL`, `UNIT` | Hardcoded "Temperature" |
| Sensors never editable | `SENSOR` has no input widget | Slider sends values |
| No value until a real reading arrives | Empty state, no default | `min` shown as value |
| Non-OK reading shows no number | Contract, section 5.2 | 0.0 values |
| Status shown only from hub | `STATUS` | None |
| Clinical bounds owned by SaMD | Manifest `RANGE` never used for clinical flags | No bounds at all |
| Synthetic label always visible | `synthetic` | None |
| Unknown or unverified manifest shows an error, not a screen | Section 6 | Silent drop |

SaMD's own form stays editable (RC-3). OneIoT's sensor screen is read-only. Both hold: a human edit happens in the clinical record, not on the device screen.

## 9. Module boundaries (R1-4 names, R2-3 placement)

| Module | Holds | Android-free? | Demo bridge | Later SaMD (T1) |
|---|---|---|---|---|
| `oneiot-manifest` | Model, parser, validator, hash, allowlist | Yes | Yes, in OneIoT | Yes |
| `oneiot-protocol` | Logical envelope, phases, reading payload | Yes | Yes, in OneIoT; **payload classes also in SaMD** | Yes |
| `oneiot-transport-wifi` | HTTP, NSD, covers USB tether | No | Yes | Yes, behind F1 gate |
| `oneiot-transport-ble` | Nordic client, CDM | No | Yes | Yes, dev only until boundary ruling |
| `oneiot-transport-usb` | AOA fallback only, if needed | No | If needed | Unknown |
| `oneiot-transport-nfc` | Tap to pair | No | When hardware arrives | Unknown |
| `oneiot-ui` | Compose template | No | Yes | Maybe; SaMD has its own form |
| App shell | Navigation, permissions, Send | No | Yes | **No** |

Under D1 SaMD needs only the payload contract: copy the classes or publish `oneiot-protocol` (OQ). Hub Python has no shared code with Kotlin, so golden files keep them equal.

## 10. Measured: validation of the drafts in this memo

Blocks M1 to M5, R1 and R2 were extracted from this file and checked with `check-jsonschema` 0.33.0. Files: `/media/sandesh/extra-ssd/oneiot-study/drafts/`. These are drafts, and no production code exists.

| Check | Result |
|---|---|
| Both schemas pass the 2020-12 metaschema | Pass |
| AC, BP, SpO2, Thermometer validate against M1 | Pass |
| Reading example validates against R1 | Pass |
| `x_` extension key allowed | Pass |

Mutation proof: each change below had to be rejected, and was.

| Mutation | Result |
|---|---|
| Positional RVAL array | Rejected |
| `LABEL` removed | Rejected |
| Unknown top-level key `CONTROL` (the `ac.json` defect) | Rejected |
| `BINDINGS` removed | Rejected |
| `schema_version` 1.0 | Rejected |
| `UNIT.ucum` removed | Rejected |
| Malformed LOINC | Rejected |
| Two types on one member | Rejected |
| `patient_id` on a reading | Rejected |
| `quality_status: ERROR` with values present | Rejected |
| `quality_status: OK` with no values | Rejected |
| Unknown quality value | Rejected |
| `provenance.session_id` removed | Rejected |

A schema cannot catch a correctly shaped wrong value: `"ucum": "%"` on systolic would pass. That check belongs to SaMD's expected-unit table (section 5.3), and a hub-side golden test.

### Sizes, compact sorted JSON (an approximation of RFC 8785 output)

Data per page is 472 bytes: the 512-byte value limit, minus the 40-byte header from section 3.5 (2 + 2 + 4 + 32).

| Manifest | Bytes | Pages at 472 bytes of data |
|---|---|---|
| BP | 1664 | 4 |
| SpO2 | 1449 | 4 (IP only today; shown for comparison) |
| Thermometer | 1336 | 3 (IP only; shown for comparison) |
| AC fixture | 1019 | 3 |
| BP, gzip -9 | 771 | Still over 512 |
| Reading example | 921 | Not sent over BLE. Binary envelope stays |

## 11. Amendments at STOP 3 (R4-K, R4-F, R4-2, R4-3; operator 2026-11-08; additions only)

### 11.1 Withdrawn under R4-K

Superseded by R4-K: every manifest key in sections 2 and 3 that is not in the paper's dictionary (p.13 to 15). Withdrawn: `BINDINGS`, `schema_version`, `manifest_id`, `manifest_version`, `device_serial`, `synthetic`, `DEVICE_TYPE`, `LABEL`, `CODES`, `ACTION`, `EVENT`, `STATUS`, the `UNIT` object form, named keys inside `RANGE`, and `CONTROL` (use `ACTUATOR`). Blocks M1 to M5 are superseded by the drafts below. Section 3.5's RFC 8785 canonical-bytes rule is superseded: the client hashes the bytes it receives and the hub serves the file bytes unchanged, so no canonical form is needed (INFERRED).

### 11.2 New drafts, exact keywords only

Scratch: `/media/sandesh/extra-ssd/oneiot-study/drafts3/`. Files: `mmf.schema.json` (the paper grammar as JSON Schema 2020-12, spellings with underscores, defects resolved as in `15` section 3), `vitals.schema.json` (a profile: extra constraints, no new keywords), `bp.json`, `spo2.json`. Ranges are the emulator scenario bands from block M3 and M4, placeholders and not clinical bounds (OQ-19).

| Rule in the vitals profile | Why |
|---|---|
| `DEVICE.DOMAIN` is `Medical`; `SENSOR`, `ACTUATOR` and `DEVICE` required | Paper p.11 names medical as a domain |
| Every numeric `SENSOR` uses `RANGE` of length 5, `OPERATION` `+`, `UNIT_VAL` one of `mm[Hg]`, `/min`, `%`, `Cel` | Unit must always be present and coded (SAMD-2) |
| `SENSOR.Status` is `STRING` with `OPTION` exactly `idle, measuring, done, error, stopped` | Status as a read-only sensor (R4-K) |
| `ACTUATOR` holds only `Measure`, `STRING` with `OPTION` exactly `start, stop` | Start as an actuator (R4-K) |
| `MODE`, `LOCATION` absent | Not used by vitals; `LOCATION` belongs to the SMF (p.12) |

### 11.3 Measured: validation (2026-11-08, `jsonschema` from `check-jsonschema==0.33.0`, Draft 2020-12, no network)

| Input | `mmf` | `vitals` |
|---|---|---|
| Both schemas against the 2020-12 metaschema | PASS | PASS |
| `bp.json` (791 bytes, sha256 `bcaa4e51f0080574...`) | PASS | PASS |
| `spo2.json` (672 bytes, sha256 `e64156e5572c13b6...`) | PASS | PASS |
| `spo2.json` plus `OEM.SOCKET` `"kernelhub1.local:8090"` | PASS | PASS |
| Legacy `ac.json` unchanged | FAIL (`CONTROL` unexpected) | FAIL |
| `ac.json` with `CONTROL` renamed `ACTUATOR` | PASS | FAIL (not a vitals device) |

Mutations, each rejected by the schema named: `BINDINGS` section (both); `CONTROL` (both); named `RANGE` (both); `CODES` in a sensor (both); `OPERATION` `x` (both); `STEP` 0 (both); `SW VERSION` with a space (both); `STRUCT` without suffix (both); `SOCKET` without port (both); `RANGE` plus `UNIT` together (both); `RANGE` without `UNIT_VAL` (vitals); no `Measure` (vitals); `Measure` options changed (vitals); `Status` moved to `ACTUATOR` (vitals); `mmHg` not UCUM (vitals); `OPERATION` `*` (vitals). 16 of 16 rejected. A first run that resolved `$ref` through a URL `$id` errored on DNS (nothing sent to a real host); `$id` was removed and refs resolved from disk.

### 11.4 BLE paging (R4-2): see `11b` section 8

Each manifest is over 504 bytes of page data, so both take 2 pages (791 and 672 bytes).

### 11.5 Latency opt-in (R4-3)

Superseded in part: section 4.4's `"protocol_version": 2` field. The flag is `simulate_latency` (boolean, default `false`) in the Invoke SET `Measure` `start` arguments. On BLE it is bit 0 of a flags byte in opcode `0x04` (`11b` section 8). Default stays instant, so SaMD's single GET after start (`D/PiGatewayVitalsSource.kt:77-88`) is unaffected, and the v1 calls are untouched.

### 11.6 Message structure (R4-F), logical form

Paper: header holds protocol name and version, and encryption type (Fig 3, p.8); request payload holds protocol name, operation name, arguments (Fig 4); response payload holds outputs (Fig 5); Invoke GET on a sensor or actuator, SET on an actuator (Fig 8, p.12) (DOCS-ONLY). JSON spellings are proposed and pend OQ-49.

```text
request  = {"header": {"protocol": "OneIoT", "version": "2", "encryption_type": "none"},
            "payload": {"phase": "<DISCOVERY|REGISTER|PROBE|INVOKE|CONFIGURE>",
                        "operation": "<GET|SET|empty>", "arguments": {...}}}
response = {"header": {...same...}, "payload": {"outputs": {...}}}
```

| Message | `arguments` | `outputs` |
|---|---|---|
| PROBE | empty (paper p.8) | HTTP: `{"manifest": "<base64 of the file bytes>"}`. BLE: pages, `11b` section 8 |
| INVOKE SET `Measure` start | `{"name": "Measure", "value": "start", "session_id": "<SaMD request id>", "simulate_latency": false}` | `{"values": {"Status": "measuring"}}`, or `done` when instant |
| INVOKE SET `Measure` stop | `{"name": "Measure", "value": "stop", "session_id": "..."}` | `{"values": {"Status": "stopped"}}` |
| INVOKE GET `Status` | `{"names": ["Status"]}` | `{"values": {"Status": "done"}}` |
| INVOKE GET reading | `{"names": ["Systolic", "Diastolic", "Pulse"]}` | Reading Outputs, 11.7 |
| CONFIGURE (kernelhub1) | `{"tick_rate_s": 5}` | `{"tick_rate_s": 5}` |
| Error, any phase | | `{"error": "BUSY" or "UNKNOWN_NAME" or "INVALID_ARGUMENT" or "NOT_READY" or "MTU_TOO_SMALL"}` |

HTTP mapping (kernelhub1): one `POST /oneiot/v2` taking and returning the JSON above; v1 routes stay byte for byte. BLE mapping (kernelhub2): `11b` section 8.

### 11.7 Reading Outputs (payload fields, not keywords)

| Field | Meaning | Name source |
|---|---|---|
| `values` | Sensor name to number; names exactly as in the MMF | MMF `SENSOR` names |
| `measurement_id` | Hub UUID, idempotency key | `E/models.py:60` (block 5.1) |
| `quality_status` | `OK`, `WARNING`, `ERROR`, `UNAVAILABLE`; `values` empty unless `OK` | NormalizedMeasurement (`D/PiGatewayApi.kt:69`) |
| `measured_at` | Device time, nullable, advisory | NormalizedMeasurement (`:73`) |
| `hub_id` | Hub name | NormalizedMeasurement (`:63`) |
| `provenance.session_id` | Echo of the SaMD request id | NormalizedMeasurement nesting (`D/PiGatewayApi.kt:54-57`) |
| `synthetic` | Always `true` for the emulator | NormalizedMeasurement (`:76`) |

Units are not repeated: `UNIT_VAL` in the MMF is the single source. `received_at` is set by the receiving app, never by the hub (block 5.1 rule). The bridge result OneIoT hands to SaMD adds per value the unit from the MMF, the manifest sha256, and OneIoT's package and version (R4-1).
