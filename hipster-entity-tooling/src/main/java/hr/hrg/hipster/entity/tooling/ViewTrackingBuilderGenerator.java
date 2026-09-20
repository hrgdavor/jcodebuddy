package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.TrackableType;
import hr.hrg.hipster.entity.tooling.meta.ViewMeta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates {@code <View>BuilderTracking} — the materialization that makes change tracking real
 * (plan.dsflash § 8.6/3.14–3.16).
 *
 * <h3>Why this shape</h3>
 * <ul>
 *   <li>It imports the tracking contract from {@code hr.hrg.hipster.entity.core}, not {@code api},
 *       because S3/X1 are deferred — a consuming project needs the {@code core} dependency (the
 *       hand-written example already does exactly this).</li>
 *   <li>It declares {@code ViewChangeTracking<View_, EEnumSet<View_>>}: {@code S} is the
 *       <em>immutable</em> {@code EEnumSet} that {@code mf.toImmutable()} actually returns, never
 *       the final class {@code EEnumSet64} (§ 4.7/DR-4 — the earlier {@code EEnumSet64} answer could
 *       not compile, because {@code EEnumSet64} is a sibling of the cached {@code EEnumSetEmpty}
 *       singleton that an empty set returns).</li>
 *   <li>Both accessors derive from the <strong>one</strong> {@code mf} field — never a second field
 *       or a snapshot cache — which is what makes {@code changes()} and {@code changesBuilder()}
 *       two views over the same state (§ 4.1/S5).</li>
 *   <li>The setter body is exactly the verified order
 *       {@code if (!Objects.equals(field, value)) { field = value; mf.addOrdinal(<literal>); }}, with
 *       the ordinal as a <em>literal</em> so the JIT can constant-fold the {@code 1L << ordinal} mask
 *       (§ 8.6/3.15). The comparison sits at the write site because the change set keeps no values:
 *       it records <em>which</em> ordinal changed and nothing about the value it changed from.</li>
 *   <li>Generated setters touch {@code mf} directly and never route through {@code changes()}:
 *       {@code changes()} allocates a snapshot and is a read-side API for callers.</li>
 * </ul>
 *
 * <p>Which concrete builder is used ({@code EEnumSetBuilder64} or {@code EEnumSetBuilderLarge}) is
 * decided <strong>at generation time</strong> from the known field count — the runtime
 * {@code .create()} selection stays only for the array/proxy route (§ 8.6/3.14).</p>
 */
public final class ViewTrackingBuilderGenerator {

    private ViewTrackingBuilderGenerator() {
    }

    /** What the generator produced, for tests and diagnostics. */
    public record Result(Path builderFile, String builderClass, List<String> writableFields) {
    }

    /**
     * @param outputRoot   the java source root
     * @param packageName  the view's package
     * @param view         the view
     * @param allProperties the view's full, resolved property list in **ordinal order**
     */
    public static Result generate(Path outputRoot, String packageName, ViewMeta view, List<Property> allProperties)
            throws IOException {
        return generate(outputRoot, packageName, view, allProperties, null);
    }

    /**
     * As {@link #generate(Path, String, ViewMeta, List)}, reporting to a divergence sink.
     *
     * <p>Emits the same {@code missing_setter} diagnostic the untracked builder does (§ 8.7/3.20): a
     * writable field with no setter in the file being replaced. The two builders are separately
     * generated and separately hand-editable, so a setter can be missing from one and present in the
     * other.</p>
     */
    /**
     * As {@link #generate(Path, String, ViewMeta, List, DivergenceReporter)} with no deep wiring.
     *
     * <p>Used by callers that have not collected the project's trackable types — the deep path is then
     * simply absent, which is what the {@code ViewChangeTracking} defaults describe.</p>
     */
    public static Result generate(Path outputRoot, String packageName, ViewMeta view,
                                  List<Property> allProperties, DivergenceReporter divergences)
            throws IOException {
        return generate(outputRoot, packageName, view, allProperties, Map.of(), java.util.Set.of(),
                divergences);
    }

    /**
     * As {@link #generate(Path, String, ViewMeta, List, DivergenceReporter)}, wiring the deep-change
     * walk for every field whose type is one of {@code trackableTypes} (plan.dsflash § 11/6.5).
     *
     * @param trackableTypes        the project's trackable views, keyed by simple name
     * @param nonTrackableViewNames views the author annotated but did not give a tracking level, so the
     *                              diagnostic can name the one-word fix
     */
    public static Result generate(Path outputRoot, String packageName, ViewMeta view,
                                  List<Property> allProperties,
                                  java.util.Map<String, TrackableType> trackableTypes,
                                  java.util.Set<String> nonTrackableViewNames,
                                  DivergenceReporter divergences)
            throws IOException {
        Path packageDir = packageName == null || packageName.isBlank()
                ? outputRoot
                : outputRoot.resolve(packageName.replace('.', '/'));

        List<Property> writable = allProperties.stream().filter(ViewAdapterGenerator::isWritable).toList();
        String builderClass = view.name() + "BuilderTracking";
        Path builderFile = packageDir.resolve(builderClass + ".java");

        List<Nested> nested = classifyNested(view, allProperties, trackableTypes,
                nonTrackableViewNames, divergences);

        if (divergences != null) {
            ViewBuilderGenerator.reportMissingSetters(builderFile, builderClass, view.name(),
                    writable, divergences);
        }
        // § 8.7/3.18 + § 4.5/G3 + the follow-up plan's § 1.1: the tracking builder is a whole-file
        // emission that users extend — the example's hand-written `TrackingStrict` is the live case —
        // so the previous revision is reconciled member by member before it is overwritten (DEC-020's
        // three-state model): a member the emitter does not produce is carried through verbatim, and an
        // edit to one it DOES produce is reported as generated_member_diverged.
        CooperativeCodegen.Reconciled reconciled = CooperativeCodegen.reconcileMembers(
                builderFile, builderClass,
                source(packageName, view, allProperties, writable, builderClass, nested),
                CooperativeCodegen.Reconciliation.ALL,
                // A retired field's setter was emitted by an earlier revision and stopped on purpose
                // (R1.4), so it must not be read as the developer's own and carried back.
                allProperties.stream()
                        .filter(ViewAdapterGenerator::isRetired)
                        .map(Property::name)
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        if (divergences != null) {
            divergences.addAll(reconciled.divergences());
        }
        Files.writeString(builderFile, reconciled.source());
        return new Result(builderFile, builderClass, writable.stream().map(Property::name).toList());
    }

    /**
     * One field the deep walk descends into.
     *
     * @param collection          {@code true} for a {@code List<Tracked>}, in which case the path
     *                            carries the element index; {@code false} for a directly nested view
     * @param declaredElementType the element's type <em>as written</em>, used to declare locals that
     *                            keep the author's own spelling (and therefore their imports)
     * @param elementType         the trackable view the element resolves to, used for the cast
     */
    record Nested(Property property, int ordinal, boolean collection, String declaredElementType,
                  TrackableType elementType) {

        /** The cast that reaches the tracking contract through the view's plain declared type. */
        String trackerExpression(String value) {
            return "(hr.hrg.hipster.entity.core.ViewChangeTracking<"
                    + elementType.enumQualifiedName() + ", ?>) " + value;
        }
    }

    /**
     * Decides which fields the deep walk descends into.
     *
     * <p>Recognition is by <strong>erased simple name</strong> against the project's trackable views
     * (§ 11/6.5), for a direct field or for the single type argument of a {@code List}. That is a name
     * lookup rather than the full symbol resolution § 8.2/3.6 describes, and deliberately so: what has
     * to be decided is only "is this one of our views, and is it generated at a tracking level", which
     * the interface map answers exactly. A raw {@code List} has no element type to inspect, so it is
     * left unwired.</p>
     *
     * @param viewLevel the level of views that are <em>not</em> trackable, so the diagnostic can say
     *                  what the field's type actually resolved to
     */
    private static List<Nested> classifyNested(ViewMeta view, List<Property> allProperties,
                                               java.util.Map<String, TrackableType> trackableTypes,
                                               java.util.Set<String> knownNonTrackableViews,
                                               DivergenceReporter divergences) {
        List<Nested> nested = new ArrayList<>();
        for (int ordinal = 0; ordinal < allProperties.size(); ordinal++) {
            Property property = allProperties.get(ordinal);
            if (ViewAdapterGenerator.isRetired(property)) {
                continue;
            }
            String erased = ValidationGenerator.erase(property.type());
            TrackableType direct = trackableTypes.get(erased);
            if (direct != null) {
                nested.add(new Nested(property, ordinal, false, property.type(), direct));
                continue;
            }
            List<String> arguments = typeArguments(property.type());
            if (arguments.size() != 1) {
                continue;
            }
            TrackableType elementType = trackableTypes.get(ValidationGenerator.erase(arguments.get(0)));
            if (elementType == null) {
                continue;
            }
            nested.add(new Nested(property, ordinal, true, arguments.get(0), elementType));
        }

        // The one actionable case: the field holds a view of this project that is simply not at a
        // tracking level, so the author asked for tracking on the parent and cannot get it here. A
        // nested JDK type, or a view that is not generated at all, is silent — warning about those
        // would drown a report whose whole value is that each line can be acted on.
        if (divergences != null) {
            for (Property property : allProperties) {
                String erased = ValidationGenerator.erase(property.type());
                if (trackableTypes.containsKey(erased)
                        || typeArguments(property.type()).stream()
                                .map(ValidationGenerator::erase)
                                .anyMatch(trackableTypes::containsKey)) {
                    continue;
                }
                if (knownNonTrackableViews.contains(erased)) {
                    divergences.report("deep_tracking_type_not_enabled",
                            view.name() + "." + property.name(),
                            "the field holds a view of this project that is not generated at a "
                                    + "tracking level, so changes inside it cannot be reached",
                            property.type(), "no deep wiring for this field",
                            "declare `@View(gen = GenLevel.BUILDER_TRACKED)` on " + erased
                                    + " (or BUILDER_ALL), or accept that changes inside it are invisible");
                }
            }
        }
        return nested;
    }

    /** The type arguments of a declared type, split at depth 0. Empty for a non-generic type. */
    static List<String> typeArguments(String declaredType) {
        if (declaredType == null) {
            return List.of();
        }
        int start = declaredType.indexOf('<');
        int end = declaredType.lastIndexOf('>');
        if (start < 0 || end <= start) {
            return List.of();
        }
        String inner = declaredType.substring(start + 1, end);
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : inner.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                arguments.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            arguments.add(current.toString().trim());
        }
        return arguments;
    }

    private static String source(String packageName, ViewMeta view, List<Property> allProperties,
                                 List<Property> writable, String builderClass, List<Nested> nested) {
        String viewName = view.name();
        String enumName = viewName + "_";
        String builderType = allProperties.size() <= 64 ? "EEnumSetBuilder64" : "EEnumSetBuilderLarge";

        StringBuilder sb = new StringBuilder();
        String fqn = packageName == null || packageName.isBlank() ? viewName : packageName + "." + viewName;
        sb.append("// {@link ").append(fqn).append("} Tracking builder for the ")
                .append(viewName).append(" view.\n");
        sb.append("// {enabled:true, blockMarker: \"implicit\"}\n");
        sb.append("package ").append(packageName).append(";\n\n");

        sb.append("import hr.hrg.hipster.entity.core.EEnumSet;\n");
        sb.append("import hr.hrg.hipster.entity.core.EEnumSetBuilder;\n");
        sb.append("import hr.hrg.hipster.entity.core.").append(builderType).append(";\n");
        sb.append("import hr.hrg.hipster.entity.core.ViewChangeTracking;\n");
        // Field types are emitted as written in the view source, so any JDK collection the view
        // mentions must be imported explicitly. Emitting `Map<String, List<Long>>` without these
        // imports is what made the first BUILDER_TRACKED output fail to compile (task 3.6's
        // "stop emitting raw source type strings" applied to the field declarations).
        for (String importName : JdkImportSupport.importsFor(allProperties)) {
            sb.append("import ").append(importName).append(";\n");
        }
        for (String importName : ValidationGenerator.importsFor(allProperties)) {
            sb.append("import ").append(importName).append(";\n");
        }
        sb.append('\n');

        sb.append("/**\n");
        sb.append(" * A mutable copy of a {@link ").append(viewName).append("} that records which fields were\n");
        sb.append(" * written with a value different from the one they held.\n");
        sb.append(" *\n");
        sb.append(" * <p>It keeps no old value: the value a field held before the write belongs to the\n");
        sb.append(" * baseline instance this builder was constructed from, which stays the caller's object.\n");
        sb.append(" * A consumer that wants an old -> new comparison is handed both and compares them.</p>\n");
        sb.append(" *\n");
        sb.append(" * <p>{@code changes()} is an immutable snapshot and {@code changesBuilder()} is the live\n");
        sb.append(" * mutable set; both are views over the single {@code mf} field, so they can never\n");
        sb.append(" * disagree.</p>\n");
        sb.append(" */\n");
        sb.append("public class ").append(builderClass)
                .append(" implements ViewChangeTracking<").append(enumName).append(", EEnumSet<").append(enumName).append(">> {\n\n");

        sb.append("    /** The one piece of tracking state. Both accessors derive from it. */\n");
        sb.append("    final ").append(builderType).append("<").append(enumName).append("> mf = new ")
                .append(builderType).append("<>(").append(enumName).append(".values());\n\n");

        for (Property property : allProperties) {
            for (hr.hrg.hipster.entity.tooling.meta.FieldConstraint constraint : property.constraints()) {
                sb.append("    ").append(constraint.annotation()).append('\n');
            }
            sb.append("    ").append(boxedType(property.type())).append(' ').append(property.name()).append(";\n");
        }
        sb.append('\n');

        // Copy constructor from the source view: without a baseline, "changed" is meaningless
        // (§ 8.6/3.16).
        sb.append("    /** Builds the tracking state from a baseline view (§ 8.6/3.16). */\n");
        sb.append("    public ").append(builderClass).append('(').append(viewName).append(" source) {\n");
        for (Property property : allProperties) {
            if (ViewAdapterGenerator.isRetired(property)) {
                // R1.4: the tombstone keeps its slot and nothing else. Its accessor is gone, so there
                // is no baseline value to copy, and the builder's field stays null.
                continue;
            }
            sb.append("        this.").append(property.name()).append(" = source.")
                    .append(property.name()).append("();\n");
        }
        sb.append("    }\n\n");

        // Read accessors, so the builder can be used wherever the view is expected.
        for (Property property : allProperties) {
            if (ViewAdapterGenerator.isRetired(property)) {
                // Accessible positionally through get(int); no named accessor for a field the view no
                // longer declares.
                continue;
            }
            sb.append("    public ").append(boxedType(property.type())).append(' ')
                    .append(property.name()).append("() { return ").append(property.name()).append("; }\n");
        }
        sb.append('\n');

        // Positional access, matching the ordinal contract.
        sb.append("    public Object get(int fieldOrdinal) {\n");
        sb.append("        return switch (fieldOrdinal) {\n");
        for (int i = 0; i < allProperties.size(); i++) {
            sb.append("            case ").append(i).append(" -> ").append(allProperties.get(i).name()).append(";\n");
        }
        sb.append("            default -> null;\n");
        sb.append("        };\n");
        sb.append("    }\n\n");

        // The canonical tracking setter, emitted only for writable fields (S1).
        //
        // The comparison is emitted AT THE WRITE SITE, which is what makes the DEC-012 no-op rule
        // visible and leaves the change set with no value state at all: the tracker records which
        // ordinal changed and nothing about what it changed from. `java.util.Objects` is spelled in
        // full so the comparison never depends on the generated file's import list.
        for (Property property : writable) {
            int ordinal = allProperties.indexOf(property);
            String type = boxedType(property.type());
            sb.append("    /** Fluent setter that marks the change when the value actually differs. */\n");
            sb.append("    public ").append(builderClass).append(' ').append(property.name())
                    .append('(').append(type).append(" value) {\n");
            sb.append("        if (!java.util.Objects.equals(this.").append(property.name())
                    .append(", value)) {\n");
            sb.append("            this.").append(property.name()).append(" = value;\n");
            sb.append("            mf.addOrdinal(").append(ordinal).append(");\n");
            sb.append("        }\n");
            sb.append("        return this;\n");
            sb.append("    }\n\n");
        }

        // set(int, Object) and set(String, Object): only writable ordinals, -1 on an unknown name.
        sb.append("    /** Positional mutator. Only writable ordinals are accepted (S1). */\n");
        sb.append("    public void set(int fieldOrdinal, Object value) {\n");
        sb.append("        switch (fieldOrdinal) {\n");
        for (Property property : writable) {
            int ordinal = allProperties.indexOf(property);
            String type = boxedType(property.type());
            sb.append("            case ").append(ordinal).append(" -> { if (!java.util.Objects.equals(this.")
                    .append(property.name()).append(", value)) { this.").append(property.name())
                    .append(" = (").append(type).append(") value; mf.addOrdinal(").append(ordinal)
                    .append("); } }\n");
        }
        sb.append("            default -> throw new UnsupportedOperationException(\n");
        sb.append("                    \"Field \" + fieldOrdinal + \" is not writable on ").append(viewName).append("\");\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        sb.append("    /** Name mutator. Returns -1 for an unknown field, matching the array contract (D5). */\n");
        sb.append("    public int set(String field, Object value) {\n");
        sb.append("        ").append(enumName).append(" def = ").append(enumName).append(".forName(field);\n");
        sb.append("        if (def == null) {\n");
        sb.append("            return -1;\n");
        sb.append("        }\n");
        sb.append("        set(def.ordinal(), value);\n");
        sb.append("        return def.ordinal();\n");
        sb.append("    }\n\n");

        // The S5 accessor pair, both derived from `mf`.
        sb.append("    @Override\n");
        sb.append("    public boolean isChanged() {\n");
        sb.append("        return !mf.isEmpty();\n");
        sb.append("    }\n\n");
        sb.append("    /** Immutable snapshot — allocates when non-empty, so never use it on a hot path. */\n");
        sb.append("    @Override\n");
        sb.append("    public EEnumSet<").append(enumName).append("> changes() {\n");
        sb.append("        return mf.toImmutable();\n");
        sb.append("    }\n\n");
        sb.append("    /** The live, mutable change set — the same state {@link #changes()} snapshots. */\n");
        sb.append("    @Override\n");
        sb.append("    public EEnumSetBuilder<").append(enumName).append("> changesBuilder() {\n");
        sb.append("        return mf;\n");
        sb.append("    }\n\n");
        sb.append("    @Override\n");
        sb.append("    public void clearChanges() {\n");
        sb.append("        mf.clear();\n");
        sb.append("    }\n\n");
        sb.append("    @Override\n");
        sb.append("    public Object currentValue(").append(enumName).append(" field) {\n");
        sb.append("        return field == null ? null : get(field.ordinal());\n");
        sb.append("    }\n");

        // § 11/6.5: the deep walk. Emitted only when at least one field actually nests something, so a
        // flat view keeps the interface's own defaults and gains no code at all.
        if (!nested.isEmpty()) {
            sb.append(deepAccessors(enumName, nested));
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * The deep-change accessors (plan.dsflash § 11/6.5, DEC-024).
     *
     * <p>Each method answers one of the questions {@code ViewChangeTracking} asks, and each is a
     * concrete, navigable method body rather than a reflection walk (AGENTS.md § 1):</p>
     * <ul>
     *   <li>{@code changesDeep()} — the paths. This level's own marked fields come first, except the
     *       fields that nest something: a marked nested field whose children report nothing falls back
     *       to naming the field itself, because "the reference was replaced" and "something inside it
     *       changed" are different findings and dropping either loses information (notes F-15/F-G).</li>
     *   <li>{@code nestedTrackers()} — the direct children by ordinal, so a caller can descend without
     *       knowing the view's shape.</li>
     *   <li>{@code collectionDeltas()} — the per-index field deltas inside a {@code List<Tracked>}. The
     *       elements are asked for their own {@code changedValues()}, which is why the collection level needs no
     *       baseline of its own: each element already carries one.</li>
     *   <li>{@code hasCollection(int)} — the structural question. It cannot be inferred from
     *       {@code collectionDeltas()}, which is empty both for a field that is not a collection and
     *       for one that is but has not changed; without it the JSON patch writer classified a
     *       replaced list as a scalar leaf (notes F-E).</li>
     * </ul>
     *
     * <p>Structural deltas — an entry added, removed or reordered — are <strong>not</strong> produced
     * here. The builder holds the elements themselves, so its baseline is each element's own; the
     * array-backed path is the one that keeps a {@code ListChangeTracker} over the list it stores and
     * can therefore report structure. Both materializations agree on the paths, which is what § 6.7's
     * parity test asserts.</p>
     */
    private static String deepAccessors(String enumName, List<Nested> nested) {
        StringBuilder sb = new StringBuilder();
        List<Nested> collections = nested.stream().filter(Nested::collection).toList();
        List<Nested> direct = nested.stream().filter(n -> !n.collection()).toList();

        sb.append('\n');
        // ASCII only in emitted code: generated files must compile under whatever source encoding a
        // consuming project happens to set, and an encoding mismatch in a comment is a build failure
        // that has nothing to do with the code (this repository has already been bitten by mixed
        // encodings in its docs).
        sb.append("    // -- Deep change tracking (plan.dsflash 11/6.5; DEC-024) ----------------------\n\n");

        if (!collections.isEmpty()) {
            sb.append("    /** Per-index field deltas inside this view's tracked collections. */\n");
            sb.append("    @Override\n");
            sb.append("    public java.util.Map<Integer, java.util.List<")
                    .append("hr.hrg.hipster.entity.core.ListDelta>> collectionDeltas() {\n");
            sb.append("        java.util.Map<Integer, java.util.List<")
                    .append("hr.hrg.hipster.entity.core.ListDelta>> all = new java.util.LinkedHashMap<>();\n");
            for (Nested collection : collections) {
                String name = collection.property().name();
                String element = collection.declaredElementType();
                String tracker = "tracker" + collection.ordinal();
                String elementVar = "element" + collection.ordinal();
                sb.append("        if (").append(name).append(" != null) {\n");
                sb.append("            java.util.List<hr.hrg.hipster.entity.core.ListDelta> deltas = "
                        + "new java.util.ArrayList<>();\n");
                sb.append("            for (int i = 0; i < ").append(name).append(".size(); i++) {\n");
                sb.append("                ").append(element).append(' ').append(elementVar)
                        .append(" = ").append(name).append(".get(i);\n");
                // The element's declared type is the plain view interface, so the tracking contract is
                // reached through a cast that names the element's own field enum. The type argument
                // matters: with a wildcard element type, changedValues()'s result could not be widened
                // into the List<FieldChange<?>> a ListDelta carries.
                sb.append("                hr.hrg.hipster.entity.core.ViewChangeTracking<")
                        .append(collection.elementType().enumQualifiedName())
                        .append(", ?> ").append(tracker).append(" = ").append(collection.trackerExpression(elementVar))
                        .append(";\n");
                sb.append("                java.util.List<hr.hrg.hipster.entity.core.FieldChange<?>> inside = "
                        + "new java.util.ArrayList<hr.hrg.hipster.entity.core.FieldChange<?>>();\n");
                sb.append("                for (hr.hrg.hipster.entity.core.FieldChange<?> change : ")
                        .append(tracker).append(".changedValues()) {\n");
                sb.append("                    inside.add(change);\n");
                sb.append("                }\n");
                sb.append("                if (!inside.isEmpty()) {\n");
                sb.append("                    deltas.add(new hr.hrg.hipster.entity.core.ListDelta(\n");
                sb.append("                            hr.hrg.hipster.entity.core.ListChangeKind.FIELD_CHANGED, i, -1,\n");
                sb.append("                            ").append(collection.elementType().identifiable()
                                ? elementVar + ".id()" : "null").append(",\n");
                sb.append("                            inside, false));\n");
                sb.append("                }\n");
                sb.append("            }\n");
                sb.append("            if (!deltas.isEmpty()) {\n");
                sb.append("                all.put(").append(collection.ordinal()).append(", deltas);\n");
                sb.append("            }\n");
                sb.append("        }\n");
            }
            sb.append("        return all;\n");
            sb.append("    }\n\n");

            sb.append("    /** Whether the field at this ordinal holds a tracked collection. */\n");
            sb.append("    @Override\n");
            sb.append("    public boolean hasCollection(int fieldOrdinal) {\n");
            sb.append("        return switch (fieldOrdinal) {\n");
            for (Nested collection : collections) {
                sb.append("            case ").append(collection.ordinal()).append(" -> true;\n");
            }
            sb.append("            default -> false;\n");
            sb.append("        };\n");
            sb.append("    }\n\n");
        }

        if (!direct.isEmpty()) {
            sb.append("    /** The directly nested tracked views, by ordinal. */\n");
            sb.append("    @Override\n");
            sb.append("    public java.util.Map<Integer, hr.hrg.hipster.entity.core.ViewChangeTracking<?, ?>> "
                    + "nestedTrackers() {\n");
            sb.append("        java.util.Map<Integer, hr.hrg.hipster.entity.core.ViewChangeTracking<?, ?>> all = "
                    + "new java.util.LinkedHashMap<>();\n");
            for (Nested field : direct) {
                String name = field.property().name();
                sb.append("        if (").append(name).append(" != null) {\n");
                sb.append("            all.put(").append(field.ordinal()).append(", ")
                        .append(field.trackerExpression(name)).append(");\n");
                sb.append("        }\n");
            }
            sb.append("        return all;\n");
            sb.append("    }\n\n");
        }

        sb.append("    /**\n");
        sb.append("     * The deep paths: this level's own changes, then one path per nested change with the\n");
        sb.append("     * element index attached.\n");
        sb.append("     */\n");
        sb.append("    @Override\n");
        sb.append("    public java.util.List<hr.hrg.hipster.entity.core.ChangePath> changesDeep() {\n");
        sb.append("        java.util.List<hr.hrg.hipster.entity.core.ChangePath> paths = "
                + "new java.util.ArrayList<>();\n");
        sb.append("        mf.forEach((field, index) -> {\n");
        sb.append("            if (");
        for (int i = 0; i < nested.size(); i++) {
            if (i > 0) {
                sb.append("\n                    && ");
            }
            sb.append("field != ").append(enumName).append('.').append(nested.get(i).property().name());
        }
        sb.append(") {\n");
        sb.append("                paths.add(hr.hrg.hipster.entity.core.ChangePath.of(field));\n");
        sb.append("            }\n");
        sb.append("        });\n");
        for (Nested field : nested) {
            String name = field.property().name();
            String flag = "descended" + field.ordinal();
            String tracker = "tracker" + field.ordinal();
            sb.append("        boolean ").append(flag).append(" = false;\n");
            if (field.collection()) {
                sb.append("        if (").append(name).append(" != null) {\n");
                sb.append("            for (int i = 0; i < ").append(name).append(".size(); i++) {\n");
                sb.append("                hr.hrg.hipster.entity.core.ViewChangeTracking<")
                        .append(field.elementType().enumQualifiedName()).append(", ?> ").append(tracker)
                        .append(" = ").append(field.trackerExpression(name + ".get(i)")).append(";\n");
                sb.append("                for (hr.hrg.hipster.entity.core.ChangePath childPath : ")
                        .append(tracker).append(".changesDeep()) {\n");
                sb.append("                    paths.add(new hr.hrg.hipster.entity.core.ChangePath(")
                        .append(enumName).append('.').append(name).append(", i, childPath));\n");
                sb.append("                    ").append(flag).append(" = true;\n");
                sb.append("                }\n");
                sb.append("            }\n");
                sb.append("        }\n");
            } else {
                sb.append("        hr.hrg.hipster.entity.core.ViewChangeTracking<")
                        .append(field.elementType().enumQualifiedName()).append(", ?> ").append(tracker)
                        .append(" = ").append(name).append(" == null ? null : ")
                        .append(field.trackerExpression(name)).append(";\n");
                sb.append("        if (").append(tracker).append(" != null) {\n");
                sb.append("            for (hr.hrg.hipster.entity.core.ChangePath childPath : ")
                        .append(tracker).append(".changesDeep()) {\n");
                sb.append("                paths.add(new hr.hrg.hipster.entity.core.ChangePath(")
                        .append(enumName).append('.').append(name).append(", -1, childPath));\n");
                sb.append("                ").append(flag).append(" = true;\n");
                sb.append("            }\n");
                sb.append("        }\n");
            }
            // The fallback: the reference itself was replaced (or the collection was), and nothing
            // inside it reports a change, so the field is the finding.
            sb.append("        if (mf.has(").append(enumName).append('.').append(name).append(") && !")
                    .append(flag).append(") {\n");
            sb.append("            paths.add(hr.hrg.hipster.entity.core.ChangePath.of(")
                    .append(enumName).append('.').append(name).append("));\n");
            sb.append("        }\n");
        }
        sb.append("        return paths;\n");
        sb.append("    }\n");
        return sb.toString();
    }

    /**
     * The erased, boxed form of a declared type, suitable for a mutable field.
     *
     * <p>Primitives are boxed because the tracking state stores values as {@code Object} and because
     * a nullable field is the only way to represent "absent" in the positional array.</p>
     */
    static String boxedType(String declaredType) {
        if (declaredType == null) {
            return "Object";
        }
        String type = declaredType.trim();
        int generic = type.indexOf('<');
        String raw = generic < 0 ? type : type.substring(0, generic);
        return switch (raw) {
            case "byte" -> "Byte";
            case "short" -> "Short";
            case "int" -> "Integer";
            case "long" -> "Long";
            case "float" -> "Float";
            case "double" -> "Double";
            case "boolean" -> "Boolean";
            case "char" -> "Character";
            default -> type;
        };
    }
}
