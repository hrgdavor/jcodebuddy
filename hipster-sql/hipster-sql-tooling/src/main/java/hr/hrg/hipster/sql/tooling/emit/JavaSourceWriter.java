package hr.hrg.hipster.sql.tooling.emit;

/**
 * A line-oriented writer for generated Java text.
 *
 * <p>The contract exists because JavaPoet could not express this generator's layout: it fixes one
 * blank line between members, reflows annotations and emits {@code \n} only. Here the caller asks
 * for exactly what it wants — {@link #blank(int)} writes exactly {@code count} empty lines, no
 * collapsing and no "at most one" rule — and the writer owns the line ending, indentation and the
 * single final newline.</p>
 */
public final class JavaSourceWriter {

    /** The line ending the generated file will have. LF is the default for this project. */
    public enum Eol {
        LF("\n"),
        CRLF("\r\n");

        private final String text;

        Eol(String text) {
            this.text = text;
        }
    }

    private final StringBuilder out = new StringBuilder();
    private final String indentUnit;
    private final String eol;
    private int depth;
    private boolean lineStart = true;

    public JavaSourceWriter(String indentUnit, Eol eol) {
        this.indentUnit = indentUnit;
        this.eol = eol.text;
    }

    /** The default writer: four spaces, LF. */
    public static JavaSourceWriter defaultWriter() {
        return new JavaSourceWriter("    ", Eol.LF);
    }

    public JavaSourceWriter line(String text) {
        if (!text.isEmpty()) {
            writeIndent();
            out.append(text.stripTrailing());
        }
        out.append(eol);
        lineStart = true;
        return this;
    }

    public JavaSourceWriter blank() {
        return blank(1);
    }

    /** Exactly {@code count} empty lines, after the current line. */
    public JavaSourceWriter blank(int count) {
        for (int i = 0; i < count; i++) {
            out.append(eol);
        }
        lineStart = true;
        return this;
    }

    public JavaSourceWriter indent() {
        depth++;
        return this;
    }

    public JavaSourceWriter unindent() {
        if (depth == 0) {
            throw new IllegalStateException("unindent below zero");
        }
        depth--;
        return this;
    }

    /** Text copied verbatim: annotation source, an expression, a fragment. Never re-indented. */
    public JavaSourceWriter raw(String text) {
        out.append(text);
        lineStart = text.endsWith(eol) || text.endsWith("\n") || text.endsWith("\r\n");
        return this;
    }

    /** The file's text, with exactly one final line ending and no trailing whitespace. */
    public String finish() {
        String text = out.toString().stripTrailing();
        return text.isEmpty() ? "" : text + eol;
    }

    private void writeIndent() {
        if (!lineStart) {
            return;
        }
        for (int i = 0; i < depth; i++) {
            out.append(indentUnit);
        }
        lineStart = false;
    }
}
