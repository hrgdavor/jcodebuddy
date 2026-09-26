package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.validation.EnumConstantOrderChecker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The view's <strong>field enum</strong> — the file that names a view's fields and, in field-enum mode,
 * carries the R1 append-only ordinal ledger.
 *
 * <h3>Phase 6: what the port changed, and what it deliberately did not</h3>
 * <p>The JavaParser version <em>built a {@code CompilationUnit}</em> — {@code addEnum}, {@code addEntry},
 * {@code addMember}, {@code setJavadocComment}, {@code parseExpression} for every initialiser — and then
 * printed it through {@code PrettyPrinterConfiguration} with two textual fix-ups
 * ({@code switch(} → {@code switch (}, and collapsing an arrow's newline). An LST is immutable, so none
 * of those calls has an equivalent; more importantly they were never the point. The output is a file, and
 * the file is generated here as <strong>text</strong>, which is what the rest of this module already does
 * ({@code ValidationGenerator}, the builders, {@code ViewRecordGenerator}).</p>
 *
 * <p>That is not a formatting free-for-all: the enums this class writes are <strong>committed</strong>,
 * and {@code ExampleRegenerationTest} regenerates the example and compares every one of them
 * byte-for-byte. So the text emitted below reproduces the printer's output exactly, including the parts
 * that only make sense as printer artefacts — {@code @Override()} with empty parentheses, a constant's
 * separating comma on a line of its own, and the mixed line endings the old path produced (the two-line
 * DEC-021 header ends each line with {@code \n} while the printed body uses the platform separator). Each
 * of those is called out where it is emitted, because a reader will otherwise delete it.</p>
 *
 * <p>The alternative — building the enum with {@code withXxx}/{@code JavaTemplate} and printing it — was
 * rejected for this file: OpenRewrite's printer formats differently from JavaParser's (measured: a space
 * after the comma inside a type argument), so it would have regenerated every committed field enum with
 * different bytes. That is a change to committed artifacts, not a port. The full reasoning is in
 * {@code doc/brainstorm/rewrite-migration/06-migration/MIGRATION-CAVEATS.md} § 4.2.</p>
 */
public final class FieldBoilerplateGenerator {

    private final String packageName;
    private final String viewName;
    private final String enumName;
    private final List<Property> properties;
    private final boolean fieldEnumMode;
    private final String metaCreatorBody;
    private final String discriminatorFieldReference;
    private final String discriminatorValue;
    private final String[] permittedSubtypeClassNames;
    private final List<String> additionalImports;
    private final java.util.function.Consumer<List<String>> divergenceSink;

    private FieldBoilerplateGenerator(Builder builder) {
        this.packageName = builder.packageName;
        this.viewName = builder.viewName;
        this.enumName = builder.enumName;
        this.properties = List.copyOf(builder.properties);
        this.fieldEnumMode = builder.fieldEnumMode;
        this.metaCreatorBody = builder.metaCreatorBody;
        this.discriminatorFieldReference = builder.discriminatorFieldReference;
        this.discriminatorValue = builder.discriminatorValue;
        this.permittedSubtypeClassNames = builder.permittedSubtypeClassNames.clone();
        this.additionalImports = List.copyOf(builder.additionalImports);
        this.divergenceSink = builder.divergenceSink;
        // Read by the static `classLiteral` while this instance emits, so the pass-level set is
        // published here (the enum's `javaType()` is the one place a type name becomes a class literal
        // in this generator).
        typeParameterNames = builder.typeParameterNames;
    }

    public static Builder builder(String packageName, String viewName, List<Property> properties) {
        return new Builder(packageName, viewName, properties);
    }

    public void generate(Path outputRoot) throws IOException {
        Objects.requireNonNull(outputRoot, "outputRoot");
        Path packageDir = packageName == null || packageName.isBlank()
                ? outputRoot
                : outputRoot.resolve(packageName.replace('.', '/'));
        Files.createDirectories(packageDir);

        Path enumFile = packageDir.resolve(enumName + ".java");

        // Never clobber a polymorphic ROOT enum (plan.dsflash § 9/4.9, § 4.5/G6 rule 0). The root's
        // `_` enum is hand-written and carries the discriminator wiring the generator cannot derive
        // from the view: the discriminator FIELD constant plus the permitted-subtype array. The
        // example's `PaymentMethod_` is the live case — four generated subclass enums, and one
        // hand-written root that binds them together. Detected structurally (a `ViewMeta`
        // initialiser with more than the four basic arguments), so a plain field enum is unaffected.
        if (fieldEnumMode && isUnreadableEnumFile(enumFile)) {
            // Fail safe (SourceReader / DR-7): the previous revision could not be read, so nothing
            // about it can be preserved — rebuild nothing, overwrite nothing, and say so. This is the
            // one case where reporting beats editing, because the file's ordinals are the ledger.
            if (divergenceSink != null) {
                divergenceSink.accept(List.of(
                        "kind=enum_not_parsed, location=" + enumName
                                + ", cause=the existing enum could not be parsed"
                                + ", current=unparseable source"
                                + ", canonical=left exactly as it is on disk"
                                + ", action=inspect the file by hand: this pass did NOT preserve the "
                                + "append-only ledger (R1) and did not regenerate the enum"));
            }
            return;
        }
        if (fieldEnumMode && isPolymorphicRootEnum(enumFile)) {
            if (divergenceSink != null) {
                divergenceSink.accept(List.of(
                        "kind=polymorphic_root_enum_preserved, location=" + enumName
                                + ", cause=the existing enum declares a ViewMeta with a discriminator"
                                + " field and permitted subtypes, current=hand-written polymorphic root"
                                + ", canonical=not regenerated, action=edit it by hand (§ 9/4.9)"));
            }
            return;
        }

        // Append-only ledger handling (plan.dsflash § 8.3/3.7a, § 4.6/R1, § 4.5/G7). The existing
        // file is read *before* the new one is written, so the constant order can be preserved.
        LedgerPlan ledger = planLedger(enumFile);
        // Cooperative preservation (DEC-020, § 8.7/3.19, and the follow-up plan's § 1.1): the constant
        // LIST is the R1 ledger's to decide, and the rest of the file is reconciled member by member —
        // a nested type or helper the developer added is carried over verbatim, an edit to a generated
        // member is reported.
        CooperativeCodegen.Reconciled reconciled = CooperativeCodegen.reconcileMembers(
                enumFile, enumName, buildSource(ledger),
                // The constant list is the R1 ledger's, and the enum's fields (its own `javaType` and
                // `META`) are the generator's, so this reconciliation owns methods and nested types —
                // the two shapes a developer actually adds here. Preserving constants would fight the
                // ledger: it would resurrect the pre-retirement constants a tombstoning pass removed,
                // and the ordinals every other artifact is driven by would no longer be the ledger's.
                CooperativeCodegen.Reconciliation.METHODS_AND_TYPES_ONLY);
        Files.writeString(enumFile, reconciled.source());
        if (divergenceSink != null) {
            divergenceSink.accept(ledger.divergences());
            divergenceSink.accept(reconciled.divergences());
        }
    }

    /**
     * Whether an existing field enum must be left completely alone.
     *
     * <p>Two answers are "yes", and the second one is the fail-safe direction of
     * {@link SourceReader}:</p>
     * <ol>
     *   <li>the file parses and is a <strong>hand-written polymorphic root</strong> — identified by a
     *       non-empty permitted-subtype list in its {@code META}, the one piece of wiring the
     *       generator cannot derive. Counting arguments is not enough: an earlier revision used "more
     *       than the four basic arguments", which is also true of a generated enum whose generator
     *       happened to pass the discriminator arguments as {@code null}/{@code ""}, and that
     *       silently disabled the append/tombstone ledger for every view. Requiring a populated list
     *       distinguishes the root from a plain generated enum that merely has the empty array;</li>
     *   <li>the file <strong>cannot be parsed at all</strong>. It used to return {@code false} here,
     *       which let the append-only pass overwrite a file nobody could read — the one artifact
     *       (§ 9/4.9's hand-written root) and the one contract (R1's ordinals) where an unreadable
     *       previous revision must stop the write. A file that cannot be read is preserved and
     *       reported, never rebuilt.</li>
     * </ol>
     *
     * <p>The caller distinguishes the two, because they are different facts: an unreadable file
     * reports {@code source_not_parsed} ("this pass did not regenerate it"), a parseable hand-written
     * root reports {@code polymorphic_root_enum_preserved} ("this is the root, edit it by hand").</p>
     */
    static boolean isPolymorphicRootEnum(Path enumFile) throws IOException {
        if (!Files.exists(enumFile)) {
            return false;
        }
        // The shared read (SourceReader), for the reason DR-7 records: a parser recovers from broken
        // source, and a partially-read enum must be preserved rather than rebuilt from what little was
        // understood — its ordinals are the ledger.
        org.openrewrite.java.tree.J.CompilationUnit unit =
                SourceReader.readSourceText(Files.readString(enumFile));
        if (unit == null) {
            // Unreadable: preserve it. The report is emitted by the caller, which owns the sink.
            return true;
        }
        return isPolymorphicRootEnum(unit);
    }

    /**
     * Whether a parsed unit carries a populated permitted-subtype list.
     *
     * <p>Phase 6: JavaParser gave five expression classes here; the LST has one node per shape, and the
     * shape that matters is {@code new Class<?>[]{A.class, B.class}} — a {@code J.NewArray} with a
     * non-empty initialiser. {@code new Class<?>[0]} is the same node type with dimensions and no
     * initialiser, which is exactly the distinction this predicate has to keep: the array is emitted
     * unconditionally, so "an array is present" is true of every generated enum and would classify all
     * of them as hand-written roots.</p>
     */
    private static boolean isPolymorphicRootEnum(org.openrewrite.java.tree.J.CompilationUnit unit) {
        for (org.openrewrite.java.tree.J.NewClass creation
                : TreeQueries.findAll(unit, org.openrewrite.java.tree.J.NewClass.class)) {
            if (creation.getClazz() == null
                    || !"DefaultViewMeta".equals(TreeQueries.simpleTypeName(creation.getClazz()))
                    || creation.getArguments() == null) {
                continue;
            }
            for (org.openrewrite.java.tree.Expression argument : creation.getArguments()) {
                if (argument instanceof org.openrewrite.java.tree.J.NewArray array
                        && array.getInitializer() != null && !array.getInitializer().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the existing enum file is present but unreadable.
     *
     * <p>Separated from {@link #isPolymorphicRootEnum(Path)} so the caller can say <em>why</em> it
     * did not regenerate: "a hand-written polymorphic root" and "a file this pass could not parse"
     * are different facts and deserve different report lines.</p>
     */
    private static boolean isUnreadableEnumFile(Path enumFile) throws IOException {
        return Files.exists(enumFile) && !SourceReader.read(enumFile).readable();
    }

    /** The constants to emit, plus every divergence the ledger plan detected. */
    private record LedgerPlan(List<Property> ordered, List<String> tombstoned, List<String> divergences) {
        static LedgerPlan fresh(List<Property> properties) {
            return new LedgerPlan(properties, List.of(), List.of());
        }
    }

    /**
     * Decides what the constant list must look like, given what is already on disk.
     *
     * <p>Two paths, scoped by the R1 marker (§ 4.5/G7, § 4.7/DR-7):</p>
     * <ul>
     *   <li><strong>marker-carrying enum</strong> — the constant list starts with the existing list
     *       verbatim (never reordered, never re-inserted into a "canonical" position); constants for
     *       fields that no longer exist are kept in place as {@code @Deprecated} tombstones; new
     *       fields are appended after all existing ones;</li>
     *   <li><strong>marker-less existing enum</strong> — it has no committed ledger, so it is
     *       bootstrapped: the list is rebuilt from the resolved fields in ordinal order and stale
     *       constants are <em>dropped</em>, reported as {@code enum_constant_removed} with action
     *       {@code bootstrap}. This is the path the legacy example enums take.</li>
     * </ul>
     *
     * <p>A malformed header counts as marked (fail safe): treating it as bootstrap could delete a
     * live ordinal.</p>
     */
    private LedgerPlan planLedger(Path enumFile) throws IOException {
        if (!fieldEnumMode || !Files.exists(enumFile)) {
            return LedgerPlan.fresh(properties);
        }
        String existing = Files.readString(enumFile);
        org.openrewrite.java.tree.J.CompilationUnit cu = SourceReader.readSourceText(existing);
        // The fail-safe direction of DR-7, now shared with every other read in the generator
        // ({@link SourceReader}): a parser is error tolerant and can return a PARTIAL compilation unit
        // for broken source. Trusting the mere presence of a result meant a syntax error made the enum
        // look like it had NO constants at all, which routed it to the bootstrap path — i.e. exactly
        // the silent renumbering R1 exists to prevent, reached by the very code that was supposed to
        // protect against it. A file with parse problems is treated as unreadable.
        if (cu == null) {
            return new LedgerPlan(properties, List.of(), List.of(
                    "kind=enum_not_parsed, location=" + enumName
                            + ", cause=the existing enum could not be parsed"
                            + ", current=unparseable source"
                            + ", canonical=rebuilt from the resolved fields"
                            + ", action=inspect the file by hand: this pass did NOT preserve the "
                            + "append-only ledger (R1)"));
        }
        EnumConstantOrderChecker.HeaderConfig header = EnumConstantOrderChecker.readHeader(cu);
        if (!header.marked()) {
            // Bootstrap: rebuild from source. Any stale constant is dropped and reported.
            List<String> sourceNames = properties.stream().map(Property::name).toList();
            List<String> stale = new ArrayList<>();
            List<String> existingNames = enumConstants(cu);
            for (String existingName : existingNames) {
                if (!sourceNames.contains(existingName)) {
                    stale.add(existingName);
                }
            }
            List<String> divergences = new ArrayList<>();
            for (String dropped : stale) {
                divergences.add("kind=enum_constant_removed, location=" + enumName + "." + dropped
                        + ", cause=the constant has no matching accessor and the enum carried no "
                        + "entityFieldEnum marker, action=bootstrap: dropped pre-R1 constant");
            }
            auditExistingEnum(cu, existingNames, true, divergences);
            return new LedgerPlan(properties, List.of(), divergences);
        }

        // Marker-carrying: preserve and tombstone.
        List<String> existingNames = enumConstants(cu);
        java.util.Map<String, Property> byName = new java.util.LinkedHashMap<>();
        for (Property property : properties) {
            byName.put(property.name(), property);
        }

        List<Property> ordered = new ArrayList<>();
        List<String> tombstoned = new ArrayList<>();
        List<String> divergences = new ArrayList<>();
        for (String name : existingNames) {
            Property property = byName.get(name);
            if (property != null) {
                ordered.add(property);
                byName.remove(name);
            } else {
                // The accessor disappeared: keep the ordinal, mark the constant deprecated. The slot
                // stays in the positional array and the record keeps a nullable component, so
                // values.length == fieldValues().length and create()'s mapping are untouched.
                ordered.add(new Property(name, "java.lang.Object"));
                tombstoned.add(name);
                divergences.add("kind=field_retired, location=" + enumName + "." + name
                        + ", cause=the accessor no longer exists, current=active constant"
                        + ", canonical=@Deprecated tombstone, action=kept to preserve ordinals (R1.4)");
            }
        }
        // Everything left is a genuinely new field: appended after all existing constants.
        for (Property property : byName.values()) {
            ordered.add(property);
            divergences.add("kind=enum_constant_appended, location=" + enumName + "." + property.name()
                    + ", cause=a new accessor, action=appended at the end (R1)");
        }
        if (header.allowReorder()) {
            divergences.add("kind=enum_reorder_allowed, location=" + enumName
                    + ", cause=allowReorder:true is present in the header"
                    + ", action=remove the escape hatch once the layout is settled");
        }
        auditExistingEnum(cu, existingNames, false, divergences);
        return new LedgerPlan(ordered, tombstoned, divergences);
    }

    /**
     * The DEC-022 field-set, switch, type and drift diagnostics the plan requires
     * (plan.dsflash § 8.7/3.20).
     *
     * <p>These compare what the interface declares against what the file on disk actually contains,
     * which is the comparison the plan singles out: "the two diagnostics that must exist first are
     * 'field in the enum but not in the interface' and 'field in the interface but not in the
     * enum' — those are the two that bit the example". Before this method existed, the ledger
     * planner reported the *ledger events* it was about to perform ({@code enum_constant_appended},
     * {@code field_retired}, {@code enum_constant_removed}) but never the field-set divergence
     * itself, so a report could not answer "did the field set change?" without the reader
     * reconstructing it from the event list.</p>
     *
     * <p><strong>{@code ordinal_drift} is reported only on the bootstrap path.</strong> That is a
     * deliberate narrowing. On a marker-carrying enum the ordinals are append-only by R1, so a field
     * declared earlier in the interface than its constant appears in the enum is the <em>correct</em>
     * steady state after any append — reporting it on every pass would train readers to ignore the
     * report, and the real ordering question (did a constant move?) is exactly what
     * {@code EnumConstantOrderChecker} answers against a git baseline, with a {@code
     * enum_order_shuffled} diagnostic. On the bootstrap path there is no committed ledger, the order
     * is about to be renumbered densely, and the drift is a fact the reader needs.</p>
     */
    private void auditExistingEnum(org.openrewrite.java.tree.J.CompilationUnit cu, List<String> existingNames,
                                   boolean bootstrapping, List<String> divergences) {
        Set<String> declared = new LinkedHashSet<>(existingNames);
        Set<String> accessors = new LinkedHashSet<>();
        for (Property property : properties) {
            accessors.add(property.name());
        }

        for (Property property : properties) {
            if (!declared.contains(property.name())) {
                divergences.add("kind=field_in_interface_not_in_enum, location=" + enumName + "."
                        + property.name()
                        + ", cause=the accessor exists on " + viewName + " and the enum has no constant"
                        + ", current=absent, canonical=a constant "
                        + (bootstrapping ? "in interface declaration order" : "appended at the end (R1)")
                        + ", action=regenerate to add it");
            }
        }
        for (String name : existingNames) {
            if (!accessors.contains(name)) {
                divergences.add("kind=field_in_enum_not_in_interface, location=" + enumName + "." + name
                        + ", cause=the constant has no matching accessor on " + viewName
                        + ", current=active constant, canonical="
                        + (bootstrapping ? "removed (the enum carries no committed ledger)"
                                : "@Deprecated tombstone (R1.4)")
                        + ", action=" + (bootstrapping ? "the bootstrap rebuild drops it"
                                : "kept in place so no ordinal moves"));
            }
        }

        // The switch must have an arm per constant and no arm without one. Only string labels are
        // considered: forName switches on the field name, and any other switch in the file is a
        // different construct the generator does not own.
        Set<String> labels = new LinkedHashSet<>();
        for (org.openrewrite.java.tree.J.Case arm
                : TreeQueries.findAll(cu, org.openrewrite.java.tree.J.Case.class)) {
            if (arm.getCaseLabels() == null) {
                continue;
            }
            for (org.openrewrite.java.tree.J label : arm.getCaseLabels()) {
                if (label instanceof org.openrewrite.java.tree.J.Literal literal
                        && literal.getValue() instanceof String text) {
                    labels.add(text);
                }
            }
        }
        for (String label : labels) {
            if (!declared.contains(label)) {
                divergences.add("kind=stale_switch, location=" + enumName + ".forName"
                        + ", cause=the switch resolves a name that is not a constant"
                        + ", current=" + label + ", canonical=one arm per constant, no more"
                        + ", action=regenerate to drop the arm");
            }
        }

        for (org.openrewrite.java.tree.J.EnumValue constant : enumConstantsOf(cu)) {
            String constantName = constant.getName().getSimpleName();
            int index = indexOfAccessor(constantName);
            List<org.openrewrite.java.tree.Expression> arguments =
                    constant.getInitializer() instanceof org.openrewrite.java.tree.J.NewClass creation
                            ? creation.getArguments() : null;
            if (index < 0 || arguments == null || arguments.isEmpty()) {
                continue;
            }
            String declaredType = properties.get(index).type();
            // A type the generator cannot resolve is NOT a type the developer changed. `classLiteral`
            // maps the type parameter name `ID` (from `Identifiable<ID>`) to `java.lang.Object`, so a
            // view resolved under a marker whose id type is still the parameter — the doc-sample
            // interfaces are the live case — produced `Object.class` as the canonical type and this
            // check called the perfectly correct `Long.class` on disk a hand edit. Reporting a
            // divergence the generator cannot be sure about is worse than not reporting it: the
            // whole point of the report is that each line is actionable.
            //
            // The comparison is against the NORMALIZED form, because that is what the other side of
            // the check is: an earlier revision compared the normalized value against the literal
            // `java.lang.Object.class`, which normalizeType strips, so the guard could never fire
            // and the false positive survived every green test run. Normalisation also makes the
            // comparison independent of the printer that produced either side (it strips whitespace).
            String canonicalType = normalizeType(typeExpression(declaredType));
            if ("Object.class".equals(canonicalType)
                    && !"Object".equals(declaredType) && !"java.lang.Object".equals(declaredType)) {
                continue;
            }
            String existingType = normalizeType(argumentText(arguments.get(0)));
            if (!existingType.equals(canonicalType)) {
                divergences.add("kind=type_mismatch, location=" + enumName + "." + constantName
                        + ", cause=the constant's declared type is not the accessor's resolved type"
                        + ", current=" + existingType + ", canonical=" + canonicalType
                        + ", action=regenerate: a hand-edited type is not preserved");
            }
        }

        if (!bootstrapping) {
            return;
        }
        for (Property property : properties) {
            int existing = existingNames.indexOf(property.name());
            int canonical = indexOfAccessor(property.name());
            if (existing >= 0 && canonical >= 0 && existing != canonical) {
                divergences.add("kind=ordinal_drift, location=" + enumName + "." + property.name()
                        + ", cause=the enum carries no committed ledger, so its order is not yet a "
                        + "guarantee"
                        + ", current=" + existing + ", canonical=" + canonical
                        + ", action=the bootstrap rebuild renumbers densely in declaration order");
            }
        }
    }

    /** The accessor's declaration index, which is the ordinal the resolved field list gives it. */
    private int indexOfAccessor(String name) {
        for (int i = 0; i < properties.size(); i++) {
            if (properties.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Every enum constant in the unit, in declaration order.
     *
     * <p>Phase 6: JavaParser modelled a constant as its own member declaration; the LST groups an enum's
     * constants into a single {@code J.EnumValueSet} statement. The reach is the same — every enum in the
     * file, nested ones included — because the contract being audited is "the file's constants", and a
     * hand-written helper enum is as much a part of it as the top-level one.</p>
     *
     * <p>Lives here rather than in {@code TreeQueries} because nothing else in the tooling wants a flat
     * cross-enum constant list; the index and the location map both walk per declaration.</p>
     */
    private static List<org.openrewrite.java.tree.J.EnumValue> enumConstantsOf(
            org.openrewrite.java.tree.J.CompilationUnit unit) {
        List<org.openrewrite.java.tree.J.EnumValue> constants = new ArrayList<>();
        for (org.openrewrite.java.tree.J.ClassDeclaration declaration : TreeQueries.enums(unit)) {
            if (declaration.getBody() == null) {
                continue;
            }
            for (org.openrewrite.java.tree.Statement statement : declaration.getBody().getStatements()) {
                if (statement instanceof org.openrewrite.java.tree.J.EnumValueSet values) {
                    constants.addAll(values.getEnums());
                }
            }
        }
        return constants;
    }

    /** The names of {@link #enumConstantsOf}, in the same order. */
    private static List<String> enumConstants(org.openrewrite.java.tree.J.CompilationUnit unit) {
        List<String> names = new ArrayList<>();
        for (org.openrewrite.java.tree.J.EnumValue constant : enumConstantsOf(unit)) {
            names.add(constant.getName().getSimpleName());
        }
        return names;
    }

    /**
     * A constant's declared type as source text, for the type-mismatch comparison.
     *
     * <p>{@code TreeQueries.expressionText} rather than {@code toString()}: the argument is a class
     * literal, and an LST literal's {@code toString()} is its <em>value</em>, but the comparison is
     * against a canonical expression like {@code java.lang.Long.class} — so the source spelling is the
     * only one that can match. Normalisation strips the whitespace afterwards, which is why the two
     * sides may come from different printers at all.</p>
     */
    private static String argumentText(org.openrewrite.java.tree.Expression argument) {
        return TreeQueries.expressionText(argument);
    }

    /**
     * A comparison form for a declared type expression: whitespace removed and the package
     * qualifiers the emitter may or may not print stripped.
     *
     * <p>This is a textual comparison, not a semantic one — resolving two type expressions to the
     * same {@code java.lang.reflect.Type} would need the user's classpath, which the generator does
     * not have. The normalization is therefore deliberately generous: it can miss a mismatch (two
     * spellings it does not know are the same), but it must not invent one, because a false
     * {@code type_mismatch} on every pass is worse than a missed diagnostic. Both sides come from
     * the same emitter in the common case, so the comparison is exact there.</p>
     */
    private static String normalizeType(String expression) {
        String normalized = expression.replaceAll("\\s+", "");
        for (String prefix : List.of("java.lang.", "java.util.", "java.time.", "java.math.",
                "java.sql.", "java.lang.reflect.", "hr.hrg.hipster.entity.api.")) {
            normalized = normalized.replace(prefix, "");
        }
        return normalized;
    }

    private String buildSource() {
        return buildSource(LedgerPlan.fresh(properties));
    }

    private String buildSource(LedgerPlan ledger) {
        return generatorHeader() + renderEnum(ledger);
    }

    /**
     * The DEC-021 two-line header that must sit above {@code package} in every generated file
     * (plan.dsflash § 8.3/3.8).
     *
     * <p>The first line carries the {@code {@link}} reference to the view, which is the
     * generator-side fulfilment of DEC-019's navigability rule. The second line is the pinned
     * single-line JSON5 config blob; in field-enum mode it carries the
     * <strong>{@code entityFieldEnum:true} marker</strong> that opts the enum into the R1
     * append-only ledger (§ 4.6/R1.2). {@code enabled: false} takes the file fully under manual
     * control.</p>
     */
    private String generatorHeader() {
        String description = "Field metadata for the " + viewName + " view.";
        String generator = EntityMetadataGenerator.class.getName();
        // DEC-035: the file marker carries the GENERATOR's FQN, which is what a reader jumps to. An earlier
        // revision put the VIEW's FQN on this line, which read like a link to the view the metadata
        // describes — useful-looking, and not what the header is for. The view's name is already in the
        // description and in the enum, so nothing is lost.
        return fieldEnumMode
                ? GeneratedCodeMarkers.fieldEnumFileHeader(generator, description)
                : GeneratedCodeMarkers.fileHeader(generator, description);
    }

    /**
     * The enum as <strong>text</strong>, byte-identical to what the JavaParser printer produced.
     *
     * <p>The layout is not arbitrary — it is the committed example's, and every detail below was read off
     * it. The two that look like mistakes are the ones a future editor is most likely to "fix", so they
     * are marked in place: the mixed line endings, and {@code @Override()} with empty parentheses.</p>
     */
    private String renderEnum(LedgerPlan ledger) {
        // The body uses the PLATFORM separator, which is what the printer emitted and therefore what the
        // committed files contain. The DEC-021 header above it uses `\n` (see `generatorHeader`), so a
        // generated file really does mix the two — 110 CRLF and 2 LF in `PersonSummary_.java`. It is
        // reproduced rather than normalised because the example gate compares bytes.
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        if (packageName != null && !packageName.isBlank()) {
            sb.append("package ").append(packageName).append(';').append(nl).append(nl);
        }
        for (String importName : importNames()) {
            sb.append("import ").append(importName).append(';').append(nl);
        }
        sb.append(nl);
        sb.append("public enum ").append(enumName);
        if (fieldEnumMode) {
            sb.append(" implements FieldDef");
        }
        sb.append(" {").append(nl);

        List<Property> ordered = ledger.ordered();
        // The printer's two enum layouts, and the gate between them is not cosmetic. Up to five
        // constants it aligns them horizontally, so the separator is `, ` in front of the next
        // constant; past five — or as soon as any constant carries a comment, which a tombstone's
        // javadoc is — it switches to one constant per line with the comma on a line of its own. Both
        // spellings are in the committed example (`PaymentMethodAuditable_` has three constants and
        // reads `, createdAt(...)`, `PersonSummary_` has six and reads `,\n    firstName(...)`), and
        // `MAX_HORIZONTAL_CONSTANTS` is JavaParser's own default rather than a number chosen here.
        boolean alignVertically = ordered.size() > MAX_HORIZONTAL_CONSTANTS || !ledger.tombstoned().isEmpty();
        for (int index = 0; index < ordered.size(); index++) {
            boolean last = index + 1 == ordered.size();
            boolean body = hasClassBody(ordered.get(index), ledger);
            if (index == 0) {
                // One blank line between the enum header and its first constant.
                sb.append(nl).append("    ");
            } else if (alignVertically) {
                sb.append(nl).append("    ,").append(nl).append("    ");
            } else {
                sb.append(nl).append("    , ");
            }
            sb.append(constantText(ordered.get(index), ledger, nl));
            if (last) {
                // A constant with a body ends on its own `}`, so its terminator sits alone on the next
                // line; a body-less one keeps it inline.
                sb.append(body ? nl + "    ;" : ";").append(nl);
                sb.append(nl);
            }
        }

        renderMembers(sb, ordered, nl);
        sb.append('}').append(nl);
        return sb.toString();
    }

    /**
     * The number of constants up to which the printer aligns an enum's constants horizontally.
     *
     * <p>JavaParser's {@code PrettyPrinterConfiguration.maxEnumConstantsToAlignHorizontally} default,
     * which the committed example encodes: every generated enum with six or more constants has its
     * separators on their own lines, and every one with five or fewer has them inline.</p>
     */
    private static final int MAX_HORIZONTAL_CONSTANTS = 5;

    /** The import set, in the order the committed files list them. */
    private List<String> importNames() {
        Set<String> imports = new LinkedHashSet<>();
        if (fieldEnumMode) {
            imports.add("java.lang.reflect.Type");
            imports.add("hr.hrg.hipster.entity.api.TypeUtils");
            imports.add("hr.hrg.hipster.entity.api.FieldNameMapper");
            imports.add("hr.hrg.hipster.entity.api.FieldDef");
            imports.add("hr.hrg.hipster.entity.api.FieldKind");
        } else {
            imports.add("java.lang.reflect.Type");
            imports.add("hr.hrg.hipster.entity.api.TypeUtils");
        }
        if (metaCreatorBody != null) {
            imports.add("hr.hrg.hipster.entity.api.ViewMeta");
            imports.add("hr.hrg.hipster.entity.api.DefaultViewMeta");
        }
        // The META creator body casts each positional value to its declared type
        // (`(Map<String, List<Long>>) values[5]`, `(PersonSummary.Record) …`), so the creator's
        // declared types need the same import resolution the builder's field declarations do.
        // Without this the emitted enum does not compile — the same defect F-17 recorded for the
        // builder, caught here by the compile gate rather than by a user.
        if (metaCreatorBody != null) {
            imports.addAll(JdkImportSupport.importsFor(properties));
        }
        imports.addAll(additionalImports);
        return List.copyOf(imports);
    }

    /** Whether a constant gains a class body: it does exactly when it has an override to declare. */
    private boolean hasClassBody(Property prop, LedgerPlan ledger) {
        return fieldEnumMode && !overridesOf(prop, ledger.tombstoned().contains(prop.name())).isEmpty();
    }

    /**
     * One constant, without its leading indentation and without its separator — the caller places both,
     * because the separator's position depends on the enum's layout (see {@link #MAX_HORIZONTAL_CONSTANTS}).
     */
    private String constantText(Property prop, LedgerPlan ledger, String nl) {
        StringBuilder sb = new StringBuilder();
        if (ledger.tombstoned().contains(prop.name())) {
            // R1.4: a tombstone reports retired() == true, so every generated writer skips it, and it is
            // @Deprecated with a short reason. The javadoc is what JavaParser's `setJavadocComment`
            // printed, one sentence per line, and the annotation sits on the line immediately above the
            // constant.
            sb.append("/**").append(nl)
                    .append("     * @deprecated no longer an accessor on ").append(viewName)
                    .append("; retained to preserve ordinals.").append(nl)
                    .append("     */").append(nl)
                    .append("    @Deprecated").append(nl);
        }
        sb.append(prop.name()).append('(').append(typeExpression(prop.type())).append(')');
        List<String> overrides = fieldEnumMode
                ? overridesOf(prop, ledger.tombstoned().contains(prop.name())) : List.of();
        if (overrides.isEmpty()) {
            // Property-enum mode, or a field with nothing to resolve: no class body at all.
            return sb.toString();
        }
        sb.append(" {");
        for (String override : overrides) {
            // A blank line before every override, the first included: `{`, blank, override, blank,
            // override, `}` is the printer's member separation, and it is what the committed enums
            // contain. Two line breaks per override, and none emitted after `{` — the first of the two
            // ends the head's line and the second opens a fresh one.
            sb.append(nl).append(nl).append(override);
        }
        return sb.append(nl).append("    }").toString();
    }

    /** The whole enum body after the constant list: the backing field and the members. */
    private void renderMembers(StringBuilder sb, List<Property> ordered, String nl) {
        String backingField = fieldEnumMode ? "javaType" : "propertyType";
        sb.append("    private final Type ").append(backingField).append(';').append(nl).append(nl);
        sb.append("    private ").append(enumName).append("(Type ").append(backingField).append(") {").append(nl)
                .append("        this.").append(backingField).append(" = ").append(backingField).append(';')
                .append(nl)
                .append("    }").append(nl).append(nl);

        if (fieldEnumMode) {
            sb.append("    public Type javaType() {").append(nl)
                    .append("        return javaType;").append(nl)
                    .append("    }").append(nl).append(nl);
            renderForNameStatement(sb, ordered, nl);
            sb.append(nl)
                    .append("    private static final FieldNameMapper<").append(enumName).append("> NAME_MAPPER = ")
                    .append(enumName).append("::forName;").append(nl);
            if (metaCreatorBody != null) {
                sb.append(nl);
                renderMeta(sb, nl);
            }
            return;
        }

        // Property-enum mode: the name/type accessors instead of `javaType()`.
        sb.append("    public String getPropertyName() {").append(nl)
                .append("        return name();").append(nl)
                .append("    }").append(nl).append(nl);
        sb.append("    public Type getPropertyType() {").append(nl)
                .append("        return propertyType;").append(nl)
                .append("    }").append(nl).append(nl);
        renderForNameExpression(sb, nl);
    }

    /**
     * {@code forName} in field-enum mode: a real {@code switch} <em>statement</em> whose arms return the
     * constant.
     *
     * <p>An earlier version emitted a switch <em>expression</em> whose arms carried {@code return}
     * statements, which the arrow-flattening pass rewrote to {@code -> return id;} — not legal Java
     * ("attempt to return out of a switch expression"). A statement switch has no such restriction.</p>
     *
     * <p>The returned constants are <strong>qualified by the enum type</strong>, and that is not
     * cosmetic: the parameter is called {@code name}, so an unqualified {@code return name;} for a view
     * with a field called {@code name} resolved to the String parameter and produced
     * {@code incompatible types: java.lang.String cannot be converted to <Enum>} — a generated enum that
     * does not compile, for any view with a field named {@code name}, {@code id} or any other name the
     * method's own scope happens to declare. Qualifying makes the arm independent of the method's
     * scope.</p>
     */
    private void renderForNameStatement(StringBuilder sb, List<Property> constants, String nl) {
        sb.append("    public static ").append(enumName).append(" forName(String name) {").append(nl)
                .append("        if (name == null)").append(nl)
                .append("            return null;").append(nl)
                .append("        switch (name) {").append(nl);
        for (Property prop : constants) {
            sb.append("            case \"").append(prop.name()).append("\":").append(nl)
                    .append("                return ").append(enumName).append('.').append(prop.name())
                    .append(';').append(nl);
        }
        sb.append("            default:").append(nl)
                .append("                return null;").append(nl)
                .append("        }").append(nl)
                .append("    }").append(nl);
    }

    /**
     * {@code forName} in property-enum mode: a switch <em>expression</em> whose arms yield the constant —
     * the shape the tooling's own Property model expects.
     *
     * <p>The arms carry a trailing semicolon, which is the printer's output for an expression arm and is
     * what {@code FieldBoilerplateGeneratorTest} pins ({@code case "metadata" -> metadata;}).</p>
     */
    private void renderForNameExpression(StringBuilder sb, String nl) {
        sb.append("    public static ").append(enumName).append(" forName(String name) {").append(nl)
                .append("        if (name == null)").append(nl)
                .append("            return null;").append(nl)
                .append("        return switch (name) {").append(nl);
        for (Property prop : properties) {
            sb.append("            case \"").append(prop.name()).append("\" -> ").append(prop.name())
                    .append(';').append(nl);
        }
        sb.append("            default -> null;").append(nl)
                .append("        };").append(nl)
                .append("    }").append(nl);
    }

    /**
     * The {@code META} constant, on one line, exactly as the printer emitted it.
     *
     * <p>{@code discriminatorFieldReference} is an expression or {@code null}, and
     * {@code discriminatorValue} a plain string: the two are genuinely different things — a field
     * reference resolves through the enum, a value is the payload's discriminator label — which is why
     * one is emitted bare and the other quoted.</p>
     */
    private void renderMeta(StringBuilder sb, String nl) {
        String viewMetaType = "ViewMeta<" + viewName + ", " + enumName + ">";
        sb.append("    public static final ").append(viewMetaType)
                .append(" META = new DefaultViewMeta<").append(viewName).append(", ").append(enumName)
                .append(">(").append(viewName).append(".class, ").append(enumName).append(".class, ")
                .append("NAME_MAPPER, ").append(metaCreatorBody).append(", ")
                .append(discriminatorFieldReference == null ? "null" : discriminatorFieldReference).append(", ")
                .append(stringLiteral(discriminatorValue)).append(", ")
                .append(permittedSubtypeArray()).append(");").append(nl);
    }

    /** {@code new Class<?>[0]}, or an initialised array of the permitted subtype literals. */
    private String permittedSubtypeArray() {
        if (permittedSubtypeClassNames.length == 0) {
            return "new Class<?>[0]";
        }
        StringBuilder sb = new StringBuilder("new Class<?>[] {");
        for (int index = 0; index < permittedSubtypeClassNames.length; index++) {
            if (index > 0) {
                sb.append(',');
            }
            sb.append(' ').append(permittedSubtypeClassNames[index]);
        }
        return sb.append(" }").toString();
    }

    /**
     * The overrides a constant declares, as source lines.
     *
     * <p>An override is emitted only where there is something to resolve: the {@code @FieldSource}
     * attributes when the accessor carries them, and <strong>{@code column()} for every {@code COLUMN}
     * field</strong> regardless of annotation. Everything else falls back to {@code FieldDef}'s defaults
     * (kind {@code COLUMN}, no relation/expression label), so an unannotated non-column field adds
     * nothing to the enum.</p>
     *
     * <p>The unconditional {@code column()} is the one rule here that is not "emit what the annotation
     * says": the accessor's name is the column name when no label is given
     * ({@code FieldDef.column()}'s contract), and this emitter is the only place that knows it. Before
     * it, a view with no {@code @FieldSource} at all produced {@code column() == null} for every field —
     * so an adapter driven by {@code column()} saw no writable column (notes F-12).</p>
     *
     * <p>A tombstone gains {@code retired()} returning {@code true} <em>in addition to</em> whatever its
     * placeholder property resolves to. That is the half of R1.4 every generated writer consults, so
     * leaving it out does not merely lose a marker: the writers stop skipping the retired slot
     * (notes DR-2).</p>
     */
    private List<String> overridesOf(Property prop, boolean tombstoned) {
        boolean hasKind = prop.fieldKind() != null;
        boolean hasColumn = prop.column() != null;
        boolean hasRelation = prop.relation() != null;
        boolean hasExpression = prop.expression() != null;
        // The resolved kind: an accessor with no `@FieldSource` is a COLUMN field (FieldDef's own
        // default), which is the common case and must still carry a column name — see below.
        boolean isColumn = hasKind ? "COLUMN".equals(prop.fieldKind()) : true;
        boolean emitsColumn = isColumn;

        List<String> overrides = new ArrayList<>();
        if (hasKind) {
            overrides.add(override("FieldKind", "fieldKind", "FieldKind." + prop.fieldKind()));
        }
        if (emitsColumn) {
            overrides.add(override("String", "column", stringLiteral(hasColumn ? prop.column() : prop.name())));
        }
        if (hasRelation) {
            overrides.add(override("String", "relation", stringLiteral(prop.relation())));
        }
        if (hasExpression) {
            overrides.add(override("String", "expression", stringLiteral(prop.expression())));
        }
        if (tombstoned) {
            overrides.add(override("boolean", "retired", "true"));
        }
        return overrides;
    }

    /**
     * One override method, indented one level inside the constant's class body.
     *
     * <p>{@code @Override()} keeps its empty parentheses because that is what the printer emitted, and
     * the committed enums contain it. Dropping them would be a formatting change to files this class
     * does not own a formatter for.</p>
     */
    private String override(String returnType, String name, String value) {
        String nl = System.lineSeparator();
        return "        @Override()" + nl
                + "        public " + returnType + " " + name + "() {" + nl
                + "            return " + value + ";" + nl
                + "        }";
    }

    /**
     * The expression a field enum constant's {@code javaType} uses.
     *
     * <p>Resolution is {@link TypeLiterals}' business, and the reporting of an unresolved type happens
     * where the property list is built ({@code EntityMetadataGenerator.dropUnresolvedTypeParameters}):
     * an accessor whose type is a type parameter never reaches this emitter, because it has no
     * declarable field type either. What remains here is the safety property that a name which somehow
     * does arrive still cannot become {@code T.class}.</p>
     */
    private String typeExpression(String rawType) {
        String type = rawType.trim();
        if (type.endsWith("[]")) {
            return "java.lang.reflect.Array.newInstance(" + typeExpression(type.substring(0, type.length() - 2)) + ", 0).getClass()";
        }

        int genericStart = type.indexOf('<');
        if (genericStart < 0) {
            if (isPrimitiveType(type)) {
                return boxedPrimitiveClass(type) + ".class";
            }
            return classLiteral(type) + ".class";
        }

        String raw = type.substring(0, genericStart).trim();
        String args = type.substring(genericStart + 1, type.lastIndexOf('>'));
        String[] generics = splitGenerics(args);
        StringBuilder argExpr = new StringBuilder();
        for (int i = 0; i < generics.length; i++) {
            argExpr.append(typeExpression(generics[i]));
            if (i < generics.length - 1) argExpr.append(", ");
        }

        return "TypeUtils.parameterizedType(" + classLiteral(raw) + ".class, " + argExpr + ")";
    }

    private static String[] splitGenerics(String args) {
        List<String> tokens = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : args.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                tokens.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            tokens.add(current.toString().trim());
        }
        return tokens.toArray(new String[0]);
    }

    private static boolean isPrimitiveType(String typeName) {
        return switch (typeName) {
            case "byte", "short", "int", "long", "float", "double", "boolean", "char" -> true;
            default -> false;
        };
    }

    private static String boxedPrimitiveClass(String typeName) {
        return switch (typeName) {
            case "byte" -> "java.lang.Byte";
            case "short" -> "java.lang.Short";
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "float" -> "java.lang.Float";
            case "double" -> "java.lang.Double";
            case "boolean" -> "java.lang.Boolean";
            case "char" -> "java.lang.Character";
            default -> null;
        };
    }

    /**
     * Type-parameter names declared in the source set being generated, set by the pass
     * (follow-up plan § 2.1). A bare name in this set has no class literal, so it becomes
     * {@code java.lang.Object} and is reported rather than emitted as {@code T.class}.
     */
    private static Set<String> typeParameterNames = Set.of();

    /** The literal a {@code .class} expression uses for a source type name; see {@link TypeLiterals}. */
    private static String classLiteral(String typeName) {
        return TypeLiterals.classLiteral(typeName, typeParameterNames);
    }

    private static String stringLiteral(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static final class Builder {
        private final String packageName;
        private final String viewName;
        private final List<Property> properties;
        private String enumName;
        private boolean enumNameExplicitlySet = false;
        private boolean fieldEnumMode = true;
        private String metaCreatorBody;
        private String discriminatorFieldReference;
        private String discriminatorValue = "";
        private String[] permittedSubtypeClassNames = new String[0];
        private final List<String> additionalImports = new ArrayList<>();
        private java.util.function.Consumer<List<String>> divergenceSink;
        private Set<String> typeParameterNames = Set.of();

        private Builder(String packageName, String viewName, List<Property> properties) {
            this.packageName = packageName == null ? "" : packageName;
            this.viewName = Objects.requireNonNull(viewName, "viewName");
            this.properties = List.copyOf(Objects.requireNonNull(properties, "properties"));
            this.enumName = viewName + "Field";
        }

        /**
         * The type-parameter names declared in the source set, so a bare name among them is not emitted
         * as {@code T.class} (follow-up plan § 2.1).
         */
        public Builder withTypeParameterNames(Set<String> names) {
            this.typeParameterNames = names == null ? Set.of() : Set.copyOf(names);
            return this;
        }

        public Builder withEnumTypeName(String enumName) {
            this.enumName = Objects.requireNonNull(enumName, "enumName");
            this.enumNameExplicitlySet = true;
            return this;
        }

        public Builder withPropertyEnumMode() {
            this.fieldEnumMode = false;
            if (!enumNameExplicitlySet) {
                this.enumName = viewName + "Property";
            }
            return this;
        }

        public Builder withMetaCreatorBody(String metaCreatorBody) {
            this.metaCreatorBody = Objects.requireNonNull(metaCreatorBody, "metaCreatorBody");
            return this;
        }

        public Builder withDiscriminatorField(String discriminatorFieldReference) {
            this.discriminatorFieldReference = Objects.requireNonNull(discriminatorFieldReference, "discriminatorFieldReference");
            return this;
        }

        public Builder withDiscriminatorValue(String discriminatorValue) {
            this.discriminatorValue = Objects.requireNonNull(discriminatorValue, "discriminatorValue");
            return this;
        }

        public Builder withPermittedSubtypeClassNames(String... permittedSubtypeClassNames) {
            this.permittedSubtypeClassNames = permittedSubtypeClassNames == null ? new String[0] : permittedSubtypeClassNames.clone();
            return this;
        }

        public Builder withAdditionalImports(String... additionalImports) {
            if (additionalImports != null) {
                for (String importName : additionalImports) {
                    this.additionalImports.add(importName);
                }
            }
            return this;
        }

        /** Receives the DEC-022 divergence entries produced by the append-only ledger pass. */
        public Builder withDivergenceSink(java.util.function.Consumer<List<String>> divergenceSink) {
            this.divergenceSink = divergenceSink;
            return this;
        }

        public FieldBoilerplateGenerator build() {
            return new FieldBoilerplateGenerator(this);
        }

        public void generate(Path outputRoot) throws IOException {
            build().generate(outputRoot);
        }
    }
}
