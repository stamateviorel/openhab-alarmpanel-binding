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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.audio.AudioException;
import org.openhab.core.audio.AudioFormat;
import org.openhab.core.audio.AudioManager;
import org.openhab.core.audio.AudioStream;
import org.openhab.core.audio.FileAudioStream;
import org.openhab.core.library.types.PercentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays an MP3 file to a configured audio sink on a recurring schedule (one
 * play per {@code reassertEverySeconds}) for as long as the driver is engaged.
 *
 * <p>
 * Uses {@link AudioManager}. If AudioManager is not available (binding loaded
 * before the audio service), engage() is a no-op and the error is recorded.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public class Mp3Driver implements OutputDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger(Mp3Driver.class);

    private final OutputDriverContext ctx;
    private final String sinkId;
    private final String audioUrl;
    private final PercentType volume;
    private final int reassertSeconds;

    private volatile boolean active;
    private volatile @Nullable String lastError;
    private @Nullable ScheduledFuture<?> loopJob;
    private @Nullable ScheduledFuture<?> testJob;

    public Mp3Driver(OutputDriverContext ctx, String sinkId, String audioUrl, int volume0to100,
            int reassertSeconds) {
        this.ctx = ctx;
        this.sinkId = sinkId;
        this.audioUrl = audioUrl;
        this.volume = new PercentType(Math.max(0, Math.min(100, volume0to100)));
        this.reassertSeconds = Math.max(1, reassertSeconds);
    }

    @Override
    public void engage() {
        synchronized (this) {
            if (active) {
                return;
            }
            active = true;
            ScheduledFuture<?> prev = loopJob;
            if (prev != null) {
                prev.cancel(false);
            }
            loopJob = ctx.scheduler.scheduleWithFixedDelay(this::playLoop, reassertSeconds, reassertSeconds,
                    TimeUnit.SECONDS);
        }
        // First play OUTSIDE the monitor: AudioManager.play() can block (sink
        // acquisition / decode); holding the lock across it would let a slow sink
        // wedge a concurrent release()/shutdown() — i.e. delay a disarm.
        playLoop();
    }

    @Override
    public synchronized void release() {
        active = false;
        ScheduledFuture<?> prev = loopJob;
        if (prev != null) {
            prev.cancel(false);
            loopJob = null;
        }
    }

    @Override
    public void test(int durationSeconds) {
        int delay = Math.max(1, durationSeconds);
        synchronized (this) {
            ScheduledFuture<?> prev = testJob;
            if (prev != null) {
                prev.cancel(false);
            }
            if (loopJob == null) {
                active = true;
            }
            testJob = ctx.scheduler.schedule(() -> {
                synchronized (Mp3Driver.this) {
                    if (loopJob == null) {
                        active = false;
                    }
                }
            }, delay, TimeUnit.SECONDS);
        }
        // Play outside the monitor (see engage()).
        playLoop();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public @Nullable String getLastError() {
        return lastError;
    }

    @Override
    public synchronized void shutdown() {
        active = false;
        ScheduledFuture<?> l = loopJob;
        if (l != null) {
            l.cancel(true);
            loopJob = null;
        }
        ScheduledFuture<?> t = testJob;
        if (t != null) {
            t.cancel(true);
            testJob = null;
        }
    }

    private void playLoop() {
        // Guard the repeating task: an escaping Throwable would cancel the
        // scheduleWithFixedDelay loop and silence the siren. playOnce() already
        // catches IOException/RuntimeException; this additionally swallows anything
        // else so the loop survives.
        try {
            playOnce();
        } catch (Throwable t) {
            lastError = "play loop error: " + t;
            LOGGER.warn("Mp3Driver: play loop error: {}", t.toString());
        }
    }

    private void playOnce() {
        AudioManager mgr = ctx.audioManager;
        if (mgr == null) {
            lastError = "AudioManager unavailable";
            LOGGER.warn("Mp3Driver: {}", lastError);
            return;
        }
        try (AudioStream stream = openAudioStream(audioUrl)) {
            mgr.play(stream, sinkId, volume);
            lastError = null;
            LOGGER.info("Mp3Driver: played {} -> {} @vol {}", audioUrl, sinkId, volume);
        } catch (IOException e) {
            lastError = "Open " + audioUrl + " failed: " + e.getMessage();
            LOGGER.warn("Mp3Driver: {}", lastError);
        } catch (RuntimeException e) {
            lastError = "Play to " + sinkId + " failed: " + e.getMessage();
            LOGGER.warn("Mp3Driver: {}", lastError);
        }
    }

    private AudioStream openAudioStream(String url) throws IOException {
        // openHAB convention: bare filenames look up sounds/ under OPENHAB_CONF.
        Path p;
        if (url.startsWith("/") || url.contains("://")) {
            if (url.contains("://")) {
                // URL not supported by this driver flavor — caller should give a file path.
                throw new IOException("URLs not supported by Mp3Driver — use a file path");
            }
            p = Paths.get(url);
        } else {
            String conf = System.getProperty("openhab.conf", "/etc/openhab");
            p = Paths.get(conf, "sounds", url);
        }
        if (!Files.exists(p)) {
            throw new IOException("audio file not found: " + p);
        }
        try {
            return new FileAudioStream(p.toFile(), AudioFormat.MP3);
        } catch (AudioException ae) {
            throw new IOException("FileAudioStream open failed: " + ae.getMessage(), ae);
        }
    }

    /**
     * Helper for tests / future expansion: wrap a raw InputStream into an
     * AudioStream. Currently unused but kept for clarity.
     */
    @SuppressWarnings("unused")
    private static InputStream openStream(Path p) throws IOException {
        return new FileInputStream(p.toFile());
    }
}
