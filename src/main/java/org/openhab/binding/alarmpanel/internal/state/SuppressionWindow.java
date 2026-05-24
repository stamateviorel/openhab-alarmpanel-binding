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
package org.openhab.binding.alarmpanel.internal.state;

import java.time.LocalTime;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * A daily HH:mm-HH:mm window used to suppress zone violations during known
 * false-positive periods (e.g. HVAC startup turbulence).
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public final class SuppressionWindow {

    private final LocalTime start;
    private final LocalTime end;

    private SuppressionWindow(LocalTime start, LocalTime end) {
        this.start = start;
        this.end = end;
    }

    /**
     * Parse a "HH:mm-HH:mm" string. Returns null for null/blank input or any
     * parse error.
     */
    public static @Nullable SuppressionWindow parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int dash = trimmed.indexOf('-');
        if (dash < 0 || dash == trimmed.length() - 1) {
            return null;
        }
        try {
            LocalTime s = LocalTime.parse(trimmed.substring(0, dash).trim());
            LocalTime e = LocalTime.parse(trimmed.substring(dash + 1).trim());
            return new SuppressionWindow(s, e);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * @return true if the given local time falls inside this window. Supports
     *         wraparound windows (e.g. 22:00-06:00).
     */
    public boolean contains(LocalTime now) {
        if (start.equals(end)) {
            return false;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        // wraparound window (e.g. 22:00-06:00)
        return !now.isBefore(start) || now.isBefore(end);
    }

    @Override
    public String toString() {
        return start + "-" + end;
    }
}
