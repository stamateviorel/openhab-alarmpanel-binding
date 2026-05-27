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

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.alarmpanel.internal.AlarmPanelBindingConstants;
import org.openhab.binding.alarmpanel.internal.state.ArmMode;
import org.openhab.binding.alarmpanel.internal.state.SuppressionWindow;
import org.openhab.binding.alarmpanel.internal.state.ZoneBehavior;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.library.items.ContactItem;
import org.openhab.core.library.items.SwitchItem;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches a set of input items (Contact or Switch) and reports violations to
 * the parent panel.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public class ZoneThingHandler extends BaseThingHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZoneThingHandler.class);

    private final Set<String> inputs = new HashSet<>();
    private ZoneBehavior behavior = ZoneBehavior.INSTANT;
    private final Set<ArmMode> armModes = EnumSet.noneOf(ArmMode.class);
    private String inputTrigger = "AUTO";
    private @Nullable SuppressionWindow suppression;
    /** Items whose ON / OPEN state suppresses this zone (e.g. airco running). */
    private final Set<String> suppressWhenItemsOn = new HashSet<>();
    private int requireSustainedSeconds;
    private @Nullable String label;
    private boolean enabled = true;

    /**
     * Per-input scheduled "still violating after N seconds" futures.
     */
    private final Map<String, ScheduledFuture<?>> pendingSustained = new HashMap<>();
    /**
     * Inputs currently violating (used for auto-arm pre-check).
     */
    private final Set<String> violatingNow = new HashSet<>();

    public ZoneThingHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        loadConfig();
        if (inputs.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "no input items configured");
            return;
        }
        AlarmPanelBridgeHandler bridge = getBridgeHandler();
        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED, "no panel bridge");
            return;
        }
        bridge.registerZone(this);
        LOGGER.info("zone {} initialized: inputs={} behavior={} armModes={} sustained={}s window={} suppressWhenItemsOn={}",
                thing.getUID(), inputs, behavior, armModes, requireSustainedSeconds, suppression, suppressWhenItemsOn);
        // Seed initial violating state from current item values.
        for (String name : inputs) {
            try {
                Item item = bridge.getItemRegistry().getItem(name);
                State s = item.getState();
                if (isViolation(item, s)) {
                    violatingNow.add(name);
                }
            } catch (ItemNotFoundException e) {
                LOGGER.info("zone {}: input item '{}' not found yet (will pick up on state change)", thing.getUID(),
                        name);
            }
        }
        updateStatus(ThingStatus.ONLINE);
        publishZoneState();
    }

    @Override
    public void dispose() {
        for (ScheduledFuture<?> f : pendingSustained.values()) {
            f.cancel(true);
        }
        pendingSustained.clear();
        AlarmPanelBridgeHandler bridge = getBridgeHandler();
        if (bridge != null) {
            bridge.unregisterZone(this);
        }
        super.dispose();
    }

    /**
     * Split a config value into entries: handles List, comma-separated String,
     * or List with comma-string elements (which is what the .things DSL gives
     * back when you write {@code inputs="A,B"} for a multi-value text param —
     * the framework wraps it as List of one element).
     */
    private static void splitConfigValues(Object raw, java.util.function.Consumer<String> sink) {
        if (raw == null) {
            return;
        }
        java.util.List<?> list = (raw instanceof java.util.List) ? (java.util.List<?>) raw
                : java.util.Collections.singletonList(raw.toString());
        for (Object o : list) {
            if (o == null) {
                continue;
            }
            for (String part : o.toString().split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    sink.accept(trimmed);
                }
            }
        }
    }

    private void loadConfig() {
        inputs.clear();
        splitConfigValues(getConfig().get("inputs"), inputs::add);

        behavior = ZoneBehavior.parse(stringConfig("behavior", "instant"));

        armModes.clear();
        splitConfigValues(getConfig().get("armModes"), this::addArmMode);
        if (armModes.isEmpty()) {
            armModes.add(ArmMode.AWAY);
        }

        inputTrigger = stringConfig("inputTrigger", "AUTO").toUpperCase(Locale.ROOT);
        suppression = SuppressionWindow.parse(stringConfig("suppressionWindow", null));

        suppressWhenItemsOn.clear();
        splitConfigValues(getConfig().get("suppressWhenItemsOn"), suppressWhenItemsOn::add);

        Object sustained = getConfig().get("requireSustainedSeconds");
        requireSustainedSeconds = sustained instanceof Number ? ((Number) sustained).intValue() : 0;

        label = stringConfig("label", null);
    }

    private void addArmMode(@Nullable String s) {
        if (s == null) {
            return;
        }
        String up = s.trim().toUpperCase(Locale.ROOT);
        if ("HOME".equals(up)) {
            armModes.add(ArmMode.HOME);
        } else if ("AWAY".equals(up)) {
            armModes.add(ArmMode.AWAY);
        }
    }

    private String stringConfig(String key, @Nullable String dflt) {
        Object v = getConfig().get(key);
        return v == null ? (dflt == null ? "" : dflt) : v.toString();
    }

    private @Nullable AlarmPanelBridgeHandler getBridgeHandler() {
        Bridge b = getBridge();
        if (b == null) {
            return null;
        }
        Object h = b.getHandler();
        return h instanceof AlarmPanelBridgeHandler ? (AlarmPanelBridgeHandler) h : null;
    }

    /**
     * Called from the bridge when any ItemStateChangedEvent fires.
     */
    public void onInputChange(String itemName, State newState) {
        if (!inputs.contains(itemName) || !enabled) {
            return;
        }
        AlarmPanelBridgeHandler bridge = getBridgeHandler();
        if (bridge == null) {
            return;
        }
        boolean violation;
        try {
            Item item = bridge.getItemRegistry().getItem(itemName);
            violation = isViolation(item, newState);
        } catch (ItemNotFoundException e) {
            return;
        }

        if (!violation) {
            violatingNow.remove(itemName);
            ScheduledFuture<?> pending = pendingSustained.remove(itemName);
            if (pending != null) {
                pending.cancel(false);
            }
            publishZoneState();
            return;
        }

        violatingNow.add(itemName);

        String reason = suppressionReason(bridge);
        if (reason != null) {
            bridge.getAuditLogger().log(new org.openhab.binding.alarmpanel.internal.audit.AuditEvent(
                    org.openhab.binding.alarmpanel.internal.audit.AuditEventType.ZONE_SUPPRESSED)
                    .set("zone", getThingUid()).set("input", itemName).set("reason", reason));
            publishZoneState();
            return;
        }

        publishZoneState();
        publishLastViolation(itemName);

        if (requireSustainedSeconds <= 0) {
            bridge.onZoneViolation(this, itemName);
            return;
        }
        // Schedule a sustained check: only count as a violation if the input is
        // still violating after requireSustainedSeconds.
        ScheduledFuture<?> existing = pendingSustained.get(itemName);
        if (existing != null && !existing.isDone()) {
            return;
        }
        ScheduledFuture<?> job = scheduler.schedule(() -> {
            pendingSustained.remove(itemName);
            if (!violatingNow.contains(itemName) || !enabled) {
                return;
            }
            String r = suppressionReason(bridge);
            if (r != null) {
                bridge.getAuditLogger().log(new org.openhab.binding.alarmpanel.internal.audit.AuditEvent(
                        org.openhab.binding.alarmpanel.internal.audit.AuditEventType.ZONE_SUPPRESSED)
                        .set("zone", getThingUid()).set("input", itemName).set("reason", r)
                        .set("after", "sustained"));
                return;
            }
            bridge.onZoneViolation(this, itemName);
        }, requireSustainedSeconds, TimeUnit.SECONDS);
        pendingSustained.put(itemName, job);
    }

    private boolean isViolation(Item item, State s) {
        switch (inputTrigger) {
            case "OPEN":
                return s == OpenClosedType.OPEN;
            case "CLOSED":
                return s == OpenClosedType.CLOSED;
            case "ON":
                return s == OnOffType.ON;
            case "OFF":
                return s == OnOffType.OFF;
            case "AUTO":
            default:
                if (item instanceof ContactItem) {
                    return s == OpenClosedType.OPEN;
                }
                if (item instanceof SwitchItem) {
                    return s == OnOffType.ON;
                }
                return false;
        }
    }

    /** Time-window suppression only — used for the channel state badge. */
    private boolean isSuppressedByWindow() {
        SuppressionWindow w = suppression;
        if (w == null) {
            return false;
        }
        return w.contains(LocalTime.now());
    }

    /**
     * True iff at least one of {@link #suppressWhenItemsOn} is currently in a
     * "blocking" state — ON for Switch/Group of Switch, OPEN for Contact.
     * Missing items don't block (defensive: prevents typos in config from
     * silently disabling a zone).
     */
    private boolean isSuppressedByItem(AlarmPanelBridgeHandler bridge) {
        if (suppressWhenItemsOn.isEmpty()) {
            return false;
        }
        for (String name : suppressWhenItemsOn) {
            try {
                Item it = bridge.getItemRegistry().getItem(name);
                State s = it.getState();
                if (s == OnOffType.ON || s == OpenClosedType.OPEN) {
                    return true;
                }
            } catch (ItemNotFoundException e) {
                LOGGER.debug("zone {}: suppressWhenItemsOn item '{}' not found — ignored", thing.getUID(), name);
            }
        }
        return false;
    }

    /**
     * Returns the suppression reason, or {@code null} if not suppressed.
     * The reason is included in the ZONE_SUPPRESSED audit row.
     */
    private @Nullable String suppressionReason(@Nullable AlarmPanelBridgeHandler bridge) {
        if (isSuppressedByWindow()) {
            return "window:" + suppression;
        }
        if (bridge != null && isSuppressedByItem(bridge)) {
            return "items:" + String.join(",", suppressWhenItemsOn);
        }
        return null;
    }


    public boolean isCurrentlyViolating() {
        return !violatingNow.isEmpty();
    }

    public boolean watchesItem(String itemName) {
        return inputs.contains(itemName);
    }

    public ZoneBehavior getBehavior() {
        return behavior;
    }

    public boolean armsIn(ArmMode mode) {
        return armModes.contains(mode);
    }

    public String getThingUid() {
        return thing.getUID().toString();
    }

    public Set<String> getInputs() {
        return Collections.unmodifiableSet(new HashSet<>(inputs));
    }

    public @Nullable String getLabel() {
        return label;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (AlarmPanelBindingConstants.CH_ZONE_ENABLE.equals(channelUID.getId())) {
            String c = command.toFullString().toUpperCase(Locale.ROOT);
            if ("ON".equals(c)) {
                enabled = true;
            } else if ("OFF".equals(c)) {
                enabled = false;
                // Cancel any pending sustained checks
                for (ScheduledFuture<?> f : pendingSustained.values()) {
                    f.cancel(false);
                }
                pendingSustained.clear();
            }
            updateState(new ChannelUID(thing.getUID(), AlarmPanelBindingConstants.CH_ZONE_ENABLE),
                    enabled ? OnOffType.ON : OnOffType.OFF);
            publishZoneState();
        }
    }

    private void publishZoneState() {
        String state;
        if (!enabled) {
            state = "DISABLED";
        } else if (suppressionReason(getBridgeHandler()) != null) {
            state = "SUPPRESSED";
        } else if (!violatingNow.isEmpty()) {
            state = "VIOLATED";
        } else {
            state = "IDLE";
        }
        updateState(new ChannelUID(thing.getUID(), AlarmPanelBindingConstants.CH_ZONE_STATE), new StringType(state));
    }

    private void publishLastViolation(String inputItem) {
        updateState(new ChannelUID(thing.getUID(), AlarmPanelBindingConstants.CH_ZONE_LAST_VIOLATION),
                new DateTimeType(ZonedDateTime.ofInstant(Instant.now(), ZoneId.systemDefault())));
        updateState(new ChannelUID(thing.getUID(), AlarmPanelBindingConstants.CH_ZONE_LAST_VIOLATION_INPUT),
                new StringType(inputItem));
    }
}
