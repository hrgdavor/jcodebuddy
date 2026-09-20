package hr.hrg.hipster.entity.tooling.validation;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import hr.hrg.hipster.entity.api.GenLevel;
import hr.hrg.hipster.entity.tooling.GenLevelResolver;
import hr.hrg.hipster.entity.tooling.ViewAnnotationReader;
import hr.hrg.hipster.entity.tooling.meta.ViewAttributes;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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
 */
public class ViewAnnotationRule implements EntityRule {

    /** Methods that make an interface a write/tracking surface rather than a view. */
    private static final Set<String> SURFACE_TYPES = Set.of("ViewReader", "ViewWriter", "ViewChangeTracking");

    /** Levels that require at least one writable field to be meaningful. */
    private static final Set<GenLevel> LEVELS_NEEDING_WRITABLE_FIELDS =
            Set.of(GenLevel.WRITABLE, GenLevel.BUILDER, GenLevel.BUILDER_TRACKED, GenLevel.BUILDER_ALL);

    @Override
    public void validate(Path file, String pkg, CompilationUnit cu, List<EntityRulesValidator.ValidationIssue> issues) {
        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            Optional<AnnotationExpr> viewOpt = decl.getAnnotationByName("View");
            if (viewOpt.isEmpty()) {
                continue;
            }
            if (!decl.isInterface()) {
                issues.add(new EntityRulesValidator.ValidationIssue(file,
                        "@View can only be applied on interfaces: " + decl.getNameAsString()));
                continue;
            }
            if (isMarker(decl, pkg)) {
                // The marker of the package is not a view; @View on it is inert (G6/G8 rule 0), and
                // an addons declaration there does nothing.
                ViewAnnotationReader.Parsed markerParsed = ViewAnnotationReader.parse(viewOpt.get());
                if (!markerParsed.attributes().addons().isEmpty()) {
                    issues.add(new EntityRulesValidator.ValidationIssue(file,
                            "addon_on_non_view: @View(addons = …) on " + decl.getNameAsString()
                                    + " has no effect because it is the package marker, not a view"));
                }
                continue;
            }

            ViewAnnotationReader.Parsed parsed = ViewAnnotationReader.parse(viewOpt.get());
            ViewAttributes attributes = parsed.attributes();
            for (String diagnostic : parsed.diagnostics()) {
                issues.add(new EntityRulesValidator.ValidationIssue(file,
                        diagnostic + " on " + decl.getNameAsString()));
            }

            List<String> fieldNames = decl.getMethods().stream()
                    .filter(m -> m.getParameters().isEmpty() && !m.getType().isVoidType() && !m.isDefault())
                    .map(m -> m.getNameAsString())
                    .toList();

            GenLevelResolver.Resolved resolved = GenLevelResolver.resolve(attributes.gen(), decl, fieldNames);
            for (String diagnostic : resolved.diagnostics()) {
                issues.add(new EntityRulesValidator.ValidationIssue(file,
                        diagnostic + " on " + decl.getNameAsString()));
            }

            // A tracking/builder level needs something to write. `id` alone is not enough: it is
            // the immutable identity on the array path.
            if (LEVELS_NEEDING_WRITABLE_FIELDS.contains(resolved.level()) && fieldNames.isEmpty()) {
                issues.add(new EntityRulesValidator.ValidationIssue(file,
                        resolved.level() + " requires at least one accessor on " + decl.getNameAsString()));
            }

            // An addons declaration on a write/tracking surface is inert (G6: propagation is
            // per-view, and a surface is not a view).
            if (!attributes.addons().isEmpty() && isSurface(decl)) {
                issues.add(new EntityRulesValidator.ValidationIssue(file,
                        "addon_on_non_view: @View(addons = …) on " + decl.getNameAsString()
                                + " has no effect because that interface extends a write/tracking surface"));
            }
        }
    }

    /** The package marker is the interface the entity's other views derive from. */
    private static boolean isMarker(ClassOrInterfaceDeclaration decl, String pkg) {
        // A marker is an interface named <Entity> or <Entity>Entity that itself extends nothing but
        // EntityBase; the generator's MarkerEntityRule owns the authoritative definition, and this
        // rule only needs the conservative "extends only EntityBase/Identifiable" reading.
        if (decl.getExtendedTypes().isEmpty()) {
            return true;
        }
        return decl.getExtendedTypes().stream().allMatch(type -> {
            String name = type.getNameAsString();
            return name.equals("EntityBase") || name.equals("Identifiable");
        });
    }

    private static boolean isSurface(ClassOrInterfaceDeclaration decl) {
        return decl.getExtendedTypes().stream().anyMatch(type -> {
            String simple = type.getNameAsString();
            int dot = simple.lastIndexOf('.');
            return SURFACE_TYPES.contains(dot >= 0 ? simple.substring(dot + 1) : simple);
        });
    }
}
