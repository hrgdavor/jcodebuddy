package hr.hrg.jcodebuddy.builder;

import hr.hrg.jcodebuddy.engine.source.SourceSplicer;

import java.util.List;

/**
 * Splices the builder members a record needs into the record's own text.
 *
 * <p><strong>This used to be a second {@code SourceSplicer} (step 3.0n collapsed the two).</strong> It carried its
 * own copies of the three primitives every splice needs — find the declaration, match its braces, read the owner's
 * indentation — and two of those copies were character-for-character identical to the engine's
 * ({@code matchingBrace}, {@code lineIndentBefore}), which is what two paths for one operation looks like in
 * practice. The name claimed to be the generic splicer while the class is really a <em>record-builder emitter</em>,
 * so it is named for its job now and it borrows the anchor from
 * {@link SourceSplicer#bodyOpenOffset}/{@link SourceSplicer#bodyCloseOffset}/{@link SourceSplicer#lineIndentBefore}.</p>
 *
 * <p><strong>The collapse went both ways, which is the useful part of having had two.</strong> The engine's anchor
 * scanned for {@code {} from the declaration name and would happily have taken the <em>next</em> declaration's brace
 * when the record has no body ({@code record Point(int x, int y);}), splicing members into the wrong type. This
 * class's version stopped at the {@code ;} instead, and that behaviour moved into the survivor before this one was
 * deleted.</p>
 *
 * <p>What stays here is what is not a splice question: which members this generator owns, how a previously emitted
 * copy is recognised and stripped so the operation is idempotent, and how the builder text is rendered.</p>
 */
final class RecordBuilderEmitter {

    private RecordBuilderEmitter() {
    }

    /**
     * {@code source} with a completed builder for the record named {@code recordName}.
     *
     * @param components the record's components, in declaration order
     * @param indent     one indentation step, as the engine was configured with
     */
    static String withBuilder(String source, String recordName, List<RecordBuilderProcessor.Component> components,
                              String indent) {
        int bodyOpen = SourceSplicer.bodyOpenOffset(source, recordName);
        if (bodyOpen < 0) {
            return source;
        }
        int bodyClose = SourceSplicer.bodyCloseOffset(source, recordName);
        if (bodyClose < 0) {
            return source;
        }

        // The record's own indentation is read rather than assumed: a nested record is indented one
        // level already, and a generated member must sit one step further in than its record, not at
        // a fixed column. The old engine re-indented the whole printed record and then counted lines
        // inside the builder to decide the depth; reading the closing brace's own indent answers the
        // same question in one line.
        String recordIndent = SourceSplicer.lineIndentBefore(source, bodyClose);
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
     * <p><strong>This is what makes the operation idempotent.</strong> The JavaParser version updated a builder in
     * place: it looked for the existing {@code Builder} class and reused its fields and setters, which is why it
     * needed node-identity bookkeeping and a stale-member sweep. Generating the builder instead replaces it, so the
     * previous copy — the {@code builder()} and {@code toBuilder()} methods and the nested {@code Builder} class —
     * has to be removed first, or the record ends up with two of each.</p>
     *
     * <p>Recognition is by name and structure, not by a marker comment: the members this generator emits are exactly
     * a static {@code builder()}, an instance {@code toBuilder()}, and a nested type named {@code Builder}. Anything
     * else the developer wrote is preserved verbatim, which is the property the old implementation got from mutating
     * only the nodes it knew.</p>
     */
    private static String stripPreviousBuilder(String body) {
        StringBuilder kept = new StringBuilder();
        int index = 0;
        while (index < body.length()) {
            // `index` is always at a declaration boundary: either the body start or just past a previous
            // declaration's terminator. Whichever comes first ends the declaration — `{` opens a body, `;` ends a
            // header — and an `@interface`-style brace inside an annotation has already been consumed as part of a
            // nested class by the matching-brace skip below.
            int open = body.indexOf('{', index);
            int semicolon = body.indexOf(';', index);
            int end;
            if (open < 0 && semicolon < 0) {
                end = body.length();
            } else if (open >= 0 && (semicolon < 0 || open < semicolon)) {
                end = SourceSplicer.matchingBrace(body, open);
                end = end < 0 ? body.length() : end;
            } else {
                end = semicolon;
            }

            // `end + 1` is clamped: a declaration that runs to the end of the body has no terminating brace or
            // semicolon, so `end` is already `body.length()` and the inclusive end would overrun by one. (Measured:
            // a one-character body threw `Range [0, 2) out of bounds for length 1`, which is exactly this
            // off-by-one.)
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
     * {@code Builder} is not swept away — only the two exact signatures the generator produces are.</p>
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
     * The retained body followed by the generated builder, with the record's closing brace put back at its own
     * indentation.
     *
     * <p>A record written on one line ({@code record User(String name) {}}) has an empty body. Appending the builder
     * to it directly would leave the closing brace on the builder's last line rather than on its own, so the
     * generated text always ends with a line break and the record's own indent — which puts the closing brace
     * exactly where a conventional multi-line record keeps it. A body that already ends with a line break gets no
     * second one.</p>
     *
     * @param recordIndent the indentation of the record's closing brace, which the generated text restores after the
     *                     builder so the brace stays at the record's own level
     */
    private static String joinBody(String builder, String recordIndent) {
        String nl = System.lineSeparator();
        return new StringBuilder().append(nl).append(builder).append(nl).append(recordIndent).toString();
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
}
