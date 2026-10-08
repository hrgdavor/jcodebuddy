// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.tools;

import hr.hrg.jcodebuddy.engine.source.SourceReader;
import hr.hrg.jcodebuddy.engine.source.TreeQueries;
import org.openrewrite.java.tree.J;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A rename recipe, implemented the repository's way (plan step 7.5, DEC-030): read through
 * {@link SourceReader}, query through {@link TreeQueries}, then <b>splice the text</b> — no second parser, and no
 * printing a tree back over hand-written code.
 *
 * <h2>Why the tree is involved at all, when the edit is a text replacement</h2>
 *
 * <p>A textual rename is the operation this repository is most suspicious of, and rightly: the same characters occur
 * in string literals, in comments, inside longer identifiers, and in a name that merely looks similar. So the tree is
 * the <b>authority on how many times the name is really used</b>: every {@link J.Identifier} carrying the old name is
 * counted, the text is spliced with identifier boundaries, and the two counts must be <b>equal</b>. When they are not,
 * the difference is exactly the case that would have been renamed wrongly — a literal, a comment, or a substring —
 * and the tool <b>refuses and says which</b> instead of producing a file that compiles but means something else.
 *
 * <p>That refusal is the point of the prototype: an agent-run rename is applied without a person reading the diff, so
 * the tool has to be able to say "I am not sure" rather than guess.
 *
 * <p><b>Positions still come from javac, never from the tree</b> (the rule DEC-030 records for this engine): the tree
 * answers <em>what</em> identifiers exist, and the splice works on the source text the reader was given. Nothing here
 * reprints a tree, which is what keeps the hand-written parts of the file byte-identical.
 */
public class RenameMemberTool implements ActionTool {

    private final String from;
    private final String to;

    /**
     * @param from the identifier to rename, or {@code null}/blank for a tool that is registered but does nothing
     *             until a project configures it — the honest state of a toolset entry that names a tool without
     *             giving it anything to do
     * @param to   what it becomes; must differ from {@code from} to be applicable
     */
    public RenameMemberTool(String from, String to) {
        this.from = from == null ? null : from.trim();
        this.to = to == null ? null : to.trim();
    }

    /** A tool that is in the menu and configured to do nothing, used when a project names no rename. */
    public RenameMemberTool() {
        this(null, null);
    }

    @Override
    public String getName() {
        return "rename";
    }

    /**
     * Applicable exactly where it can act: a configured rename, a file this engine can read, and an identifier of
     * that name in the tree.
     *
     * <p>An unparseable file is <b>not</b> applicable rather than an error, because the watcher calls this on every
     * file change: a half-written file is normal during editing, and refusing quietly is what keeps that from filling
     * the audit log with failures nobody can act on.
     */
    @Override
    public boolean isApplicable(ToolContext context) {
        if (from == null || from.isEmpty() || to == null || to.isEmpty() || from.equals(to)) {
            return false;
        }
        return identifiersNamed(context).count > 0;
    }

    @Override
    public List<FileChange> execute(ToolContext context) {
        Path path = context.getFilePath();
        SourceReader.Read read;
        String source;
        try {
            read = SourceReader.read(path);
            source = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read " + path + ": " + failure.getMessage(), failure);
        }
        if (read.unparseable()) {
            throw new IllegalStateException("refusing to rename in " + path
                    + ": the source does not parse, so the tree cannot say how many uses there are");
        }
        Occurrences occurrences = identifiersNamed(context);
        if (occurrences.count == 0) {
            throw new IllegalStateException("refusing to rename in " + path + ": no identifier named '" + from + "'");
        }
        if (occurrences.namedTo > 0) {
            // A rename onto a name the file already uses is a collision the compiler would report, and this tool does
            // not emit code it cannot vouch for (the same rule the generators follow).
            throw new IllegalStateException("refusing to rename '" + from + "' to '" + to + "' in " + path
                    + ": the file already declares or uses that name " + occurrences.namedTo + " time(s)");
        }

        int replaced = countIdentifierOccurrences(source, from);
        if (replaced != occurrences.count) {
            throw new IllegalStateException("refusing to rename '" + from + "' in " + path + ": the tree sees "
                    + occurrences.count + " identifier(s) and the text has " + replaced + " occurrence(s). The "
                    + "difference is a string literal, a comment or a longer identifier that a plain textual rename "
                    + "would have changed as well.");
        }
        String renamed = spliceIdentifiers(source, from, to);
        return List.of(new FileChange(path, renamed, ChangeType.CHANGE));
    }

    /** How many identifiers the tree carries for each name. */
    private record Occurrences(int count, int namedTo) {
    }

    private Occurrences identifiersNamed(ToolContext context) {
        if (from == null || from.isEmpty()) {
            return new Occurrences(0, 0);
        }
        SourceReader.Read read;
        try {
            read = SourceReader.read(context.getFilePath());
        } catch (IOException | RuntimeException failure) {
            return new Occurrences(0, 0);
        }
        if (read.unparseable() || read.unit() == null) {
            return new Occurrences(0, 0);
        }
        int count = 0;
        int namedTo = 0;
        for (J.Identifier identifier : TreeQueries.findAll(read.unit(), J.Identifier.class)) {
            if (from.equals(identifier.getSimpleName())) {
                count++;
            } else if (to != null && to.equals(identifier.getSimpleName())) {
                namedTo++;
            }
        }
        return new Occurrences(count, namedTo);
    }

    /**
     * How many whole-word occurrences the text has, using Java's identifier boundaries.
     *
     * <p>{@code $} is a legal identifier character in Java, so it is part of the boundary class: renaming {@code name}
     * in a file that also has {@code name$1} must not touch it.
     */
    static int countIdentifierOccurrences(String source, String identifier) {
        return occurrences(source, identifier).count;
    }

    /** Replace every whole-word occurrence of {@code from} with {@code to}, returning the spliced text. */
    static String spliceIdentifiers(String source, String from, String to) {
        return occurrences(source, from).splicedTo(to);
    }

    private static Spliced occurrences(String source, String identifier) {
        StringBuilder result = new StringBuilder(source.length());
        int count = 0;
        int index = 0;
        while (index < source.length()) {
            int hit = source.indexOf(identifier, index);
            if (hit < 0) {
                break;
            }
            boolean leftBoundary = hit == 0 || !isIdentifierPart(source.charAt(hit - 1));
            int after = hit + identifier.length();
            boolean rightBoundary = after >= source.length() || !isIdentifierPart(source.charAt(after));
            if (leftBoundary && rightBoundary) {
                count++;
                result.append(source, index, hit);
                result.append('\u0000');   // placeholder for the new name, applied by splicedTo
                index = after;
            } else {
                result.append(source, index, after);
                index = after;
            }
        }
        result.append(source, index, source.length());
        return new Spliced(result.toString(), count);
    }

    private static boolean isIdentifierPart(char character) {
        return Character.isJavaIdentifierPart(character) || character == '$';
    }

    private record Spliced(String text, int count) {
        String splicedTo(String newName) {
            return text.replace("\u0000", newName);
        }
    }
}
