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

import java.time.Duration;
import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Tracks consecutive failed PIN attempts and enforces a lockout once a
 * threshold is reached. All methods are synchronized.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public class RateLimiter {

    private final int maxAttempts;
    private final Duration lockoutDuration;

    private int failedCount;
    private @Nullable Instant lockedUntil;

    public RateLimiter(int maxAttempts, Duration lockoutDuration) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.lockoutDuration = lockoutDuration;
    }

    public synchronized boolean isLocked() {
        Instant l = lockedUntil;
        if (l == null) {
            return false;
        }
        if (Instant.now().isBefore(l)) {
            return true;
        }
        // Expired — clear and start fresh.
        lockedUntil = null;
        failedCount = 0;
        return false;
    }

    public synchronized @Nullable Instant getLockedUntil() {
        return lockedUntil;
    }

    public synchronized int getFailedCount() {
        return failedCount;
    }

    /**
     * Record a wrong attempt. Returns true iff this attempt caused a lockout.
     */
    public synchronized boolean recordFailure() {
        failedCount++;
        if (failedCount >= maxAttempts) {
            lockedUntil = Instant.now().plus(lockoutDuration);
            return true;
        }
        return false;
    }

    /**
     * Record a successful attempt; clears failure counter.
     */
    public synchronized void recordSuccess() {
        failedCount = 0;
        lockedUntil = null;
    }

    /**
     * Manually clear lockout (Karaf "disarm-emergency" or admin reset).
     */
    public synchronized void reset() {
        failedCount = 0;
        lockedUntil = null;
    }
}
