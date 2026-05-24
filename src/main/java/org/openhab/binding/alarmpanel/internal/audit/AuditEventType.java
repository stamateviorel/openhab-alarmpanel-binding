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
package org.openhab.binding.alarmpanel.internal.audit;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Categorical audit event types written to the audit log.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public enum AuditEventType {
    STATE,
    ARM,
    DISARM,
    TRIGGER,
    PIN_OK,
    PIN_WRONG,
    PIN_LOCKED,
    PIN_ADDED,
    PIN_REMOVED,
    ZONE_VIOLATION,
    ZONE_SUPPRESSED,
    OUTPUT_ASSERTED,
    OUTPUT_RELEASED,
    OUTPUT_ERROR,
    CONFIG_ERROR,
    RESTORE,
    EMERGENCY_DISARM
}
