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
package org.openhab.binding.alarmpanel.internal.pin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.alarmpanel.internal.AlarmPanelBindingConstants;
import org.openhab.binding.alarmpanel.internal.handler.PinThingHandler;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Facade over child PIN Things owned by the panel bridge.
 *
 * <p>Each PIN credential is now a Thing of type {@code alarmpanel:pin} parented
 * to the bridge. This class exposes the original {@code add / remove / verify /
 * list} surface used by the bridge handler, Karaf commands and REST endpoints,
 * but reads/writes go through the {@link ThingRegistry} instead of a JSON file.
 *
 * <p>Why: PIN Things show up natively in MainUI's Settings → Things page as
 * children of the bridge — no custom UI required for CRUD.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public class PinStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(PinStore.class);

    private final Bridge bridge;
    private final ThingRegistry thingRegistry;
    private final ManagedThingProvider managedThingProvider;
    private final PinHasher hasher;

    public PinStore(Bridge bridge, ThingRegistry thingRegistry, ManagedThingProvider managedThingProvider,
            PinHasher hasher) {
        this.bridge = bridge;
        this.thingRegistry = thingRegistry;
        this.managedThingProvider = managedThingProvider;
        this.hasher = hasher;
    }

    public List<PinRecord> list() {
        List<PinRecord> out = new ArrayList<>();
        for (Thing t : bridge.getThings()) {
            if (!isPin(t)) {
                continue;
            }
            PinRecord r = toRecord(t);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    public int size() {
        int n = 0;
        for (Thing t : bridge.getThings()) {
            if (isPin(t) && hasHash(t)) {
                n++;
            }
        }
        return n;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public List<PinRecord> snapshot() {
        return Collections.unmodifiableList(list());
    }

    /**
     * Add a new PIN. Creates a child Thing under the bridge with the hash
     * already in Properties so the user's PIN-code field never persists.
     *
     * @return the new Thing's UID-string (also serves as the PIN id).
     */
    public synchronized String add(String label, char[] plain) {
        if (plain.length == 0) {
            throw new IllegalArgumentException("PIN must not be empty");
        }
        String hash;
        try {
            hash = hasher.hash(plain);
        } finally {
            Arrays.fill(plain, '0');
        }
        return addExisting(label, hash, Instant.now(), null, false);
    }

    /**
     * Lower-level entry — register a Thing for an already-hashed PIN. Used by
     * {@link #migrateLegacyFile} so re-hashing isn't required.
     */
    public synchronized String addExisting(String label, String hash, Instant created,
            @Nullable Instant lastUsed, boolean disabled) {
        String id = UUID.randomUUID().toString();
        ThingUID thingUid = new ThingUID(AlarmPanelBindingConstants.THING_TYPE_PIN,
                bridge.getUID(), id);

        Configuration cfg = new Configuration();
        cfg.put(AlarmPanelBindingConstants.CFG_PIN_LABEL, label);
        cfg.put(AlarmPanelBindingConstants.CFG_PIN_DISABLED, disabled);
        cfg.put(AlarmPanelBindingConstants.CFG_PIN_CODE, "");

        Map<String, String> props = new HashMap<>();
        props.put(AlarmPanelBindingConstants.PROP_PIN_HASH, hash);
        props.put(AlarmPanelBindingConstants.PROP_PIN_CREATED, created.toString());
        props.put(AlarmPanelBindingConstants.PROP_PIN_SET, "yes");
        if (lastUsed != null) {
            props.put(AlarmPanelBindingConstants.PROP_PIN_LAST_USED, lastUsed.toString());
        }

        Thing pin = ThingBuilder.create(AlarmPanelBindingConstants.THING_TYPE_PIN, thingUid)
                .withBridge(bridge.getUID())
                .withLabel("PIN " + label)
                .withConfiguration(cfg)
                .withProperties(props)
                .build();
        // Use ManagedThingProvider directly — guarantees JSONDB persistence on add.
        // (thingRegistry.add() delegates to "a" provider but is asynchronous and
        //  can lose the Thing if the bundle restarts before flush; this was the
        //  bug behind PINs disappearing after bundle:update.)
        managedThingProvider.add(pin);
        LOGGER.info("Added PIN Thing {} (label='{}', hash stored as property)", thingUid, label);
        return thingUid.getAsString();
    }

    /**
     * @param idOrLabel either the Thing UID-string, the bare id segment, or the label.
     */
    public synchronized boolean remove(String idOrLabel) {
        Thing match = find(idOrLabel);
        if (match == null) {
            return false;
        }
        Thing removed = thingRegistry.remove(match.getUID());
        if (removed != null) {
            LOGGER.info("Removed PIN Thing {}", match.getUID());
            return true;
        }
        return false;
    }

    public synchronized boolean setDisabled(String idOrLabel, boolean disabled) {
        Thing match = find(idOrLabel);
        if (match == null) {
            return false;
        }
        Configuration cfg = match.getConfiguration();
        cfg.put(AlarmPanelBindingConstants.CFG_PIN_DISABLED, disabled);
        thingRegistry.update(match);
        return true;
    }

    public synchronized boolean rename(String idOrLabel, String newLabel) {
        Thing match = find(idOrLabel);
        if (match == null) {
            return false;
        }
        Configuration cfg = match.getConfiguration();
        cfg.put(AlarmPanelBindingConstants.CFG_PIN_LABEL, newLabel);
        thingRegistry.update(match);
        return true;
    }

    /**
     * Iterate PIN Things, verify the given plain PIN against each hash.
     * On a hit, asks the matching PinThingHandler to stamp {@code lastUsed}.
     *
     * @return matching record or null. Plain array is wiped on return.
     */
    public synchronized @Nullable PinRecord verify(char[] plain) {
        try {
            for (Thing t : bridge.getThings()) {
                if (!isPin(t)) {
                    continue;
                }
                if (isDisabled(t)) {
                    continue;
                }
                String hash = t.getProperties().get(AlarmPanelBindingConstants.PROP_PIN_HASH);
                if (hash == null || hash.isBlank()) {
                    continue;
                }
                if (hasher.verify(plain, hash)) {
                    PinRecord r = toRecord(t);
                    ThingHandler h = t.getHandler();
                    if (h instanceof PinThingHandler) {
                        try {
                            ((PinThingHandler) h).recordUsage();
                        } catch (RuntimeException re) {
                            LOGGER.warn("recordUsage failed for {}: {}", t.getUID(), re.getMessage());
                        }
                    }
                    return r;
                }
            }
            return null;
        } finally {
            Arrays.fill(plain, '0');
        }
    }

    // ----- helpers -----

    private @Nullable Thing find(String idOrLabel) {
        for (Thing t : bridge.getThings()) {
            if (!isPin(t)) {
                continue;
            }
            String uid = t.getUID().getAsString();
            String idSeg = t.getUID().getId();
            String label = labelOf(t);
            if (uid.equals(idOrLabel) || idSeg.equals(idOrLabel) || label.equals(idOrLabel)) {
                return t;
            }
        }
        return null;
    }

    private static boolean isPin(Thing t) {
        ThingTypeUID type = t.getThingTypeUID();
        return AlarmPanelBindingConstants.THING_TYPE_PIN.equals(type);
    }

    private static boolean hasHash(Thing t) {
        String h = t.getProperties().get(AlarmPanelBindingConstants.PROP_PIN_HASH);
        return h != null && !h.isBlank();
    }

    private static boolean isDisabled(Thing t) {
        Object v = t.getConfiguration().get(AlarmPanelBindingConstants.CFG_PIN_DISABLED);
        return v instanceof Boolean && (Boolean) v;
    }

    private static String labelOf(Thing t) {
        Object v = t.getConfiguration().get(AlarmPanelBindingConstants.CFG_PIN_LABEL);
        return v instanceof String ? (String) v : "";
    }

    private static @Nullable PinRecord toRecord(Thing t) {
        String hash = t.getProperties().get(AlarmPanelBindingConstants.PROP_PIN_HASH);
        if (hash == null || hash.isBlank()) {
            return null;
        }
        String id = t.getUID().getId();
        String label = labelOf(t);
        Instant createdParsed = parseInstant(t.getProperties().get(AlarmPanelBindingConstants.PROP_PIN_CREATED),
                Instant.EPOCH);
        Instant created = createdParsed != null ? createdParsed : Instant.EPOCH;
        Instant lastUsed = parseInstant(t.getProperties().get(AlarmPanelBindingConstants.PROP_PIN_LAST_USED),
                null);
        boolean disabled = isDisabled(t);
        return new PinRecord(id, label, hash, created, lastUsed, disabled);
    }

    private static @Nullable Instant parseInstant(@Nullable String s, @Nullable Instant dflt) {
        if (s == null || s.isEmpty()) {
            return dflt;
        }
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            return dflt;
        }
    }
}
