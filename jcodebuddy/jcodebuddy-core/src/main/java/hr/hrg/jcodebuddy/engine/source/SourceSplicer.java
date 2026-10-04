package hr.hrg.jcodebuddy.engine.source;

import java.util.List;

/**
 * Splices generated members into an existing type declaration's source text.
 *
 * <h3>Why text, and not a tree</h3>
 * <p>The JavaParser version added a member to a live AST and relied on
 * {@code LexicalPreservingPrinter} to write the file back without disturbing the rest of it. That
 * approach had a hole it could not close: the printer refuses some constructs, and an added
 * {@code default} modifier is one of them — it fails with <em>"Not supported keywordDEFAULT"</em> — so
 * the code carried a {@code catch} that fell back to {@code cu.toString()} and reformatted the whole
 * hand-written file. The very case this generator exists for therefore took the lossy path.</p>
 *
 * <p>An LST is immutable, so the member cannot be added to a tree at all. Splicing the generated text
 * in before the closing brace removes the problem rather than working around it: everything outside
 * the insertion point is byte-identical by construction, which is exactly the property the printer was
 * there to provide and could not always deliver.</p>
 *
 * <h3>What this does not do</h3>
 * <p>It does not remove or replace a member — recognition of an existing member happens before this is
 * called (DEC-020's shape-based check), and a member that is present is left alone. It also does not
 * reorder: generated members are appended in the order given, after whatever the type already
 * declared.</p>
 *
 * <h3>Limits, stated rather than discovered</h3>
 * <p>Brace matching is textual, so a brace inside a string literal or a comment within the type body
 * would confuse it. That is acceptable here and deliberately not defended against: the alternative is a
 * full lexical scanner, and this file is only ever handed source that has already been parsed cleanly
 * (the caller checks {@link SourceReader.Read#readable()} first), so the text is known to be
 * well-formed Java. A record or interface whose body contains a brace in a string literal would still
 * be spliced at the right place because the <em>closing</em> brace is found by depth, which such a
 * brace balances.</p>
 */
public final class SourceSplicer {

    private SourceSplicer() {
    }

    /**
     * The declaration named {@code typeName} plus {@code members} inserted before its closing brace.
     *
     * @param source   the file's text, or the declaration's own text
     * @param typeName the simple name of the type to extend
     * @param members  the members to append, in emission order
     * @param indent   one indentation step
     * @return the rewritten text, or {@code source} unchanged when the type or its body is not found
     */
    public static String withMembers(String source, String typeName, List<EntryPoint> members,
                              String indent) {
        if (source == null || typeName == null || members == null || members.isEmpty()) {
            return source;
        }
        int bodyOpen = bodyOpenOffset(source, typeName);
        if (bodyOpen < 0) {
            return source;
        }
        int bodyClose = matchingBrace(source, bodyOpen);
        if (bodyClose < 0) {
            return source;
        }

        // The member indentation is read from the type's own body rather than assumed: a nested type is
        // indented relative to its enclosing type, and a generated member must sit one step further in
        // than its owner. Deriving it from the closing brace's own line answers that in one step.
        String typeIndent = lineIndentBefore(source, bodyClose);
        String memberIndent = typeIndent + indent;

        String nl = System.lineSeparator();
        StringBuilder out = new StringBuilder(source.substring(0, bodyClose));
        if (!out.toString().endsWith(nl)) {
            out.append(nl);
        }
        for (EntryPoint member : members) {
            out.append(memberIndent).append(render(member)).append(nl);
        }
        out.append(typeIndent).append(source, bodyClose, source.length());
        return out.toString();
    }

    /**
     * One entry point as a {@code default} method.
     *
     * <p>The shape is the contract documented on {@code ViewInterfaceGenerator}, and it is emitted as
     * text because there is no tree to build it into: a {@code default} interface method returning a
     * new builder over {@code this}. The body is one statement by design — the generator's job is to
     * make the entry point exist, and the builder owns what happens next.</p>
     */
    private static String render(EntryPoint member) {
        return "public default " + member.builderType() + " " + member.methodName()
                + "() { return new " + member.builderType() + "(this); }";
    }

    /**
     * The offset of a type declaration named {@code typeName}, at a token boundary.
     *
     * <p>Requires a declaration keyword before the name ({@code interface}, {@code class},
     * {@code record}, {@code enum}) so a use of the type inside its own body — a self-referencing
     * method signature, say — cannot be mistaken for the declaration.</p>
     */
    private static int declarationNameOffset(String source, String typeName) {
        for (int index = source.indexOf(typeName); index >= 0; index = source.indexOf(typeName, index + 1)) {
            boolean leftFree = index == 0 || !Character.isJavaIdentifierPart(source.charAt(index - 1));
            int after = index + typeName.length();
            boolean rightFree = after >= source.length() || !Character.isJavaIdentifierPart(source.charAt(after));
            if (!leftFree || !rightFree) {
                continue;
            }
            int cursor = index - 1;
            while (cursor >= 0 && Character.isWhitespace(source.charAt(cursor))) {
                cursor--;
            }
            int wordEnd = cursor + 1;
            while (cursor >= 0 && Character.isJavaIdentifierPart(source.charAt(cursor))) {
                cursor--;
            }
            String keyword = source.substring(cursor + 1, wordEnd);
            if ("interface".equals(keyword) || "class".equals(keyword)
                    || "record".equals(keyword) || "enum".equals(keyword)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The offset of the brace matching the one at {@code open}, or {@code -1}.
     *
     * <p>Public because it is one of the four primitives a splice is built from, and a generator that walks a
     * declaration's own body — to recognise and strip the members it emitted before, say — needs the same brace
     * matching the splicer uses rather than a second copy of it. The copy is what step 3.0n deleted.</p>
     */
    public static int matchingBrace(String source, int open) {
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

    /**
     * Where the declaration named {@code typeName} opens its body — <strong>the one place a splice is
     * anchored</strong>, and the answer step 3.0n made shared.
     *
     * <p>From the name, the next {@code {} opens the body and a {@code ;} ends the header instead: a record
     * written {@code record Point(int x, int y);} has no body to put a member in, and the earlier version of this
     * method — an {@code indexOf('{', name)} — would have found the <em>next</em> declaration's brace and spliced
     * a member into the wrong type. That care came from the second implementation this method replaced, which is
     * the useful half of having had two: the survivor is now the more correct of the two.</p>
     *
     * @return the offset of the body's opening brace, or {@code -1} when the declaration or its body is absent
     */
    public static int bodyOpenOffset(String source, String typeName) {
        if (source == null || typeName == null) {
            return -1;
        }
        int name = declarationNameOffset(source, typeName);
        if (name < 0) {
            return -1;
        }
        for (int index = name + typeName.length(); index < source.length(); index++) {
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

    /**
     * Where the declaration named {@code typeName} closes its body, or {@code -1}.
     *
     * <p>The other half of the anchor: a member is inserted immediately before this offset, which is what makes
     * everything outside the insertion point byte-identical.</p>
     */
    public static int bodyCloseOffset(String source, String typeName) {
        int bodyOpen = bodyOpenOffset(source, typeName);
        return bodyOpen < 0 ? -1 : matchingBrace(source, bodyOpen);
    }

    /**
     * The whitespace at the start of the line containing {@code offset} — the indentation a generated member
     * inherits from its owner.
     *
     * <p>Public because a generator that anchors its own insertion (a record's builder members, say) needs the same
     * answer as the splicer, and reading the owner's own line is the only way not to assume a column: a nested type
     * is already indented.</p>
     */
    public static String lineIndentBefore(String source, int offset) {
        int lineStart = source.lastIndexOf('\n', Math.max(offset - 1, 0)) + 1;
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
