package hr.hrg.webview.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The digest a page must present before a write is allowed: {@code sha256:<hex>} over the file's bytes.
 *
 * <p>Why the bytes and not the text: the edit contract's whole safety argument is "the browser cannot clobber
 * a file the reader did not see". Comparing decoded text would let a file change its encoding, its line
 * endings or its trailing newline while still matching, and the write afterwards would silently normalise all
 * three. The digest is therefore over exactly what {@code /file/} or {@code /page/} handed out.
 *
 * <p>The prefix is part of the value, not decoration: a page can paste it into a form unchanged, and a future
 * contract can accept {@code sha512:} without the old values being mistaken for new ones.
 */
public final class SourceDigest {

    /** The only algorithm this version issues. */
    public static final String ALGORITHM = "sha256";
    /** What every digest string starts with, including the colon. */
    public static final String PREFIX = ALGORITHM + ":";

    private SourceDigest() {
    }

    /** {@code sha256:<hex>} of the file's current bytes, or null when it cannot be read. */
    public static String of(Path file) {
        try {
            return of(Files.readAllBytes(file));
        } catch (IOException e) {
            return null;
        }
    }

    public static String of(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return PREFIX + HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform; failing here is a broken JVM, not a runtime case.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static String ofText(String text) {
        return of(text.getBytes(StandardCharsets.UTF_8));
    }

    /** True when {@code presented} is the digest of {@code bytes}, prefix and all. */
    public static boolean matches(String presented, byte[] bytes) {
        return presented != null && presented.equalsIgnoreCase(of(bytes));
    }

    /** True when the string looks like a digest this contract issues, so a caller can fail early and clearly. */
    public static boolean isWellFormed(String digest) {
        if (digest == null || !digest.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return false;
        }
        String hex = digest.substring(PREFIX.length());
        if (hex.length() != 64) {
            return false;
        }
        for (int i = 0; i < hex.length(); i++) {
            if (Character.digit(hex.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
