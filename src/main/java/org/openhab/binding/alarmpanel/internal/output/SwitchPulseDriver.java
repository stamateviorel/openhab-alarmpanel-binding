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
 * Drives a Switch item: ON on engage, OFF on release. When
 * {@code claimDuringTriggered} is true, re-asserts ON every
 * {@code reassertEverySeconds}.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public class SwitchPulseDriver implements OutputDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger(SwitchPulseDriver.class);

    private final OutputDriverContext ctx;
    private final String targetItemName;
    private final boolean claim;
    private final int reassertSeconds;

    private volatile boolean active;
    private volatile @Nullable String lastError;
    private @Nullable ScheduledFuture<?> reassertJob;
    private @Nullable ScheduledFuture<?> testJob;

    public SwitchPulseDriver(OutputDriverContext ctx, String targetItemName, boolean claim, int reassertSeconds) {
        this.ctx = ctx;
        this.targetItemName = targetItemName;
        this.claim = claim;
        this.reassertSeconds = Math.max(1, reassertSeconds);
    }

    @Override
    public synchronized void engage() {
        if (active) {
            return;
        }
        active = true;
        sendCommand(OnOffType.ON);
        if (claim) {
            ScheduledFuture<?> prev = reassertJob;
            if (prev != null) {
                prev.cancel(false);
            }
            reassertJob = ctx.scheduler.scheduleWithFixedDelay(this::reassert, reassertSeconds, reassertSeconds,
                    TimeUnit.SECONDS);
        }
    }

    @Override
    public synchronized void release() {
        if (!active) {
            return;
        }
        active = false;
        ScheduledFuture<?> prev = reassertJob;
        if (prev != null) {
            prev.cancel(false);
            reassertJob = null;
        }
        sendCommand(OnOffType.OFF);
    }

    @Override
    public synchronized void test(int durationSeconds) {
        ScheduledFuture<?> prev = testJob;
        if (prev != null) {
            prev.cancel(false);
        }
        sendCommand(OnOffType.ON);
        active = true;
        int delay = Math.max(1, durationSeconds);
        testJob = ctx.scheduler.schedule(() -> {
            synchronized (SwitchPulseDriver.this) {
                if (reassertJob == null) {
                    // No engage() in flight; flip off.
                    sendCommand(OnOffType.OFF);
                    active = false;
                }
            }
        }, delay, TimeUnit.SECONDS);
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
        ScheduledFuture<?> r = reassertJob;
        if (r != null) {
            r.cancel(true);
            reassertJob = null;
        }
        ScheduledFuture<?> t = testJob;
        if (t != null) {
            t.cancel(true);
            testJob = null;
        }
        if (active) {
            sendCommand(OnOffType.OFF);
            active = false;
        }
    }

    private void reassert() {
        if (!active) {
            return;
        }
        sendCommand(OnOffType.ON);
    }

    private void sendCommand(OnOffType cmd) {
        try {
            // Verify item exists; ItemNotFoundException is caught below.
            ctx.itemRegistry.getItem(targetItemName);
            EventPublisher publisher = ctx.eventPublisher;
            publisher.post(ItemEventFactory.createCommandEvent(targetItemName, cmd, "alarmpanel"));
            lastError = null;
        } catch (ItemNotFoundException e) {
            lastError = "Item not found: " + targetItemName;
            LOGGER.warn("SwitchPulseDriver: {}", lastError);
        } catch (RuntimeException e) {
            lastError = "Send failed: " + e.getMessage();
            LOGGER.warn("SwitchPulseDriver: failed to send {} to {}: {}", cmd, targetItemName, e.getMessage());
        }
    }
}
