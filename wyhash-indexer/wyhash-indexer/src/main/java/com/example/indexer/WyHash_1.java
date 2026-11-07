package com.example.indexer;

import java.nio.charset.StandardCharsets;

/**
 * WyHash final v4 - pinned secret, stable forever.
 * Reference: wangyi-fudan/wyhash
 */
public final class WyHash {
    private static final long[] SECRET = {
            0xa0761d6478bd642fL, 0xe7037ed1a0b428dbL,
            0x8ebc6af09c88c6e3L, 0x589965cc75374cc3L
    };

    private WyHash() {}

    private static long wymum(long a, long b) {
        long ha = a >>> 32;
        long la = a & 0xFFFFFFFFL;
        long hb = b >>> 32;
        long lb = b & 0xFFFFFFFFL;
        long rh = ha * hb;
        long rm0 = ha * lb;
        long rm1 = hb * la;
        long rl = la * lb;
        long t = rl + (rm0 << 32);
        long c = ((t < rl) ? 1 : 0) + (t < (rm0 << 32) ? 1 : 0);
        rh += (rm0 >>> 32) + (rm1 >>> 32) + c;
        t += (rm1 << 32);
        if (t < (rm1 << 32)) rh++;
        return (rh ^ t) ^ (rh >>> 32 ^ t >>> 32);
    }

    private static long wymix(long a, long b) { return wymum(a, b); }

    private static long r8(byte[] p, int off) { return p[off] & 0xFFL; }
    private static long r16(byte[] p, int off) { return (p[off] & 0xFFL) | ((p[off+1] & 0xFFL) << 8); }
    private static long r32(byte[] p, int off) {
        return (p[off] & 0xFFL) | ((p[off+1] & 0xFFL) << 8) | ((p[off+2] & 0xFFL) << 16) | ((p[off+3] & 0xFFL) << 24);
    }
    private static long r64(byte[] p, int off) {
        return (p[off] & 0xFFL) | ((p[off+1] & 0xFFL) << 8) | ((p[off+2] & 0xFFL) << 16) | ((p[off+3] & 0xFFL) << 24)
                | ((p[off+4] & 0xFFL) << 32) | ((p[off+5] & 0xFFL) << 40) | ((p[off+6] & 0xFFL) << 48) | ((p[off+7] & 0xFFL) << 56);
    }

    public static long hash(String s) { return hash(s.getBytes(StandardCharsets.UTF_8), 0); }

    public static long hash(byte[] key, long seed) {
        int len = key.length;
        int p = 0;
        long a, b;
        if (len <= 16) {
            if (len >= 4) {
                a = (r32(key, p) << 32) | r32(key, p + ((len >> 3) << 2));
                b = (r32(key, p + len - 4) << 32) | r32(key, p + len - 4 - ((len >> 3) << 2));
            } else if (len > 0) {
                a = (r8(key, p) << 56) | (r8(key, p + (len >> 1)) << 32) | r8(key, p + len - 1);
                b = 0;
            } else { a = b = 0; }
        } else {
            long i = len;
            if (i > 48) {
                long see1 = seed, see2 = seed, see3 = seed;
                do {
                    seed = wymix(r64(key, p) ^ SECRET[0], r64(key, p + 8) ^ seed);
                    see1 = wymix(r64(key, p + 16) ^ SECRET[1], r64(key, p + 24) ^ see1);
                    see2 = wymix(r64(key, p + 32) ^ SECRET[2], r64(key, p + 40) ^ see2);
                    see3 = wymix(r64(key, p + 48) ^ SECRET[3], r64(key, p + 56) ^ see3);
                    p += 64; i -= 64;
                } while (i > 48);
                seed ^= see1 ^ see2 ^ see3;
            }
            while (i > 16) {
                seed = wymix(r64(key, p) ^ SECRET[0], r64(key, p + 8) ^ seed);
                p += 16; i -= 16;
            }
            a = r64(key, p + i - 16);
            b = r64(key, p + i - 8);
        }
        return wymix(SECRET[1] ^ (long) len, wymix(a ^ SECRET[0] ^ seed, b ^ seed));
    }

    public static long combine(long h1, long h2) { return wymix(h1 ^ SECRET[0], h2 ^ SECRET[1]); }
    public static long combine(long h1, long h2, long h3) { return combine(combine(h1, h2), h3); }
}
