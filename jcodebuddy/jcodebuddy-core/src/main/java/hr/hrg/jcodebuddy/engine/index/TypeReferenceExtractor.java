package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.query.TypeResolver;
import hr.hrg.jcodebuddy.engine.source.TreeQueries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the written type names of one class row into {@link TypeReference}s.
 *
 * <p>This is extended metadata, not a row field: the answer depends on the whole index, the file's imports
 * and an optional classpath resolver. It therefore never changes a file-local row, and a caller that has no
 * resolver gets an explicit {@link TypeReference.Resolution#UNRESOLVED} instead of an invented answer.</p>
 */
public final class TypeReferenceExtractor {

    private static final Set<String> PRIMITIVES = Set.of(
            "boolean", "byte", "char", "short", "int", "long", "float", "double", "void");

    private static final Set<String> JAVA_LANG = Set.of(
            "Boolean", "Byte", "Character", "Class", "Double", "Enum", "Float", "Integer", "Iterable",
            "Long", "Object", "Record", "Short", "String", "Void");

    private TypeReferenceExtractor() {
    }

    public static List<TypeReferenceSite> of(ClassRecord row, List<String> imports, TypeResolver resolver) {
        if (row == null) {
            return List.of();
        }
        Context context = new Context(packageOf(row), imports, resolver);
        List<TypeReferenceSite> sites = new ArrayList<>();
        for (TypeRelation relation : row.relations()) {
            sites.add(new TypeReferenceSite(row.fqn(), "", "relation",
                    resolve(relation.name(), relation.span(), context)));
        }
        for (MemberRecord member : row.members()) {
            String role = switch (member.kind()) {
                case FIELD -> "field";
                case METHOD -> "return";
                case NESTED -> "nested";
                default -> "";
            };
            if (!role.isEmpty() && !member.type().isEmpty()) {
                sites.add(new TypeReferenceSite(row.fqn(), member.name(), role,
                        resolve(member.type(), member.span(), context)));
            }
            for (MemberParameter parameter : member.parameters()) {
                sites.add(new TypeReferenceSite(row.fqn(), member.name(), "parameter",
                        resolve(parameter.type(), member.span(), context)));
            }
        }
        return List.copyOf(sites);
    }

    private static TypeReference resolve(String written, TreeQueries.SourceSpan span, Context context) {
        String text = written == null ? "" : written.trim();
        if (text.isEmpty()) {
            return new TypeReference(text, text, List.of(), null,
                    TypeReference.Resolution.UNRESOLVED, "", List.of(), span);
        }
        String raw = rawType(text);
        List<TypeReference> arguments = new ArrayList<>();
        for (String argument : typeArguments(text)) {
            arguments.add(resolve(argument, span, context));
        }
        if (PRIMITIVES.contains(raw)) {
            return new TypeReference(text, raw, arguments, null,
                    TypeReference.Resolution.RESOLVED, "primitive", List.of(), span);
        }
        if (raw.startsWith("?")) {
            return new TypeReference(text, raw, arguments, null,
                    TypeReference.Resolution.UNRESOLVED, "", List.of(), span);
        }
        if (raw.contains(".") && Character.isLowerCase(raw.charAt(0))) {
            return new TypeReference(text, raw, arguments, raw,
                    TypeReference.Resolution.RESOLVED, "fqn", List.of(), span);
        }
        String imported = context.explicitImports.get(raw);
        if (imported != null) {
            return new TypeReference(text, raw, arguments, imported,
                    TypeReference.Resolution.RESOLVED, "import", List.of(), span);
        }
        if (JAVA_LANG.contains(raw)) {
            return new TypeReference(text, raw, arguments, "java.lang." + raw,
                    TypeReference.Resolution.RESOLVED, "java.lang", List.of(), span);
        }
        if (!context.packageName.isEmpty()) {
            String candidate = context.packageName + "." + raw;
            if (context.knows(candidate)) {
                return new TypeReference(text, raw, arguments, candidate,
                        TypeReference.Resolution.RESOLVED, "same-package", List.of(), span);
            }
        }
        Set<String> wildcardCandidates = new LinkedHashSet<>();
        for (String packageName : context.wildcardPackages) {
            String candidate = packageName + "." + raw;
            if (context.knows(candidate)) {
                wildcardCandidates.add(candidate);
            }
        }
        if (wildcardCandidates.size() == 1) {
            return new TypeReference(text, raw, arguments, wildcardCandidates.iterator().next(),
                    TypeReference.Resolution.RESOLVED, "wildcard", List.copyOf(wildcardCandidates), span);
        }
        if (wildcardCandidates.size() > 1) {
            return new TypeReference(text, raw, arguments, null,
                    TypeReference.Resolution.AMBIGUOUS, "wildcard", List.copyOf(wildcardCandidates), span);
        }
        if (context.resolver != null && context.resolver.resolve(raw) != null) {
            return new TypeReference(text, raw, arguments, raw,
                    TypeReference.Resolution.RESOLVED, "resolver", List.of(), span);
        }
        return new TypeReference(text, raw, arguments, null,
                TypeReference.Resolution.UNRESOLVED, "", List.of(), span);
    }

    private static String rawType(String type) {
        String raw = type;
        int generic = raw.indexOf('<');
        if (generic >= 0) {
            raw = raw.substring(0, generic);
        }
        while (raw.endsWith("[]")) {
            raw = raw.substring(0, raw.length() - 2);
        }
        return raw.trim();
    }

    private static List<String> typeArguments(String type) {
        int open = type.indexOf('<');
        int close = type.lastIndexOf('>');
        if (open < 0 || close <= open) {
            return List.of();
        }
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int i = start; i < close; i++) {
            char c = type.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                arguments.add(type.substring(start, i).trim());
                start = i + 1;
            }
        }
        arguments.add(type.substring(start, close).trim());
        return arguments;
    }

    private static String packageOf(ClassRecord row) {
        if (row.enclosing() != null) {
            int lastDot = row.enclosing().lastIndexOf('.');
            return lastDot < 0 ? "" : row.enclosing().substring(0, lastDot);
        }
        int lastDot = row.fqn().lastIndexOf('.');
        return lastDot < 0 ? "" : row.fqn().substring(0, lastDot);
    }

    private static final class Context {
        private final String packageName;
        private final Map<String, String> explicitImports = new LinkedHashMap<>();
        private final List<String> wildcardPackages = new ArrayList<>();
        private final TypeResolver resolver;

        private Context(String packageName, List<String> imports, TypeResolver resolver) {
            this.packageName = packageName;
            this.resolver = resolver;
            for (String line : imports == null ? List.<String>of() : imports) {
                String text = line.trim();
                if (!text.startsWith("import ") || text.startsWith("import static ")) {
                    continue;
                }
                String fqn = text.substring("import ".length(), text.length() - 1).trim();
                if (fqn.endsWith(".*")) {
                    wildcardPackages.add(fqn.substring(0, fqn.length() - 2));
                } else {
                    int dot = fqn.lastIndexOf('.');
                    if (dot > 0) {
                        explicitImports.put(fqn.substring(dot + 1), fqn);
                    }
                }
            }
        }

        private boolean knows(String fqn) {
            return resolver == null || resolver.resolve(fqn) != null;
        }
    }
}
