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
package org.openhab.binding.alarmpanel.internal.console;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.alarmpanel.internal.AlarmPanelBindingConstants;
import org.openhab.binding.alarmpanel.internal.handler.AlarmPanelBridgeHandler;
import org.openhab.binding.alarmpanel.internal.pin.PinRecord;
import org.openhab.binding.alarmpanel.internal.pin.PinStore;
import org.openhab.core.io.console.Console;
import org.openhab.core.io.console.extensions.AbstractConsoleCommandExtension;
import org.openhab.core.io.console.extensions.ConsoleCommandExtension;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.binding.ThingHandler;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Karaf console commands for the alarmpanel binding.
 *
 * <pre>
 * openhab:alarmpanel state                      # print panel state
 * openhab:alarmpanel pin list                   # show PIN labels
 * openhab:alarmpanel pin add &lt;label&gt;            # add a PIN (auto-generated)
 * openhab:alarmpanel pin remove &lt;id|label&gt;      # remove a PIN
 * openhab:alarmpanel pin reset-attempts         # clear failed-attempt counter
 * openhab:alarmpanel disarm-emergency           # force DISARM (audit row)
 * </pre>
 *
 * Note: this console flavor does not support interactive password prompts in a
 * portable way, so {@code pin add} generates a random 6-digit PIN and prints
 * it once. Operators should re-type the printed PIN as themselves; storage
 * keeps only the hash.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
@Component(service = ConsoleCommandExtension.class)
public class AlarmPanelConsoleCommandExtension extends AbstractConsoleCommandExtension {

    private final ThingRegistry thingRegistry;

    @Activate
    public AlarmPanelConsoleCommandExtension(final @Reference ThingRegistry thingRegistry) {
        super("alarmpanel", "Manage the alarm panel binding");
        this.thingRegistry = thingRegistry;
    }

    @Override
    public List<String> getUsages() {
        List<String> u = new ArrayList<>();
        u.add(buildCommandUsage("state", "show panel state"));
        u.add(buildCommandUsage("pin list", "list configured PINs"));
        u.add(buildCommandUsage("pin add <label> [<pin>]", "add a PIN (auto-generated 6-digit if no value given)"));
        u.add(buildCommandUsage("pin remove <id-or-label>", "remove a PIN"));
        u.add(buildCommandUsage("pin rename <id-or-label> <new-label>", "rename a PIN"));
        u.add(buildCommandUsage("pin enable <id-or-label>", "re-enable a disabled PIN"));
        u.add(buildCommandUsage("pin disable <id-or-label>", "disable without deleting"));
        u.add(buildCommandUsage("pin reset-attempts", "clear failed-attempt counter"));
        u.add(buildCommandUsage("events [N]", "tail the last N audit events (default 10)"));
        u.add(buildCommandUsage("disarm-emergency", "force DISARM (audit row)"));
        return u;
    }

    @Override
    public void execute(String[] args, Console console) {
        if (args.length == 0) {
            printUsage(console);
            return;
        }
        AlarmPanelBridgeHandler bridge = findBridge();
        if (bridge == null) {
            console.println("No alarmpanel bridge Thing found.");
            return;
        }
        String cmd = args[0];
        switch (cmd) {
            case "state":
                console.println(bridge.describeState());
                return;
            case "disarm-emergency":
                bridge.emergencyDisarm("console");
                console.println("Emergency disarm performed; audit row written.");
                return;
            case "pin":
                handlePin(args, console, bridge);
                return;
            case "events":
                handleEvents(args, console, bridge);
                return;
            default:
                printUsage(console);
        }
    }

    private void handleEvents(String[] args, Console console, AlarmPanelBridgeHandler bridge) {
        int n = 10;
        if (args.length >= 2) {
            try {
                n = Math.max(1, Math.min(500, Integer.parseInt(args[1])));
            } catch (NumberFormatException e) {
                console.println("Bad N — using 10");
            }
        }
        // Read the audit log path from the bridge's actual config, not a
        // hardcoded constant — supports any user-overridden path.
        Object cfg = bridge.getThing().getConfiguration().get("auditLogPath");
        String pathStr = cfg instanceof String && !((String) cfg).isBlank()
                ? (String) cfg
                : "/var/log/openhab/alarm-audit.log";
        java.nio.file.Path log = java.nio.file.Paths.get(pathStr);
        if (!java.nio.file.Files.exists(log)) {
            console.println("audit log not present: " + log);
            return;
        }
        try {
            List<String> lines = java.nio.file.Files.readAllLines(log, java.nio.charset.StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - n);
            for (int i = start; i < lines.size(); i++) {
                console.println(lines.get(i));
            }
        } catch (java.io.IOException e) {
            console.println("read failed: " + e.getMessage());
        }
    }

    private void handlePin(String[] args, Console console, AlarmPanelBridgeHandler bridge) {
        PinStore store = bridge.getPinStore();
        if (store == null) {
            console.println("PIN store not initialized.");
            return;
        }
        if (args.length < 2) {
            printUsage(console);
            return;
        }
        switch (args[1]) {
            case "list": {
                for (PinRecord r : store.snapshot()) {
                    console.println(String.format("%s  %-24s created=%s lastUsed=%s%s", r.id, r.label, r.created,
                            r.lastUsed == null ? "-" : r.lastUsed, r.disabled ? "  [disabled]" : ""));
                }
                console.println("(" + store.size() + " PIN(s))");
                return;
            }
            case "add": {
                if (args.length < 3) {
                    console.println("usage: pin add <label> [<pin>]");
                    return;
                }
                String label = args[2];
                char[] pinChars;
                boolean wasGenerated;
                if (args.length >= 4) {
                    pinChars = args[3].toCharArray();
                    wasGenerated = false;
                } else {
                    pinChars = generatePin();
                    wasGenerated = true;
                }
                String id = store.add(label, Arrays.copyOf(pinChars, pinChars.length));
                console.println("Added PIN id=" + id + " label=" + label);
                if (wasGenerated) {
                    console.println("PIN (shown once, then forgotten): " + new String(pinChars));
                } else {
                    console.println("PIN stored (hash only; original supplied by you).");
                }
                Arrays.fill(pinChars, '0');
                return;
            }
            case "remove": {
                if (args.length < 3) {
                    console.println("usage: pin remove <id-or-label>");
                    return;
                }
                String target = args[2];
                String matchedId = null;
                for (PinRecord r : store.snapshot()) {
                    if (r.id.equals(target) || r.label.equals(target)) {
                        matchedId = r.id;
                        break;
                    }
                }
                if (matchedId == null) {
                    console.println("No PIN matched id-or-label '" + target + "'");
                    return;
                }
                if (store.remove(matchedId)) {
                    console.println("Removed PIN " + matchedId);
                } else {
                    console.println("Remove failed.");
                }
                return;
            }
            case "rename": {
                if (args.length < 4) {
                    console.println("usage: pin rename <id-or-label> <new-label>");
                    return;
                }
                if (store.rename(args[2], args[3])) {
                    console.println("Renamed PIN " + args[2] + " → " + args[3]);
                } else {
                    console.println("No PIN matched id-or-label '" + args[2] + "'");
                }
                return;
            }
            case "enable":
            case "disable": {
                if (args.length < 3) {
                    console.println("usage: pin " + args[1] + " <id-or-label>");
                    return;
                }
                boolean wantDisabled = "disable".equals(args[1]);
                if (store.setDisabled(args[2], wantDisabled)) {
                    console.println((wantDisabled ? "Disabled" : "Enabled") + " PIN " + args[2]);
                } else {
                    console.println("No PIN matched id-or-label '" + args[2] + "'");
                }
                return;
            }
            case "reset-attempts":
                // Re-enter via emergency disarm path — that resets the limiter as a side
                // effect (no state transition if already DISARMED).
                bridge.emergencyDisarm("console:reset-attempts");
                console.println("Failed-attempt counter cleared.");
                return;
            default:
                printUsage(console);
        }
    }

    private char[] generatePin() {
        SecureRandom rng = new SecureRandom();
        char[] pin = new char[6];
        for (int i = 0; i < pin.length; i++) {
            pin[i] = (char) ('0' + rng.nextInt(10));
        }
        return pin;
    }

    private @Nullable AlarmPanelBridgeHandler findBridge() {
        for (Thing t : thingRegistry.getAll()) {
            if (AlarmPanelBindingConstants.THING_TYPE_PANEL.equals(t.getThingTypeUID())) {
                ThingHandler h = t.getHandler();
                if (h instanceof AlarmPanelBridgeHandler) {
                    return (AlarmPanelBridgeHandler) h;
                }
            }
        }
        return null;
    }
}
