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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * PBKDF2-WithHmacSHA256 hasher. Pure JDK — no native code, no extra deps.
 *
 * <p>
 * Hash format (modular-crypt style):
 *
 * <pre>
 * $pbkdf2-sha256$i=&lt;iter&gt;$&lt;b64salt&gt;$&lt;b64hash&gt;
 * </pre>
 *
 * Default 600 000 iterations — OWASP 2023 recommendation for PBKDF2-SHA256 in
 * interactive verification scenarios. Tunable per-instance for testing.
 *
 * @author openHAB - Initial contribution
 */
@NonNullByDefault
public class Pbkdf2PinHasher implements PinHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String ID = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int DEFAULT_ITERATIONS = 600_000;

    private final int iterations;
    private final SecureRandom random;

    public Pbkdf2PinHasher() {
        this(DEFAULT_ITERATIONS);
    }

    public Pbkdf2PinHasher(int iterations) {
        if (iterations < 10_000) {
            throw new IllegalArgumentException("iterations must be >= 10000");
        }
        this.iterations = iterations;
        this.random = new SecureRandom();
    }

    @Override
    public String hash(char[] pin) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = derive(pin, salt, iterations, HASH_BYTES);
        return "$" + ID + "$i=" + iterations + "$" + Base64.getEncoder().withoutPadding().encodeToString(salt) + "$"
                + Base64.getEncoder().withoutPadding().encodeToString(derived);
    }

    @Override
    public boolean verify(char[] pin, String stored) {
        try {
            if (stored == null || !stored.startsWith("$" + ID + "$")) {
                return false;
            }
            String[] parts = stored.split("\\$");
            // [empty, id, "i=<n>", salt, hash]
            if (parts.length != 5) {
                return false;
            }
            String iterParam = parts[2];
            if (!iterParam.startsWith("i=")) {
                return false;
            }
            int iter = Integer.parseInt(iterParam.substring(2));
            byte[] salt = Base64.getDecoder().decode(parts[3]);
            byte[] expected = Base64.getDecoder().decode(parts[4]);
            byte[] actual = derive(pin, salt, iter, expected.length);
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private byte[] derive(char[] pin, byte[] salt, int iter, int hashBytes) {
        try {
            KeySpec spec = new PBEKeySpec(pin, salt, iter, hashBytes * 8);
            SecretKeyFactory f = SecretKeyFactory.getInstance(ALGORITHM);
            return f.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 unavailable: " + e.getMessage(), e);
        }
    }
}
