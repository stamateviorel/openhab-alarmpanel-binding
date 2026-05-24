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
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.alarmpanel.internal.AlarmPanelBindingConstants;
import org.openhab.binding.alarmpanel.internal.pin.Pbkdf2PinHasher;
import org.openhab.binding.alarmpanel.internal.pin.PinHasher;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for a single PIN credential. Each PIN is its own Thing under the
 * panel bridge, so MainUI's Settings → Things page lists them natively (no
 * custom widget required).
 *
 * <p>Lifecycle of {@code pinCode}:
 * <ol>
 *   <li>User types a PIN into the config field and saves.</li>
 *   <li>{@link #initialize()} sees a non-empty {@code pinCode}, hashes it
 *       with PBKDF2-SHA256, stores the hash as a Thing property, and clears
 *       {@code pinCode} from config so the plaintext never persists.</li>
 *   <li>{@code pinSet} property toggles to {@code yes}; status goes ONLINE.</li>
 * </ol>
 *
 * <p>If the Thing is created without a PIN, it goes OFFLINE with a CONFIG_PENDING
 * status until a PIN is supplied.
 *
 * <p>Verification is performed by the bridge's {@code PinStore}, which uses
 * the property {@code hash} on every PIN Thing. This handler exposes
 * {@link #recordUsage()} so the bridge can stamp {@code lastUsed} after a
 * successful verify.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public class PinThingHandler extends BaseThingHandler {

    // Belt-and-suspenders: reject labels with 4+ consecutive digits (PIN leak risk).
    private static final Pattern LABEL_DIGIT_LEAK = Pattern.compile("\\d{4,}");

    private final Logger logger = LoggerFactory.getLogger(PinThingHandler.class);
    private final PinHasher hasher = new Pbkdf2PinHasher();

    public PinThingHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        Configuration cfg = getConfig();
        String label = stringConfig(cfg, AlarmPanelBindingConstants.CFG_PIN_LABEL, "");
        String pinCode = stringConfig(cfg, AlarmPanelBindingConstants.CFG_PIN_CODE, "");

        if (label.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "label is required");
            return;
        }
        if (LABEL_DIGIT_LEAK.matcher(label).find()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "label may not contain 4+ consecutive digits (PIN leak risk)");
            return;
        }

        Map<String, String> props = new HashMap<>(getThing().getProperties());

        if (!pinCode.isBlank()) {
            // User supplied a new PIN — hash and clear.
            if (!pinCode.matches("\\d{4,8}")) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "PIN must be 4-8 digits");
                return;
            }
            char[] chars = pinCode.toCharArray();
            String hash;
            try {
                hash = hasher.hash(chars);
            } finally {
                java.util.Arrays.fill(chars, '0');
            }
            props.put(AlarmPanelBindingConstants.PROP_PIN_HASH, hash);
            if (!props.containsKey(AlarmPanelBindingConstants.PROP_PIN_CREATED)) {
                props.put(AlarmPanelBindingConstants.PROP_PIN_CREATED, Instant.now().toString());
            }
            props.put(AlarmPanelBindingConstants.PROP_PIN_SET, "yes");
            updateProperties(props);

            // Clear pinCode from config so plaintext doesn't persist.
            cfg.put(AlarmPanelBindingConstants.CFG_PIN_CODE, "");
            updateConfiguration(cfg);
            logger.info("PIN '{}' set/rotated; hash stored as property, pinCode cleared.", label);
        }

        String storedHash = props.get(AlarmPanelBindingConstants.PROP_PIN_HASH);
        if (storedHash == null || storedHash.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                    "set the PIN Code field to activate");
            return;
        }

        boolean disabled = boolConfig(cfg, AlarmPanelBindingConstants.CFG_PIN_DISABLED, false);
        if (disabled) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.DISABLED, "PIN disabled");
        } else {
            updateStatus(ThingStatus.ONLINE);
        }
    }

    /** Called by the bridge's PinStore after a successful verify(). */
    public void recordUsage() {
        Map<String, String> props = new HashMap<>(getThing().getProperties());
        props.put(AlarmPanelBindingConstants.PROP_PIN_LAST_USED, Instant.now().toString());
        updateProperties(props);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // PIN Things have no channels — config-only.
    }

    private static String stringConfig(Configuration cfg, String key, String dflt) {
        Object v = cfg.get(key);
        return v instanceof String ? (String) v : dflt;
    }

    private static boolean boolConfig(Configuration cfg, String key, boolean dflt) {
        Object v = cfg.get(key);
        return v instanceof Boolean ? (Boolean) v : dflt;
    }
}
