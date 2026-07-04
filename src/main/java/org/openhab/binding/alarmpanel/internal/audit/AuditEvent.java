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

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Mutable builder + immutable value for one audit row.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public final class AuditEvent {

    private final Instant when;
    private final AuditEventType type;
    private final Map<String, String> fields = new LinkedHashMap<>();

    public AuditEvent(AuditEventType type) {
        this.when = Instant.now();
        this.type = type;
    }

    public AuditEvent set(String key, @Nullable String value) {
        if (value != null) {
            fields.put(key, value);
        }
        return this;
    }

    public AuditEvent set(String key, @Nullable Number value) {
        if (value != null) {
            fields.put(key, value.toString());
        }
        return this;
    }

    public AuditEvent set(String key, boolean value) {
        fields.put(key, Boolean.toString(value));
        return this;
    }

    public Instant getWhen() {
        return when;
    }

    public AuditEventType getType() {
        return type;
    }

    public Map<String, String> getFields() {
        // Return an immutable, insertion-ordered snapshot. The field is mutable
        // during the builder phase (set()), but once handed to a reader it must not
        // be mutated; a copy also means a reader can never hit a
        // ConcurrentModificationException if the event is ever shared across threads.
        return Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
