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
package org.openhab.binding.alarmpanel.internal.output;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.OnOffType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Toggles a Switch item on/off following a fixed millisecond pattern (e.g.
 * 500,500 for symmetric 1 Hz strobe).
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public class StrobeDriver implements OutputDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger(StrobeDriver.class);

    private final OutputDriverContext ctx;
    private final String targetItemName;
    private final long[] patternMs;

    private volatile boolean active;
    private volatile @Nullable String lastError;
    private @Nullable ScheduledFuture<?> loopJob;
    private int patternIndex;
    private boolean lampOn;

    public StrobeDriver(OutputDriverContext ctx, String targetItemName, String patternSpec) {
        this.ctx = ctx;
        this.targetItemName = targetItemName;
        this.patternMs = parsePattern(patternSpec);
    }

    @Override
    public synchronized void engage() {
        if (active) {
            return;
        }
        active = true;
        patternIndex = 0;
        lampOn = false;
        scheduleNext(0);
    }

    @Override
    public synchronized void release() {
        active = false;
        ScheduledFuture<?> prev = loopJob;
        if (prev != null) {
            prev.cancel(false);
            loopJob = null;
        }
        if (lampOn) {
            sendCommand(OnOffType.OFF);
            lampOn = false;
        }
    }

    @Override
    public synchronized void test(int durationSeconds) {
        if (!active) {
            engage();
        }
        ctx.scheduler.schedule(() -> {
            synchronized (StrobeDriver.this) {
                release();
            }
        }, Math.max(1, durationSeconds), TimeUnit.SECONDS);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public @Nullable String getLastError() {
        return lastError;
    }

    @Override
    public synchronized void shutdown() {
        release();
    }

    private void tick() {
        synchronized (this) {
            if (!active) {
                return;
            }
            lampOn = !lampOn;
            sendCommand(lampOn ? OnOffType.ON : OnOffType.OFF);
            patternIndex = (patternIndex + 1) % patternMs.length;
            scheduleNext(patternMs[patternIndex]);
        }
    }

    private void scheduleNext(long delayMs) {
        ScheduledFuture<?> prev = loopJob;
        if (prev != null) {
            prev.cancel(false);
        }
        loopJob = ctx.scheduler.schedule(this::tick, Math.max(1, delayMs), TimeUnit.MILLISECONDS);
    }

    private void sendCommand(OnOffType cmd) {
        try {
            ctx.itemRegistry.getItem(targetItemName);
            EventPublisher publisher = ctx.eventPublisher;
            publisher.post(ItemEventFactory.createCommandEvent(targetItemName, cmd, "alarmpanel"));
            lastError = null;
        } catch (ItemNotFoundException e) {
            lastError = "Item not found: " + targetItemName;
            LOGGER.warn("StrobeDriver: {}", lastError);
        } catch (RuntimeException e) {
            lastError = "Send failed: " + e.getMessage();
            LOGGER.warn("StrobeDriver: failed to send {} to {}: {}", cmd, targetItemName, e.getMessage());
        }
    }

    private static long[] parsePattern(String spec) {
        if (spec == null || spec.isBlank()) {
            return new long[] { 500, 500 };
        }
        String[] parts = spec.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Long.parseLong(parts[i].trim());
                if (out[i] < 1) {
                    out[i] = 1;
                }
            } catch (NumberFormatException e) {
                out[i] = 500;
            }
        }
        return out;
    }
}
