# Contributing

Thanks for considering a contribution. This binding is a fork-friendly,
single-file-per-concern codebase — please keep it that way.

## Build

```sh
JAVA_HOME=/path/to/jdk-21 mvn package -DskipTests -Dspotless.check.skip=true
cp target/org.openhab.binding.alarmpanel-*.jar $OPENHAB_HOME/addons/
```

Hot-reload via Karaf: `bundle:update <id>` (find with `bundle:list | grep alarmpanel`).

## Project layout

```
src/main/java/org/openhab/binding/alarmpanel/internal/
├── AlarmPanelBindingConstants.java        # ThingTypeUIDs, channel names, property keys
├── AlarmPanelBindingConfig.java           # binding-level OSGi @ConfigurableService
├── AlarmPanelHandlerFactory.java          # creates handlers for the 4 Thing types
├── audit/                                 # AuditEvent, AuditEventType, AuditLogger
├── console/AlarmPanelConsoleCommandExtension.java   # Karaf openhab:alarmpanel commands
├── handler/
│   ├── AlarmPanelBridgeHandler.java       # state machine + audit + PIN store owner
│   ├── ZoneThingHandler.java
│   ├── OutputThingHandler.java
│   └── PinThingHandler.java               # one Thing per PIN credential
├── output/                                # Mp3Driver, ItemDriver, StrobeDriver
├── pin/                                   # PinStore + PinRecord + Pbkdf2PinHasher + RateLimiter
├── rest/AlarmPanelRestResource.java       # /rest/alarmpanel/* JAX-RS endpoints
└── state/                                 # StateMachine, PanelState, ArmMode, Transition,
                                           # ZoneBehavior

src/main/resources/OH-INF/
├── addon/addon.xml                        # binding metadata (type, name, service-id)
├── binding/binding.xml                    # binding identity + config-description-ref
├── config/                                # per-Thing-type config-description XML
│   ├── binding.xml                        # binding-level (Add-on Settings)
│   ├── panel.xml, zone.xml, output.xml, pin.xml
└── thing/                                 # Thing-type definitions + channel-types
```

## Code style

- All files start with the standard openHAB EPL-2.0 header (`SPDX-License-Identifier: EPL-2.0`).
- `@NonNullByDefault` at the package or class level; `@Nullable` annotations on the rare exception.
- Avoid `null` returns where `Optional` would be clearer, but match surrounding style.
- Loggers: `private static final Logger LOGGER = LoggerFactory.getLogger(...)`.
- Run `mvn spotless:apply` before committing (skipped in the snippet above for speed
  during local hot-reload iteration).

## Tests

`src/test/` is currently empty. Contributions especially welcome for:

- `Pbkdf2PinHasherTest` — round-trip hash + verify, wrong PIN, malformed hash, parameter parsing
- `StateMachineTest` — all legal transitions, illegal transitions emit CONFIG_ERROR
- `ZoneBehaviorTest` — sustained-violation debounce, timeWindow gating, arm-mode skipping
- `RateLimiterTest` — N attempts then lockout, lockout expiry

## Pull requests

- Use a short, imperative title (`Add strobe output driver`, not `Strobe`).
- One commit per concern; squash if you accumulate fix-ups.
- DCO sign-off required: `git commit -s …`.
- Briefly explain the WHY in the body — the diff already shows the WHAT.

## Issues

Please include:

- openHAB version (`bundle:list | head -1` in Karaf)
- This binding's version (in the JAR name)
- Bridge `.things` definition (sanitized of any passwords)
- Relevant audit-log rows: `openhab:alarmpanel events 50`
- The `openhab.log` excerpt around the failure (`grep alarmpanel /var/log/openhab/openhab.log | tail -200`)
