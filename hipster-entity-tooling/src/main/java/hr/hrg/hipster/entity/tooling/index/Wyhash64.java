// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Davor Hrg
//
// VENDORED COPY of hr.hrg.wyhash:wyhash:1.0.0 (hr/hrg/wyhash/Wyhash64.java), MIT licensed, copied
// byte-for-byte from the library's own sources jar so the tooling does not grow a Maven dependency
// (DEC-029 § "the content hash").
//
// Why a copy, and why it is checked: the watch agent (java-watch-core's ChecksumDatabase and
// java-watch-agent's MetadataCache) hashes text files with THIS algorithm. The class index's
// `checksum` column has to agree with those tables about what "the same content" means, and a silent
// divergence between this copy and the library would be exactly the failure the single-hash
// reservation (plan.metadata-locations.md § 2.3.2) existed to prevent.
//
// ContentHashTest therefore pins the vendored code to golden vectors produced by the LIBRARY, not by
// itself. If this file is ever regenerated from a newer upstream release, those vectors must be
// regenerated with the same release, and the algorithm name in ClassIndex's `hash` header (and
// DEC-029) must be revisited with it.
//
// The library's ByteBuffer entry points and its streaming form are deliberately NOT vendored: nothing
// in this repository hashes a ByteBuffer, and an unused code path in a vendored copy is code that no
// test covers.
package hr.hrg.hipster.entity.tooling.index;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Standalone implementation of Wyhash matching Zig 0.15 std.hash.Wyhash.
 *
 * <p>Vendored from {@code hr.hrg.wyhash:wyhash:1.0.0}; see the file header for why.</p>
 */
final class Wyhash64 {

    private static final long[] DEFAULT_SECRET = {
            0xa0761d6478bd642fL, 0xe7037ed1a0b428dbL, 0x8ebc6af09c88c6e3L, 0x589965cc75374cc3L
    };

    private static final VarHandle LONG_HANDLE = MethodHandles.byteArrayViewVarHandle(long[].class,
            ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_HANDLE = MethodHandles.byteArrayViewVarHandle(int[].class,
            ByteOrder.LITTLE_ENDIAN);

    private Wyhash64() {
    }

    static long hash(long seed, byte[] data) {
        return hash(seed, data, 0, data.length);
    }

    static long hash(long seed, byte[] data, int off, int len) {
        long s = initSeed(seed);
        long secret1 = DEFAULT_SECRET[1];
        long secret2 = DEFAULT_SECRET[2];
        long secret3 = DEFAULT_SECRET[3];

        long a, b;

        if (len <= 16) {
            if (len >= 4) {
                a = ((long) getInt(data, off) << 32) | (getInt(data, off + ((len >> 3) << 2)) & 0xFFFFFFFFL);
                b = ((long) getInt(data, off + len - 4) << 32)
                        | (getInt(data, off + len - 4 - ((len >> 3) << 2)) & 0xFFFFFFFFL);
            } else if (len > 0) {
                a = wyr3(data, off, len);
                b = 0;
            } else {
                a = 0;
                b = 0;
            }
        } else {
            int i = len;
            int p = off;
            long see0 = s;
            long see1 = s;
            long see2 = s;

            while (i > 48) {
                see0 = mix(getLong(data, p) ^ secret1, getLong(data, p + 8) ^ see0);
                see1 = mix(getLong(data, p + 16) ^ secret2, getLong(data, p + 24) ^ see1);
                see2 = mix(getLong(data, p + 32) ^ secret3, getLong(data, p + 40) ^ see2);
                p += 48;
                i -= 48;
            }
            see0 ^= see1 ^ see2;
            while (i > 16) {
                see0 = mix(getLong(data, p) ^ secret1, getLong(data, p + 8) ^ see0);
                i -= 16;
                p += 16;
            }
            a = getLong(data, off + len - 16);
            b = getLong(data, off + len - 8);
            s = see0;
        }

        return finish(a, b, s, len);
    }

    private static long initSeed(long seed) {
        return seed ^ mix(seed ^ DEFAULT_SECRET[0], DEFAULT_SECRET[1]);
    }

    private static long mix(long a, long b) {
        long low = a * b;
        long high = Math.unsignedMultiplyHigh(a, b);
        return low ^ high;
    }

    private static long finish(long a, long b, long seed, long len) {
        long _a = a ^ DEFAULT_SECRET[1];
        long _b = b ^ seed;
        long low = _a * _b;
        long high = Math.multiplyHigh(_a, _b) + ((_a >> 63) & _b) + ((_b >> 63) & _a);
        return mix(low ^ DEFAULT_SECRET[0] ^ len, high ^ DEFAULT_SECRET[1]);
    }

    private static long wyr3(byte[] data, int off, int k) {
        return ((data[off] & 0xFFL) << 16) | ((data[off + (k >> 1)] & 0xFFL) << 8) | (data[off + k - 1] & 0xFFL);
    }

    private static int getInt(byte[] b, int off) {
        return (int) INT_HANDLE.get(b, off);
    }

    private static long getLong(byte[] b, int off) {
        return (long) LONG_HANDLE.get(b, off);
    }
}
