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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.ConfigurableService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds binding-wide ("Add-on Settings") configuration for the alarmpanel
 * binding. Backed by OSGi Configuration Admin under PID
 * {@code binding.alarmpanel} — the same PID MainUI uses when you edit
 * Settings → Add-on Settings → Alarm Panel.
 *
 * <p>Registered as an OSGi service so:
 * <ul>
 *   <li>Java handlers can {@code @Reference AlarmPanelBindingConfig} and call
 *       the getters.</li>
 *   <li>JSR223 / JS rules can look it up via
 *       {@code osgi.getService('org.openhab.binding.alarmpanel.internal.AlarmPanelBindingConfig')}
 *       (see {@code shared_utils.alarmNotificationsEnabled()}).</li>
 * </ul>
 *
 * <p>The pair of fields is intentionally narrow: things that <em>aren't</em>
 * per-instance go here. Per-bridge timing parameters (entry delay, exit
 * delay, audit log path, …) stay on the bridge Thing.
 *
 * @author openHAB - Initial contribution
 */
@Component(service = AlarmPanelBindingConfig.class, configurationPid = "binding.alarmpanel", immediate = true)
@ConfigurableService(category = "binding", label = "Alarm Panel", description_uri = "binding:alarmpanel")
@NonNullByDefault
public class AlarmPanelBindingConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmPanelBindingConfig.class);

    private volatile boolean notificationsEnabled = true;
    private volatile String unifiDisarmDenylist = "";

    @Activate
    public AlarmPanelBindingConfig(@Nullable Map<String, Object> properties) {
        apply(properties);
    }

    @Modified
    public void modified(@Nullable Map<String, Object> properties) {
        apply(properties);
    }

    private void apply(@Nullable Map<String, Object> properties) {
        if (properties == null) {
            return;
        }
        Object n = properties.get("notificationsEnabled");
        if (n instanceof Boolean) {
            notificationsEnabled = (Boolean) n;
        } else if (n instanceof String) {
            notificationsEnabled = !"false".equalsIgnoreCase((String) n);
        }
        Object d = properties.get("unifiDisarmDenylist");
        if (d instanceof String) {
            unifiDisarmDenylist = (String) d;
        } else if (d != null) {
            unifiDisarmDenylist = d.toString();
        }
        LOGGER.info("Alarm Panel binding config updated: notificationsEnabled={} unifiDisarmDenylist='{}'",
                notificationsEnabled, unifiDisarmDenylist);
    }

    /** @return {@code true} if rules should publish push / TTS / email notifications. */
    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    /** @return raw comma-separated denylist string (may be empty). */
    public String getUnifiDisarmDenylistRaw() {
        return unifiDisarmDenylist;
    }

    /** @return parsed denylist as a list of trimmed names (never null). */
    public List<String> getUnifiDisarmDenylist() {
        String raw = unifiDisarmDenylist;
        if (raw.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
