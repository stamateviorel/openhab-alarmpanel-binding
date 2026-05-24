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
 * The discrete states of the alarm panel.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public enum PanelState {
    UNKNOWN,
    DISARMED,
    EXIT_DELAY,
    ARMED_HOME,
    ARMED_AWAY,
    ENTRY_DELAY,
    TRIGGERED;

    public boolean isArmed() {
        return this == ARMED_HOME || this == ARMED_AWAY || this == EXIT_DELAY || this == ENTRY_DELAY
                || this == TRIGGERED;
    }

    public boolean isCountingDown() {
        return this == EXIT_DELAY || this == ENTRY_DELAY;
    }

    public static PanelState parseOrDefault(String name, PanelState dflt) {
        try {
            return PanelState.valueOf(name);
        } catch (IllegalArgumentException e) {
            return dflt;
        }
    }
}
