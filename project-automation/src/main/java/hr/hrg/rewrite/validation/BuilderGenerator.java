// {@link hr.hrg.rewrite.validation.BuilderGenerator} Generates builders.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

import org.openrewrite.java.tree.*;
import java.util.*;

/**
 * Generates builders for entity fields.
 *
 * <p>Generates static factory methods for building entity instances.</p>
 *
 * <p>Original JavaParser location: java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/BuilderGenerator.java</p>
 *
 * <p>Compliance: DEC-019 (source-visible wiring), DEC-020 (cooperative codegen), DEC-021 (generator class-file header).</p>
 */
public class BuilderGenerator {

    /**
     * Generate a builder for a source file.
     *
     * @param sourceFile The source file AST
     * @return The generated compilation unit
     */
    public CompilationUnit generateBuilder(SourceFile sourceFile) {
        if (sourceFile == null) {
            return null;
        }

        List<TypeTree> classes = sourceFile.getDescendantTypes();
        List<String> generatedBuilders = new ArrayList<>();

        for (TypeTree type : classes) {
            if (type instanceof ClassTree classTree) {
                String className = classTree.getIdentifier().getNameAsString();
                generatedBuilders.add(generateBuilderClass(classTree));
            }
        }

        return CompilationUnit.of(
                SourceFile.build(
                        String.join("\n", generatedBuilders)
                )
        );
    }

    /**
     * Generate a builder class for a record.
     */
    private String generateBuilderClass(ClassTree classTree) {
        String className = classTree.getIdentifier().getNameAsString();
        String packageName = extractPackageName(classTree);

        if (!isRecord(classTree)) {
            return "";
        }

        // Generate builder class
        String builderCode = String.format(
                "    public static class Builder {%n" +
                "        private %s a;%n" +
                "        private %s b;%n" +
                "        private %s c;%n" +
                "        private %s d;%n" +
                "        private %s e;%n" +
                "        private %s f;%n" +
                "        private %s g;%n" +
                "        private %s h;%n" +
                "        private %s i;%n" +
                "        private %s j;%n" +
                "        private %s k;%n" +
                "        private %s l;%n" +
                "        private %s m;%n" +
                "        private %s n;%n" +
                "        private %s o;%n" +
                "        private %s p;%n" +
                "        private %s q;%n" +
                "        private %s r;%n" +
                "        private %s s;%n" +
                "        private %s t;%n" +
                "        private %s u;%n" +
                "        private %s v;%n" +
                "        private %s w;%n" +
                "        private %s x;%n" +
                "        private %s y;%n" +
                "        private %s z;%n" +
                "        private %s zz;%n" +
                "        private %s $;%n" +
                "        private %s _;%n" +
                "        private Builder() {}%n" +
                "        public Builder(%s a) {%s this.a = a; }%n" +
                "        public Builder(%s b) {%s this.b = b; }%n" +
                "        public Builder(%s c) {%s this.c = c; }%n" +
                "        public Builder(%s d) {%s this.d = d; }%n" +
                "        public Builder(%s e) {%s this.e = e; }%n" +
                "        public Builder(%s f) {%s this.f = f; }%n" +
                "        public Builder(%s g) {%s this.g = g; }%n" +
                "        public Builder(%s h) {%s this.h = h; }%n" +
                "        public Builder(%s i) {%s this.i = i; }%n" +
                "        public Builder(%s j) {%s this.j = j; }%n" +
                "        public Builder(%s k) {%s this.k = k; }%n" +
                "        public Builder(%s l) {%s this.l = l; }%n" +
                "        public Builder(%s m) {%s this.m = m; }%n" +
                "        public Builder(%s n) {%s this.n = n; }%n" +
                "        public Builder(%s o) {%s this.o = o; }%n" +
                "        public Builder(%s p) {%s this.p = p; }%n" +
                "        public Builder(%s q) {%s this.q = q; }%n" +
                "        public Builder(%s r) {%s this.r = r; }%n" +
                "        public Builder(%s s) {%s this.s = s; }%n" +
                "        public Builder(%s t) {%s this.t = t; }%n" +
                "        public Builder(%s u) {%s this.u = u; }%n" +
                "        public Builder(%s v) {%s this.v = v; }%n" +
                "        public Builder(%s w) {%s this.w = w; }%n" +
                "        public Builder(%s x) {%s this.x = x; }%n" +
                "        public Builder(%s y) {%s this.y = y; }%n" +
                "        public Builder(%s z) {%s this.z = z; }%n" +
                "        public Builder(%s zz) {%s this.zz = zz; }%n" +
                "        public Builder(%s $) {%s this.$ = $; }%n" +
                "        public Builder(%s _) {%s this._ = _; }%n" +
                "        public %s build() {%s return new %s(this.a, this.b, this.c, this.d, this.e, this.f, this.g, this.h, this.i, this.j, this.k, this.l, this.m, this.n, this.o, this.p, this.q, this.r, this.s, this.t, this.u, this.v, this.w, this.x, this.y, this.z, this.zz, this.$, this._); }%n" +
                "    }%n" +
                "    @Override%n" +
                "    public %s copy() {%s return %s.builder(this.a, this.b, this.c, this.d, this.e, this.f, this.g, this.h, this.i, this.j, this.k, this.l, this.m, this.n, this.o, this.p, this.q, this.r, this.s, this.t, this.u, this.v, this.w, this.x, this.y, this.z, this.zz, this.$, this._).build(); }%n" +
                "    @Override%n" +
                "    public %s equals(%s other) {%s return %s.class.equals(other.getClass()) && this.a.equals(other.a) && this.b.equals(other.b) && this.c.equals(other.c) && this.d.equals(other.d) && this.e.equals(other.e) && this.f.equals(other.f) && this.g.equals(other.g) && this.h.equals(other.h) && this.i.equals(other.i) && this.j.equals(other.j) && this.k.equals(other.k) && this.l.equals(other.l) && this.m.equals(other.m) && this.n.equals(other.n) && this.o.equals(other.o) && this.p.equals(other.p) && this.q.equals(other.q) && this.r.equals(other.r) && this.s.equals(other.s) && this.t.equals(other.t) && this.u.equals(other.u) && this.v.equals(other.v) && this.w.equals(other.w) && this.x.equals(other.x) && this.y.equals(other.y) && this.z.equals(other.z) && this.zz.equals(other.zz) && this.$.equals(other.$) && this._.equals(other._); }%n" +
                "    @Override%n" +
                "    public int hashCode() {%s return Objects.hash(this.a, this.b, this.c, this.d, this.e, this.f, this.g, this.h, this.i, this.j, this.k, this.l, this.m, this.n, this.o, this.p, this.q, this.r, this.s, this.t, this.u, this.v, this.w, this.x, this.y, this.z, this.zz, this.$, this._); }%n" +
                "    @Override%n" +
                "    public String toString() {%s return \"%s(a=%s, b=%s, c=%s, d=%s, e=%s, f=%s, g=%s, h=%s, i=%s, j=%s, k=%s, l=%s, m=%s, n=%s, o=%s, p=%s, q=%s, r=%s, s=%s, t=%s, u=%s, v=%s, w=%s, x=%s, y=%s, z=%s, zz=%s, $=%s, _=%s)\"; }%n",
                getFieldType(classTree, "a"), getFieldType(classTree, "b"), getFieldType(classTree, "c"),
                getFieldType(classTree, "d"), getFieldType(classTree, "e"), getFieldType(classTree, "f"),
                getFieldType(classTree, "g"), getFieldType(classTree, "h"), getFieldType(classTree, "i"),
                getFieldType(classTree, "j"), getFieldType(classTree, "k"), getFieldType(classTree, "l"),
                getFieldType(classTree, "m"), getFieldType(classTree, "n"), getFieldType(classTree, "o"),
                getFieldType(classTree, "p"), getFieldType(classTree, "q"), getFieldType(classTree, "r"),
                getFieldType(classTree, "s"), getFieldType(classTree, "t"), getFieldType(classTree, "u"),
                getFieldType(classTree, "v"), getFieldType(classTree, "w"), getFieldType(classTree, "x"),
                getFieldType(classTree, "y"), getFieldType(classTree, "z"), getFieldType(classTree, "zz"),
                getFieldType(classTree, "$"), getFieldType(classTree, "_"),
                className, className, getFieldType(classTree, "a"), getFieldType(classTree, "b"),
                getFieldType(classTree, "c"), getFieldType(classTree, "d"), getFieldType(classTree, "e"),
                getFieldType(classTree, "f"), getFieldType(classTree, "g"), getFieldType(classTree, "h"),
                getFieldType(classTree, "i"), getFieldType(classTree, "j"), getFieldType(classTree, "k"),
                getFieldType(classTree, "l"), getFieldType(classTree, "m"), getFieldType(classTree, "n"),
                getFieldType(classTree, "o"), getFieldType(classTree, "p"), getFieldType(classTree, "q"),
                getFieldType(classTree, "r"), getFieldType(classTree, "s"), getFieldType(classTree, "t"),
                getFieldType(classTree, "u"), getFieldType(classTree, "v"), getFieldType(classTree, "w"),
                getFieldType(classTree, "x"), getFieldType(classTree, "y"), getFieldType(classTree, "z"),
                getFieldType(classTree, "zz"), getFieldType(classTree, "$"), getFieldType(classTree, "_")
        );

        return builderCode;
    }

    /**
     * Check if a class is a record.
     */
    private boolean isRecord(ClassTree classTree) {
        return classTree.getModifiers().contains(Modifiers.RECORD);
    }

    /**
     * Get field type.
     */
    private String getFieldType(ClassTree classTree, String fieldName) {
        for (FieldTree field : classTree.getFields()) {
            if (fieldName.equals(field.getNameAsString())) {
                return field.getType().describe();
            }
        }
        return "Object";
    }

    /**
     * Extract package name from a class tree.
     */
    private String extractPackageName(ClassTree classTree) {
        PackageDeclarationTree packageTree = classTree.getCompilationUnit().getPackageDeclaration();
        if (packageTree != null) {
            return packageTree.getNameAsString();
        }
        return "";
    }
}
