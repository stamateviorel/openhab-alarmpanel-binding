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

import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.audio.AudioManager;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemRegistry;

/**
 * Bundle of OSGi services a driver may need. Constructed once per output Thing
 * and held by reference.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public final class OutputDriverContext {

    public final EventPublisher eventPublisher;
    public final ItemRegistry itemRegistry;
    public final @Nullable AudioManager audioManager;
    public final ScheduledExecutorService scheduler;

    public OutputDriverContext(EventPublisher eventPublisher, ItemRegistry itemRegistry,
            @Nullable AudioManager audioManager, ScheduledExecutorService scheduler) {
        this.eventPublisher = eventPublisher;
        this.itemRegistry = itemRegistry;
        this.audioManager = audioManager;
        this.scheduler = scheduler;
    }
}
