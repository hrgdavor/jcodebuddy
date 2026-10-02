package hr.hrg.hipster.entity.tooling.meta;

import java.util.List;

/**
 * One field of a view, as the generator resolves it.
 *
 * @param name        the accessor name, which is also the enum constant name and the JSON/column name
 * @param type        the declared type as written in source, e.g. {@code Map<String, List<Long>>}
 * @param fieldKind   the {@code FieldKind} name from {@code @FieldSource}, or {@code null} for an
 *                    unannotated accessor (which is {@code COLUMN}, i.e. writable — S1)
 * @param column      the {@code @FieldSource(column = …)} override, or {@code null}
 * @param relation    the {@code @FieldSource(relation = …)} value, or {@code null}
 * @param expression  the {@code @FieldSource(expression = …)} value, or {@code null}
 * @param lineNumber  the accessor's source line, or {@code -1}
 * @param constraints the Bean Validation constraints declared on the accessor, in declaration order
 *                    (plan.dsflash § 12.3/7.9). Empty for an unconstrained field, and empty for the
 *                    synthetic {@code id} and tombstone placeholders the generator creates, which have
 *                    no accessor to carry an annotation.
 * @param typeImports the imports the declared type needs in a <em>generated</em> file
 *                    (plan.dsflash § 8.2/3.6). The type text is emitted as the author wrote it, so a
 *                    type the author imported — `import a.hr.Node;` then `Node head()` — reaches the
 *                    emitter as the bare `Node` and does not compile unless the generated file imports
 *                    it too. Resolved where the accessor is parsed, because that is the only place the
 *                    declaring compilation unit's import table is in scope: an inherited accessor's
 *                    type text comes from its own supertype's source, so resolving a whole view's
 *                    property list against the view's own imports would be wrong.
 * @param sourcePath  the file that declares this accessor, <strong>relative to the module root</strong>
 *                    (e.g. {@code src/main/java/a/b/Person.java}), or {@code null} for a property the
 *                    generator synthesised (a tombstone, the inherited {@code id}). Module-relative and
 *                    not project-relative on purpose: in a multi-module build the module is what a
 *                    consumer — a report, an IDE plugin, this module's own `.jcodebuddy/` — resolves
 *                    against, and a project-relative path would be wrong in every module but one. The
 *                    accessor's own file matters because an inherited field is declared in a
 *                    <em>different</em> interface than the view that exposes it.
 * @param locations   the interface-side half of "where is this field": the declaring accessor and, when
 *                    present, the {@code @FieldSource} line above it. Recorded while the accessor's AST
 *                    is in hand, because neither line can be recovered from {@code lineNumber} — that is
 *                    the declaration <em>start</em>, annotations included, which is a different number
 *                    from the accessor's own line (16 vs 17 for {@code PersonSummary.age}). This is a
 *                    model-only field: the JSON does not carry it, because {@code views[].fields[].at}
 *                    already holds the interface-side lines for every field the view declares, and
 *                    {@code properties[]} stays the "own declaration" record (DEC-028 § 2.6)
 */
public record Property(String name, String type, String fieldKind, String column, String relation,
                       String expression, int lineNumber, List<FieldConstraint> constraints,
                       List<String> typeImports, String sourcePath, List<SourceLocation> locations) {

    public Property {
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        typeImports = typeImports == null ? List.of() : List.copyOf(typeImports);
        locations = locations == null ? List.of() : List.copyOf(locations);
    }

    public Property(String name, String type) {
        this(name, type, null, null, null, null, -1, List.of(), List.of(), null, List.of());
    }

    public Property(String name, String type, int lineNumber) {
        this(name, type, null, null, null, null, lineNumber, List.of(), List.of(), null, List.of());
    }

    /** Back-compatible convenience for callers that carry no source location. */
    public Property(String name, String type, String fieldKind, String column, String relation,
                    String expression, int lineNumber, List<FieldConstraint> constraints,
                    List<String> typeImports) {
        this(name, type, fieldKind, column, relation, expression, lineNumber, constraints, typeImports,
                null, List.of());
    }

    /** Back-compatible convenience for callers that predate the location list. */
    public Property(String name, String type, String fieldKind, String column, String relation,
                    String expression, int lineNumber, List<FieldConstraint> constraints,
                    List<String> typeImports, String sourcePath) {
        this(name, type, fieldKind, column, relation, expression, lineNumber, constraints, typeImports,
                sourcePath, List.of());
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getFieldKind() { return fieldKind; }
    public String getColumn() { return column; }
    public String getRelation() { return relation; }
    public String getExpression() { return expression; }
    public int getLineNumber() { return lineNumber; }
    public List<FieldConstraint> getConstraints() { return constraints; }
    public List<String> getTypeImports() { return typeImports; }
    public String getSourcePath() { return sourcePath; }
    public List<SourceLocation> getLocations() { return locations; }

    /** The recorded line for {@code role} in the artifact named {@code artifact}, or {@code -1}. */
    public int locationLine(String artifact, String role) {
        for (SourceLocation location : locations) {
            if (location.artifact().equals(artifact) && location.role().equals(role)) {
                return location.line();
            }
        }
        return -1;
    }

    /** Whether any Bean Validation constraint was declared on this field's accessor. */
    public boolean constrained() {
        return !constraints.isEmpty();
    }
}
