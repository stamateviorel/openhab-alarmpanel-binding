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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * A driver that asserts an alarm output (siren, strobe, switch) for the
 * duration of the TRIGGERED state.
 *
 * <p>
 * Lifecycle:
 *
 * <pre>
 *   engage()  → driver starts asserting
 *   ...       → (optional reassertion loop)
 *   release() → driver stops asserting; restores prior state if applicable
 *   test()    → fire briefly without committing engage/release cycle
 *   shutdown()→ release resources; called from Thing dispose()
 * </pre>
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public interface OutputDriver {

    void engage();

    void release();

    void test(int durationSeconds);

    boolean isActive();

    @Nullable
    String getLastError();

    void shutdown();
}
