/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.alarmpanel.internal.handler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.alarmpanel.internal.AlarmPanelBindingConstants;
import org.openhab.binding.alarmpanel.internal.audit.AuditEvent;
import org.openhab.binding.alarmpanel.internal.audit.AuditEventType;
import org.openhab.binding.alarmpanel.internal.audit.AuditLogger;
import org.openhab.binding.alarmpanel.internal.pin.PinRecord;
import org.openhab.binding.alarmpanel.internal.pin.PinStore;
import org.openhab.binding.alarmpanel.internal.pin.Pbkdf2PinHasher;
import org.openhab.binding.alarmpanel.internal.pin.RateLimiter;
import org.openhab.binding.alarmpanel.internal.state.ArmMode;
import org.openhab.binding.alarmpanel.internal.state.PanelState;
import org.openhab.binding.alarmpanel.internal.state.StateMachine;
import org.openhab.binding.alarmpanel.internal.state.Transition;
import org.openhab.binding.alarmpanel.internal.state.ZoneBehavior;
import org.openhab.core.audio.AudioManager;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.events.EventSubscriber;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The panel handler — owns the state machine, the PIN store, the audit log,
 * and the countdown timer. Zones and outputs are children that register on
 * their own {@link org.openhab.core.thing.binding.ThingHandler#initialize()}.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public class AlarmPanelBridgeHandler extends BaseBridgeHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmPanelBridgeHandler.class);

    // OSGi services (constructor-injected from the factory)
    private final EventPublisher eventPublisher;
    private final ItemRegistry itemRegistry;
    private final @Nullable AudioManager audioManager;

    // Owned state
    private final StateMachine machine = new StateMachine();
    private final AuditLogger audit = new AuditLogger();
    private @Nullable PinStore pinStore;
    private @Nullable RateLimiter rateLimiter;

    // Children
    private final Set<ZoneThingHandler> zones = ConcurrentHashMap.newKeySet();
    private final Set<OutputThingHandler> outputs = ConcurrentHashMap.newKeySet();

    // Config (resolved in initialize)
    private int entryDelaySec = 10;
    private int exitDelaySec = 20;
    private int triggerDurationSec = 600;
    private int pinMaxAttempts = 3;
    private int pinLockoutMinutes = 15;
    private int autoArmIdleMinutes;
    private int autoArmGraceMinutes = 5;
    private int reminderIntervalSec = 1260;
    private boolean persistAcrossRestart = true;

    // Active futures
    private @Nullable ScheduledFuture<?> countdownJob;
    private @Nullable ScheduledFuture<?> triggerSafetyJob;
    private @Nullable ScheduledFuture<?> autoArmJob;
    private @Nullable ScheduledFuture<?> reminderJob;

    // Track which mode the user requested so EXIT_DELAY knows where to go.
    private @Nullable ArmMode pendingArmMode;

    // Event subscription (item state changes) — registered on initialize, unregistered on dispose.
    private @Nullable ServiceRegistration<EventSubscriber> eventReg;

    private final ThingRegistry thingRegistry;
    private final ManagedThingProvider managedThingProvider;

    public AlarmPanelBridgeHandler(Bridge bridge, EventPublisher eventPublisher, ItemRegistry itemRegistry,
            ThingRegistry thingRegistry, ManagedThingProvider managedThingProvider,
            @Nullable AudioManager audioManager) {
        super(bridge);
        this.eventPublisher = eventPublisher;
        this.itemRegistry = itemRegistry;
        this.thingRegistry = thingRegistry;
        this.managedThingProvider = managedThingProvider;
        this.audioManager = audioManager;
    }

    public EventPublisher getEventPublisher() {
        return eventPublisher;
    }

    public ItemRegistry getItemRegistry() {
        return itemRegistry;
    }

    public @Nullable AudioManager getAudioManager() {
        return audioManager;
    }

    public AuditLogger getAuditLogger() {
        return audit;
    }

    public StateMachine getStateMachine() {
        return machine;
    }

    public @Nullable PinStore getPinStore() {
        return pinStore;
    }

    public PanelState getCurrentState() {
        return machine.getState();
    }

    @Override
    public void initialize() {
        loadConfig();

        // PIN store — now backed by child PIN Things; pins.json (if any) is
        // migrated to child Things on first init after upgrade.
        PinStore ps = new PinStore(getThing(), thingRegistry, managedThingProvider, new Pbkdf2PinHasher());
        pinStore = ps;
        migrateLegacyPinFileIfPresent(ps);
        rateLimiter = new RateLimiter(pinMaxAttempts, Duration.ofMinutes(pinLockoutMinutes));

        // Audit log
        Object pathCfg = getConfig().get("auditLogPath");
        if (pathCfg instanceof String && !((String) pathCfg).isBlank()) {
            audit.setFile(Paths.get((String) pathCfg));
        } else {
            audit.setFile(Paths.get("/var/log/openhab/alarm-audit.log"));
        }
        audit.setListener(this::publishAuditEvent);

        // Restore state from properties
        if (persistAcrossRestart) {
            restoreFromProperties();
        }

        updateStatus(ThingStatus.ONLINE);
        publishStateToChannels();
        rescheduleAutoArm();

        // Register the item-state-change subscriber so zones see input updates.
        try {
            BundleContext bc = FrameworkUtil.getBundle(getClass()).getBundleContext();
            if (bc != null) {
                eventReg = bc.registerService(EventSubscriber.class, new AlarmPanelEventSubscriber(this),
                        new Hashtable<>());
            } else {
                LOGGER.warn("alarmpanel: no BundleContext — zones will not receive input events");
            }
        } catch (RuntimeException e) {
            LOGGER.warn("alarmpanel: failed to register event subscriber: {}", e.getMessage());
        }

        if (ps.isEmpty()) {
            LOGGER.warn(
                    "alarmpanel: no PINs configured. Add one via Karaf: openhab:alarmpanel pin add <label>");
        }

        audit.log(new AuditEvent(AuditEventType.RESTORE).set("state", machine.getState().name()));
    }

    private void loadConfig() {
        entryDelaySec = intConfig("entryDelaySeconds", 10);
        exitDelaySec = intConfig("exitDelaySeconds", 20);
        triggerDurationSec = intConfig("triggerDurationSeconds", 600);
        pinMaxAttempts = intConfig("pinMaxAttempts", 3);
        pinLockoutMinutes = intConfig("pinLockoutMinutes", 15);
        autoArmIdleMinutes = intConfig("autoArmIdleMinutes", 0);
        autoArmGraceMinutes = intConfig("autoArmGraceMinutes", 5);
        reminderIntervalSec = intConfig("reminderIntervalSeconds", 1260);
        Object persist = getConfig().get("persistStateAcrossRestart");
        persistAcrossRestart = !(persist instanceof Boolean) || (Boolean) persist;
    }

    /**
     * One-shot migration from {@code ${OPENHAB_USERDATA}/alarmpanel/pins.json}
     * to child PIN Things. Runs whenever the file exists. After successful
     * migration the file is renamed to {@code pins.json.migrated-<ts>} so the
     * migration doesn't repeat. If migration fails the original file is left
     * in place and we log a warning — the previous user can still disarm via
     * other means.
     */
    private void migrateLegacyPinFileIfPresent(PinStore ps) {
        String userdata = System.getProperty("openhab.userdata", "/var/lib/openhab");
        Path pinFile = Paths.get(userdata, "alarmpanel", "pins.json");
        if (!Files.exists(pinFile)) {
            return;
        }
        try {
            String text = Files.readString(pinFile, StandardCharsets.UTF_8);
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(text);
            if (!root.isJsonArray()) {
                LOGGER.warn("Legacy pins.json malformed (not a JSON array) — skipping migration");
                return;
            }
            int migrated = 0;
            int skipped = 0;
            for (com.google.gson.JsonElement el : root.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                com.google.gson.JsonObject o = el.getAsJsonObject();
                String label = optJsonString(o, "label");
                String hash = optJsonString(o, "hash");
                if (label.isEmpty() || hash.isEmpty()) {
                    skipped++;
                    continue;
                }
                // Skip if a child Thing with this label already exists.
                boolean exists = false;
                for (org.openhab.core.thing.Thing t : getThing().getThings()) {
                    if (!AlarmPanelBindingConstants.THING_TYPE_PIN.equals(t.getThingTypeUID())) {
                        continue;
                    }
                    Object lblCfg = t.getConfiguration().get(AlarmPanelBindingConstants.CFG_PIN_LABEL);
                    if (lblCfg instanceof String && label.equals(lblCfg)) {
                        exists = true;
                        break;
                    }
                }
                if (exists) {
                    skipped++;
                    continue;
                }
                java.time.Instant created = parseLegacyInstant(optJsonString(o, "created"));
                java.time.Instant lastUsed = parseLegacyInstantOrNull(optJsonString(o, "lastUsed"));
                boolean disabled = o.has("disabled") && o.get("disabled").getAsBoolean();
                try {
                    ps.addExisting(label, hash, created, lastUsed, disabled);
                    migrated++;
                } catch (RuntimeException re) {
                    LOGGER.warn("Migration of PIN '{}' failed: {}", label, re.getMessage());
                    skipped++;
                }
            }
            if (migrated > 0) {
                Path archive = pinFile.resolveSibling("pins.json.migrated-"
                        + java.time.Instant.now().toString().replace(':', '-'));
                Files.move(pinFile, archive);
                LOGGER.info("Migrated {} PIN(s) from {} to child Things (skipped {}); legacy file moved to {}",
                        migrated, pinFile, skipped, archive);
            } else if (skipped == 0) {
                // No records, just remove the empty file.
                Files.deleteIfExists(pinFile);
            }
        } catch (java.io.IOException e) {
            LOGGER.warn("Failed to read legacy pins.json at {}: {}", pinFile, e.getMessage());
        }
    }

    private static String optJsonString(com.google.gson.JsonObject o, String key) {
        com.google.gson.JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? "" : el.getAsString();
    }

    private static java.time.Instant parseLegacyInstant(String s) {
        if (s == null || s.isEmpty()) {
            return java.time.Instant.EPOCH;
        }
        try {
            return java.time.Instant.parse(s);
        } catch (RuntimeException e) {
            return java.time.Instant.EPOCH;
        }
    }

    private static java.time.@Nullable Instant parseLegacyInstantOrNull(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return java.time.Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int intConfig(String key, int dflt) {
        Object v = getConfig().get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return dflt;
    }

    private void restoreFromProperties() {
        String stateStr = getThing().getProperties().get(AlarmPanelBindingConstants.PROP_LAST_STATE);
        String countdownEndStr = getThing().getProperties().get(AlarmPanelBindingConstants.PROP_COUNTDOWN_ENDS_AT);
        String armedAtStr = getThing().getProperties().get(AlarmPanelBindingConstants.PROP_ARMED_AT);
        String lastDisarmStr = getThing().getProperties().get(AlarmPanelBindingConstants.PROP_LAST_DISARM_AT);
        String lastDisarmSrc = getThing().getProperties().get(AlarmPanelBindingConstants.PROP_LAST_DISARM_SOURCE);

        PanelState restored = stateStr != null ? PanelState.parseOrDefault(stateStr, PanelState.UNKNOWN)
                : PanelState.UNKNOWN;
        Instant countdownEnd = parseInstant(countdownEndStr);
        Instant armedAt = parseInstant(armedAtStr);
        Instant lastDisarm = parseInstant(lastDisarmStr);

        machine.restoreFromPersistence(restored, armedAt, countdownEnd, lastDisarmSrc, lastDisarm);

        // If countdown still has time left, resume it
        if (countdownEnd != null && countdownEnd.isAfter(Instant.now())) {
            scheduleCountdownTick();
        } else if (restored == PanelState.EXIT_DELAY || restored == PanelState.ENTRY_DELAY) {
            // Countdown elapsed while we were down — fall through to the post-countdown
            // state immediately on restore.
            scheduler.execute(this::onCountdownExpiry);
        }
    }

    private @Nullable Instant parseInstant(@Nullable String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void dispose() {
        ServiceRegistration<EventSubscriber> reg = eventReg;
        if (reg != null) {
            try {
                reg.unregister();
            } catch (RuntimeException e) {
                LOGGER.debug("alarmpanel: eventReg.unregister threw: {}", e.getMessage());
            }
            eventReg = null;
        }
        cancel(countdownJob);
        cancel(triggerSafetyJob);
        cancel(autoArmJob);
        cancel(reminderJob);
        for (OutputThingHandler o : outputs) {
            o.shutdownDriver();
        }
        super.dispose();
    }

    /**
     * Called by the EventSubscriber to fan an item state change out to interested zones.
     */
    public void routeItemStateChange(String itemName, State newState) {
        for (ZoneThingHandler z : zones) {
            if (z.watchesItem(itemName)) {
                try {
                    z.onInputChange(itemName, newState);
                } catch (RuntimeException e) {
                    LOGGER.warn("alarmpanel: zone {} input handler threw: {}", z.getThingUid(), e.getMessage());
                }
            }
        }
    }

    private void cancel(@Nullable ScheduledFuture<?> f) {
        if (f != null) {
            f.cancel(false);
        }
    }

    public void registerZone(ZoneThingHandler z) {
        zones.add(z);
    }

    public void unregisterZone(ZoneThingHandler z) {
        zones.remove(z);
    }

    public void registerOutput(OutputThingHandler o) {
        outputs.add(o);
        if (machine.getState() == PanelState.TRIGGERED) {
            o.engageDriver();
        }
    }

    public void unregisterOutput(OutputThingHandler o) {
        outputs.remove(o);
    }

    /**
     * Called by a Zone handler when one of its inputs goes into the violating
     * state and (if configured) has stayed there long enough.
     */
    public void onZoneViolation(ZoneThingHandler zone, String inputItem) {
        PanelState s = machine.getState();
        if (!s.isArmed()) {
            // Not armed — informational only.
            audit.log(new AuditEvent(AuditEventType.ZONE_VIOLATION).set("zone", zone.getThingUid())
                    .set("input", inputItem).set("state", s.name()).set("acted", false));
            return;
        }
        if (s == PanelState.ENTRY_DELAY || s == PanelState.TRIGGERED) {
            audit.log(new AuditEvent(AuditEventType.ZONE_VIOLATION).set("zone", zone.getThingUid())
                    .set("input", inputItem).set("state", s.name()).set("acted", false));
            return;
        }

        ZoneBehavior behavior = zone.getBehavior();
        // Honor armModes
        ArmMode currentMode = (s == PanelState.ARMED_HOME) ? ArmMode.HOME : ArmMode.AWAY;
        if (!zone.armsIn(currentMode) && behavior != ZoneBehavior.TWENTYFOUR_HOUR) {
            return;
        }
        switch (behavior) {
            case INFORMATIONAL:
                audit.log(new AuditEvent(AuditEventType.ZONE_VIOLATION).set("zone", zone.getThingUid())
                        .set("input", inputItem).set("informational", true));
                return;
            case INSTANT:
            case TWENTYFOUR_HOUR:
                triggerAlarm("zone:" + zone.getThingUid() + "/" + inputItem);
                return;
            case ENTRY_DELAY:
                startEntryCountdown("zone:" + zone.getThingUid() + "/" + inputItem);
                return;
            default:
                return;
        }
    }

    public void requestArm(ArmMode mode, String source) {
        pendingArmMode = mode;
        scheduleExitCountdown(source, mode);
    }

    public void requestDisarm(String source, @Nullable String detail) {
        Transition t = machine.transitionTo(PanelState.DISARMED, source, detail);
        if (t == null) {
            audit.log(new AuditEvent(AuditEventType.CONFIG_ERROR).set("attempted", "DISARM")
                    .set("from", machine.getState().name()).set("reason", "illegal_transition"));
            return;
        }
        cancel(countdownJob);
        cancel(triggerSafetyJob);
        cancel(reminderJob);
        releaseAllOutputs();
        audit.log(new AuditEvent(AuditEventType.DISARM).set("from", t.from.name()).set("source", source)
                .set("detail", detail));
        afterTransition(t);
    }

    /**
     * Called when an external source (a legacy JS rule, REST client, etc.)
     * reports a zone-equivalent violation. Mirrors what
     * {@link #onZoneViolation} does for a `entry-delay` zone behavior:
     * starts an entry countdown when ARMED_*, ignored otherwise.
     */
    public void requestExternalViolation(String source) {
        PanelState s = machine.getState();
        if (s == PanelState.ARMED_HOME || s == PanelState.ARMED_AWAY) {
            startEntryCountdown(source);
            audit.log(new AuditEvent(AuditEventType.ZONE_VIOLATION).set("source", source).set("via", "external"));
            return;
        }
        // Not armed or already counting down/triggered — log only.
        audit.log(new AuditEvent(AuditEventType.ZONE_VIOLATION).set("source", source).set("via", "external")
                .set("state", s.name()).set("acted", false));
    }

    public void requestSilence(String source) {
        if (machine.getState() != PanelState.TRIGGERED) {
            return;
        }
        releaseAllOutputs();
        cancel(reminderJob);
        audit.log(new AuditEvent(AuditEventType.OUTPUT_RELEASED).set("source", source).set("reason", "SILENCE"));
    }

    public void emergencyDisarm(String source) {
        RateLimiter rl = rateLimiter;
        if (rl != null) {
            rl.reset();
        }
        audit.log(new AuditEvent(AuditEventType.EMERGENCY_DISARM).set("source", source));
        requestDisarm("emergency:" + source, "console");
    }

    /**
     * Called from the pinEntry channel. Verifies and routes.
     */
    public void onPinEntered(String enteredPin) {
        PinStore ps = pinStore;
        RateLimiter rl = rateLimiter;
        if (ps == null || rl == null) {
            return;
        }
        if (rl.isLocked()) {
            audit.log(new AuditEvent(AuditEventType.PIN_LOCKED).set("entered_len", String.valueOf(enteredPin.length())));
            return;
        }
        char[] chars = enteredPin.toCharArray();
        PinRecord rec = ps.verify(chars);
        if (rec != null) {
            rl.recordSuccess();
            audit.log(new AuditEvent(AuditEventType.PIN_OK).set("label", rec.label));
            // Disarm always — if not in an armed state this is a no-op transition.
            requestDisarm("pin:" + rec.label, null);
        } else {
            boolean locked = rl.recordFailure();
            audit.log(new AuditEvent(AuditEventType.PIN_WRONG).set("attempts", rl.getFailedCount())
                    .set("locked", locked));
        }
        // Wipe the StringType-derived PIN value from the channel — write the
        // empty string back so it doesn't persist as a state.
        updateState(new ChannelUID(getThing().getUID(), AlarmPanelBindingConstants.CH_PIN_ENTRY), UnDefType.NULL);
    }

    private void scheduleExitCountdown(String source, ArmMode mode) {
        if (machine.getState() != PanelState.DISARMED && machine.getState() != PanelState.UNKNOWN) {
            audit.log(new AuditEvent(AuditEventType.CONFIG_ERROR).set("attempted", "ARM:" + mode.name())
                    .set("from", machine.getState().name()).set("reason", "illegal_transition"));
            return;
        }
        Transition t = machine.transitionTo(PanelState.EXIT_DELAY, source, mode.name());
        if (t == null) {
            return;
        }
        Instant endsAt = Instant.now().plusSeconds(exitDelaySec);
        machine.setCountdownEndsAt(endsAt);
        audit.log(new AuditEvent(AuditEventType.ARM).set("mode", mode.name()).set("source", source)
                .set("exitDelay", exitDelaySec));
        afterTransition(t);
        scheduleCountdownTick();
    }

    private void startEntryCountdown(String source) {
        PanelState s = machine.getState();
        if (s != PanelState.ARMED_HOME && s != PanelState.ARMED_AWAY) {
            return;
        }
        Transition t = machine.transitionTo(PanelState.ENTRY_DELAY, source, null);
        if (t == null) {
            return;
        }
        Instant endsAt = Instant.now().plusSeconds(entryDelaySec);
        machine.setCountdownEndsAt(endsAt);
        afterTransition(t);
        scheduleCountdownTick();
    }

    private void scheduleCountdownTick() {
        cancel(countdownJob);
        countdownJob = scheduler.scheduleAtFixedRate(this::countdownTick, 1, 1, TimeUnit.SECONDS);
        // Publish initial countdown value immediately
        publishCountdownToChannel();
    }

    private void countdownTick() {
        try {
            Instant ends = machine.getCountdownEndsAt();
            if (ends == null) {
                cancel(countdownJob);
                countdownJob = null;
                publishCountdownToChannel();
                return;
            }
            long millisLeft = java.time.Duration.between(Instant.now(), ends).toMillis();
            if (millisLeft <= 0) {
                cancel(countdownJob);
                countdownJob = null;
                machine.setCountdownEndsAt(null);
                onCountdownExpiry();
                return;
            }
            publishCountdownToChannel();
        } catch (RuntimeException e) {
            LOGGER.warn("countdownTick error: {}", e.getMessage());
        }
    }

    private void onCountdownExpiry() {
        PanelState current = machine.getState();
        if (current == PanelState.EXIT_DELAY) {
            ArmMode mode = pendingArmMode;
            PanelState target = (mode == ArmMode.HOME) ? PanelState.ARMED_HOME : PanelState.ARMED_AWAY;
            Transition t = machine.transitionTo(target, "exit_countdown", null);
            if (t != null) {
                audit.log(new AuditEvent(AuditEventType.STATE).set("from", t.from.name()).set("to", t.to.name()));
                afterTransition(t);
            }
        } else if (current == PanelState.ENTRY_DELAY) {
            triggerAlarm("entry_countdown");
        }
    }

    private void triggerAlarm(String source) {
        Transition t = machine.transitionTo(PanelState.TRIGGERED, source, null);
        if (t == null) {
            return;
        }
        audit.log(new AuditEvent(AuditEventType.TRIGGER).set("from", t.from.name()).set("source", source));
        afterTransition(t);
        engageAllOutputs();
        scheduleTriggerSafety();
        scheduleReminder();
    }

    private void engageAllOutputs() {
        for (OutputThingHandler o : outputs) {
            try {
                o.engageDriver();
            } catch (RuntimeException e) {
                audit.log(new AuditEvent(AuditEventType.OUTPUT_ERROR).set("output", o.getThingUid())
                        .set("error", e.getMessage()));
            }
        }
    }

    private void releaseAllOutputs() {
        for (OutputThingHandler o : outputs) {
            try {
                o.releaseDriver();
            } catch (RuntimeException e) {
                audit.log(new AuditEvent(AuditEventType.OUTPUT_ERROR).set("output", o.getThingUid())
                        .set("error", e.getMessage()));
            }
        }
    }

    private void scheduleTriggerSafety() {
        cancel(triggerSafetyJob);
        triggerSafetyJob = scheduler.schedule(() -> {
            if (machine.getState() == PanelState.TRIGGERED) {
                LOGGER.info("alarmpanel: triggerDuration reached, auto-silencing");
                requestSilence("safety_cap");
            }
        }, triggerDurationSec, TimeUnit.SECONDS);
    }

    private void scheduleReminder() {
        cancel(reminderJob);
        if (reminderIntervalSec <= 0) {
            return;
        }
        reminderJob = scheduler.scheduleAtFixedRate(() -> {
            if (machine.getState() == PanelState.TRIGGERED) {
                audit.log(new AuditEvent(AuditEventType.TRIGGER).set("reminder", true));
            }
        }, reminderIntervalSec, reminderIntervalSec, TimeUnit.SECONDS);
    }

    private void rescheduleAutoArm() {
        cancel(autoArmJob);
        if (autoArmIdleMinutes <= 0) {
            return;
        }
        autoArmJob = scheduler.scheduleAtFixedRate(this::autoArmTick, 60, 60, TimeUnit.SECONDS);
    }

    private void autoArmTick() {
        try {
            PanelState s = machine.getState();
            if (s != PanelState.DISARMED) {
                return;
            }
            Instant disarmedAt = machine.getLastDisarmAt();
            if (disarmedAt != null) {
                long minsSinceDisarm = ChronoUnit.MINUTES.between(disarmedAt, Instant.now());
                if (minsSinceDisarm < autoArmGraceMinutes) {
                    return;
                }
            }
            boolean anyZoneViolated = false;
            for (ZoneThingHandler z : zones) {
                if (z.isCurrentlyViolating()) {
                    anyZoneViolated = true;
                    break;
                }
            }
            if (anyZoneViolated) {
                return;
            }
            // Use armedAt timestamp of last arm to determine idle period — if
            // we've been disarmed for autoArmIdleMinutes, arm AWAY.
            if (disarmedAt == null
                    || ChronoUnit.MINUTES.between(disarmedAt, Instant.now()) >= autoArmIdleMinutes) {
                requestArm(ArmMode.AWAY, "auto_arm");
            }
        } catch (RuntimeException e) {
            LOGGER.warn("autoArmTick error: {}", e.getMessage());
        }
    }

    private void afterTransition(Transition t) {
        if (persistAcrossRestart) {
            persistTransition();
        }
        publishStateToChannels();
        // Always re-publish countdown so it goes to 0 when leaving EXIT_DELAY /
        // ENTRY_DELAY (without this the channel sticks at the last visible value,
        // e.g. "4" if disarmed at countdown=4).
        publishCountdownToChannel();
        publishAuditEvent(new AuditEvent(AuditEventType.STATE).set("from", t.from.name()).set("to", t.to.name())
                .set("source", t.source));
    }

    private void persistTransition() {
        updateProperty(AlarmPanelBindingConstants.PROP_LAST_STATE, machine.getState().name());
        Instant cnd = machine.getCountdownEndsAt();
        updateProperty(AlarmPanelBindingConstants.PROP_COUNTDOWN_ENDS_AT, cnd != null ? cnd.toString() : null);
        Instant armedAt = machine.getArmedAt();
        updateProperty(AlarmPanelBindingConstants.PROP_ARMED_AT, armedAt != null ? armedAt.toString() : null);
        Instant disarmAt = machine.getLastDisarmAt();
        updateProperty(AlarmPanelBindingConstants.PROP_LAST_DISARM_AT, disarmAt != null ? disarmAt.toString() : null);
        updateProperty(AlarmPanelBindingConstants.PROP_LAST_DISARM_SOURCE, machine.getLastDisarmSource());
    }

    private void publishStateToChannels() {
        PanelState s = machine.getState();
        updateState(new ChannelUID(getThing().getUID(), AlarmPanelBindingConstants.CH_STATE), new StringType(s.name()));
        Instant armedAt = machine.getArmedAt();
        if (armedAt != null) {
            updateState(new ChannelUID(getThing().getUID(), AlarmPanelBindingConstants.CH_ARMED_AT),
                    new DateTimeType(ZonedDateTime.ofInstant(armedAt, ZoneId.systemDefault())));
        }
        Instant triggeredAt = machine.getTriggeredAt();
        if (triggeredAt != null) {
            updateState(new ChannelUID(getThing().getUID(), AlarmPanelBindingConstants.CH_TRIGGERED_AT),
                    new DateTimeType(ZonedDateTime.ofInstant(triggeredAt, ZoneId.systemDefault())));
        }
        Instant disarmAt = machine.getLastDisarmAt();
        if (disarmAt != null) {
            updateState(new ChannelUID(getThing().getUID(), AlarmPanelBindingConstants.CH_LAST_DISARM_TIME),
                    new DateTimeType(ZonedDateTime.ofInstant(disarmAt, ZoneId.systemDefault())));
        }
        String disarmSrc = machine.getLastDisarmSource();
        if (disarmSrc != null) {
            updateState(new ChannelUID(getThing().getUID(), AlarmPanelBindingConstants.CH_LAST_DISARM_SOURCE),
                    new StringType(disarmSrc));
        }
        RateLimiter rl = rateLimiter;
        if (rl != null) {
            updateState(new ChannelUID(getThing().getUID(), AlarmPanelBindingConstants.CH_FAILED_ATTEMPTS),
                    new DecimalType(rl.getFailedCount()));
        }
    }

    private void publishCountdownToChannel() {
        Instant ends = machine.getCountdownEndsAt();
        int secondsLeft;
        if (ends == null) {
            secondsLeft = 0;
        } else {
            // Round UP so the user sees the full configured value at the start
            // (e.g. 20 immediately when ARM is sent — not 19 because of a few ms
            // of processing delay) and sees "1" as the last visible value before
            // the state transition fires.
            long millisLeft = java.time.Duration.between(Instant.now(), ends).toMillis();
            long ceilSeconds = (millisLeft + 999) / 1000;
            secondsLeft = (int) Math.max(0, ceilSeconds);
        }
        updateState(new ChannelUID(getThing().getUID(), AlarmPanelBindingConstants.CH_COUNTDOWN),
                new DecimalType(secondsLeft));
    }

    private void publishAuditEvent(AuditEvent event) {
        try {
            String json = AuditLogger.toJsonLine(event);
            triggerChannel(AlarmPanelBindingConstants.CH_AUDIT, json);
            recordRecentEvent(event);
        } catch (RuntimeException e) {
            LOGGER.warn("publishAuditEvent failed: {}", e.getMessage());
        }
    }

    // Ring buffer of last 5 audit events, surfaced as Thing properties so they
    // render natively on Settings → Things → AlarmPanel main → Properties.
    private final java.util.Deque<String> recentEvents = new java.util.ArrayDeque<>(5);

    private void recordRecentEvent(AuditEvent event) {
        try {
            String line = summarizeAudit(event);
            java.util.Map<String, String> props = new java.util.HashMap<>(getThing().getProperties());
            synchronized (recentEvents) {
                recentEvents.addFirst(line);
                while (recentEvents.size() > 5) {
                    recentEvents.removeLast();
                }
                int i = 1;
                for (String e : recentEvents) {
                    props.put("recent_event_" + i, e);
                    i++;
                }
                // Clear any stale slots past current size.
                for (int j = recentEvents.size() + 1; j <= 5; j++) {
                    props.remove("recent_event_" + j);
                }
            }
            // Mirror disarm fields as Properties too (in addition to the channels).
            if (event.getType() == AuditEventType.DISARM) {
                String src = event.getFields().getOrDefault("source", "");
                props.put("last_disarm_source", maskDigitsForDisplay(src));
                props.put("last_disarm_time", java.time.LocalDateTime.now()
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
            }
            updateProperties(props);
        } catch (RuntimeException e) {
            LOGGER.warn("recordRecentEvent failed: {}", e.getMessage());
        }
    }

    private static String summarizeAudit(AuditEvent event) {
        String localTime = java.time.LocalTime.now()
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        StringBuilder sb = new StringBuilder(localTime).append("  ").append(event.getType().name());
        for (java.util.Map.Entry<String, String> e : event.getFields().entrySet()) {
            sb.append(' ').append(e.getKey()).append('=').append(maskDigitsForDisplay(e.getValue()));
        }
        return sb.toString();
    }

    /** Replace any run of 4+ digits with first char + "***" (PIN leak guard). */
    private static String maskDigitsForDisplay(String s) {
        if (s == null) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{4,}").matcher(s);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            out.append(s.charAt(m.start())).append("***");
            last = m.end();
        }
        out.append(s, last, s.length());
        return out.toString();
    }

    // PIN slot CRUD lives on the alarmpanel:pin-manager Thing (managed,
    // editable). The bridge itself is often file-managed and therefore not
    // editable from MainUI — see PinManagerThingHandler.

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String id = channelUID.getId();
        String raw = command.toFullString();
        // REFRESH is sent by openHAB framework when an item is linked or the
        // user clicks the refresh action — never treat it as a user value.
        boolean isRefresh = "REFRESH".equals(raw);
        if (AlarmPanelBindingConstants.CH_COMMAND.equals(id)) {
            if (isRefresh) {
                publishStateToChannels();
                return;
            }
            handleCommandChannel(command);
        } else if (AlarmPanelBindingConstants.CH_PIN_ENTRY.equals(id)) {
            if (isRefresh || raw.isEmpty()) {
                return;
            }
            onPinEntered(raw);
        } else if (AlarmPanelBindingConstants.CH_STATE.equals(id)) {
            // The widget refreshes by reading STATE. We honor REFRESH by republishing.
            publishStateToChannels();
        }
    }

    private void handleCommandChannel(Command command) {
        // Accept either a bare verb ("DISARM") or a verb with an attribution
        // suffix ("DISARM:unifi:badge:Alice(Garage)"). The suffix becomes the
        // audit source so external callers (badge readers via JS glue) can
        // record who actually authenticated.
        String full = command.toFullString().trim();
        String verb;
        String suffix = null;
        int colon = full.indexOf(':');
        if (colon > 0) {
            verb = full.substring(0, colon).toUpperCase(Locale.ROOT);
            suffix = full.substring(colon + 1).trim();
            if (suffix.isEmpty()) {
                suffix = null;
            }
        } else {
            verb = full.toUpperCase(Locale.ROOT);
        }
        String defaultSource = "channel:command";
        String effectiveSource = suffix != null ? suffix : defaultSource;
        switch (verb) {
            case "ARM_HOME":
            case "ARMED_HOME":
                requestArm(ArmMode.HOME, effectiveSource);
                break;
            case "ARM_AWAY":
            case "ARMED_AWAY":
                requestArm(ArmMode.AWAY, effectiveSource);
                break;
            case "DISARM":
                requestDisarm(effectiveSource, suffix);
                break;
            case "VIOLATE":
            case "VIOLATION":
                // External violation report — caller (e.g. an existing JS rule)
                // detected an OPEN sensor in an armed state and is asking the
                // bridge to react. If we're already in ENTRY_DELAY or TRIGGERED
                // this is a no-op (the entry countdown is already running).
                requestExternalViolation(effectiveSource);
                break;
            case "TRIGGER":
                // Instant trigger (skips entry delay). Used for 24h zones or
                // panic buttons. Only fires when already armed.
                if (machine.getState().isArmed()) {
                    triggerAlarm(effectiveSource);
                }
                break;
            case "TEST":
                audit.log(new AuditEvent(AuditEventType.STATE).set("test", true));
                break;
            case "SILENCE":
                requestSilence(effectiveSource);
                break;
            default:
                audit.log(new AuditEvent(AuditEventType.CONFIG_ERROR).set("unknown_command", verb));
                LOGGER.info("alarmpanel: unknown command '{}'", verb);
        }
    }

    /**
     * Convenience used by zones/outputs to surface a configuration error against
     * the bridge so the user can see the binding is mis-set without digging
     * through logs.
     */
    public void surfaceChildError(String detail) {
        updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, detail);
    }

    /**
     * Used by tests + console. Allows externally seeded panel state.
     */
    public void forceState(PanelState s) {
        Transition t = machine.transitionTo(s, "force", "console");
        if (t != null) {
            afterTransition(t);
        }
    }

    /**
     * List a debug summary string for console output.
     */
    public String describeState() {
        PanelState s = machine.getState();
        StringBuilder sb = new StringBuilder();
        sb.append("state=").append(s);
        Instant ends = machine.getCountdownEndsAt();
        if (ends != null) {
            sb.append(" countdownEndsAt=").append(ends);
        }
        List<String> zoneIds = new ArrayList<>();
        for (ZoneThingHandler z : zones) {
            zoneIds.add(z.getThingUid());
        }
        sb.append(" zones=").append(zoneIds.size());
        sb.append(" outputs=").append(outputs.size());
        PinStore ps = pinStore;
        if (ps != null) {
            sb.append(" pins=").append(ps.size());
        }
        return sb.toString();
    }

    /**
     * Used by Karaf console / REST to remove the current pendingArmMode tracking
     * when something external (a badge) issues a disarm. Idempotent.
     */
    public void clearPendingArmMode() {
        pendingArmMode = null;
    }

    /**
     * Returns a copy of the zones set — used by console summary only.
     */
    public Set<ZoneThingHandler> getZones() {
        return new HashSet<>(zones);
    }
}
