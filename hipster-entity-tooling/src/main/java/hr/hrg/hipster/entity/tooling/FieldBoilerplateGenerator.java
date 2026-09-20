package hr.hrg.hipster.entity.tooling;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.WildcardType;
import com.github.javaparser.printer.configuration.PrettyPrinterConfiguration;

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
        SourceReader.Read read = SourceReader.read(enumFile);
        if (!read.readable()) {
            // Unreadable: preserve it. The report is emitted by the caller, which owns the sink.
            return true;
        }
        return isPolymorphicRootEnum(read.unit());
    }

    /** Whether a parsed compilation unit carries a populated permitted-subtype list. */
    private static boolean isPolymorphicRootEnum(CompilationUnit cu) {
        return cu.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).stream()
                .filter(creation -> creation.getType().getNameAsString().equals("DefaultViewMeta"))
                .anyMatch(creation -> creation.getArguments().stream()
                        .anyMatch(argument -> argument instanceof ArrayInitializerExpr array
                                && !array.getValues().isEmpty()));
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
        SourceReader.Read read = SourceReader.readText(existing);
        // The fail-safe direction of DR-7, now shared with every other read in the generator
        // ({@link SourceReader}): JavaParser is error tolerant and returns a PARTIAL compilation unit
        // for broken source. Trusting the mere presence of a result meant a syntax error made the enum
        // look like it had NO constants at all, which routed it to the bootstrap path — i.e. exactly
        // the silent renumbering R1 exists to prevent, reached by the very code that was supposed to
        // protect against it. A file with parse problems is treated as unreadable.
        if (!read.readable()) {
            return new LedgerPlan(properties, List.of(), List.of(
                    "kind=enum_not_parsed, location=" + enumName
                            + ", cause=the existing enum could not be parsed"
                            + ", current=unparseable source"
                            + ", canonical=rebuilt from the resolved fields"
                            + ", action=inspect the file by hand: this pass did NOT preserve the "
                            + "append-only ledger (R1)"));
        }
        CompilationUnit cu = read.unit();
        EnumConstantOrderChecker.HeaderConfig header = EnumConstantOrderChecker.readHeader(cu);
        if (!header.marked()) {
            // Bootstrap: rebuild from source. Any stale constant is dropped and reported.
            List<String> sourceNames = properties.stream().map(Property::name).toList();
            List<String> stale = new ArrayList<>();
            List<String> existingNames = new ArrayList<>();
            for (EnumDeclaration decl : cu.findAll(EnumDeclaration.class)) {
                for (EnumConstantDeclaration constant : decl.getEntries()) {
                    existingNames.add(constant.getNameAsString());
                    if (!sourceNames.contains(constant.getNameAsString())) {
                        stale.add(constant.getNameAsString());
                    }
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
        List<String> existingNames = new ArrayList<>();
        for (EnumDeclaration decl : cu.findAll(EnumDeclaration.class)) {
            for (EnumConstantDeclaration constant : decl.getEntries()) {
                existingNames.add(constant.getNameAsString());
            }
        }
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
    private void auditExistingEnum(CompilationUnit cu, List<String> existingNames,
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
        for (com.github.javaparser.ast.stmt.SwitchStmt sw : cu.findAll(com.github.javaparser.ast.stmt.SwitchStmt.class)) {
            for (SwitchEntry entry : sw.getEntries()) {
                for (Expression label : entry.getLabels()) {
                    if (label instanceof StringLiteralExpr literal) {
                        labels.add(literal.asString());
                    }
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

        for (EnumDeclaration decl : cu.findAll(EnumDeclaration.class)) {
            for (EnumConstantDeclaration constant : decl.getEntries()) {
                int index = indexOfAccessor(constant.getNameAsString());
                if (index < 0 || constant.getArguments().isEmpty()) {
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
                // and the false positive survived every green test run.
                String canonicalType = normalizeType(parseTypeExpression(declaredType).toString());
                if ("Object.class".equals(canonicalType)
                        && !"Object".equals(declaredType) && !"java.lang.Object".equals(declaredType)) {
                    continue;
                }
                String existingType = normalizeType(constant.getArgument(0).toString());
                if (!existingType.equals(canonicalType)) {
                    divergences.add("kind=type_mismatch, location=" + enumName + "."
                            + constant.getNameAsString()
                            + ", cause=the constant's declared type is not the accessor's resolved type"
                            + ", current=" + existingType + ", canonical=" + canonicalType
                            + ", action=regenerate: a hand-edited type is not preserved");
                }
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
        CompilationUnit cu = buildCompilationUnit(ledger);
        PrettyPrinterConfiguration printerConfig = new PrettyPrinterConfiguration();
        printerConfig.setIndentSize(4);
        String source = cu.toString(printerConfig).replace("switch(", "switch (");
        source = source.replaceAll("->\\s*\\r?\\n\\s*", "-> ");
        return generatorHeader() + source;
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
        StringBuilder sb = new StringBuilder();
        String viewFqn = packageName == null || packageName.isBlank() ? viewName : packageName + "." + viewName;
        sb.append("// {@link ").append(viewFqn).append("} Field metadata for the ")
                .append(viewName).append(" view.\n");
        sb.append("// {enabled:true");
        if (fieldEnumMode) {
            sb.append(", entityFieldEnum:true");
        }
        sb.append(", blockMarker: \"implicit\"}\n");
        return sb.toString();
    }

    private CompilationUnit buildCompilationUnit(LedgerPlan ledger) {
        CompilationUnit cu = new CompilationUnit();
        if (packageName != null && !packageName.isBlank()) {
            cu.setPackageDeclaration(packageName);
        }

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

        imports.forEach(cu::addImport);

        EnumDeclaration enumDecl = cu.addEnum(enumName);
        if (fieldEnumMode) {
            enumDecl.addImplementedType("FieldDef");
        }

        for (Property prop : ledger.ordered()) {
            Expression initializer = parseTypeExpression(prop.type());
            EnumConstantDeclaration constant = new EnumConstantDeclaration(prop.name());
            constant.getArguments().add(initializer);
            if (fieldEnumMode) {
                addFieldSourceOverrides(constant, prop);
            }
            if (ledger.tombstoned().contains(prop.name())) {
                // R1.4: a tombstone reports retired() == true, so every generated writer skips it,
                // and it is @Deprecated with a short reason.
                constant.addAnnotation("Deprecated");
                constant.setJavadocComment(
                        "@deprecated no longer an accessor on " + viewName
                                + "; retained to preserve ordinals.");
                addOverride(constant, "retired", new ClassOrInterfaceType(null, "boolean"),
                        parseExpression("true"));
            }
            enumDecl.addEntry(constant);
        }

        if (fieldEnumMode) {
            // javaType() is declared to return Type (FieldDef.javaType()), not Class<?>: a generic
            // field is passed as TypeUtils.parameterizedType(...), which is a Type and would not
            // compile against a Class<?> return type. The backing field and constructor parameter
            // are therefore also Type (plan.dsflash § 8.3/3.7).
            enumDecl.addMember(new FieldDeclaration(NodeList.nodeList(Modifier.privateModifier(), Modifier.finalModifier()),
                    new VariableDeclarator(new ClassOrInterfaceType(null, "Type"), "javaType")));
        } else {
            enumDecl.addMember(new FieldDeclaration(NodeList.nodeList(Modifier.privateModifier(), Modifier.finalModifier()),
                    new VariableDeclarator(new ClassOrInterfaceType(null, "Type"), "propertyType")));
        }

        addConstructor(enumDecl);
        if (fieldEnumMode) {
            addJavaTypeMethod(enumDecl);
        } else {
            addPropertyMethods(enumDecl);
        }
        // forName must resolve the RETIRED names too: an incoming payload that still carries a
        // removed field has to be accepted and bound to the tombstone (R1.4), and the R1 checker
        // compares the switch against the constant list.
        addForNameMethod(enumDecl, ledger.ordered());

        if (fieldEnumMode) {
            FieldDeclaration mapperField = new FieldDeclaration(NodeList.nodeList(Modifier.privateModifier(), Modifier.staticModifier(), Modifier.finalModifier()),
                    new VariableDeclarator(new ClassOrInterfaceType(null, "FieldNameMapper").setTypeArguments(NodeList.nodeList(new ClassOrInterfaceType(null, enumName))), "NAME_MAPPER", parseExpression(enumName + "::forName")));
            enumDecl.addMember(mapperField);
        }

        if (metaCreatorBody != null) {
            if (!fieldEnumMode) {
                throw new IllegalStateException("ViewMeta generation requires field enum mode");
            }

            ClassOrInterfaceType metaType = new ClassOrInterfaceType(null, "ViewMeta")
                    .setTypeArguments(NodeList.nodeList(new ClassOrInterfaceType(null, viewName), new ClassOrInterfaceType(null, enumName)));
            ObjectCreationExpr initializer = new ObjectCreationExpr(null,
                    new ClassOrInterfaceType(null, "DefaultViewMeta").setTypeArguments(NodeList.nodeList(new ClassOrInterfaceType(null, viewName), new ClassOrInterfaceType(null, enumName))),
                    NodeList.nodeList(
                            new ClassExpr(new ClassOrInterfaceType(null, viewName)),
                            new ClassExpr(new ClassOrInterfaceType(null, enumName)),
                            new NameExpr("NAME_MAPPER"),
                            parseExpression(metaCreatorBody),
                            discriminatorFieldReference == null ? new NullLiteralExpr() : parseExpression(discriminatorFieldReference),
                            new StringLiteralExpr(discriminatorValue),
                            permittedSubtypeArrayExpression()
                    ));

            FieldDeclaration metaField = new FieldDeclaration(NodeList.nodeList(Modifier.publicModifier(), Modifier.staticModifier(), Modifier.finalModifier()),
                    new VariableDeclarator(metaType, "META", initializer));
            enumDecl.addMember(metaField);
        }

        return cu;
    }

    private Type classTypeWithWildcard() {
        return new ClassOrInterfaceType(null, "Class").setTypeArguments(NodeList.nodeList(new WildcardType()));
    }

    /**
     * Emits the {@code @FieldSource}-derived overrides on a single enum constant
     * (plan.dsflash § 8.3/3.9, § 4.3/X2).
     *
     * <p>The generator emits an override only where there is something to resolve: the {@code
     * @FieldSource} attributes when the accessor carries them, and <strong>{@code column()} for every
     * {@code COLUMN} field</strong> regardless of annotation. Everything else falls back to
     * {@code FieldDef}'s defaults (kind {@code COLUMN}, no relation/expression label), so an
     * unannotated non-column field adds nothing to the enum.</p>
     *
     * <p>The unconditional {@code column()} is the one rule here that is not "emit what the annotation
     * says": the accessor's name is the column name when no label is given
     * ({@code FieldDef.column()}'s contract), and this emitter is the only place that knows it. Before
     * it, a view with no {@code @FieldSource} at all produced {@code column() == null} for every field
     * — so an adapter driven by {@code column()} saw no writable column (notes F-12).</p>
     */
    private void addFieldSourceOverrides(EnumConstantDeclaration constant, Property prop) {
        boolean hasKind = prop.fieldKind() != null;
        boolean hasColumn = prop.column() != null;
        boolean hasRelation = prop.relation() != null;
        boolean hasExpression = prop.expression() != null;
        // The resolved kind: an accessor with no `@FieldSource` is a COLUMN field (FieldDef's own
        // default), which is the common case and must still carry a column name — see below.
        boolean isColumn = hasKind ? "COLUMN".equals(prop.fieldKind()) : true;
        // A COLUMN field always answers `column()`, whether or not it was annotated. `FieldDef.column()`
        // documents "the accessor name when the annotation's label is empty", and that resolution has
        // to happen here because only the generator knows the accessor's name — the enum constant does,
        // but `FieldDef` is not handed it. Without this, `column()` returned null for every field of a
        // view that carried no `@FieldSource` at all, so an adapter driven purely by `column()` saw no
        // writable column name (notes F-12, flagged for § 12.1/7.2–7.4).
        boolean emitsColumn = isColumn;
        if (!hasKind && !hasColumn && !hasRelation && !hasExpression && !emitsColumn) {
            return;
        }

        if (hasKind) {
            addOverride(constant, "fieldKind", new ClassOrInterfaceType(null, "FieldKind"),
                    parseExpression("FieldKind." + prop.fieldKind()));
        }
        if (emitsColumn) {
            addOverride(constant, "column", new ClassOrInterfaceType(null, "String"),
                    new StringLiteralExpr(hasColumn ? prop.column() : prop.name()));
        }
        if (hasRelation) {
            addOverride(constant, "relation", new ClassOrInterfaceType(null, "String"),
                    new StringLiteralExpr(prop.relation()));
        }
        if (hasExpression) {
            addOverride(constant, "expression", new ClassOrInterfaceType(null, "String"),
                    new StringLiteralExpr(prop.expression()));
        }
    }

    private void addOverride(EnumConstantDeclaration constant, String name, Type returnType, Expression body) {
        MethodDeclaration method = new MethodDeclaration(
                NodeList.nodeList(Modifier.publicModifier()), returnType, name);
        method.addAnnotation("Override");
        method.setBody(new BlockStmt(NodeList.nodeList(new ReturnStmt(body))));
        // A per-constant override lives in the constant's class body. JavaParser models that body
        // as a NodeList<BodyDeclaration<?>> obtained from getClassBody(), which is only
        // materialised once the list is non-empty (there is no addClassBody()).
        constant.getClassBody().add(method);
    }

    private void addConstructor(EnumDeclaration enumDecl) {
        String parameterName = fieldEnumMode ? "javaType" : "propertyType";
        Type parameterType = new ClassOrInterfaceType(null, "Type");
        ConstructorDeclaration ctor = enumDecl.addConstructor(Modifier.Keyword.PRIVATE);
        ctor.addParameter(parameterType, parameterName);
        ctor.getBody().addStatement(new AssignExpr(
                new FieldAccessExpr(new ThisExpr(), parameterName),
                new NameExpr(parameterName),
                AssignExpr.Operator.ASSIGN));
    }

    private void addJavaTypeMethod(EnumDeclaration enumDecl) {
        MethodDeclaration method = enumDecl.addMethod("javaType", Modifier.Keyword.PUBLIC);
        method.setType(new ClassOrInterfaceType(null, "Type"));
        method.setBody(new BlockStmt(NodeList.nodeList(new ReturnStmt(new NameExpr("javaType")))));
    }

    private void addPropertyMethods(EnumDeclaration enumDecl) {
        MethodDeclaration nameMethod = enumDecl.addMethod("getPropertyName", Modifier.Keyword.PUBLIC);
        nameMethod.setType("String");
        nameMethod.setBody(new BlockStmt(NodeList.nodeList(new ReturnStmt(new MethodCallExpr("name")))));

        MethodDeclaration typeMethod = enumDecl.addMethod("getPropertyType", Modifier.Keyword.PUBLIC);
        typeMethod.setType(new ClassOrInterfaceType(null, "Type"));
        typeMethod.setBody(new BlockStmt(NodeList.nodeList(new ReturnStmt(new NameExpr("propertyType")))));
    }

    private void addForNameMethod(EnumDeclaration enumDecl, List<Property> constants) {
        MethodDeclaration method = enumDecl.addMethod("forName", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC);
        method.setType(enumName);
        method.addParameter(new ClassOrInterfaceType(null, "String"), "name");

        // Tooling must use JavaParser AST nodes directly, not string building.
        // The desired generated code is:
        //   public static PersonSummary_ forName(String name) {
        //       if (name == null) return null;
        //       switch (name) {
        //           case "id": return PersonSummary_.id;
        //           ...
        //           default: return null;
        //       }
        //   }
        //
        // The returned constants are QUALIFIED by the enum type, and that is not cosmetic: the
        // parameter is called `name`, so an unqualified `return name;` for a view with a field called
        // `name` resolved to the String parameter and produced
        // `incompatible types: java.lang.String cannot be converted to <Enum>` — a generated enum that
        // does not compile, for any view with a field named `name`, `id` or any other name the method's
        // own scope happens to declare. Qualifying makes the arm independent of the method's scope.

        BlockStmt body = new BlockStmt();
        body.addStatement(new IfStmt(
                new BinaryExpr(new NameExpr("name"), new NullLiteralExpr(), BinaryExpr.Operator.EQUALS),
                new ReturnStmt(new NullLiteralExpr()),
                null));

        if (fieldEnumMode) {
            // Field-enum mode: a real `switch` STATEMENT whose arms `return` the constant.
            // An earlier version emitted a SwitchExpr whose arms carried `return` statements, which
            // the arrow-flattening pass then rewrote to `-> return id;` — not legal Java ("attempt
            // to return out of a switch expression"). A statement switch has no such restriction.
            com.github.javaparser.ast.stmt.SwitchStmt switchStmt = new com.github.javaparser.ast.stmt.SwitchStmt();
            switchStmt.setSelector(new NameExpr("name"));
            for (Property prop : constants) {
                switchStmt.getEntries().add(new SwitchEntry(
                        NodeList.nodeList(new StringLiteralExpr(prop.name())),
                        SwitchEntry.Type.STATEMENT_GROUP,
                        NodeList.nodeList(new ReturnStmt(new FieldAccessExpr(
                                new NameExpr(enumName), prop.name())))));
            }
            switchStmt.getEntries().add(new SwitchEntry(
                    NodeList.nodeList(),
                    SwitchEntry.Type.STATEMENT_GROUP,
                    NodeList.nodeList(new ReturnStmt(new NullLiteralExpr()))));
            body.addStatement(switchStmt);
            method.setBody(body);
            return;
        }

        // Property-enum mode: a switch EXPRESSION yielding the constant, used as an expression
        // statement — the shape the tooling's own Property model expects.
        SwitchExpr switchExpr = new SwitchExpr();
        switchExpr.setSelector(new NameExpr("name"));
        for (Property prop : constants) {
            switchExpr.getEntries().add(new SwitchEntry(
                    NodeList.nodeList(new StringLiteralExpr(prop.name())),
                    SwitchEntry.Type.EXPRESSION,
                    NodeList.nodeList(new ExpressionStmt(new NameExpr(prop.name())))));
        }
        switchExpr.getEntries().add(new SwitchEntry(
                NodeList.nodeList(),
                SwitchEntry.Type.EXPRESSION,
                NodeList.nodeList(new ExpressionStmt(new NullLiteralExpr()))));
        body.addStatement(new ReturnStmt(switchExpr));
        method.setBody(body);
    }

    private Expression permittedSubtypeArrayExpression() {
        if (permittedSubtypeClassNames.length == 0) {
            return parseExpression("new Class<?>[0]");
        }
        ArrayCreationExpr arrayCreation = new ArrayCreationExpr();
        arrayCreation.setElementType(new ClassOrInterfaceType(null, "Class").setTypeArguments(new NodeList<>(new WildcardType())));
        arrayCreation.setLevels(new NodeList<>(new ArrayCreationLevel()));
        ArrayInitializerExpr initializer = new ArrayInitializerExpr();
        NodeList<Expression> values = new NodeList<>();
        for (String subtype : permittedSubtypeClassNames) {
            values.add(parseExpression(subtype));
        }
        initializer.setValues(values);
        arrayCreation.setInitializer(initializer);
        return arrayCreation;
    }

    private Expression parseExpression(String source) {
        return SourceReader.parser().parseExpression(source)
                .getResult()
                .orElseThrow(() -> new IllegalArgumentException("Unable to parse expression: " + source));
    }

    private Expression parseTypeExpression(String rawType) {
        return parseExpression(typeExpression(rawType));
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
