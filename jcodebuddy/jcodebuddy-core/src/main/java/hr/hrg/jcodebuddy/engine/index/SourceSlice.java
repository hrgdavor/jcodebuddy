package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.source.TreeQueries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reading the text a range points at (DEC-040 D2 and D6): the model stores **names and ranges**, and this is how
 * a consumer turns a range into the words the source wrote.
 *
 * <h3>Why the checksum is part of the read, not a separate step</h3>
 *
 * <p>A range is a pointer into one revision of one file, and the row records which revision that was — the
 * {@code checksum} of its LF-normalised content. So the slice is verified in the same breath as it is taken: if
 * the file has moved on, the caller is told rather than handed text that no longer means what the row says it
 * means. That is the property a stored copy cannot have. A copy in a table has no way to announce that it is
 * stale; a range plus a checksum has no way to hide it.</p>
 *
 * <h3>Offsets are read from the file as it is, the checksum from the file normalised</h3>
 *
 * <p>The two views are different on purpose and both are right. javac's offsets index the text <em>as it was
 * read</em> — a CRLF file's offsets count its {@code \r}s — while the row's checksum is of the content with CRLF
 * normalised to LF (DEC-029 § 4, which is what keeps two checkouts with different line endings agreeing). So
 * this class reads the bytes as they are and slices by offset, and normalises only to verify. Slicing the
 * normalised text instead would shift every range in a CRLF file by the number of line breaks before it.</p>
 *
 * <h3>What it refuses</h3>
 *
 * <p>A missing file, a range outside the text, and a file whose checksum no longer matches the row are all
 * reported as a {@link Slice#problem()} with the text it did manage to read, rather than as an exception or a
 * silent empty string. An unverifiable slice that looks fine is the failure mode this whole design avoids.</p>
 */
public final class SourceSlice {

    private SourceSlice() {
    }

    /**
     * One read of a range.
     *
     * @param text    the slice, or {@code null} when nothing could be read
     * @param current whether the file still has the content the row was written from
     * @param problem what went wrong, or {@code null} when nothing did — never a substitute for {@code text}
     */
    public record Slice(String text, boolean current, String problem) {

        /** Whether this slice can be trusted: there is text, it is verified, and nothing was reported. */
        public boolean usable() {
            return text != null && current && problem == null;
        }
    }

    /**
     * The text {@code span} names in the file {@code row} describes.
     *
     * @param index the index the row came from — it knows the module root the row's path is relative to
     * @param row   the row whose file the range points into
     * @param span  the range, as the row recorded it
     */
    public static Slice read(ClassIndex index, ClassRecord row, TreeQueries.SourceSpan span) {
        if (index == null || row == null) {
            return new Slice(null, false, "a slice needs the index the row came from and the row itself");
        }
        if (span == null) {
            return new Slice(null, false, "this row records no span for what was asked, so there is no range to"
                    + " read — the fact is not recorded rather than absent (DEC-040 D4)");
        }
        Path file = index.moduleRoot().resolve(row.path());
        if (!Files.isRegularFile(file)) {
            return new Slice(null, false, "the file the row names does not exist: " + row.path());
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return new Slice(null, false, "the file could not be read: " + unreadable.getMessage());
        }

        // Verified against the row's own revision, in the same read that produces the text.
        String checksum;
        try {
            checksum = ContentHash.of(file);
        } catch (IOException unreadable) {
            return new Slice(null, false, "the file's checksum could not be computed: " + unreadable.getMessage());
        }
        boolean current = checksum.equals(row.checksum());

        if (span.start() < 0 || span.end() > text.length() || span.end() < span.start()) {
            return new Slice(null, current, "the recorded range " + span.start() + ".." + span.end()
                    + " is outside the file (" + text.length() + " characters)");
        }
        String slice = text.substring(span.start(), span.end());
        return new Slice(slice, current, current ? null
                : "the file has changed since this row was written (checksum " + row.checksum() + " is now "
                        + checksum + "), so the range points into a different revision");
    }

    /**
     * The written form of whichever fact in {@code row} the span belongs to, verified — the shorthand for
     * "a table holds pointers, give me the words" that a generator or a page actually wants.
     */
    public static Slice read(ClassIndex index, ClassRecord row, MemberRecord member) {
        return read(index, row, member == null ? null : member.span());
    }
}
