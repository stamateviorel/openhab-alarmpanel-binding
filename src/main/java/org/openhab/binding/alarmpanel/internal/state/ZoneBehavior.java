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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * How a zone violation is handled by the panel.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public enum ZoneBehavior {
    /** Violation triggers immediately (no entry delay). */
    INSTANT,
    /** Violation opens an ENTRY_DELAY countdown that fires TRIGGERED on expiry. */
    ENTRY_DELAY,
    /** Violation is logged and listed at arm-complete, but never triggers. */
    INFORMATIONAL,
    /** Always armed, regardless of panel state. Use for fire/glass-break sensors. */
    TWENTYFOUR_HOUR;

    public static ZoneBehavior parse(String raw) {
        switch (raw == null ? "" : raw.trim().toLowerCase()) {
            case "instant":
                return INSTANT;
            case "entry-delay":
                return ENTRY_DELAY;
            case "informational":
                return INFORMATIONAL;
            case "twentyfour-hour":
            case "24h":
                return TWENTYFOUR_HOUR;
            default:
                return INSTANT;
        }
    }
}
