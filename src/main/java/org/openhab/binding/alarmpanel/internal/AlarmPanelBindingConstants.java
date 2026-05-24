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
package org.openhab.binding.alarmpanel.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * Identifiers exposed by the alarmpanel binding.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public final class AlarmPanelBindingConstants {

    public static final String BINDING_ID = "alarmpanel";

    public static final ThingTypeUID THING_TYPE_PANEL = new ThingTypeUID(BINDING_ID, "panel");
    public static final ThingTypeUID THING_TYPE_ZONE = new ThingTypeUID(BINDING_ID, "zone");
    public static final ThingTypeUID THING_TYPE_OUTPUT = new ThingTypeUID(BINDING_ID, "output");
    public static final ThingTypeUID THING_TYPE_PIN = new ThingTypeUID(BINDING_ID, "pin");

    // Panel channels
    public static final String CH_STATE = "state";
    public static final String CH_COMMAND = "command";
    public static final String CH_PIN_ENTRY = "pinEntry";
    public static final String CH_COUNTDOWN = "countdown";
    public static final String CH_LAST_DISARM_SOURCE = "lastDisarmSource";
    public static final String CH_LAST_DISARM_TIME = "lastDisarmTime";
    public static final String CH_ARMED_AT = "armedAt";
    public static final String CH_TRIGGERED_AT = "triggeredAt";
    public static final String CH_FAILED_ATTEMPTS = "failedAttempts";
    public static final String CH_AUDIT = "audit";

    // Zone channels
    public static final String CH_ZONE_STATE = "state";
    public static final String CH_ZONE_LAST_VIOLATION = "lastViolation";
    public static final String CH_ZONE_LAST_VIOLATION_INPUT = "lastViolationInput";
    public static final String CH_ZONE_ENABLE = "enable";

    // Output channels
    public static final String CH_OUTPUT_ACTIVE = "active";
    public static final String CH_OUTPUT_TEST = "test";
    public static final String CH_OUTPUT_LAST_ERROR = "lastError";

    // Commands
    public static final String CMD_ARM_HOME = "ARM_HOME";
    public static final String CMD_ARM_AWAY = "ARM_AWAY";
    public static final String CMD_DISARM = "DISARM";
    public static final String CMD_TEST = "TEST";
    public static final String CMD_SILENCE = "SILENCE";

    // Persisted Thing properties (survive restart, restored in initialize())
    public static final String PROP_LAST_STATE = "lastState";
    public static final String PROP_COUNTDOWN_ENDS_AT = "countdownEndsAtIsoUtc";
    public static final String PROP_ARMED_AT = "armedAtIsoUtc";
    public static final String PROP_LAST_DISARM_AT = "lastDisarmAtIsoUtc";
    public static final String PROP_LAST_DISARM_SOURCE = "lastDisarmSource";

    // PIN Thing properties (managed by PinThingHandler — visible read-only in MainUI)
    public static final String PROP_PIN_HASH = "hash";
    public static final String PROP_PIN_CREATED = "created";
    public static final String PROP_PIN_LAST_USED = "lastUsed";
    public static final String PROP_PIN_SET = "pinSet";

    // PIN Thing config keys
    public static final String CFG_PIN_LABEL = "label";
    public static final String CFG_PIN_DISABLED = "disabled";
    public static final String CFG_PIN_CODE = "pinCode";

    private AlarmPanelBindingConstants() {
    }
}
