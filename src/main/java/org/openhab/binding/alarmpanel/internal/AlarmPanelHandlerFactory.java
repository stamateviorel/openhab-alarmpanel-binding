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
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.alarmpanel.internal.handler.AlarmPanelBridgeHandler;
import org.openhab.binding.alarmpanel.internal.handler.OutputThingHandler;
import org.openhab.binding.alarmpanel.internal.handler.PinThingHandler;
import org.openhab.binding.alarmpanel.internal.handler.ZoneThingHandler;
import org.openhab.core.audio.AudioManager;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

/**
 * Constructs the panel/zone/output handlers.
 *
 * @author openHAB - Initial contribution
 */
@Component(service = ThingHandlerFactory.class)
@NonNullByDefault
public class AlarmPanelHandlerFactory extends BaseThingHandlerFactory {

    private final EventPublisher eventPublisher;
    private final ItemRegistry itemRegistry;
    private final ThingRegistry thingRegistry;
    private final ManagedThingProvider managedThingProvider;
    private volatile @Nullable AudioManager audioManager;

    @Activate
    public AlarmPanelHandlerFactory(final @Reference EventPublisher eventPublisher,
            final @Reference ItemRegistry itemRegistry,
            final @Reference ThingRegistry thingRegistry,
            final @Reference ManagedThingProvider managedThingProvider) {
        this.eventPublisher = eventPublisher;
        this.itemRegistry = itemRegistry;
        this.thingRegistry = thingRegistry;
        this.managedThingProvider = managedThingProvider;
    }

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    public void setAudioManager(AudioManager audioManager) {
        this.audioManager = audioManager;
    }

    public void unsetAudioManager(AudioManager audioManager) {
        if (this.audioManager == audioManager) {
            this.audioManager = null;
        }
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return AlarmPanelBindingConstants.THING_TYPE_PANEL.equals(thingTypeUID)
                || AlarmPanelBindingConstants.THING_TYPE_ZONE.equals(thingTypeUID)
                || AlarmPanelBindingConstants.THING_TYPE_OUTPUT.equals(thingTypeUID)
                || AlarmPanelBindingConstants.THING_TYPE_PIN.equals(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID t = thing.getThingTypeUID();
        if (AlarmPanelBindingConstants.THING_TYPE_PANEL.equals(t)) {
            return new AlarmPanelBridgeHandler((Bridge) thing, eventPublisher, itemRegistry, thingRegistry,
                    managedThingProvider, audioManager);
        }
        if (AlarmPanelBindingConstants.THING_TYPE_ZONE.equals(t)) {
            return new ZoneThingHandler(thing);
        }
        if (AlarmPanelBindingConstants.THING_TYPE_OUTPUT.equals(t)) {
            return new OutputThingHandler(thing);
        }
        if (AlarmPanelBindingConstants.THING_TYPE_PIN.equals(t)) {
            return new PinThingHandler(thing);
        }
        return null;
    }
}
