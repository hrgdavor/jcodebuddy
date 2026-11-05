package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.meta.ArtifactMeta;
import hr.hrg.hipster.entity.tooling.meta.EntityMeta;
import hr.hrg.hipster.entity.tooling.meta.InterfaceInfo;
import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.ViewMeta;
import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ClassRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The input set of one view: the FQNs whose content can change what the pass emits or records for it.
 *
 * <p>This is the writer half of plan step E3 and the reader side E4 consumes. It is deliberately a
 * <strong>set of FQNs</strong>, never paths: the class index is the one table that turns an FQN into the
 * file a change report names, and a path in a document would be a second source of truth (DEC-029).</p>
 *
 * <p>The set is conservative. It includes the view's own file, its marker, every supertype and addon
 * (transitively), every source type named by a field, every generated artifact that belongs to the view,
 * and the other endpoint of a mapper/merge request. A type that cannot be resolved to a class-index row is
 * recorded in {@link Inputs#unresolved()} and reported; E4 then treats that view as always affected rather
 * than skipping it on a guess.</p>
 */
final class ViewInputs {

    /** The generated-from set and the names that could not be resolved. */
    record Inputs(Set<String> inputs, Set<String> unresolved) {
        Inputs {
            inputs = Set.copyOf(inputs);
            unresolved = Set.copyOf(unresolved);
        }

        boolean resolved() {
            return unresolved.isEmpty();
        }
    }

    /** A token in a declared type: {@code Map<String, List<Long>>} yields Map, String, List, Long. */
    private static final Pattern TYPE_NAME = Pattern.compile("[A-Z][A-Za-z0-9_$]*(?:\\.[A-Z][A-Za-z0-9_$]*)*");

    /**
     * Common JDK names that are not imports: they live in {@code java.lang} or are so ordinary that
     * {@link JdkImportSupport} has no reason to list them. A type in this set has no source file the
     * pass could select, so it is not an input.
     */
    private static final Set<String> JDK_NAMES = Set.of(
            "Object", "String", "Boolean", "Byte", "Short", "Integer", "Long", "Float", "Double",
            "Character", "Void", "Number", "Class", "Enum", "Record", "Iterable", "Comparable",
            "List", "Map", "Set", "Collection", "Optional", "BigDecimal", "BigInteger",
            "LocalDate", "LocalDateTime", "Instant", "Duration", "UUID");

    private ViewInputs() {
    }

    /** Compute one input record per view, keyed by {@link #key(ViewMeta)}. */
    static Map<String, Inputs> collect(EntityMeta entityMeta,
                                       Map<String, InterfaceInfo> interfaceMap,
                                       ClassIndex index,
                                       Map<String, Map<String, String>> importsByPath,
                                       List<String> mapperRequests,
                                       List<String> mergeRequests,
                                       DivergenceReporter divergences) {
        Map<String, Inputs> result = new LinkedHashMap<>();
        String markerFqn = entityMeta.packageName() == null || entityMeta.packageName().isBlank()
                ? entityMeta.markerInterface()
                : entityMeta.packageName() + "." + entityMeta.markerInterface();
        Set<String> markerFiles = new LinkedHashSet<>();
        addPath(index, entityMeta.markerSourcePath(), markerFiles);
        addFqn(index, markerFqn, markerFiles);

        for (ViewMeta view : entityMeta.views()) {
            Set<String> inputs = new TreeSet<>();
            Set<String> unresolved = new TreeSet<>();
            InterfaceInfo viewInfo = findView(interfaceMap, view);
            String viewPackage = viewInfo != null ? viewInfo.packageName() : entityMeta.packageName();

            // The view's own file and the marker it derives from. Adding every type declared by the
            // file is deliberate: a nested record or a second top-level interface in the same file is
            // changed by the same edit.
            addPath(index, view.sourcePath(), inputs);
            addFqn(index, markerFqn, inputs);
            inputs.addAll(markerFiles);

            if (viewInfo != null) {
                collectInterface(viewInfo, interfaceMap, index, importsByPath, inputs, unresolved,
                        new LinkedHashSet<>());
            }
            if (viewInfo != null && viewInfo.view() != null) {
                for (String addon : viewInfo.view().addons()) {
                    InterfaceInfo addonInfo = resolveInterface(interfaceMap, viewPackage, addon, null);
                    if (addonInfo != null) {
                        collectInterface(addonInfo, interfaceMap, index, importsByPath, inputs, unresolved,
                                new LinkedHashSet<>());
                    }
                }
            }

            for (Property property : view.properties()) {
                // The accessor's declaring file is an input even when the accessor is inherited.
                addPath(index, property.sourcePath(), inputs);
                // `typeImports` already resolved the non-same-package names; add every source type it
                // names, then resolve the simple names the emitter kept in the type text (same-package
                // types and generic arguments do not appear there).
                for (String imported : property.typeImports()) {
                    addFqn(index, imported, inputs);
                }
                Map<String, String> imports = importsByPath.getOrDefault(property.sourcePath(), Map.of());
                String declaringPackage = declaringPackageOf(property, viewPackage, interfaceMap);
                for (String name : typeNames(property.type())) {
                    String resolved = resolveType(index, imports, declaringPackage, name, interfaceMap);
                    if (resolved != null) {
                        addFqn(index, resolved, inputs);
                    } else if (looksProjectType(name)) {
                        unresolved.add(name);
                    }
                }
            }

            for (ArtifactMeta artifact : view.artifacts()) {
                addArtifact(index, artifact, inputs, unresolved);
            }

            addRequestPartners(view, index, mapperRequests, inputs);
            addRequestPartners(view, index, mergeRequests, inputs);

            if (!unresolved.isEmpty()) {
                divergences.report("unresolved_input", view.name(),
                        "these type names in the view's input set could not be resolved through the class index",
                        String.join(", ", unresolved),
                        "an FQN present in " + index.indexFile().getFileName(),
                        "fix the type name, or add the source that declares it to the pass");
            }
            result.put(key(view), new Inputs(inputs, unresolved));
        }
        return result;
    }

    /** The map key the writer and E4 share. Module-relative source path plus simple name is unique. */
    static String key(ViewMeta view) {
        return (view.sourcePath() == null ? "" : view.sourcePath()) + "|" + view.name();
    }

    private static String declaringPackageOf(Property property, String fallback,
                                             Map<String, InterfaceInfo> interfaceMap) {
        if (property.sourcePath() == null) {
            return fallback;
        }
        for (InterfaceInfo info : interfaceMap.values()) {
            if (Objects.equals(info.sourcePath(), property.sourcePath())) {
                return info.packageName();
            }
        }
        return fallback;
    }

    private static void collectInterface(InterfaceInfo info,
                                         Map<String, InterfaceInfo> interfaceMap,
                                         ClassIndex index,
                                         Map<String, Map<String, String>> importsByPath,
                                         Set<String> inputs,
                                         Set<String> unresolved,
                                         Set<String> visited) {
        String fqn = info.packageName() == null || info.packageName().isBlank()
                ? info.name()
                : info.packageName() + "." + info.name();
        if (!visited.add(fqn)) {
            return;
        }
        addFqn(index, fqn, inputs);
        addPath(index, info.sourcePath(), inputs);
        Map<String, String> imports = importsByPath.getOrDefault(info.sourcePath(), Map.of());
        for (String declared : info.extendsTypes()) {
            String raw = stripGenerics(declared);
            InterfaceInfo parent = resolveInterface(interfaceMap, info.packageName(), raw, imports);
            if (parent != null) {
                collectInterface(parent, interfaceMap, index, importsByPath, inputs, unresolved, visited);
            } else if (looksProjectType(raw)) {
                unresolved.add(raw);
            }
        }
        if (info.view() != null) {
            for (String addon : info.view().addons()) {
                InterfaceInfo addonInfo = resolveInterface(interfaceMap, info.packageName(), addon, imports);
                if (addonInfo != null) {
                    collectInterface(addonInfo, interfaceMap, index, importsByPath, inputs, unresolved,
                            visited);
                }
            }
        }
    }

    /** The interface behind a view, resolved by source path when one is recorded. */
    static InterfaceInfo infoOf(Map<String, InterfaceInfo> interfaceMap, ViewMeta view) {
        return findView(interfaceMap, view);
    }

    private static InterfaceInfo findView(Map<String, InterfaceInfo> interfaceMap, ViewMeta view) {
        for (InterfaceInfo info : interfaceMap.values()) {
            if (!Objects.equals(info.name(), view.name())) {
                continue;
            }
            if (view.sourcePath() == null || Objects.equals(info.sourcePath(), view.sourcePath())) {
                return info;
            }
        }
        return null;
    }

    private static InterfaceInfo resolveInterface(Map<String, InterfaceInfo> interfaceMap,
                                                  String packageName,
                                                  String rawName,
                                                  Map<String, String> imports) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String name = stripGenerics(rawName).trim();
        String simple = name.substring(name.lastIndexOf('.') + 1);
        if (imports != null) {
            String imported = imports.get(simple);
            if (imported != null) {
                InterfaceInfo found = interfaceMap.get(imported);
                if (found != null) {
                    return found;
                }
            }
        }
        InterfaceInfo direct = interfaceMap.get(
                packageName == null || packageName.isBlank() ? simple : packageName + "." + simple);
        if (direct != null) {
            return direct;
        }
        if (name.contains(".")) {
            InterfaceInfo qualified = interfaceMap.get(name);
            if (qualified != null) {
                return qualified;
            }
        }
        InterfaceInfo match = null;
        for (InterfaceInfo candidate : interfaceMap.values()) {
            if (!candidate.name().equals(simple)) {
                continue;
            }
            if (match != null) {
                return null; // ambiguous simple name: do not guess
            }
            match = candidate;
        }
        return match;
    }

    private static String resolveType(ClassIndex index,
                                      Map<String, String> imports,
                                      String packageName,
                                      String rawName,
                                      Map<String, InterfaceInfo> interfaceMap) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String simple = rawName.substring(rawName.lastIndexOf('.') + 1);
        if (JDK_NAMES.contains(simple) || JdkImportSupport.isKnownJdkType(simple) || rawName.startsWith("java.")) {
            return null;
        }
        String imported = imports.get(simple);
        if (imported != null && index.row(imported) != null) {
            return imported;
        }
        InterfaceInfo info = resolveInterface(interfaceMap, packageName, rawName, imports);
        if (info != null) {
            String fqn = info.packageName() == null || info.packageName().isBlank()
                    ? info.name()
                    : info.packageName() + "." + info.name();
            if (index.row(fqn) != null) {
                return fqn;
            }
        }
        if (index.row(rawName) != null) {
            return rawName;
        }
        String samePackage = packageName == null || packageName.isBlank()
                ? simple
                : packageName + "." + simple;
        if (index.row(samePackage) != null) {
            return samePackage;
        }
        ClassRecord match = null;
        for (ClassRecord row : index.rows()) {
            String name = row.fqn().substring(row.fqn().lastIndexOf('.') + 1);
            if (!name.equals(simple)) {
                continue;
            }
            if (match != null) {
                return null; // ambiguous
            }
            match = row;
        }
        return match == null ? null : match.fqn();
    }

    private static void addPath(ClassIndex index, String path, Set<String> inputs) {
        if (path == null || path.isBlank()) {
            return;
        }
        for (ClassRecord row : index.byPath(path)) {
            inputs.add(row.fqn());
        }
    }

    private static void addArtifact(ClassIndex index, ArtifactMeta artifact, Set<String> inputs,
                                    Set<String> unresolved) {
        if (artifact.file() == null || artifact.file().isBlank()) {
            unresolved.add(artifact.name());
            return;
        }
        String wanted = artifact.name() == null ? "" : artifact.name();
        for (ClassRecord row : index.byPath(artifact.file())) {
            if (row.fqn().equals(wanted) || row.fqn().endsWith("." + wanted)) {
                inputs.add(row.fqn());
                return;
            }
        }
        // The artifact's name can be a display name (`PersonSummary.Record`) while the row is keyed
        // by the FQN; a unique row in that file is still the unambiguous answer.
        List<ClassRecord> rows = index.byPath(artifact.file());
        if (rows.size() == 1) {
            inputs.add(rows.get(0).fqn());
            return;
        }
        unresolved.add(wanted);
    }

    private static void addFqn(ClassIndex index, String fqn, Set<String> inputs) {
        if (fqn != null && index.row(fqn) != null) {
            inputs.add(fqn);
        }
    }

    /** The other endpoint of a mapper/merge request, when it resolves to a class-index row. */
    private static void addRequestPartners(ViewMeta view, ClassIndex index, List<String> requests,
                                           Set<String> inputs) {
        for (String request : requests) {
            String[] parts = request.split(":");
            if (parts.length != 2) {
                continue;
            }
            String left = simpleName(parts[0]);
            String right = simpleName(parts[1]);
            String partner = null;
            if (left.equals(view.name())) {
                partner = right;
            } else if (right.equals(view.name())) {
                partner = left;
            }
            if (partner == null) {
                continue;
            }
            ClassRecord match = null;
            for (ClassRecord row : index.rows()) {
                if (!row.fqn().endsWith("." + partner) && !row.fqn().equals(partner)) {
                    continue;
                }
                if (match != null) {
                    match = null;
                    break;
                }
                match = row;
            }
            if (match != null) {
                inputs.add(match.fqn());
            }
        }
    }

    private static String simpleName(String text) {
        String name = stripGenerics(text).trim();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String stripGenerics(String text) {
        int angle = text.indexOf('<');
        return angle < 0 ? text : text.substring(0, angle);
    }

    private static List<String> typeNames(String declaredType) {
        List<String> names = new ArrayList<>();
        if (declaredType == null) {
            return names;
        }
        Matcher matcher = TYPE_NAME.matcher(declaredType);
        while (matcher.find()) {
            String name = matcher.group();
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * A type worth reporting: a fully qualified project name whose source the pass should have indexed.
     * A simple name with no row is an external dependency or a type variable; it has no source file in
     * this pass, so treating it as an unresolved input would turn every library type into a divergence.
     */
    private static boolean looksProjectType(String raw) {
        String name = raw == null ? "" : raw.trim();
        return name.startsWith("hr.")
                || name.startsWith("hipster.")
                || name.startsWith("jcodebuddy.");
    }
}
