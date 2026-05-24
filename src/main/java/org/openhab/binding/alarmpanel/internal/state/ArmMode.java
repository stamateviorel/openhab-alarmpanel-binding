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
 * Which arm-mode a zone or transition refers to.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public enum ArmMode {
    HOME,
    AWAY;

    public PanelState toState() {
        return this == HOME ? PanelState.ARMED_HOME : PanelState.ARMED_AWAY;
    }

    public static ArmMode fromState(PanelState s) {
        if (s == PanelState.ARMED_HOME) {
            return HOME;
        }
        return AWAY;
    }
}
