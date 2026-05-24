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

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * One PIN row in the store.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public final class PinRecord {
    public final String id;
    public String label;
    public String hash;
    public Instant created;
    public @Nullable Instant lastUsed;
    public boolean disabled;

    public PinRecord(String id, String label, String hash, Instant created, @Nullable Instant lastUsed,
            boolean disabled) {
        this.id = id;
        this.label = label;
        this.hash = hash;
        this.created = created;
        this.lastUsed = lastUsed;
        this.disabled = disabled;
    }
}
