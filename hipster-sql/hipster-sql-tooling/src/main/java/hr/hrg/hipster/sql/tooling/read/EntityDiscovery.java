package hr.hrg.hipster.sql.tooling.read;

import hr.hrg.hipster.sql.tooling.model.AnnotationModel;
import hr.hrg.hipster.sql.tooling.model.EntityModel;
import hr.hrg.hipster.sql.tooling.model.PropertyModel;
import hr.hrg.jcodebuddy.engine.source.SourceReader;
import hr.hrg.jcodebuddy.engine.source.TreeQueries;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Discovers {@code @HipsterEntity} interfaces in a source root and builds the generator's model.
 *
 * <p>The first slice is deliberately project-scoped and small: it walks one source root, reads every
 * {@code .java} through {@link SourceReader}, and keeps the top-level interfaces annotated with
 * {@code @HipsterEntity}. A file that cannot be read is a divergence, never a silent skip
 * (DEC-030's fail-safe); a nested entity is reported as unsupported rather than emitted with the
 * wrong FQN.</p>
 */
public final class EntityDiscovery {

    private EntityDiscovery() {
    }

    /** The discovered entities plus every reason a file or declaration was not usable. */
    public record Result(List<EntityModel> entities, List<String> divergences) {
        public Result {
            entities = List.copyOf(entities);
            divergences = List.copyOf(divergences);
        }
    }

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while");

    /**
     * Reads one source root and returns its entities sorted by FQN, so two runs over the same tree
     * write files in the same order.
     */
    public static Result discover(Path sourceRoot) throws IOException {
        return discover(sourceRoot, TypeOracle.empty());
    }

    /** Read one source root, optionally resolving unimported simple type names against a classpath. */
    public static Result discover(Path sourceRoot, TypeOracle oracle) throws IOException {
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            throw new IOException("not a source directory: " + sourceRoot);
        }
        List<EntityModel> entities = new ArrayList<>();
        List<String> divergences = new ArrayList<>();

        List<Path> files;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            files = walk.filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        for (Path file : files) {
            SourceReader.Read read;
            try {
                read = SourceReader.read(file);
            } catch (Exception e) {
                divergences.add("unreadable source " + relative(sourceRoot, file) + ": " + e.getMessage());
                continue;
            }
            if (!read.readable()) {
                divergences.add("unreadable source " + relative(sourceRoot, file));
                continue;
            }
            J.CompilationUnit unit = read.unit();
            String packageName = TreeQueries.packageName(unit);
            Map<String, String> imports = importsOf(unit);
            List<J.ClassDeclaration> topLevel = TreeQueries.topLevelTypes(unit);
            Set<J.ClassDeclaration> topLevelSet = Set.copyOf(topLevel);

            for (J.ClassDeclaration declaration : topLevel) {
                if (!TreeQueries.isKind(declaration, J.ClassDeclaration.Kind.Type.Interface)) {
                    continue;
                }
                if (TreeQueries.annotationNamed(declaration, "HipsterEntity") == null) {
                    continue;
                }
                entities.add(entityOf(declaration, packageName, relative(sourceRoot, file), imports,
                        oracle, divergences));
            }

            // A nested entity is valid Java and valid in the old processor, but its generated package
            // has the `pkg_` suffix. The first slice reports it rather than silently using the outer
            // package; the full model will add the nesting chain.
            for (J.ClassDeclaration declaration : TreeQueries.interfaces(unit)) {
                if (!topLevelSet.contains(declaration)
                        && TreeQueries.annotationNamed(declaration, "HipsterEntity") != null) {
                    divergences.add("nested entity not supported in this slice: "
                            + packageName + "." + declaration.getName().getSimpleName()
                            + " in " + relative(sourceRoot, file));
                }
            }
        }

        entities.sort(Comparator.comparing(EntityModel::fqn));
        return new Result(entities, divergences);
    }

    private static EntityModel entityOf(J.ClassDeclaration declaration, String packageName,
                                        String sourcePath, Map<String, String> declaredImports,
                                        TypeOracle oracle, List<String> divergences) {
        Map<String, String> imports = new LinkedHashMap<>(declaredImports);
        String simpleName = declaration.getName().getSimpleName();
        String fqn = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        AnnotationModel entityAnnotation = AnnotationModel.find(
                annotationsOf(declaration.getLeadingAnnotations()), "HipsterEntity");
        String tableName = entityAnnotation == null ? simpleName
                : entityAnnotation.stringArg("table", simpleName);

        List<PropertyModel> properties = new ArrayList<>();
        List<PropertyModel> skipped = new ArrayList<>();
        boolean primarySeen = false;
        for (J.MethodDeclaration method : TreeQueries.methodsOf(declaration)) {
            if (!isGetter(method)) {
                continue;
            }
            String getterName = method.getName().getSimpleName();
            String typeText = TreeQueries.typeText(method.getReturnTypeExpression());
            List<AnnotationModel> annotations = annotationsOf(method);
            boolean transientField = AnnotationModel.find(annotations, "Transient") != null;
            AnnotationModel column = AnnotationModel.find(annotations, "HipsterColumn");
            boolean keepRest = column != null && column.booleanArg("keepRest", false);
            boolean isId = AnnotationModel.find(annotations, "Id") != null;
            String initial = column == null ? "" : column.stringArg("initial", "");
            if (column != null && column.booleanArg("skip", false)) {
                skipped.add(new PropertyModel(-1, propertyName(getterName), getterName, typeText,
                        transientField, keepRest, false, annotations, initial));
                continue;
            }
            boolean primary = isId && !primarySeen;
            if (primary) {
                primarySeen = true;
            } else if (isId) {
                divergences.add("multiple_id, location=" + fqn + "." + getterName
                        + ", action=first @Id wins");
            }
            properties.add(new PropertyModel(properties.size(), propertyName(getterName), getterName,
                    typeText, transientField, keepRest, primary, annotations, initial));
        }
        resolveTypes(packageName, properties, imports, oracle, divergences, fqn);
        return new EntityModel(packageName, simpleName, tableName, sourcePath,
                TreeQueries.supertypeTexts(declaration), imports, properties, skipped);
    }

    private static void resolveTypes(String packageName, List<PropertyModel> properties,
                                     Map<String, String> imports, TypeOracle oracle,
                                     List<String> divergences, String entityFqn) {
        if (oracle == null || oracle.isEmpty()) {
            return;
        }
        for (PropertyModel property : properties) {
            String raw = rawType(property.typeText());
            if (raw.isEmpty() || raw.contains(".") || raw.contains("[]")
                    || imports.containsKey(raw) || isJavaLang(raw) || isPrimitive(raw)) {
                continue;
            }
            TypeOracle.Resolution resolution = oracle.resolve(raw);
            if (resolution.resolved()) {
                imports.put(raw, resolution.fqn());
            } else if (resolution.candidates().size() > 1) {
                divergences.add("ambiguous_type, location=" + entityFqn + "." + property.name()
                        + ", type=" + raw + ", candidates=" + resolution.candidates());
            } else {
                divergences.add("unresolved_type, location=" + entityFqn + "." + property.name()
                        + ", type=" + raw);
            }
        }
    }

    private static String rawType(String type) {
        String raw = type;
        int generic = raw.indexOf('<');
        if (generic >= 0) {
            raw = raw.substring(0, generic);
        }
        return raw.trim();
    }

    private static boolean isJavaLang(String simple) {
        return switch (simple) {
            case "String", "Object", "Boolean", "Byte", "Short", "Integer", "Long", "Float",
                 "Double", "Character", "Void", "Class", "Enum", "Record", "Iterable" -> true;
            default -> false;
        };
    }

    private static boolean isPrimitive(String simple) {
        return switch (simple) {
            case "int", "long", "short", "byte", "float", "double", "boolean", "char", "void" -> true;
            default -> false;
        };
    }

    /** The accessor's annotations, preserving a fully qualified spelling and written arguments. */
    private static List<AnnotationModel> annotationsOf(J.MethodDeclaration method) {
        return annotationsOf(method.getLeadingAnnotations());
    }

    private static List<AnnotationModel> annotationsOf(List<J.Annotation> leading) {
        List<AnnotationModel> annotations = new ArrayList<>();
        for (J.Annotation annotation : leading) {
            List<String> arguments = new ArrayList<>();
            if (annotation.getArguments() != null) {
                for (Expression argument : annotation.getArguments()) {
                    if (argument instanceof J.Empty) {
                        continue;
                    }
                    arguments.add(TreeQueries.expressionText(argument));
                }
            }
            annotations.add(new AnnotationModel(
                    TreeQueries.typeText(annotation.getAnnotationType()), arguments));
        }
        return List.copyOf(annotations);
    }

    /**
     * The old processor's accessor rule: no parameters, not a default or static method, a
     * {@code get} name, or an {@code is}/{@code has} name with a boolean/Boolean return type.
     */
    private static boolean isGetter(J.MethodDeclaration method) {
        if (!TreeQueries.hasNoParameters(method)
                || TreeQueries.isDefaultMethod(method)
                || TreeQueries.isStaticMethod(method)
                || method.getReturnTypeExpression() == null) {
            return false;
        }
        String name = method.getName().getSimpleName();
        String type = TreeQueries.typeText(method.getReturnTypeExpression());
        if (name.startsWith("get")) {
            return true;
        }
        return (name.startsWith("is") || name.startsWith("has"))
                && ("boolean".equals(type) || "Boolean".equals(type));
    }

    private static String propertyName(String getterName) {
        String base = getterName.startsWith("get") || getterName.startsWith("has")
                ? getterName.substring(3)
                : getterName.substring(2);
        String name = Character.toLowerCase(base.charAt(0)) + base.substring(1);
        return JAVA_KEYWORDS.contains(name) ? "_" + name : name;
    }

    private static String stringArg(J.Annotation annotation, String name, String fallback) {
        Expression expression = TreeQueries.annotationArg(annotation, name);
        if (expression instanceof J.Literal literal && literal.getValue() instanceof String text) {
            return text;
        }
        return fallback;
    }

    private static boolean booleanArg(J.Annotation annotation, String name) {
        Expression expression = TreeQueries.annotationArg(annotation, name);
        return expression != null && "true".equals(TreeQueries.expressionText(expression));
    }

    private static Map<String, String> importsOf(J.CompilationUnit unit) {
        Map<String, String> imports = new LinkedHashMap<>();
        for (String line : TreeQueries.importLines(unit)) {
            String text = line.trim();
            if (!text.startsWith("import ")
                    || text.startsWith("import static ")
                    || text.endsWith(".*;")) {
                continue;
            }
            String fqn = text.substring("import ".length(), text.length() - 1).trim();
            int dot = fqn.lastIndexOf('.');
            if (dot > 0) {
                imports.put(fqn.substring(dot + 1), fqn);
            }
        }
        return Map.copyOf(imports);
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
