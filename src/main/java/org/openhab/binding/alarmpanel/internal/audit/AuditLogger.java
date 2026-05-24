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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes one JSON-line audit row per event. Best-effort: if the log file cannot
 * be written (permissions, missing directory), the failure is logged via slf4j
 * and the event is dropped — the alarm is not blocked by audit IO.
 *
 * <p>
 * Also fans events out to an optional in-memory consumer used by the trigger
 * channel.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public class AuditLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditLogger.class);

    private @Nullable Path file;
    private @Nullable Consumer<AuditEvent> listener;

    public synchronized void setFile(@Nullable Path file) {
        this.file = file;
    }

    public synchronized void setListener(@Nullable Consumer<AuditEvent> listener) {
        this.listener = listener;
    }

    public void log(AuditEvent event) {
        // Drop the listener call outside the lock so a slow consumer doesn't
        // block the audit writer.
        Consumer<AuditEvent> snapshotListener;
        Path snapshotFile;
        synchronized (this) {
            snapshotListener = listener;
            snapshotFile = file;
        }
        if (snapshotFile != null) {
            try {
                Path parent = snapshotFile.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                String line = toJsonLine(event) + System.lineSeparator();
                Files.writeString(snapshotFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOGGER.warn("Audit write failed ({}): {}", snapshotFile, e.getMessage());
            }
        }
        if (snapshotListener != null) {
            try {
                snapshotListener.accept(event);
            } catch (RuntimeException e) {
                LOGGER.warn("Audit listener threw: {}", e.getMessage());
            }
        }
    }

    /** Render an event as a single-line JSON object. */
    public static String toJsonLine(AuditEvent event) {
        StringBuilder sb = new StringBuilder(96);
        sb.append('{');
        appendKey(sb, "t");
        appendQuoted(sb, event.getWhen().toString());
        sb.append(',');
        appendKey(sb, "type");
        appendQuoted(sb, event.getType().name());
        for (Map.Entry<String, String> e : event.getFields().entrySet()) {
            sb.append(',');
            appendKey(sb, e.getKey());
            appendQuoted(sb, e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendKey(StringBuilder sb, String key) {
        appendQuoted(sb, key);
        sb.append(':');
    }

    private static void appendQuoted(StringBuilder sb, @Nullable String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
