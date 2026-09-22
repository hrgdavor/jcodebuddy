// {@link com.codebuddy.merge.ConflictMarkerParser} Parses a file carrying git conflict markers into blocks and whole versions.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads a file that carries git conflict markers - what a working tree holds
 * after {@code git merge} stopped - and turns it into the pieces the rest of the
 * module works with.
 *
 * <h2>What it produces</h2>
 *
 * <ul>
 *   <li>one {@link Block} per conflict, with each side's text, the labels git
 *       put on the markers, the exact marker lines and the block verbatim;</li>
 *   <li>the reconstructed <em>whole</em> versions of the file: ours (every block
 *       replaced by its ours side), theirs, and - only when the file uses the
 *       {@code diff3}/{@code zdiff3} style and <em>every</em> block carries a
 *       {@code |||||||} base section - the base.</li>
 * </ul>
 *
 * <h2>Why a base may be missing</h2>
 *
 * <p>Git's default conflict style records only two sides. A base invented for a
 * block would fabricate history, and a wrong base makes conflicts appear that do
 * not exist ({@code docs/WHAT_IS_BASE.md}), so this parser reports "no base"
 * rather than guessing. Callers that can obtain a real base - the {@code diff3}
 * sections, or stage 1 of the repository's index through
 * {@link RepositoryProbe} - pass it in themselves.
 *
 * <h2>Failure is louder than a false clean</h2>
 *
 * <p>An unterminated block, a nested {@code <<<<<<<} or a {@code >>>>>>>}
 * without a separator is a corrupt marker file, and this parser throws with the
 * line number instead of reporting "no conflicts". A false clean is the one
 * answer this module must never give.
 *
 * <h2>Known limitation</h2>
 *
 * <p>Markers are recognised at the start of a line, exactly as git writes them.
 * A Java text block whose content itself contains a column-0
 * {@code <<<<<<<}/{@code =======}/{@code >>>>>>>} sequence would be misread -
 * the same limitation git's own tooling has.
 */
public final class ConflictMarkerParser {

    private ConflictMarkerParser() {
    }

    /**
     * One conflict block of a marked file.
     *
     * @param number      1-based index of the block within the file
     * @param startLine   1-based line of the {@code <<<<<<<} marker
     * @param endLine     1-based line of the {@code >>>>>>>} marker, inclusive
     * @param oursLabel   the label after {@code <<<<<<<}, often "ours" or a branch name
     * @param theirsLabel the label after {@code >>>>>>>}
     * @param baseLabel   the label after {@code |||||||}, or {@code null} without a base section
     * @param base        the base side's text, or {@code null} when the file does not use the diff3 style
     * @param ours        the ours side's text, never {@code null} (empty is possible)
     * @param theirs      the theirs side's text, never {@code null} (empty is possible)
     * @param raw         the block verbatim, markers included
     */
    public record Block(int number, int startLine, int endLine,
                        String oursLabel, String theirsLabel, String baseLabel,
                        String base, String ours, String theirs, String raw) {

        /**
         * True when this block carries a real diff3 base section.
         */
        public boolean hasBase() {
            return base != null;
        }

        /**
         * The lines of the marked file this block occupies.
         */
        public Region markerRegion() {
            return new Region(startLine, endLine);
        }

        @Override
        public String toString() {
            return "block " + number + " (lines " + startLine + "-" + endLine + ")"
                + (hasBase() ? " [diff3]" : "");
        }
    }

    /**
     * A marked file, parsed.
     *
     * @param lines            every line of the file, line endings stripped
     * @param blocks           the conflict blocks, in file order
     * @param eol              the dominant line ending detected in the file
     * @param endsWithNewline  whether the file ended with a line ending
     */
    public record ParsedFile(List<String> lines, List<Block> blocks,
                             String eol, boolean endsWithNewline) {

        public ParsedFile {
            lines = List.copyOf(lines);
            blocks = List.copyOf(blocks);
        }

        /**
         * True when the file carries at least one well-formed conflict block.
         */
        public boolean hasConflicts() {
            return !blocks.isEmpty();
        }

        /**
         * The whole file with every block replaced by its ours side.
         */
        public String oursVersion() {
            return rebuild(block -> splitSide(block.ours()));
        }

        /**
         * The whole file with every block replaced by its theirs side.
         */
        public String theirsVersion() {
            return rebuild(block -> splitSide(block.theirs()));
        }

        /**
         * The whole file with every block replaced by its base side - only
         * possible when the file uses the diff3 style throughout, so a base is
         * never fabricated for a block that did not carry one.
         */
        public Optional<String> baseVersion() {
            boolean allCarryBase = !blocks.isEmpty() && blocks.stream().allMatch(Block::hasBase);
            if (!allCarryBase) {
                return Optional.empty();
            }
            return Optional.of(rebuild(block -> splitSide(block.base())));
        }

        /**
         * The file text as received (with mixed line endings normalised to
         * {@link #eol()}).
         */
        public String conflictedText() {
            return join(lines);
        }

        private String rebuild(Function<Block, List<String>> sideOf) {
            List<String> rebuilt = new ArrayList<>(lines.size());
            int index = 0;
            int blockAt = 0;
            while (index < lines.size()) {
                if (blockAt < blocks.size()
                    && index == blocks.get(blockAt).startLine() - 1) {
                    Block block = blocks.get(blockAt);
                    rebuilt.addAll(sideOf.apply(block));
                    index = block.endLine();          // the line after >>>>>>>
                    blockAt++;
                    continue;
                }
                rebuilt.add(lines.get(index));
                index++;
            }
            return join(rebuilt);
        }

        private String join(List<String> source) {
            if (source.isEmpty()) {
                // A side that deleted everything reconstructs to an empty file,
                // not to a lone trailing newline.
                return "";
            }
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < source.size(); i++) {
                if (i > 0) {
                    text.append(eol);
                }
                text.append(source.get(i));
            }
            if (endsWithNewline) {
                text.append(eol);
            }
            return text.toString();
        }

        private static List<String> splitSide(String side) {
            if (side == null || side.isEmpty()) {
                return List.of();
            }
            return List.of(side.split("\n", -1));
        }
    }

    /**
     * Parse a file's text. Never returns a file with conflicts silently read as
     * clean: a malformed marker sequence throws.
     *
     * @throws IllegalArgumentException when the markers are unbalanced or nested
     */
    public static ParsedFile parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("no text to parse");
        }
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        boolean endsWithNewline = text.endsWith("\n") || text.endsWith("\r");
        String[] rawLines = text.split("\n", -1);
        List<String> lines = new ArrayList<>(rawLines.length);
        for (String raw : rawLines) {
            lines.add(raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw);
        }
        if (endsWithNewline && !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }

        List<Block> blocks = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            if (!isOursStart(lines.get(index))) {
                index++;
                continue;
            }
            index = parseBlock(lines, index, blocks.size() + 1, eol, blocks);
        }
        return new ParsedFile(lines, blocks, eol, endsWithNewline);
    }

    /**
     * Consume one block starting at {@code start} (the {@code <<<<<<<} line) and
     * append it to {@code into}; return the index of the first line after the
     * block.
     */
    private static int parseBlock(List<String> lines, int start, int number, String eol,
                                  List<Block> into) {
        String oursLabel = label(lines.get(start), '<');
        List<String> base = null;
        List<String> ours = new ArrayList<>();
        List<String> theirs = null;
        String baseLabel = null;
        String theirsLabel = null;
        List<String> target = ours;

        int index = start + 1;
        while (true) {
            if (index >= lines.size()) {
                throw new IllegalArgumentException("unterminated conflict block starting at line "
                    + (start + 1) + ": the file ends before a '>>>>>>>' marker");
            }
            String line = lines.get(index);
            if (isOursStart(line)) {
                throw new IllegalArgumentException("nested conflict marker at line "
                    + (index + 1) + ": a block starting at line " + (start + 1)
                    + " is still open");
            }
            if (isBaseStart(line)) {
                if (theirs != null || base != null) {
                    throw new IllegalArgumentException("misplaced '|||||||' base marker at line "
                        + (index + 1) + ": the base section must come after the ours side"
                        + " and before the '=======' separator, exactly once");
                }
                // Diff3 order: ours lines were just collected; the lines that
                // follow, up to the separator, are the base side.
                baseLabel = label(line, '|');
                base = new ArrayList<>();
                target = base;
                index++;
                continue;
            }
            if (isSeparator(line)) {
                if (theirs != null) {
                    throw new IllegalArgumentException("duplicate '=======' separator at line "
                        + (index + 1) + " in the block starting at line " + (start + 1));
                }
                theirs = new ArrayList<>();
                target = theirs;
                index++;
                continue;
            }
            if (isTheirsEnd(line)) {
                if (theirs == null) {
                    throw new IllegalArgumentException("conflict block starting at line "
                        + (start + 1) + " ends at line " + (index + 1)
                        + " without a '=======' separator");
                }
                theirsLabel = label(line, '>');
                Block block = new Block(number, start + 1, index + 1,
                    oursLabel, theirsLabel, baseLabel,
                    base == null ? null : String.join("\n", base),
                    String.join("\n", ours),
                    String.join("\n", theirs),
                    String.join(eol, lines.subList(start, index + 1)));
                into.add(block);
                return index + 1;
            }
            target.add(line);
            index++;
        }
    }

    /**
     * The label after a marker's seven characters, trimmed. Git writes
     * {@code <<<<<<< ours}, {@code >>>>>>> 3f9a1c2 (title)} or nothing at all.
     */
    private static String label(String markerLine, char markerChar) {
        String rest = markerLine.substring(7).trim();
        return rest.isEmpty() ? defaultLabel(markerChar) : rest;
    }

    private static String defaultLabel(char markerChar) {
        return switch (markerChar) {
            case '<' -> "ours";
            case '>' -> "theirs";
            case '|' -> "base";
            default -> "";
        };
    }

    private static boolean isOursStart(String line) {
        return line.startsWith("<<<<<<<");
    }

    private static boolean isBaseStart(String line) {
        return line.startsWith("|||||||");
    }

    private static boolean isTheirsEnd(String line) {
        return line.startsWith(">>>>>>>");
    }

    /**
     * Git writes exactly seven {@code =} on the separator line; longer runs are
     * accepted because some tools emit them. A line of fewer than seven, or one
     * with leading whitespace, is content.
     */
    private static boolean isSeparator(String line) {
        if (line.length() < 7) {
            return false;
        }
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != '=') {
                return false;
            }
        }
        return true;
    }
}
