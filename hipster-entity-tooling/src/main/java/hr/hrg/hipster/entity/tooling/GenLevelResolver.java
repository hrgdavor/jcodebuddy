package hr.hrg.hipster.entity.tooling;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;

import hr.hrg.hipster.entity.api.GenLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * The single owner of the {@link GenLevel#DEFAULT} resolution rule
 * (plan.dsflash § 4.5/G1, § 4.7/DR-3).
 *
 * <p>{@code GenLevel}'s own javadoc says DEFAULT is "determined by the code generator based on the
 * presence of other options and the type of view" but does not define the rule. This class defines
 * it, and it is called by <strong>both</strong> the validator ({@code ViewAnnotationRule}) and the
 * generator — neither may carry its own "DEFAULT means META" shortcut, because that would let
 * validation and generation disagree about a view whose shape resolves to {@code RECORD} or
 * {@code BUILDER}.</p>
 *
 * <h3>The rule, in strict precedence order</h3>
 * <ol>
 *   <li>If the view declares a nested {@code record} whose component list matches the field enum
 *       order &rarr; {@link GenLevel#RECORD}.</li>
 *   <li>Else if the view declares a nested {@code Write} interface &rarr; {@link GenLevel#BUILDER}.</li>
 *   <li>Else &rarr; {@link GenLevel#META}.</li>
 * </ol>
 *
 * <p><strong>What "matches the field enum order" means for an entity view.</strong> The resolved
 * ordinal list of a view derived from an entity marker begins with the marker's {@code id}, which
 * the view itself does not declare. Both callers of this resolver hold only the view's <em>own</em>
 * accessors at the point where they can ask — the validator never sees the marker, and the
 * generator resolves the level while it is still building the type map — so a strict comparison
 * against the declared accessors could never match a nested record that (correctly) includes
 * {@code id}. Rule 1 was therefore unreachable for every entity view: the resolver called a
 * perfectly good nested record "stale", fell back to {@link GenLevel#META}, and generated a proxy
 * where the author had asked for nothing and the shape said "record". Accepting the
 * {@code [id] + declared} list closes that without weakening the check: a record whose components
 * are neither the declared list nor that list prefixed by {@code id} is still reported as a
 * mismatch.</p>
 *
 * <p>A field contributed by an {@code addons} declaration is <em>not</em> considered here, because
 * neither caller can resolve addons at this point. A view that both carries addons and declares a
 * nested record therefore still falls back to {@code META} with a mismatch diagnostic — the safe
 * direction, and a shape the tree does not contain.</p>
 *
 * <p>Rationale: DEFAULT means "do not add anything the author did not ask for, but do not leave the
 * declared interface unimplemented". It <strong>never</strong> returns
 * {@code BUILDER_TRACKED}/{@code BUILDER_ALL} — a view that wants tracking must say so, because
 * tracking changes the public surface ({@code changes()}/{@code changesBuilder()}).</p>
 */
public final class GenLevelResolver {

    private GenLevelResolver() {
    }

    /** The outcome of resolving a level, including the ambiguity diagnostics for rule 1. */
    public record Resolved(GenLevel level, List<String> diagnostics) {

        public Resolved {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /**
     * Resolves a requested level against the declaring interface's shape.
     *
     * @param requested the level from {@code @View(gen = …)}, or {@code null} for "no annotation"
     * @param decl      the declaring interface
     * @param fieldNames the view's resolved field names in ordinal order, used to check a nested
     *                  record's component list; may be empty when the caller has not collected them
     *                  yet, in which case rule 1 falls back to {@code META} with a diagnostic
     */
    public static Resolved resolve(GenLevel requested, ClassOrInterfaceDeclaration decl, List<String> fieldNames) {
        GenLevel level = requested == null ? GenLevel.DEFAULT : requested;
        if (level != GenLevel.DEFAULT) {
            return new Resolved(level, List.of());
        }

        List<String> diagnostics = new ArrayList<>();

        // Rule 1: a nested record with a matching component list means the author wants a record.
        for (RecordDeclaration record : decl.getMembers().stream()
                .filter(m -> m instanceof RecordDeclaration)
                .map(m -> (RecordDeclaration) m)
                .toList()) {
            List<String> components = record.getParameters().stream()
                    .map(p -> p.getNameAsString())
                    .toList();
            if (fieldNames == null || fieldNames.isEmpty()) {
                diagnostics.add("default_level_unresolved: nested record " + record.getNameAsString()
                        + " found but the view's field list is not known yet; falling back to META");
                return new Resolved(GenLevel.META, diagnostics);
            }
            if (components.equals(fieldNames) || components.equals(withInheritedId(fieldNames))) {
                return new Resolved(GenLevel.RECORD, diagnostics);
            }
            // "If the two disagree (a stale record), emit the DEC-022 divergence diagnostic and
            // fall back to META rather than generating a create() that would not compile."
            diagnostics.add("nested_record_mismatch: nested record " + record.getNameAsString()
                    + " has components " + components + " but the view's fields are "
                    + withInheritedId(fieldNames)
                    + "; falling back to META");
            return new Resolved(GenLevel.META, diagnostics);
        }

        // Rule 2: a nested Write interface means the author wants a builder.
        boolean hasWrite = false;
        for (var member : decl.getMembers()) {
            if (member instanceof ClassOrInterfaceDeclaration nested
                    && nested.isInterface()
                    && "Write".equals(nested.getNameAsString())) {
                hasWrite = true;
                break;
            }
        }
        if (hasWrite) {
            return new Resolved(GenLevel.BUILDER, diagnostics);
        }

        // Rule 3.
        return new Resolved(GenLevel.META, diagnostics);
    }

    /** Convenience: resolve without any field-name information for rule 1. */
    public static Resolved resolve(GenLevel requested, ClassOrInterfaceDeclaration decl) {
        return resolve(requested, decl, null);
    }

    /**
     * The view's declared accessors as they appear in the <em>resolved</em> ordinal list: an entity
     * view's list starts with the marker's {@code id}, which the view does not declare itself.
     *
     * <p>Prefixed only when the list does not already start with {@code id}, so a view that declares
     * its own {@code id()} is unaffected.</p>
     */
    private static List<String> withInheritedId(List<String> fieldNames) {
        if (fieldNames == null || fieldNames.isEmpty() || "id".equals(fieldNames.get(0))) {
            return fieldNames;
        }
        List<String> withId = new ArrayList<>(fieldNames.size() + 1);
        withId.add("id");
        withId.addAll(fieldNames);
        return withId;
    }
}
