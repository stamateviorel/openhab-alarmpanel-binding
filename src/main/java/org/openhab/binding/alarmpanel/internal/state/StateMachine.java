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
package org.openhab.binding.alarmpanel.internal.state;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Owns the panel state and enforces legal transitions.
 *
 * Thread-safe via a single ReentrantLock; all callers go through {@link #transitionTo}.
 *
 * Legal transitions:
 *
 * <pre>
 * DISARMED      -> EXIT_DELAY (ARM)
 * EXIT_DELAY    -> ARMED_HOME / ARMED_AWAY (countdown 0) | DISARMED (DISARM)
 * ARMED_HOME    -> ENTRY_DELAY (violation) | DISARMED (DISARM) | TRIGGERED (instant zone)
 * ARMED_AWAY    -> ENTRY_DELAY (violation) | DISARMED (DISARM) | TRIGGERED (instant zone)
 * ENTRY_DELAY   -> TRIGGERED (countdown 0) | DISARMED (DISARM)
 * TRIGGERED     -> DISARMED (DISARM / SILENCE)
 * UNKNOWN       -> any
 * </pre>
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public class StateMachine {

    private final ReentrantLock lock = new ReentrantLock();
    private PanelState state = PanelState.UNKNOWN;
    private @Nullable Instant armedAt;
    private @Nullable Instant triggeredAt;
    private @Nullable Instant lastDisarmAt;
    private @Nullable String lastDisarmSource;
    private @Nullable Instant countdownEndsAt;

    private static final Set<PanelState> CAN_DISARM_FROM = EnumSet.of(PanelState.EXIT_DELAY, PanelState.ARMED_HOME,
            PanelState.ARMED_AWAY, PanelState.ENTRY_DELAY, PanelState.TRIGGERED, PanelState.UNKNOWN);

    public PanelState getState() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    public @Nullable Instant getArmedAt() {
        lock.lock();
        try {
            return armedAt;
        } finally {
            lock.unlock();
        }
    }

    public @Nullable Instant getTriggeredAt() {
        lock.lock();
        try {
            return triggeredAt;
        } finally {
            lock.unlock();
        }
    }

    public @Nullable Instant getLastDisarmAt() {
        lock.lock();
        try {
            return lastDisarmAt;
        } finally {
            lock.unlock();
        }
    }

    public @Nullable String getLastDisarmSource() {
        lock.lock();
        try {
            return lastDisarmSource;
        } finally {
            lock.unlock();
        }
    }

    public @Nullable Instant getCountdownEndsAt() {
        lock.lock();
        try {
            return countdownEndsAt;
        } finally {
            lock.unlock();
        }
    }

    public void setCountdownEndsAt(@Nullable Instant when) {
        lock.lock();
        try {
            countdownEndsAt = when;
        } finally {
            lock.unlock();
        }
    }

    public void restoreFromPersistence(@Nullable PanelState restoredState, @Nullable Instant restoredArmedAt,
            @Nullable Instant restoredCountdownEnd, @Nullable String restoredDisarmSource,
            @Nullable Instant restoredDisarmAt) {
        lock.lock();
        try {
            if (restoredState != null) {
                state = restoredState;
            }
            armedAt = restoredArmedAt;
            countdownEndsAt = restoredCountdownEnd;
            lastDisarmSource = restoredDisarmSource;
            lastDisarmAt = restoredDisarmAt;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempt a transition. Returns the {@link Transition} record on success,
     * or null if the move is illegal in the current state (and is silently
     * rejected — caller is responsible for emitting an audit row).
     */
    public @Nullable Transition transitionTo(PanelState target, String source, @Nullable String detail) {
        lock.lock();
        try {
            if (!isLegal(state, target)) {
                return null;
            }
            PanelState previous = state;
            state = target;
            Instant now = Instant.now();

            switch (target) {
                case ARMED_HOME:
                case ARMED_AWAY:
                    if (previous != PanelState.ENTRY_DELAY) {
                        // Only set armedAt when we move into armed from EXIT_DELAY; an ENTRY_DELAY-to-armed
                        // step is not legal but the switch covers all enums.
                        armedAt = now;
                    }
                    countdownEndsAt = null;
                    break;
                case TRIGGERED:
                    triggeredAt = now;
                    countdownEndsAt = null;
                    break;
                case DISARMED:
                    lastDisarmAt = now;
                    lastDisarmSource = source;
                    countdownEndsAt = null;
                    armedAt = null;
                    break;
                case EXIT_DELAY:
                case ENTRY_DELAY:
                    // countdown will be set by caller via setCountdownEndsAt
                    break;
                default:
                    break;
            }
            return new Transition(previous, target, now, source, detail);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Test whether the requested transition is legal, given the current state.
     */
    public boolean isLegal(PanelState from, PanelState to) {
        if (from == to) {
            return false;
        }
        if (from == PanelState.UNKNOWN) {
            return true;
        }
        switch (to) {
            case DISARMED:
                return CAN_DISARM_FROM.contains(from);
            case EXIT_DELAY:
                return from == PanelState.DISARMED;
            case ARMED_HOME:
            case ARMED_AWAY:
                return from == PanelState.EXIT_DELAY;
            case ENTRY_DELAY:
                return from == PanelState.ARMED_HOME || from == PanelState.ARMED_AWAY;
            case TRIGGERED:
                return from == PanelState.ENTRY_DELAY || from == PanelState.ARMED_HOME || from == PanelState.ARMED_AWAY;
            case UNKNOWN:
                return false;
            default:
                return false;
        }
    }
}
