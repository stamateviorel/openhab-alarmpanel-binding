# openHAB Alarm Panel Binding

## ⚠️ Disclaimer & Security Warning

**Please read this carefully before installing or relying on this binding.**

This project was built for the OpenHAB community to easily integrate alarm logic into their smart homes. However, it is a DIY project and comes with significant limitations:

* **Not a Pro System:** This binding does not try to replicate a real, certified alarm system and will never replace a professional one. That said, a DIY alarm is better than no alarm, and pairing it with home cameras allows you to verify triggers yourself.
* **Not a Secure Implementation:** Use this at your own risk; it comes with no responsibility or warranty. **Do not** take this as a fully secure implementation. Currently, PINs are stored in plaintext within the script, and OpenHAB rules can be bypassed or disabled. 
* **Hardware Requirements for Reliability:** To make this effective, make sure you connect it to a loud, stable, and reliable alarm/siren system to scare off intruders. You also **highly recommend** using a UPS (Uninterruptible Power Supply) for your OpenHAB server, network switch, internet modem, and all critical sensors. 
* **Know Your Needs:** If you are extremely concerned about home security, do not rely solely on this binding. You should pay for a professionally installed and monitored solution so that humans can make informed decisions when an alarm is triggered or the power/internet connection is cut.

A generic, item-agnostic alarm panel for openHAB.
Any `Contact` / `Switch` item can be wired as a zone input; any audio sink,
KNX switch or relay can be wired as an output. State machine, PIN store,
audit log and notifications live entirely inside the binding — no JSR223 /
rule DSL required for the safety-critical paths.

## Table of Contents

1. [Supported Things](#supported-things)
2. [State Machine](#state-machine)
3. [Bridge Configuration](#bridge-configuration)
4. [Zone Things](#zone-things)
5. [Output Things](#output-things)
6. [PIN Things](#pin-things)
7. [Add-on (binding-level) Settings](#add-on-binding-level-settings)
8. [Channels](#channels)
9. [Recommended Item Layout](#recommended-item-layout)
10. [Karaf Console Commands](#karaf-console-commands)
11. [REST API](#rest-api)
12. [Audit Log](#audit-log)
13. [Rule Examples](#rule-examples)
14. [Sitemap Example](#sitemap-example)
15. [Troubleshooting](#troubleshooting)

---

## Supported Things

| Thing type | Bridge? | Role |
|---|---|---|
| `alarmpanel:panel` | **bridge** | Owns the state machine, PIN store, audit log, and emits the audit trigger channel |
| `alarmpanel:zone` | child | A group of input items reporting violations to the bridge |
| `alarmpanel:output` | child | A driver (siren MP3 / KNX switch / item-write) invoked on `TRIGGERED` |
| `alarmpanel:pin` | child | One hashed PIN credential — add one Thing per user |

The bridge can host any number of zones, outputs and PINs.

---

## State Machine

```
                    ┌───────────────┐
                    │   DISARMED    │ ◄────────────────────┐
                    └───────┬───────┘                       │
              ARM_HOME │    │ ARM_AWAY                      │
                    ┌──▼────▼──┐                            │
                    │EXIT_DELAY│                            │
                    └─────┬────┘                            │ DISARM (PIN/Karaf/badge/…)
                          ▼                                 │
              ┌────────────────────────┐                    │
              │ ARMED_HOME / ARMED_AWAY│                    │
              └───────────┬────────────┘                    │
                          │ zone violation                  │
                   ┌──────▼──────┐                          │
                   │ ENTRY_DELAY │                          │
                   └──────┬──────┘                          │
                          │ countdown expires               │
                   ┌──────▼──────┐                          │
                   │  TRIGGERED  │ ──► outputs fire (siren) │
                   └──────┬──────┘                          │
                          │ DISARM                          │
                          └─────────────────────────────────┘
```

Transitions are guarded; illegal ones emit a `CONFIG_ERROR` audit row
instead of changing state.

---

## Bridge Configuration

Defined as a `.things` file or added via MainUI.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `entryDelaySeconds` | integer (0-300) | 10 | Grace period from violation → TRIGGERED |
| `exitDelaySeconds` | integer (0-300) | 20 | Grace period from ARM command → ARMED |
| `triggerDurationSeconds` | integer (30-3600) | 600 | Auto-stop the siren after N seconds |
| `pinMaxAttempts` | integer (1-20) | 3 | Wrong PINs before lockout |
| `pinLockoutMinutes` | integer (1-1440) | 15 | Lockout duration |
| `autoArmIdleMinutes` | integer (0-1440) | 0 (off) | Auto-arm ARMED_AWAY after N minutes of zone-group quiet |
| `autoArmGraceMinutes` | integer (0-60) | 5 | Skip auto-arm for N minutes after a manual disarm |
| `reminderIntervalSeconds` | integer (60-7200) | 1260 | Audit reminder cadence while TRIGGERED |
| `auditLogPath` | text | `/var/log/openhab/alarm-audit.log` | JSON-lines audit file |
| `persistStateAcrossRestart` | boolean | true | Restore state + countdown on openHAB restart |

### `.things` example

```dsl
Bridge alarmpanel:panel:main "Alarm Panel" [
    entryDelaySeconds=10,
    exitDelaySeconds=20,
    triggerDurationSeconds=600,
    pinMaxAttempts=3,
    pinLockoutMinutes=15,
    autoArmIdleMinutes=30,
    autoArmGraceMinutes=5,
    reminderIntervalSeconds=1260,
    auditLogPath="/var/log/openhab/alarm-audit.log",
    persistStateAcrossRestart=true
] {
    Thing zone main_hall_contacts "Main Hall Contacts" [
        inputs="Door_back_main_area_sensor,Gate_main_area_sensor",
        behavior="entry-delay",
        armModes="HOME,AWAY",
        sustainedSeconds=0
    ]
    Thing zone office_motion "Office Motion" [
        inputs="Movement_office_detector",
        behavior="entry-delay",
        armModes="AWAY",
        sustainedSeconds=5,
        timeWindow="04:00-08:00"
    ]
    Thing output siren "Siren" [
        driver="mp3",
        mp3Path="/etc/openhab/sounds/alarm.mp3",
        mp3Sink="enhancedjavasound",
        mp3LoopSeconds=600
    ]
}
```

---

## Zone Things

`alarmpanel:zone` — one per logical zone. Inputs may be `Contact` or `Switch`.

| Parameter | Type | Description |
|---|---|---|
| `inputs` | text | Comma-separated item names (e.g. `Door_A,Window_B`) |
| `behavior` | text | `entry-delay` \| `instant` \| `informational` (audit-only, never triggers) |
| `armModes` | text | Comma-separated subset of `HOME,AWAY` — when the zone is "live" |
| `inputTrigger` | text | `OPEN` (contacts) \| `ON` (switches) \| `AUTO` (binding picks per item type) |
| `sustainedSeconds` | integer | Require the input to stay violated for N seconds before counting (debounce; default 0) |
| `timeWindow` | text | `HH:MM-HH:MM` local-time window in which violations count (e.g. nighttime motion) |

Exposes `state`, `enable`, `lastViolation`, `lastViolationInput` channels
(see [Channels](#channels)).

---

## Output Things

`alarmpanel:output` — one per actuator. The driver picks how to act on
`TRIGGERED`.

| Driver | What it does | Required params |
|---|---|---|
| `mp3` | Play an MP3 to an openHAB audio sink, looped | `mp3Path`, `mp3Sink`, `mp3LoopSeconds` |
| `item` | Command an arbitrary item (e.g. a relay) | `targetItem`, `onCommand`, `offCommand` |
| `strobe` | Pulse an item ON/OFF for the duration | `targetItem`, `pulseMillis` |

All outputs expose `active`, `test`, `lastError` channels.

---

## PIN Things

`alarmpanel:pin` — one Thing per credential. Add via MainUI → Settings →
Things → AlarmPanel main → `+` → **Alarm Panel PIN**, or via Karaf (below).

| Config | Type | Description |
|---|---|---|
| `label` | text | Display name. Must not contain 4+ consecutive digits (PIN-leak guard) |
| `disabled` | boolean | Temporarily disable without deleting |
| `pinCode` | text (password) | Type a 4-8 digit code; the handler hashes it (PBKDF2-SHA256, 600 000 iterations) into the Thing's `hash` property and **clears this field** |

Properties (read-only, set by the handler):

- `hash` — PBKDF2 hash; never the plain code
- `created` — ISO timestamp
- `lastUsed` — ISO timestamp (set on every successful verify)
- `pinSet` — `yes` once a PIN has been hashed

To rotate a PIN: open the PIN Thing → type a new value in `pinCode` → Save.
The handler re-hashes and clears the field.

PIN Things are persisted via `ManagedThingProvider` so they survive
`bundle:update` and openHAB restarts.

---

## Add-on (binding-level) Settings

Visible under **MainUI → Settings → Other → Bindings → Alarm Panel** (or
via Karaf `config:edit binding.alarmpanel`).

| Parameter | Type | Default | Description |
|---|---|---|---|
| `notificationsEnabled` | boolean | true | Master kill switch for push / TTS / e-mail emitted by rule files. Turn off during testing |
| `unifiDisarmDenylist` | text | (empty) | Comma-separated UniFi user names that may not disarm via badge. Triggers a 10-second block on the KNX gate reader path too, to cover the same physical-badge timing window |

JSR223 rules read these via `shared_utils.alarmNotificationsEnabled()` and
`shared_utils.alarmUnifiDenylist()`.

---

## Channels

### Bridge `alarmpanel:panel`

| Channel | Type | Direction | Purpose |
|---|---|---|---|
| `state` | String | read | Current state name |
| `command` | String | write | Send `ARMED_HOME`, `ARMED_AWAY`, `DISARM[:source]`, `TEST`, `SILENCE` |
| `pinEntry` | String | write | Send a typed PIN; bridge verifies and either disarms or emits `PIN_WRONG` |
| `countdown` | Number | read | Live seconds remaining in EXIT_DELAY / ENTRY_DELAY / TRIGGERED recovery |
| `lastDisarmSource` | String | read | Human-readable source of the last disarm |
| `lastDisarmTime` | DateTime | read | When the last disarm happened |
| `armedAt` | DateTime | read | When the bridge last reached an ARMED_* state |
| `triggeredAt` | DateTime | read | When the bridge last TRIGGERED |
| `failedAttempts` | Number | read | Wrong-PIN count since last successful disarm or reset |
| `audit` | trigger | event | Fires on every state change with a JSON payload; see [Audit Log](#audit-log) |

### Zone `alarmpanel:zone`

| Channel | Type | Purpose |
|---|---|---|
| `state` | String | `IDLE`, `PRE_ALARM`, `VIOLATION`, `SUPPRESSED`, `DISABLED` |
| `enable` | Switch | Per-zone master toggle; commands accepted |
| `lastViolation` | DateTime | Timestamp of the most recent violation |
| `lastViolationInput` | String | Item name that caused the last violation |

### Output `alarmpanel:output`

| Channel | Type | Purpose |
|---|---|---|
| `active` | Switch | ON while the driver is asserting (siren playing, etc.) |
| `test` | Switch | Send ON to test for `triggerDurationSeconds`; binding auto-flips OFF |
| `lastError` | String | Driver error message if the last invocation failed |

---

## Recommended Item Layout

Single `.items` file consolidating all alarm channels:

```items
// /etc/openhab/items/alarmpanel.items

// Core bridge
String   Alarm_State              "Alarmstatus [%s]"               { channel="alarmpanel:panel:main:state" }
String   Alarm_Command            "Alarmcommando [%s]"             { channel="alarmpanel:panel:main:command" }
String   Alarm_Code               "Alarmcode [%s]"                 { channel="alarmpanel:panel:main:pinEntry" }
Number   Alarm_Countdown          "Alarm aftelling [%d]"           { channel="alarmpanel:panel:main:countdown" }
String   Alarm_LastDisarmSource   "Laatste uitschakelbron [%s]"    { channel="alarmpanel:panel:main:lastDisarmSource" }
DateTime Alarm_LastDisarmTime     "Laatste uitschakeling [%1$tA %1$tH:%1$tM]" { channel="alarmpanel:panel:main:lastDisarmTime" }
DateTime Alarm_ArmedAt            "Ingeschakeld sinds [%1$tA %1$tH:%1$tM]"    { channel="alarmpanel:panel:main:armedAt" }
DateTime Alarm_TriggeredAt        "Laatste alarm [%1$tA %1$tH:%1$tM]"          { channel="alarmpanel:panel:main:triggeredAt" }
Number   Alarm_FailedAttempts     "Verkeerde pincodes [%d]"        { channel="alarmpanel:panel:main:failedAttempts" }

// Output
Switch   AlarmPanel_Siren_Active     "Sirene actief [%s]"      { channel="alarmpanel:output:main:siren:active" }
Switch   AlarmPanel_Siren_Test       "Sirene test [%s]"        { channel="alarmpanel:output:main:siren:test" }
String   AlarmPanel_Siren_LastError  "Sirene foutmelding [%s]" { channel="alarmpanel:output:main:siren:lastError" }

// Per-zone (one block per zone; groups make sitemap rendering easy)
Group g_AlarmZ_State         "Zone toestanden"
Group g_AlarmZ_Enable        "Zone aan/uit"
Group g_AlarmZ_LastViolation "Zone laatste activatie"

String   AlarmZ_OfficeMotion_State          "Kantoor beweging [%s]"   (g_AlarmZ_State)         { channel="alarmpanel:zone:main:office_motion:state" }
Switch   AlarmZ_OfficeMotion_Enable         "Kantoor beweging actief" (g_AlarmZ_Enable)        { channel="alarmpanel:zone:main:office_motion:enable" }
DateTime AlarmZ_OfficeMotion_LastViolation  "Kantoor beweging laatste [%1$tH:%1$tM]" (g_AlarmZ_LastViolation) { channel="alarmpanel:zone:main:office_motion:lastViolation" }
// … one set per zone
```

---

## Karaf Console Commands

```sh
openhab:alarmpanel state                                  # show bridge state + counts
openhab:alarmpanel pin list                               # list PINs (labels + ids)
openhab:alarmpanel pin add <label> [<pin>]                # add (auto-generates 6-digit PIN if omitted)
openhab:alarmpanel pin remove <id|label>
openhab:alarmpanel pin rename <id|label> <newLabel>
openhab:alarmpanel pin enable  <id|label>
openhab:alarmpanel pin disable <id|label>
openhab:alarmpanel pin reset-attempts                     # clear lockout counter
openhab:alarmpanel events [N]                             # tail last N audit rows (default 10)
openhab:alarmpanel disarm-emergency                       # force DISARM, writes audit row
```

---

## REST API

Stable endpoints, all under `/rest/alarmpanel/`:

```
GET    /rest/alarmpanel/state          → {state, pins}
GET    /rest/alarmpanel/pin            → [{id, label, created, lastUsed, disabled}]
POST   /rest/alarmpanel/pin            → body {label, pin}; creates a PIN Thing
PATCH  /rest/alarmpanel/pin/{id}       → body {label}; renames
DELETE /rest/alarmpanel/pin/{id}       → 204
```

All routes use standard openHAB Bearer-token auth.

---

## Audit Log

JSON-lines file at the configured `auditLogPath`. One row per event.

| field | example | notes |
|---|---|---|
| `t` | `2026-05-24T08:21:23.825975950Z` | UTC ISO-8601 |
| `type` | `STATE` | see below |
| (extra fields) | varies | per event type |

### Event types

| `type` | Extra fields |
|---|---|
| `STATE` | `from`, `to`, optional `source` |
| `ARM` | `mode` (`HOME`/`AWAY`), `source`, `exitDelay` |
| `DISARM` | `from`, `source`, optional `detail` |
| `TRIGGER` | `from`, `source` |
| `ZONE_VIOLATION` | `zone`, `input`, `state`, `acted` |
| `ZONE_SUPPRESSED` | `zone`, `input` |
| `PIN_OK` | `label` |
| `PIN_WRONG` | `attempts`, `locked` |
| `PIN_LOCKED` | `entered_len` |
| `EMERGENCY_DISARM` | `source` |
| `CONFIG_ERROR` | `attempted`, `from`, `reason` |
| `RESTORE` | `state` |
| `OUTPUT_ERROR` | `output`, `error` |

The audit channel `alarmpanel:panel:<id>:audit` fires each row as a trigger
event so JSR223 rules can subscribe (`GenericEventTrigger` on topic
`openhab/channels/alarmpanel:panel:<id>:audit/triggered`).

---

## Rule Examples

### Arm AWAY at midnight, weekdays only

```js
const { items, rules, triggers } = require('openhab');
rules.JSRule({
  name: 'AutoArm midnight weekdays',
  triggers: [triggers.GenericCronTrigger('0 0 0 ? * MON-FRI')],
  execute: () => items.getItem('Alarm_Command').sendCommand('ARMED_AWAY')
});
```

### Push notification on every state change

```js
const { items, rules, triggers } = require('openhab');
const { sendBroadcastNotification, alarmNotificationsEnabled } = require('shared_utils');
rules.JSRule({
  name: 'Alarm state push',
  triggers: [triggers.ItemStateChangeTrigger('Alarm_State')],
  execute: (e) => {
    if (!alarmNotificationsEnabled()) return;
    sendBroadcastNotification(
      `Alarm: ${e.oldState} → ${e.newState}`,
      'f7:shield_lefthalf_fill', 'Alarm', 'Status'
    );
  }
});
```

### Subscribe to the audit channel (rich event data)

```js
const { rules, triggers } = require('openhab');
rules.JSRule({
  name: 'Audit listener',
  triggers: [triggers.GenericEventTrigger(
      'openhab/channels/alarmpanel:panel:main:audit/triggered', '', 'ChannelTriggeredEvent')],
  execute: (ev) => {
    let raw = '';
    if (ev.payload != null) {
      let p = ev.payload;
      if (typeof p === 'string') { try { p = JSON.parse(p); } catch (e) {} }
      if (p && typeof p.event === 'string') raw = p.event;
    }
    if (!raw) return;
    const payload = JSON.parse(raw);
    if (payload.type === 'PIN_WRONG' && payload.locked === 'true') {
      console.warn(`Lockout! ${payload.attempts} wrong attempts`);
    }
  }
});
```


## Sitemap Example

```sitemap
sitemap alarm label="Alarm" {
    Frame label="Status" {
        Text item=Alarm_State icon="shield"
        Text item=Alarm_Countdown label="Aftelling [%d s]" visibility=[Alarm_Countdown>0]
        Text item=Alarm_LastDisarmSource label="Laatst uit door [%s]"
    }
    Frame label="Bediening" {
        Switch item=Alarm_Command mappings=[ARMED_HOME="Aanwezig", ARMED_AWAY="Weg", DISARM="Uit"]
        Setpoint item=Alarm_Code label="Pincode [%s]" minValue=0 maxValue=99999999 step=1
    }
    Frame label="Zones" {
        Group item=g_AlarmZ_State label="Toestanden"
        Group item=g_AlarmZ_Enable label="Aan/uit"
    }
    Frame label="Sirene" {
        Switch item=AlarmPanel_Siren_Active label="Actief"
        Switch item=AlarmPanel_Siren_Test label="Test"
    }
}
```

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `state=UNKNOWN` after `bundle:update` | Bridge re-initialising, hasn't restored state yet | Wait ~10 s; if stuck, `openhab:alarmpanel disarm-emergency` |
| PIN Things disappeared after `bundle:update` (pre-fix bug) | Older `PinStore` used `ThingRegistry.add()` instead of `ManagedThingProvider.add()` | Fixed in 5.1.3-SNAPSHOT+; if you hit it on an older build, restore via Karaf `pin add` |
| Wrong-PIN lockout reached | `pinMaxAttempts` failed attempts in a row | Karaf `openhab:alarmpanel pin reset-attempts` (writes an `EMERGENCY_DISARM` audit row) |
| Notifications fire when they shouldn't | `notificationsEnabled` is `true` | Settings → Other → Bindings → Alarm Panel → toggle off, or `config:edit binding.alarmpanel; property-set notificationsEnabled false; update` |
| Audit log file not updating | `auditLogPath` not writable by the openHAB user | `chown openhab:openhab` the parent dir; check `openhab.log` for `audit log write failed` warnings |
| Auto-arm not firing | `autoArmIdleMinutes=0` (disabled) | Set to 30 (or whatever) in the bridge Thing config |
| KNX gate reader disarms despite UniFi denylist | UniFi badge event fires the KNX-bus side too, 100-200 ms after | The 2-step block (`cache.shared.alarmpanel_knx_block_until`) in `alarmpanel_input_dispatch.js` covers this — UniFi rejection sets a 10 s flag the KNX rule honors |
| Lots of `Context is already closed` errors after a JSR223 reload | Lingering async callbacks from the unloaded JS context | Self-clears once the new context finishes initialising; unchanged behaviour |

### Where state lives

- **PIN hashes** — `org.openhab.core.thing.Thing.json` (JSONDB) under each `alarmpanel:pin:*` UID's properties
- **State + countdown survival across restarts** — bridge Thing properties (`lastState`, `countdownEndsAtIsoUtc`, `armedAtIsoUtc`)
- **Audit log** — file at `auditLogPath` (default `/var/log/openhab/alarm-audit.log`)
- **Notifications + denylist** — ConfigurationAdmin under PID `binding.alarmpanel` → file `/var/lib/openhab/etc/binding.alarmpanel.cfg`

---

## License

EPL-2.0. Contains no third-party native code; pure JDK + openHAB core APIs.
