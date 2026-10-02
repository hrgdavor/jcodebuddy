package hr.hrg.hipster.entity.tooling.validation;

import hr.hrg.hipster.entity.tooling.GenLevelResolver;
import hr.hrg.hipster.entity.tooling.TreeQueries;
import hr.hrg.hipster.entity.tooling.ViewAnnotationReader;
import org.openrewrite.java.tree.J;

import hr.hrg.hipster.entity.api.GenLevel;
import hr.hrg.hipster.entity.tooling.meta.ViewAttributes;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Validates the {@code @View} annotation (plan.dsflash § 6.3/1.12).
 *
 * <p>The previous implementation inspected {@code viewAnn.toString()} and reported an issue unless
 * the source text contained the substring {@code "read"} or {@code "write"} — attributes the real
 * annotation does not have. It therefore rejected <em>every</em> valid
 * {@code @View(gen = …)} and was referenced only from tests, which is why nobody noticed.</p>
 *
 * <p>This rule now parses through the shared, shape-blind {@link ViewAnnotationReader} and resolves
 * {@code DEFAULT} through the shared {@link GenLevelResolver}. It carries <strong>no</strong>
 * {@code DEFAULT → META} shortcut of its own: that would let validation and generation disagree
 * about a view whose shape resolves to {@code RECORD} or {@code BUILDER} (§ 4.7/DR-3).</p>
 *
 * <h3>Phase 6</h3>
 * <p>Two changes that must stay together, because a port that makes only one of them still
 * compiles and quietly stops reporting:</p>
 * <ul>
 *   <li>{@code cu.findAll(ClassOrInterfaceDeclaration.class)} becomes
 *       {@link TreeQueries#typeDeclarations}. This walk is deliberately <em>not</em> restricted to
 *       interfaces: the rule must still see {@code @View} on a <em>class</em> in order to report
 *       "can only be applied on interfaces". So the type family widens from
 *       {@code ClassOrInterfaceDeclaration} to {@link J.ClassDeclaration}, and the interface test
 *       moves inside the loop where the diagnostic can name the offender.</li>
 *   <li>The accessor list is read through {@link TreeQueries#noArgMethodNames}, which knows that an
 *       empty LST parameter list is a single {@link J.Empty} placeholder rather than an empty
 *       list — the quirk that would otherwise make every accessor look like a one-argument
 *       method.</li>
 * </ul>
 */
public class ViewAnnotationRule implements EntityRule {

    /** Methods that make an interface a write/tracking surface rather than a view. */
    private static final Set<String> SURFACE_TYPES = Set.of("ViewReader", "ViewWriter", "ViewChangeTracking");

    /** Levels that require at least one writable field to be meaningful. */
    private static final Set<GenLevel> LEVELS_NEEDING_WRITABLE_FIELDS =
            Set.of(GenLevel.WRITABLE, GenLevel.BUILDER, GenLevel.BUILDER_TRACKED, GenLevel.BUILDER_ALL);

    @Override
    public void validate(Path file, String pkg, J.CompilationUnit cu,
                         List<EntityRulesValidator.ValidationIssue> issues) {
        for (J.ClassDeclaration decl : TreeQueries.typeDeclarations(cu)) {
            J.Annotation view = TreeQueries.annotationNamed(decl, "View");
            if (view == null) {
                continue;
            }
            String name = decl.getSimpleName();
            if (!TreeQueries.isKind(decl, J.ClassDeclaration.Kind.Type.Interface)) {
                issues.add(new EntityRulesValidator.ValidationIssue(file,
                        "@View can only be applied on interfaces: " + name));
                continue;
            }
            if (isMarker(decl)) {
                // The marker of the package is not a view; @View on it is inert (G6/G8 rule 0), and
                // an addons declaration there does nothing.
                ViewAnnotationReader.Parsed markerParsed = ViewAnnotationReader.parse(view);
                if (!markerParsed.attributes().addons().isEmpty()) {
                    issues.add(new EntityRulesValidator.ValidationIssue(file,
                            "addon_on_non_view: @View(addons = …) on " + name
                                    + " has no effect because it is the package marker, not a view"));
                }
                continue;
            }

            ViewAnnotationReader.Parsed parsed = ViewAnnotationReader.parse(view);
            ViewAttributes attributes = parsed.attributes();
            for (String diagnostic : parsed.diagnostics()) {
                issues.add(new EntityRulesValidator.ValidationIssue(file,
                        diagnostic + " on " + name));
            }

            List<String> fieldNames = TreeQueries.noArgMethodNames(decl);

            GenLevelResolver.Resolved resolved = GenLevelResolver.resolve(attributes.gen(), decl, fieldNames);
            for (String diagnostic : resolved.diagnostics()) {
                issues.add(new EntityRulesValidator.ValidationIssue(file,
                        diagnostic + " on " + name));
            }

            // A tracking/builder level needs something to write. `id` alone is not enough: it is
            // the immutable identity on the array path.
            if (LEVELS_NEEDING_WRITABLE_FIELDS.contains(resolved.level()) && fieldNames.isEmpty()) {
                issues.add(new EntityRulesValidator.ValidationIssue(file,
                        resolved.level() + " requires at least one accessor on " + name));
            }

            // An addons declaration on a write/tracking surface is inert (G6: propagation is
            // per-view, and a surface is not a view).
            if (!attributes.addons().isEmpty() && isSurface(decl)) {
                issues.add(new EntityRulesValidator.ValidationIssue(file,
                        "addon_on_non_view: @View(addons = …) on " + name
                                + " has no effect because that interface extends a write/tracking surface"));
            }
        }
    }

    /**
     * The package marker is the interface the entity's other views derive from.
     *
     * <p>Two shapes, matching the generator's authoritative predicate
     * ({@code EntityMetadataGenerator.isMarkerEntityOrRoot}, G8 rule 0): an interface that extends
     * {@code EntityBase}/{@code Identifiable} itself, and one that is a marker by convention and
     * reaches {@code EntityBase} through nothing but other markers.</p>
     *
     * <p><strong>An interface with no {@code extends} clause at all is NOT a marker.</strong> The
     * first version of this method returned {@code true} for that case, which made every
     * {@code @View}-seeded rootless view ({@code PersonCreateForm}, and any adopter's first view) look like
     * a marker — and so silenced exactly the {@code addon_on_non_view} diagnostic the method exists to
     * produce. The generator does not treat such an interface as a marker either: it is a view, seeded
     * by its annotation.</p>
     */
    private static boolean isMarker(J.ClassDeclaration decl) {
        // Simple names only: `getExtendedTypes()` yielded a ClassOrInterfaceType whose name could
        // carry a package prefix, and the LST splits a generic supertype into a ParameterizedType,
        // so the unwrapping lives in TreeQueries rather than here.
        for (String name : TreeQueries.supertypeNames(decl)) {
            if ("EntityBase".equals(name) || "Identifiable".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSurface(J.ClassDeclaration decl) {
        return TreeQueries.supertypeNames(decl).stream().anyMatch(SURFACE_TYPES::contains);
    }
}
