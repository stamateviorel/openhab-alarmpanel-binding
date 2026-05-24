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

import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.alarmpanel.internal.AlarmPanelBindingConstants;
import org.openhab.binding.alarmpanel.internal.output.Mp3Driver;
import org.openhab.binding.alarmpanel.internal.output.OutputDriver;
import org.openhab.binding.alarmpanel.internal.output.OutputDriverContext;
import org.openhab.binding.alarmpanel.internal.output.StrobeDriver;
import org.openhab.binding.alarmpanel.internal.output.SwitchPulseDriver;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hosts one of the {@link OutputDriver} implementations selected via Thing
 * config. Subscribes to the bridge for engage/release callbacks.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public class OutputThingHandler extends BaseThingHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutputThingHandler.class);

    private @Nullable OutputDriver driver;
    private int testDurationSec = 5;

    public OutputThingHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        AlarmPanelBridgeHandler bridge = getBridgeHandler();
        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED, "no panel bridge");
            return;
        }
        String driverKind = stringConfig("driver", "switchPulse").trim();
        OutputDriverContext ctx = new OutputDriverContext(bridge.getEventPublisher(), bridge.getItemRegistry(),
                bridge.getAudioManager(), scheduler);
        boolean claim = boolConfig("claimDuringTriggered", true);
        int reassert = intConfig("reassertEverySeconds", 5);
        testDurationSec = intConfig("testDurationSeconds", 5);

        OutputDriver d = null;
        try {
            switch (driverKind.toLowerCase(Locale.ROOT)) {
                case "switchpulse": {
                    String target = stringConfig("targetItem", "");
                    if (target.isBlank()) {
                        throw new IllegalArgumentException("targetItem required for switchPulse");
                    }
                    d = new SwitchPulseDriver(ctx, target, claim, reassert);
                    break;
                }
                case "mp3": {
                    String sink = stringConfig("audioSink", "");
                    String url = stringConfig("audioUrl", "");
                    int vol = intConfig("audioVolume", 100);
                    if (sink.isBlank() || url.isBlank()) {
                        throw new IllegalArgumentException("audioSink and audioUrl required for mp3");
                    }
                    d = new Mp3Driver(ctx, sink, url, vol, reassert);
                    break;
                }
                case "strobe": {
                    String target = stringConfig("targetItem", "");
                    String pattern = stringConfig("strobePatternMs", "500,500");
                    if (target.isBlank()) {
                        throw new IllegalArgumentException("targetItem required for strobe");
                    }
                    d = new StrobeDriver(ctx, target, pattern);
                    break;
                }
                default:
                    throw new IllegalArgumentException("unknown driver: " + driverKind);
            }
        } catch (RuntimeException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
            return;
        }
        driver = d;
        bridge.registerOutput(this);
        updateStatus(ThingStatus.ONLINE);
        publishActive();
    }

    @Override
    public void dispose() {
        AlarmPanelBridgeHandler bridge = getBridgeHandler();
        if (bridge != null) {
            bridge.unregisterOutput(this);
        }
        shutdownDriver();
        super.dispose();
    }

    public void shutdownDriver() {
        OutputDriver d = driver;
        if (d != null) {
            try {
                d.shutdown();
            } catch (RuntimeException e) {
                LOGGER.warn("Output {} shutdown threw: {}", thing.getUID(), e.getMessage());
            }
        }
    }

    public void engageDriver() {
        OutputDriver d = driver;
        if (d == null) {
            return;
        }
        try {
            d.engage();
            publishActive();
        } catch (RuntimeException e) {
            publishError(e.getMessage());
        }
    }

    public void releaseDriver() {
        OutputDriver d = driver;
        if (d == null) {
            return;
        }
        try {
            d.release();
            publishActive();
        } catch (RuntimeException e) {
            publishError(e.getMessage());
        }
    }

    public String getThingUid() {
        return thing.getUID().toString();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (AlarmPanelBindingConstants.CH_OUTPUT_TEST.equals(channelUID.getId())) {
            String c = command.toFullString().toUpperCase(Locale.ROOT);
            if ("ON".equals(c)) {
                OutputDriver d = driver;
                if (d != null) {
                    try {
                        d.test(testDurationSec);
                        publishActive();
                    } catch (RuntimeException e) {
                        publishError(e.getMessage());
                    }
                }
                // Auto-clear the test switch back to OFF after the test duration so the
                // momentary semantics are visible in the UI. Also re-publish active state
                // (it goes false inside the driver when the test scheduled task fires).
                scheduler.schedule(() -> {
                    updateState(channelUID, OnOffType.OFF);
                    publishActive();
                }, testDurationSec + 1, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    private void publishActive() {
        OutputDriver d = driver;
        boolean active = d != null && d.isActive();
        updateState(new ChannelUID(thing.getUID(), AlarmPanelBindingConstants.CH_OUTPUT_ACTIVE),
                active ? OnOffType.ON : OnOffType.OFF);
        if (d != null) {
            String err = d.getLastError();
            if (err != null) {
                publishError(err);
            }
        }
    }

    private void publishError(@Nullable String msg) {
        if (msg == null) {
            return;
        }
        updateState(new ChannelUID(thing.getUID(), AlarmPanelBindingConstants.CH_OUTPUT_LAST_ERROR),
                new StringType(msg));
    }

    private @Nullable AlarmPanelBridgeHandler getBridgeHandler() {
        Bridge b = getBridge();
        if (b == null) {
            return null;
        }
        Object h = b.getHandler();
        return h instanceof AlarmPanelBridgeHandler ? (AlarmPanelBridgeHandler) h : null;
    }

    private String stringConfig(String key, String dflt) {
        Object v = getConfig().get(key);
        return v == null ? dflt : v.toString();
    }

    private int intConfig(String key, int dflt) {
        Object v = getConfig().get(key);
        return v instanceof Number ? ((Number) v).intValue() : dflt;
    }

    private boolean boolConfig(String key, boolean dflt) {
        Object v = getConfig().get(key);
        return v instanceof Boolean ? (Boolean) v : dflt;
    }
}
