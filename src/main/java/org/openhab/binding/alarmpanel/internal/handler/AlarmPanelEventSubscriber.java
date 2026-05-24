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
package org.openhab.binding.alarmpanel.internal.handler;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventFilter;
import org.openhab.core.events.EventSubscriber;
import org.openhab.core.items.events.ItemStateChangedEvent;
import org.openhab.core.types.State;

/**
 * Forwards {@link ItemStateChangedEvent}s to a single {@link AlarmPanelBridgeHandler}
 * which fans them out to interested zones.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public class AlarmPanelEventSubscriber implements EventSubscriber {

    private static final Set<String> SUBSCRIBED;
    static {
        Set<String> s = new HashSet<>();
        s.add(ItemStateChangedEvent.TYPE);
        SUBSCRIBED = Collections.unmodifiableSet(s);
    }

    private final AlarmPanelBridgeHandler bridge;

    public AlarmPanelEventSubscriber(AlarmPanelBridgeHandler bridge) {
        this.bridge = bridge;
    }

    @Override
    public Set<String> getSubscribedEventTypes() {
        return SUBSCRIBED;
    }

    @Override
    public @Nullable EventFilter getEventFilter() {
        return null;
    }

    @Override
    public void receive(Event event) {
        if (event instanceof ItemStateChangedEvent) {
            ItemStateChangedEvent ev = (ItemStateChangedEvent) event;
            String itemName = ev.getItemName();
            State newState = ev.getItemState();
            bridge.routeItemStateChange(itemName, newState);
        }
    }
}
