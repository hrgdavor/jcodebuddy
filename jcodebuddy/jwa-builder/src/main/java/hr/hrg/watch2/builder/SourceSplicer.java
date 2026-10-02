// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.builder;

import java.util.List;

/**
 * Splices a generated builder into a record's source text.
 *
 * <h3>Why text, and not a tree</h3>
 * <p>The JavaParser implementation built the builder by mutating an AST — adding methods, removing
 * stale fields and setters, then reordering members — and depended on
 * {@code LexicalPreservingPrinter} to write it back without disturbing the rest of the file. The LST
 * is immutable, so every one of those operations is unavailable; more importantly, they were never
 * what the feature <em>is</em>. The feature is "a record gains a builder", and the most direct
 * expression of that is to generate the builder's text and put it in the record.</p>
 *
 * <p>Doing it in text also removes the two failure modes the mutation version had to work around:</p>
 * <ul>
 *   <li>no node-identity bookkeeping is needed to stop a field being emitted twice, because the
 *       builder's text is produced once from one component list;</li>
 *   <li>no post-hoc indentation pass is needed, because the indent is applied as the text is built
 *       (the old engine printed the record and then re-indented it line by line, tracking whether it
 *       was inside the builder with a boolean).</li>
 * </ul>
 *
 * <p>Everything outside the record stays byte-identical, which is the property the printer was there
 * to protect and which this approach gets for free.</p>
 *
 * <h3>The output contract</h3>
 * <p>Exact, because it is a user-visible edit applied to the developer's file and it is pinned by
 * {@code RecordBuilderFormattingTest}:</p>
 * <pre>{@code
 * public record User(String name) {
 *     public static Builder builder() { return new Builder(); }
 *     public Builder toBuilder() { return new Builder().name(this.name()); }
 *     public static class Builder {
 *         private String name;
 *         public Builder name(String name) { this.name = name; return this; }
 *         public User build() { return new User(name); }
 *     }
 * }</pre>
 * <ul>
 *   <li>{@code builder()}, {@code toBuilder()} and the {@code Builder} class are placed in that order,
 *       after whatever the record already declared — so a record's own methods and javadoc are
 *       preserved and the generated members append.</li>
 *   <li>The builder's own members are grouped: fields, then {@code build()}, then one setter per
 *       component. The old implementation went to some trouble to reproduce that grouping while
 *       reusing existing nodes; generating it makes the grouping the obvious default.</li>
 *   <li>{@code toBuilder()} chains {@code .component(this.component())} for every component, in
 *       declaration order.</li>
 * </ul>
 */
final class SourceSplicer {

    private SourceSplicer() {
    }

    /**
     * {@code source} with a completed builder for the record named {@code recordName}.
     *
     * @param components the record's components, in declaration order
     * @param indent     one indentation step, as the engine was configured with
     */
    static String withBuilder(String source, String recordName, List<RecordBuilderProcessor.Component> components,
                              String indent) {
        int bodyOpen = bodyOpenBrace(source, recordName);
        if (bodyOpen < 0) {
            return source;
        }
        int bodyClose = matchingBrace(source, bodyOpen);
        if (bodyClose < 0) {
            return source;
        }

        // The record's own indentation is read rather than assumed: a nested record is indented one
        // level already, and a generated member must sit one step further in than its record, not at
        // a fixed column. The old engine re-indented the whole printed record and then counted lines
        // inside the builder to decide the depth; reading the closing brace's own indent answers the
        // same question in one line.
        String recordIndent = lineIndentBefore(source, bodyClose);
        String memberIndent = recordIndent + indent;
        String builderIndent = memberIndent;
        String builderMemberIndent = builderIndent + indent;

        String body = source.substring(bodyOpen + 1, bodyClose);
        String builder = renderBuilder(recordName, components, memberIndent, builderMemberIndent);

        return source.substring(0, bodyOpen + 1)
                + stripPreviousBuilder(body)
                + generatedMembers(recordName, components, memberIndent)
                + joinBody(builder, recordIndent)
                + source.substring(bodyClose);
    }

    /**
     * The record body with any members this generator owns removed.
     *
     * <p><strong>This is what makes the operation idempotent.</strong> The JavaParser version updated a
     * builder in place: it looked for the existing {@code Builder} class and reused its fields and
     * setters, which is why it needed node-identity bookkeeping and a stale-member sweep. Generating the
     * builder instead replaces it, so the previous copy — the {@code builder()} and {@code toBuilder()}
     * methods and the nested {@code Builder} class — has to be removed first, or the record ends up with
     * two of each.</p>
     *
     * <p>Recognition is by name and structure, not by a marker comment: the members this generator emits
     * are exactly a static {@code builder()}, an instance {@code toBuilder()}, and a nested type named
     * {@code Builder}. Anything else the developer wrote is preserved verbatim, which is the property the
     * old implementation got from mutating only the nodes it knew.</p>
     *
     * @param memberIndent the indentation generated members use, which is how a declaration's own line is
     *                     recognised as belonging to this generator rather than being a nested statement
     */
    private static String stripPreviousBuilder(String body) {
        StringBuilder kept = new StringBuilder();
        int index = 0;
        while (index < body.length()) {
            // `index` is always at a declaration boundary: either the body start or just past a
            // previous declaration's terminator. Whichever comes first ends the declaration — `{` opens
            // a body, `;` ends a header — and an `@interface`-style brace inside an annotation has
            // already been consumed as part of a nested class by the matching-brace skip below.
            int open = body.indexOf('{', index);
            int semicolon = body.indexOf(';', index);
            int end;
            boolean block;
            if (open < 0 && semicolon < 0) {
                end = body.length();
                block = false;
            } else if (open >= 0 && (semicolon < 0 || open < semicolon)) {
                end = matchingBrace(body, open);
                end = end < 0 ? body.length() : end;
                block = true;
            } else {
                end = semicolon;
                block = false;
            }

            // `end + 1` is clamped: a declaration that runs to the end of the body has no terminating
            // brace or semicolon, so `end` is already `body.length()` and the inclusive end would
            // overrun by one. (Measured: a one-character body threw `Range [0, 2) out of bounds for
            // length 1`, which is exactly this off-by-one.)
            String declaration = body.substring(index, Math.min(end + 1, body.length()));
            if (isGeneratedEntryPoint(declaration.trim()) || isBuilderDeclaration(declaration.trim())) {
                // Dropped: this generator owns it and is about to emit a fresh one.
            } else {
                kept.append(declaration);
            }
            index = end + 1;
        }
        return kept.toString();
    }

    /**
     * Whether a declaration is one of the two entry-point methods this generator emits.
     *
     * <p>Matched on the declaration's own leading text, so a developer's method that merely returns a
     * {@code Builder} is not swept away — only the two exact signatures the generator produces are.
     * </p>
     */
    private static boolean isGeneratedEntryPoint(String declaration) {
        return declaration.startsWith("public static Builder builder()")
                || declaration.startsWith("public Builder toBuilder()");
    }

    /** Whether a declaration is the nested {@code Builder} class this generator owns. */
    private static boolean isBuilderDeclaration(String declaration) {
        return declaration.startsWith("public static class Builder")
                || declaration.startsWith("static class Builder")
                || declaration.startsWith("public class Builder");
    }

    /** The two entry-point methods every completed record gains. */
    private static String generatedMembers(String recordName, List<RecordBuilderProcessor.Component> components,
                                           String indent) {
        String nl = System.lineSeparator();
        StringBuilder out = new StringBuilder();
        out.append(nl).append(indent).append("public static Builder builder() { return new Builder(); }");
        out.append(nl).append(indent).append("public Builder toBuilder() { return new Builder()");
        for (RecordBuilderProcessor.Component component : components) {
            out.append('.').append(component.name())
                    .append("(this.").append(component.name()).append("())");
        }
        out.append("; }");
        return out.toString();
    }

    /**
     * The record body as it was, followed by the generated builder.
     *
     * <p>A record written on one line ({@code record User(String name) {}}) has an empty body.
     * Appending the builder to it directly would leave the closing brace on the builder's last line
     * rather than on its own, so the generated text always ends with a line break and the record's
     * own indent — which puts the closing brace exactly where a conventional multi-line record keeps
     * it. A body that already ends with a line break gets no second one.</p>
     *
     * @param recordIndent the indentation of the record's closing brace, which the generated text
     *                     restores after the builder so the brace stays at the record's own level
     */
    /**
     * The retained body followed by the generated builder, with the record's closing brace put back at
     * its own indentation.
     *
     * <p>The retained body is whatever {@link #stripPreviousBuilder} kept. It is inserted verbatim —
     * including its leading whitespace — so a record's own methods, javadoc and annotations survive byte
     * for byte, and the generated members are appended after them.</p>
     *
     * @param recordIndent the indentation of the record's closing brace
     */
    private static String joinBody(String builder, String recordIndent) {
        String nl = System.lineSeparator();
        StringBuilder out = new StringBuilder();
        out.append(nl).append(builder).append(nl).append(recordIndent);
        return out.toString();
    }

    /** The {@code Builder} class, its fields, its {@code build()} and one setter per component. */
    private static String renderBuilder(String recordName, List<RecordBuilderProcessor.Component> components,
                                        String builderIndent, String memberIndent) {
        String nl = System.lineSeparator();
        StringBuilder out = new StringBuilder();
        out.append(builderIndent).append("public static class Builder {");
        for (RecordBuilderProcessor.Component component : components) {
            out.append(nl).append(memberIndent)
                    .append("private ").append(component.type()).append(' ').append(component.name()).append(';');
        }
        out.append(nl).append(memberIndent).append("public ").append(recordName).append(" build() { return new ")
                .append(recordName).append('(')
                .append(String.join(", ", components.stream().map(RecordBuilderProcessor.Component::name).toList()))
                .append("); }");
        for (RecordBuilderProcessor.Component component : components) {
            out.append(nl).append(memberIndent)
                    .append("public Builder ").append(component.name())
                    .append('(').append(component.type()).append(' ').append(component.name())
                    .append(") { this.").append(component.name()).append(" = ").append(component.name())
                    .append("; return this; }");
        }
        out.append(nl).append(builderIndent).append('}');
        return out.toString();
    }

    /**
     * The offset of the record's body-opening brace.
     *
     * <p>Found from the record's name, so an annotation or a preceding declaration that also contains
     * a brace cannot be mistaken for the body. A record with no body at all (a compact declaration
     * ending in {@code ;}) has no place to put a builder and yields {@code -1}.</p>
     */
    private static int bodyOpenBrace(String source, String recordName) {
        int name = indexOfDeclarationName(source, recordName);
        if (name < 0) {
            return -1;
        }
        // From the name, the next `{` or `;` ends the header: `{` opens the body, `;` is a record
        // with no body.
        for (int index = name + recordName.length(); index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                return index;
            }
            if (character == ';') {
                return -1;
            }
        }
        return -1;
    }

    /** The offset of a declaration named {@code recordName}, at a token boundary. */
    private static int indexOfDeclarationName(String source, String recordName) {
        for (int index = source.indexOf(recordName); index >= 0; index = source.indexOf(recordName, index + 1)) {
            boolean leftFree = index == 0 || !Character.isJavaIdentifierPart(source.charAt(index - 1));
            int after = index + recordName.length();
            boolean rightFree = after >= source.length() || !Character.isJavaIdentifierPart(source.charAt(after));
            if (!leftFree || !rightFree) {
                continue;
            }
            // Require the declaration keyword before the name, so a use of the type inside its own
            // header (`record Node(Node next)`) cannot be picked as the declaration.
            int cursor = index - 1;
            while (cursor >= 0 && Character.isWhitespace(source.charAt(cursor))) {
                cursor--;
            }
            int wordEnd = cursor + 1;
            while (cursor >= 0 && Character.isJavaIdentifierPart(source.charAt(cursor))) {
                cursor--;
            }
            String keyword = source.substring(cursor + 1, wordEnd);
            if ("record".equals(keyword)) {
                return index;
            }
        }
        return -1;
    }

    /** The offset of the brace matching the one at {@code open}, or {@code -1}. */
    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    /** The whitespace at the start of the line containing {@code offset}. */
    private static String lineIndentBefore(String source, int offset) {
        int lineStart = source.lastIndexOf('\n', offset - 1) + 1;
        StringBuilder indent = new StringBuilder();
        for (int index = lineStart; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character != ' ' && character != '\t') {
                break;
            }
            indent.append(character);
        }
        return indent.toString();
    }
}
