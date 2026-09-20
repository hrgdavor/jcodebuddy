package hr.hrg.hipster.entity.tooling;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import hr.hrg.hipster.entity.tooling.meta.EntityFieldMeta;
import hr.hrg.hipster.entity.tooling.meta.EntityMeta;
import hr.hrg.hipster.entity.tooling.meta.FieldConstraint;
import hr.hrg.hipster.entity.tooling.meta.InterfaceInfo;
import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.TrackableType;
import hr.hrg.hipster.entity.api.GenLevel;
import hr.hrg.hipster.entity.api.meta.TypeDescriptor;
import hr.hrg.hipster.entity.tooling.meta.ViewAttributes;
import hr.hrg.hipster.entity.tooling.meta.ViewMeta;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class EntityMetadataGenerator {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ── identity, and the flag surface a runner depends on ───────────────────

    /** The generator's stable name, used by the identity banner, {@code --version} and the run record. */
    public static final String GENERATOR_NAME = "hipster-entity-generator";

    /**
     * The output directory that must never receive generated {@code .java} (DEC-026, {@code AGENTS.md}
     * § 2): a {@code .jcodebuddy/} directory is a module's <em>metadata</em> root — entity JSON for
     * tooling consumers, indexes, caches — and the main output, generated source, belongs in the
     * normal source tree.
     */
    public static final String JCODEBUDDY_DIR = ".jcodebuddy";

    /** Every CLI flag this build understands; {@code --version} prints it. */
    public static final List<String> SUPPORTED_FLAGS = List.of(
            "<source-root>", "<output-dir>", "--packages", "--java-out", "--validate", "--mapper",
            "--adapters", "--run-record", "--version");

    /**
     * The flags every documented invocation of this generator passes.
     *
     * <p>JCodeBuddy is a side-car: nothing here is bound to a build lifecycle. These are the flags
     * the module POM's explicit {@code exec:java} goals and {@code scripts/gen.cmd} pass, and they
     * are also the flags a caller reads out of that POM to learn the invocation contract.
     * {@link GeneratorPreflight} asserts this list against the tooling actually on the classpath,
     * so an <em>older</em> tooling — which silently ignores the flags it does not know, turning
     * them into positional arguments and writing generated Java into positional 2, the metadata
     * directory — fails loudly instead of mis-generating.</p>
     */
    public static final List<String> EXECUTION_FLAGS = List.of(
            "--java-out", "--packages", "--validate", "--run-record");

    /** Whether this build understands {@code flag} — the preflight's whole assertion. */
    public static boolean supportsFlag(String flag) {
        return SUPPORTED_FLAGS.contains(flag);
    }

    /**
     * Where the running generator's classes came from: a jar path, or a module's
     * {@code target/classes}.
     *
     * <p>This is the fact that identifies the wrong tooling. A pass that reports
     * {@code from .../.m2/repository/...} is running an installed revision, which is not necessarily
     * the revision in the working tree; {@code from .../hipster-entity-tooling/target/classes} is the
     * reactor's own output.</p>
     */
    public static String generatorClasspath() {
        try {
            java.security.CodeSource source =
                    EntityMetadataGenerator.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                return source.getLocation().toString();
            }
        } catch (RuntimeException unreadable) {
            // A security manager or a stripped class loader: report "unknown" rather than fail a pass
            // over a diagnostic.
        }
        return "unknown";
    }

    /**
     * The tooling revision: the jar manifest's {@code Implementation-Version}, or the timestamp of the
     * class file when there is no manifest.
     *
     * <p>The fallback matters because the reactor's own output is a {@code target/classes}
     * <em>directory</em>, which has no manifest at all — and "which revision is this?" is exactly the
     * question a mis-generated tree raises. A timestamp is enough to spot a directory left over from
     * an earlier build.</p>
     */
    public static String generatorVersion() {
        Package module = EntityMetadataGenerator.class.getPackage();
        String version = module == null ? null : module.getImplementationVersion();
        if (version != null && !version.isBlank()) {
            return version;
        }
        return "unversioned classes dated " + generatorClassTimestamp();
    }

    /**
     * The class file's last-modified time as an ISO instant, or the artifact's when the classes came
     * from a jar, or {@code "unknown"}.
     */
    private static String generatorClassTimestamp() {
        try {
            java.net.URL own = EntityMetadataGenerator.class
                    .getResource("EntityMetadataGenerator.class");
            if (own != null && "file".equals(own.getProtocol())) {
                return Files.getLastModifiedTime(Path.of(own.toURI())).toInstant().toString();
            }
        } catch (Exception unreadable) {
            // A jar entry, or a loader that will not resolve the resource: fall through.
        }
        try {
            java.security.CodeSource source =
                    EntityMetadataGenerator.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path artifact = Path.of(source.getLocation().toURI());
                if (Files.isRegularFile(artifact)) {
                    return Files.getLastModifiedTime(artifact).toInstant().toString();
                }
            }
        } catch (Exception unreadable) {
            // Nothing resolvable to a timestamp: "unknown" is the honest answer, and the classpath
            // still names the artifact.
        }
        return "unknown";
    }

    /** The one-line identity banner: name, version, and where the classes came from. */
    public static String generatorIdentity() {
        return GENERATOR_NAME + " " + generatorVersion() + " from " + generatorClasspath();
    }

    /**
     * Refuses to write generated {@code .java} under a {@link #JCODEBUDDY_DIR} directory.
     *
     * <p>Called before the first write of every pass, so every entry point is covered — the CLI, the
     * module POM's explicit {@code exec:java} goals, {@code scripts/gen.cmd}, and
     * {@code EntityRegenerationWatcher}. The rule it enforces is the layout rule, not a preference:
     * {@code .jcodebuddy/} holds the module's auxiliary metadata, and generated source is ordinary
     * committed Java that belongs next to the view it came from.</p>
     *
     * <p>In practice this fires when the invocation has no {@code --java-out} and no other place to
     * put source, so positional 2 — the metadata directory — is all that is left. The message says
     * both things, because which one it is decides the fix.</p>
     */
    static void rejectJavaOutputUnderJcodebuddy(Path javaOutputRoot) throws IOException {
        if (javaOutputRoot == null) {
            return;
        }
        for (Path part : javaOutputRoot.toAbsolutePath().normalize()) {
            if (JCODEBUDDY_DIR.equals(part.toString())) {
                throw new IOException("refusing to write generated .java into " + javaOutputRoot
                        + ": a " + JCODEBUDDY_DIR + "/ directory is a module's metadata root and never"
                        + " holds generated source (DEC-026, AGENTS.md section 2). Pass --java-out with"
                        + " the source root, as `scripts\\gen.cmd` and the module POM's exec:java goal"
                        + " both do. If --java-out was passed and still lost, the tooling on the"
                        + " classpath is older than the invocation: it does not know the flag and writes"
                        + " generated Java into positional 2. Rebuild it with"
                        + " `scripts\\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am compile`, or let"
                        + " `scripts\\gen.cmd` compile and export the classpath for you.");
            }
        }
    }

    public static EntityMeta fromJson(String json) throws java.io.IOException {
        JsonNode root = OBJECT_MAPPER.readTree(json);

        String entityName = root.path("entityName").asText();
        String packageName = root.path("package").asText();
        String markerInterface = root.path("markerInterface").asText();
        String idType = root.path("idType").asText();

        List<ViewMeta> views = new ArrayList<>();
        for (JsonNode viewNode : root.path("views")) {
            String viewName = viewNode.path("name").asText();
            List<String> extendsTypes = new ArrayList<>();
            for (JsonNode ext : viewNode.path("extends")) {
                extendsTypes.add(ext.asText());
            }
            GenLevel gen = parseGenLevel(viewNode.path("gen").asText(null));
            String discriminatorField = viewNode.path("discriminatorField").asText("");
            List<String> addons = new ArrayList<>();
            for (JsonNode addon : viewNode.path("addons")) {
                addons.add(addon.asText());
            }
            int viewLineNumber = viewNode.path("lineNumber").asInt(-1);

            List<Property> properties = new ArrayList<>();
            for (JsonNode propNode : viewNode.path("properties")) {
                String name = propNode.path("name").asText();
                String type = parseTypeText(propNode.path("type"));
                String fieldKind = propNode.has("fieldKind") ? propNode.path("fieldKind").asText(null) : null;
                String column = propNode.has("column") ? propNode.path("column").asText(null) : null;
                String relation = propNode.has("relation") ? propNode.path("relation").asText(null) : null;
                String expression = propNode.has("expression") ? propNode.path("expression").asText(null) : null;
                int lineNumber = propNode.path("lineNumber").asInt(-1);
                // § 12.3/7.9: the constraints ride the metadata JSON so a regeneration pass reproduces
                // them, and so a consumer can see what the generated artifacts were annotated with.
                List<FieldConstraint> constraints = new ArrayList<>();
                for (JsonNode constraintNode : propNode.path("constraints")) {
                    constraints.add(new FieldConstraint(
                            constraintNode.path("annotation").asText(),
                            constraintNode.path("arguments").asText("")));
                }
                // § 8.2/3.6: the resolved type imports ride the JSON too, so a consumer reading the
                // metadata can reproduce a generated declaration without re-parsing the view source.
                List<String> typeImports = new ArrayList<>();
                for (JsonNode importNode : propNode.path("typeImports")) {
                    typeImports.add(importNode.asText());
                }
                properties.add(new Property(name, type, fieldKind, column, relation, expression,
                        lineNumber, constraints, typeImports));
            }
            views.add(new ViewMeta(viewName, extendsTypes, gen, discriminatorField, addons, properties, viewLineNumber));
        }

        List<EntityFieldMeta> allFields = new ArrayList<>();
        for (JsonNode fieldNode : root.path("allFields")) {
            String name = fieldNode.path("name").asText();
            String type = parseTypeText(fieldNode.path("type"));
            String fieldKind = fieldNode.path("fieldKind").asText(null);
            String column = fieldNode.path("column").asText(null);
            String relation = fieldNode.path("relation").asText(null);
            String expression = fieldNode.path("expression").asText(null);
            int lineNumber = fieldNode.path("lineNumber").asInt(-1);

            List<String> fieldViews = new ArrayList<>();
            for (JsonNode v : fieldNode.path("views")) {
                fieldViews.add(v.asText());
            }

            Map<String,String> typeByView = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = fieldNode.path("typeByView").propertyStream().iterator(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                typeByView.put(entry.getKey(), parseTypeText(entry.getValue()));
            }

            allFields.add(new EntityFieldMeta(name, type, fieldKind, column, relation, expression, lineNumber, fieldViews, typeByView));
        }

        return new EntityMeta(entityName, packageName, markerInterface, idType, views, allFields);
    }

    private static String parseTypeText(JsonNode typeNode) {
        if (typeNode.isTextual()) {
            return typeNode.asText();
        }
        if (typeNode.isObject()) {
            return typeNode.path("type").asText();
        }
        return null;
    }

    // metadata classes are now top-level in same package (Property, ViewMeta, EntityMeta, EntityFieldMeta)

    private static TypeDescriptor parseTypeDescriptor(String rawType) {
        String type = rawType.trim();
        boolean array = false;
        while (type.endsWith("[]")) {
            array = true;
            type = type.substring(0, type.length() - 2).trim();
        }

        int genericStart = type.indexOf('<');
        if (genericStart < 0) {
            boolean primitive = isPrimitiveType(type) || isBoxedPrimitiveType(type);
            String typeName;
            if (primitive) {
                typeName = boxedPrimitiveClass(type);
                if (typeName == null) {
                    typeName = classLiteral(type);
                }
            } else {
                typeName = classLiteral(type);
            }
            return new TypeDescriptor(typeName, List.of(), array, primitive, List.of());
        }

        String raw = type.substring(0, genericStart).trim();
        String inner = type.substring(genericStart + 1, type.lastIndexOf('>'));
        String[] generics = splitGenerics(inner);
        List<TypeDescriptor> args = new ArrayList<>();

        for (String g : generics) {
            args.add(parseTypeDescriptor(g));
        }

        return new TypeDescriptor(classLiteral(raw), args, array, false, List.of());
    }

    private static void appendJsonType(StringBuilder sb, TypeDescriptor td, boolean trailingComma, int indent) {
        String indentStr = " ".repeat(indent);
        if (!td.isParameterized() && !td.array()) {
            if (td.primitive()) {
                sb.append(indentStr).append("{\n");
                sb.append(indentStr).append("  \"type\": \"").append(td.typeName()).append("\",").append("\n");
                sb.append(indentStr).append("  \"unboxed\": \"").append(unboxedPrimitiveType(td.typeName())).append("\",").append("\n");
                sb.append(indentStr).append("  \"primitive\": true\n");
                sb.append(indentStr).append("}");
                if (trailingComma) sb.append(",");
                sb.append("\n");
                return;
            }
            sb.append(indentStr).append("\"").append(td.typeName()).append("\"");
            if (trailingComma) sb.append(",");
            sb.append("\n");
            return;
        }

        sb.append(indentStr).append("{\n");
        sb.append(indentStr).append("  \"type\": \"").append(td.typeName()).append("\",").append("\n");
        if (td.array()) {
            sb.append(indentStr).append("  \"array\": true,").append("\n");
        }
        if (td.isParameterized()) {
            sb.append(indentStr).append("  \"genericArguments\": [\n");
            for (int i = 0; i < td.typeArguments().size(); i++) {
                appendJsonType(sb, td.typeArguments().get(i), i < td.typeArguments().size() - 1, indent + 4);
            }
            sb.append(indentStr).append("  ]\n");
        }
        sb.append(indentStr).append("}");
        if (trailingComma) sb.append(",");
        sb.append("\n");
    }

    /**
     * Package filter for <strong>generation</strong> (plan.dsflash § 4.3/X3, § 4.7/DR-1).
     *
     * <p>Crucially this filters generation, <em>not</em> indexing: every source file under the
     * source root is still parsed into {@code interfaceMap}, so cross-package supertypes and addons
     * stay resolvable. Only view discovery and output are restricted, which is what keeps the
     * example's documentation-sample packages and its {@code example/} package untouched.</p>
     *
     * <p>Empty means "generate everything", which is the historical behaviour.</p>
     */
    private static java.util.Set<String> generationPackages = java.util.Set.of();

    /** Sets the generation package filter; an empty or null set disables the filter. */
    public static void setGenerationPackages(java.util.Collection<String> packages) {
        generationPackages = packages == null || packages.isEmpty()
                ? java.util.Set.of()
                : java.util.Set.copyOf(packages);
    }

    /** Whether a view declared in {@code packageName} is a generation target. */
    private static boolean isGenerationTarget(String packageName) {
        return generationPackages.isEmpty() || generationPackages.contains(packageName);
    }

    /**
     * Whether SQL generation (the positional {@code <View>RowAdapter} / {@code <View>Binder} pair) is
     * enabled for this pass.
     *
     * <p><strong>Status: draft / exploration, and strictly opt-in.</strong> SQL materialization is not
     * a supported generator: it emits only when a project asks for it with {@code --adapters}, it is
     * off in the default pass (this field's initial value), and the example project deliberately does
     * not enable it. Whatever SQL support comes later must keep that shape — an explicit request, never
     * a default — so a project never receives generated SQL classes it did not choose. The opt-in rule
     * is pinned by {@code ViewAdapterGeneratorTest#sqlGenerationIsOptInAndOffByDefault}.</p>
     */
    private static boolean generateAdapters = false;

    /**
     * Enables or disables generated JDBC adapters ({@code --adapters}).
     *
     * <p>The flag exists so tests and a manual run can exercise the draft; production projects are
     * expected to leave it alone until SQL support is settled.</p>
     */
    public static void setGenerateAdapters(boolean enabled) {
        generateAdapters = enabled;
    }

    /** Whether SQL generation is currently opted in (its own flag, never a default). */
    public static boolean isGenerateAdapters() {
        return generateAdapters;
    }

    /**
     * Requested view-to-view mappers (§ 12.2/7.5), as {@code <sourceView>:<targetView>[:<ClassName>]}
     * entries.
     *
     * <p>A mapper is requested rather than derived because there is no way to know which of the
     * n×(n-1) view pairs a project actually wants, and generating all of them would be noise. The flag
     * is repeatable and is the same surface every documented invocation uses (§ 8.8/3.23).</p>
     */
    private static List<String> mapperRequests = new ArrayList<>();

    /** Sets the requested mappers ({@code --mapper}). */
    public static void setMapperRequests(List<String> requests) {
        mapperRequests = requests == null ? new ArrayList<>() : new ArrayList<>(requests);
    }

    /**
     * How the entity-rules validator participates in a generation pass (plan.dsflash § 6.3/1.13).
     *
     * <p>Task 1.13 says <em>"wire the validator into {@code EntityMetadataGenerator.generate(...)}:
     * collect {@code ValidationIssues} and fail (or warn behind a {@code strict} flag) before writing
     * files"</em>. That task was recorded as done while {@link
     * hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator} was in fact instantiated only from
     * tests: nothing in {@code src/main} constructed it, there was no {@code validate} subcommand, and
     * the four rules (plus the enum-order rule) therefore had no way to report anything about a real
     * tree. Running them by hand over the committed example produced <strong>20 issues</strong> that no
     * build had ever shown.</p>
     *
     * <p>The plan left the failure policy as a choice between "fail" and "warn behind a strict flag".
     * The choice made here is the one that makes validation real without making the existing example
     * unbuildable while its advisory rules are reconciled:</p>
     * <ul>
     *   <li>{@link Policy#OFF} — the rules do not run. This is the default for a <em>library</em>
     *       caller that did not ask for validation, and for the generator's own unit tests, so
     *       introducing validation cannot change unrelated fixtures.</li>
     *   <li>{@link Policy#REPORT} — the rules run, every issue is printed once, and generation
     *       continues. This is what the example's invocation uses: it makes the validator's output
     *       part of every pass log, which is how a newly broken rule becomes visible.</li>
     *   <li>{@link Policy#STRICT} — the rules run and the pass fails <em>before</em> any file is
     *       written, so a violating tree is never half-regenerated. This is the mode an adopting project
     *       gates its build with, and the mode the {@code validate} subcommand exits non-zero on.</li>
     * </ul>
     *
     * <p>Validation is deliberately a policy rather than a hard failure: the rules encode naming and
     * package conventions that a project may legitimately not want, and a generator that refused to run
     * because a view is named {@code PersonSummary} rather than {@code PersonSummaryEntity} would be
     * the framework dictating style. {@link Policy#REPORT} gives the diagnostics; {@link Policy#STRICT}
     * is the opt-in gate.</p>
     */
    public enum Policy {
        OFF, REPORT, STRICT
    }

    private static Policy validationPolicy = Policy.OFF;

    private static List<hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator.ValidationIssue>
            lastValidationIssues = List.of();

    /** Selects the validation policy; null means {@link Policy#OFF}. */
    public static void setValidationPolicy(Policy policy) {
        validationPolicy = policy == null ? Policy.OFF : policy;
    }

    /** The selected validation policy. */
    public static Policy validationPolicy() {
        return validationPolicy;
    }

    /**
     * The issues the last validation pass found, in file order — the assertion hook for tests and for
     * a caller that wants to inspect rather than print. Empty when validation was off or clean.
     */
    public static List<hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator.ValidationIssue>
            lastValidationIssues() {
        return lastValidationIssues;
    }

    /**
     * Runs the entity rules over {@code sourceRoot} according to {@link #validationPolicy}, records the
     * issues, prints them, and fails the pass when the policy is {@link Policy#STRICT}.
     *
     * <p>Called at the <em>start</em> of generation, before the first file is written: a strict failure
     * must not leave a half-regenerated tree behind, and a report is more useful next to the source
     * root it describes than at the end of a long log.</p>
     */
    private static void runValidation(Path sourceRoot) throws IOException {
        lastValidationIssues = List.of();
        if (validationPolicy == Policy.OFF) {
            return;
        }
        List<hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator.ValidationIssue> issues =
                new hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator().validate(sourceRoot);
        lastValidationIssues = issues;

        if (issues.isEmpty()) {
            System.out.println("Validation: no issues in " + sourceRoot);
            return;
        }
        System.out.println("Validation: " + issues.size() + " issue(s) in " + sourceRoot
                + " (policy " + validationPolicy + ")");
        for (hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator.ValidationIssue issue : issues) {
            // One line per issue, `file :: message`, so the report is greppable in a build log and
            // stable enough for the docs' own example to be checked against it.
            System.out.println("  validation: " + sourceRoot.relativize(issue.file) + " :: " + issue.message);
        }
        // STRICT fails on a violation but not on a warning: `allowReorder` is an escape hatch a
        // project sets on purpose, and R1.3 says warnings go to stderr without failing, while
        // `--strict` (the CLI's flag) is what promotes them.
        if (validationPolicy == Policy.STRICT && !blockingIssues(issues).isEmpty()) {
            throw new ValidationFailedException(blockingIssues(issues));
        }
    }

    /** The issues that fail a strict pass: every violation, plus warnings when the caller asked. */
    private static List<hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator.ValidationIssue>
            blockingIssues(List<hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator.ValidationIssue> issues) {
        return issues.stream()
                .filter(issue -> strictWarnings || !issue.isWarning())
                .toList();
    }

    /**
     * Whether the advisory kinds ({@link
     * hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator.ValidationIssue#isWarning()}) are
     * promoted to failures. Set by the {@code validate --strict} CLI form and by
     * {@code --validate=STRICT}; R1.3 requires {@code allowReorder} to be visible without failing the
     * build until someone says it should.
     */
    private static boolean strictWarnings = false;

    /**
     * Signal that the entity rules rejected the tree. A named exception rather than a bare
     * {@link IllegalStateException} so a failed pass says which layer refused,
     * and so a caller can catch exactly this and still read {@link #lastValidationIssues()}.
     */
    public static class ValidationFailedException extends IOException {
        private final transient List<
                hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator.ValidationIssue> issues;

        public ValidationFailedException(List<
                hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator.ValidationIssue> issues) {
            super("entity validation failed with " + issues.size() + " issue(s); the first is: "
                    + (issues.isEmpty() ? "-" : issues.get(0)));
            this.issues = List.copyOf(issues);
        }

        public List<hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator.ValidationIssue>
                issues() {
            return issues;
        }
    }

    public static void main(String[] args) throws IOException {
        // Sub-command dispatch. The generator remains the default so every existing invocation
        // keeps working; `enum-order` is the R1 checker CLI (§ 6.3/1.16), wired next to the
        // generator's entry point rather than into a separate launcher.
        if (args.length > 0 && "enum-order".equals(args[0])) {
            String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
            System.exit(hr.hrg.hipster.entity.tooling.validation.EnumConstantOrderCli.run(rest));
        }
        if (args.length > 0 && "enum-compact".equals(args[0])) {
            // § 12.4/7.13–7.16: the deliberate compaction mode. It is a subcommand and not a flag so it
            // can never be reached by adding an argument to a normal generation run.
            String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
            System.exit(hr.hrg.hipster.entity.tooling.validation.EnumCompactionCli.run(rest));
        }
        if (args.length > 0 && "validate".equals(args[0])) {
            // § 6.3/1.13, as a subcommand: the entity rules as a gate an adopting project can run
            // from a pre-commit hook or a build step. It exits 0 on a clean tree and 1 when any rule
            // reports, so it composes with `&&` the way the other two subcommands do.
            String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
            System.exit(runValidateSubcommand(rest));
        }

        // CLI flags may appear anywhere after the two positional arguments (§ 8.8/3.23), so one
        // flag surface serves every invocation: the module POM's exec:java goals, scripts\gen.cmd,
        // and the watcher.
        // A flag belongs to one invocation, never to the process: a second pass in the same JVM (a
        // test, a watcher run) must not inherit the first pass's mode.
        validationPolicy = Policy.OFF;
        strictWarnings = false;
        List<String> positional = new ArrayList<>();
        List<String> packages = new ArrayList<>();
        List<String> mappers = new ArrayList<>();
        Path javaOutputOverride = null;
        Path runRecord = null;
        long startedAt = System.currentTimeMillis();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--version".equals(arg)) {
                // Identity without side effects: no pass, no write, no exit call (a `--version` that
                // killed the JVM would take the Maven JVM with it under exec:java).
                System.out.println(generatorIdentity());
                System.out.println("flags: " + String.join(" ", SUPPORTED_FLAGS));
                return;
            } else if ("--packages".equals(arg) && i + 1 < args.length) {
                for (String pkg : args[++i].split(",")) {
                    if (!pkg.isBlank()) {
                        packages.add(pkg.trim());
                    }
                }
            } else if (arg.startsWith("--packages=")) {
                for (String pkg : arg.substring("--packages=".length()).split(",")) {
                    if (!pkg.isBlank()) {
                        packages.add(pkg.trim());
                    }
                }
            } else if ("--adapters".equals(arg)) {
                generateAdapters = true;
            } else if ("--validate".equals(arg)) {
                validationPolicy = Policy.REPORT;
            } else if (arg.startsWith("--validate=")) {
                // `--validate=strict` is the gate form; `--validate=off` exists so a project can pass
                // one flag surface everywhere and still switch the rules off in one profile.
                String mode = arg.substring("--validate=".length()).trim().toUpperCase(java.util.Locale.ROOT);
                try {
                    validationPolicy = Policy.valueOf(mode);
                    strictWarnings = validationPolicy == Policy.STRICT;
                } catch (IllegalArgumentException e) {
                    System.err.println("Unknown --validate mode '" + mode + "'; expected OFF, REPORT or STRICT.");
                    System.exit(1);
                }
            } else if ("--mapper".equals(arg) && i + 1 < args.length) {
                mappers.add(args[++i]);
            } else if (arg.startsWith("--mapper=")) {
                mappers.add(arg.substring("--mapper=".length()));
            } else if ("--run-record".equals(arg) && i + 1 < args.length) {
                // Opt-in, so a library caller and every test are unaffected: a record of what this
                // pass ran with, in the same JSON metadata tree the rest of the tooling reads.
                runRecord = Path.of(args[++i]);
            } else if (arg.startsWith("--run-record=")) {
                runRecord = Path.of(arg.substring("--run-record=".length()));
            } else if ("--java-out".equals(arg) && i + 1 < args.length) {
                // Where generated .java goes, independently of where the metadata JSON goes. The
                // two always differ for this project (§ 8.8/3.23): source is regenerated in place
                // under src/main/java, while the JSON belongs in the module's
                // `.jcodebuddy/metadata/entity` (DEC-026), so a pass never drops generated source
                // into the metadata tree.
                javaOutputOverride = Path.of(args[++i]);
            } else if (arg.startsWith("--java-out=")) {
                javaOutputOverride = Path.of(arg.substring("--java-out=".length()));
            } else {
                positional.add(arg);
            }
        }
        setGenerationPackages(packages);
        setMapperRequests(mappers);

        if (positional.size() < 2) {
            printUsage();
            System.exit(1);
        }

        Path inputPath = Path.of(positional.get(0));
        Path outputDir = Path.of(positional.get(1));
        Path sourceRoot = deriveSourceRoot(inputPath);
        // Where generated .java goes: the source root for a single-file input, --java-out when given,
        // and positional 2 otherwise (the historical default the guard below exists to catch).
        Path javaOutputRoot = inputPath.toString().endsWith(".java")
                ? sourceRoot
                : (javaOutputOverride != null ? javaOutputOverride : outputDir);
        // Identity first, on every run: when a build misbehaves, the first question is which
        // generator revision ran, and this line answers it without a second invocation.
        System.out.println(generatorIdentity());
        System.out.println("Using source root: " + sourceRoot);
        if (!generationPackages.isEmpty()) {
            System.out.println("Generating only for packages: " + generationPackages);
        }
        if (javaOutputOverride != null && !inputPath.toString().endsWith(".java")) {
            // § 8.8/3.23: a pass regenerates the committed source in place and keeps the
            // metadata JSON in `.jcodebuddy/metadata/entity`, so it never drops untracked files
            // into src/main/java.
            System.out.println("Writing generated java to: " + javaOutputOverride);
        }
        try {
            generate(sourceRoot, outputDir, javaOutputRoot);
            writeRunRecord(runRecord, "ok", null, sourceRoot, outputDir, javaOutputRoot, packages,
                    mappers, startedAt, args);
        } catch (IOException | RuntimeException failure) {
            // A failed pass is the run a reader most wants a record of, so the record is written
            // before the failure is rethrown.
            writeRunRecord(runRecord, "failed", failure, sourceRoot, outputDir, javaOutputRoot, packages,
                    mappers, startedAt, args);
            throw failure;
        } finally {
            // Reset the policy so a second pass in the same JVM (a test, a watcher run) does not
            // inherit the first pass's mode. Flags belong to one invocation, not to the process —
            // which goes for the generator's other process-global knobs too, since a caller that
            // invoked main() in-process must not have its own configuration overwritten afterwards.
            validationPolicy = Policy.OFF;
            strictWarnings = false;
            setGenerationPackages(List.of());
            setMapperRequests(List.of());
            setGenerateAdapters(false);
        }
    }

    /**
     * Writes the opt-in run record ({@code --run-record <file>}).
     *
     * <p>It is the answer to "what generated this tree, with which flags, from which artifact, and
     * what did it report?" — the question a reviewer asks of a regenerated diff, and the one a
     * stale-artifact build cannot answer after the fact. It lands in the module's metadata tree
     * alongside the entity JSON, because that is what the tree is for: machine-readable facts for
     * tooling, not source.</p>
     *
     * <p>Written on success <em>and</em> on failure, and a no-op when the flag is absent, so a
     * library caller and every existing test is unaffected by its existence.</p>
     */
    private static void writeRunRecord(Path recordFile, String status, Exception failure, Path sourceRoot,
                                       Path outputDir, Path javaOutputRoot, List<String> packages,
                                       List<String> mappers, long startedAt, String[] args)
            throws IOException {
        if (recordFile == null) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("generator", GENERATOR_NAME);
        record.put("version", generatorVersion());
        record.put("classpath", generatorClasspath());
        record.put("status", status);
        record.put("startedAt", java.time.Instant.ofEpochMilli(startedAt).toString());
        record.put("durationMs", System.currentTimeMillis() - startedAt);
        record.put("sourceRoot", sourceRoot == null ? null : sourceRoot.toString());
        record.put("reportDir", outputDir == null ? null : outputDir.toString());
        record.put("javaOut", javaOutputRoot == null ? null : javaOutputRoot.toString());
        record.put("packages", packages);
        record.put("mappers", mappers);
        record.put("adapters", generateAdapters);
        record.put("validate", validationPolicy.name());
        record.put("args", List.of(args));
        if (failure == null) {
            record.put("validationIssues", lastValidationIssues.size());
            record.put("divergences", lastDivergences.entries());
        } else {
            // No divergence report exists for a pass that threw, but the reason must not be lost.
            record.put("failure", failure.getClass().getName() + ": " + failure.getMessage());
        }
        Path parent = recordFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(recordFile, OBJECT_MAPPER.writeValueAsString(record) + "\n");
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar hipster-entity-tooling.jar <source-root|java-source-file> <output-dir>");
        System.err.println("If the first argument is a .java file, the tool will locate the source root by searching for src/main/java or src/test/java.");
        System.err.println("Generated view boilerplate for a single Java file is written back into the source tree using the underscore suffix convention.");
        System.err.println();
        System.err.println("Flags:");
        System.err.println("  --packages <a.b,c.d>  generate only for views in these packages (indexing is unaffected)");
        System.err.println("  --adapters            [DRAFT/EXPLORATION, opt-in] also emit the positional JDBC adapters and binders.");
        System.err.println("  --java-out <dir>      write generated .java here instead of <output-dir>");
        System.err.println("  --mapper <Src>:<Tgt>[:<ClassName>]");
        System.err.println("                        also emit a statically-dispatched mapper between two views");
        System.err.println("  --validate[=MODE]     run the entity rules before writing (MODE: OFF, REPORT, STRICT).");
        System.err.println("                        Bare --validate means REPORT: print and continue. STRICT refuses");
        System.err.println("                        to write anything until the reported issues are fixed.");
        System.err.println("  --run-record <file>   also write what this pass ran with (revision, flags, counts)");
        System.err.println("                        as JSON. A generation pass writes");
        System.err.println("                        .jcodebuddy/metadata/entity/generation.json.");
        System.err.println("  --version             print the generator identity and its flag surface, then stop.");
        System.err.println();
        System.err.println("Generated .java is never written under a .jcodebuddy/ directory: that is the");
        System.err.println("module's metadata root, not a source tree (DEC-026). Pass --java-out.");
        System.err.println();
        System.err.println("Entity rules:");
        System.err.println("  java -jar hipster-entity-tooling.jar validate [<source-root>] [--strict]");
        System.err.println("  Exits 0 on a clean tree, 1 when a rule reports, 2 on a usage error.");
        System.err.println();
        System.err.println("R1 order checker:");
        System.err.println("  java -jar hipster-entity-tooling.jar enum-order --repo <path> --baseline <ref> [--target <ref>] [--strict]");
        System.err.println("  Exits non-zero on any enum_order_shuffled / enum_constant_removed violation.");
        System.err.println();
        System.err.println("Field-enum compaction (a deliberate ordinal migration; needs BOTH acknowledgements):");
        System.err.println("  java -jar hipster-entity-tooling.jar enum-compact --repo <path> --allow-reorder \\");
        System.err.println("        --acknowledge-drained-data [--target <path-substring>]");
    }

    private static Path deriveSourceRoot(Path inputPath) {
        if (Files.isDirectory(inputPath)) {
            return inputPath;
        }

        if (inputPath.toString().endsWith(".java")) {
            Path current = inputPath.getParent();
            while (current != null) {
                if (current.getFileName() != null && "java".equals(current.getFileName().toString())) {
                    Path parent = current.getParent();
                    if (parent != null && "main".equals(parent.getFileName().toString())
                            && parent.getParent() != null
                            && "src".equals(parent.getParent().getFileName().toString())) {
                        return current;
                    }
                    if (parent != null && "test".equals(parent.getFileName().toString())
                            && parent.getParent() != null
                            && "src".equals(parent.getParent().getFileName().toString())) {
                        return current;
                    }
                }
                current = current.getParent();
            }

            // If no standard Maven source root is found, derive the source root from the package declaration.
            try {
                String source = Files.readString(inputPath);
                ParseResult<CompilationUnit> parseResult = SourceReader.parser().parse(source);
                CompilationUnit cu = parseResult.getResult().orElse(null);
                if (cu != null && cu.getPackageDeclaration().isPresent()) {
                    String packageName = cu.getPackageDeclaration().get().getNameAsString();
                    Path parent = inputPath.getParent();
                    String[] segments = packageName.split("\\.");
                    for (int i = segments.length - 1; i >= 0 && parent != null; i--) {
                        if (segments[i].equals(parent.getFileName().toString())) {
                            parent = parent.getParent();
                        } else {
                            parent = null;
                        }
                    }
                    if (parent != null) {
                        return parent;
                    }
                }
            } catch (IOException ignored) {
                // Fall back to the file's parent directory if package parsing fails.
            }
            return inputPath.getParent();
        }

        return inputPath;
    }

    public static void generate(Path sourceRoot, Path outputDir) throws IOException {
        generate(sourceRoot, outputDir, outputDir);
    }

    /**
     * Interface accessors that belong to the framework surface rather than to the view's field set
     * (plan.dsflash § 8.2/3.5). Collected by declaration origin — a method whose erased signature
     * matches one of these — rather than by matching against a hand-kept list of view names.
     *
     * <p>The {@code default}-method filter alone is not enough: an <em>abstract</em> zero-arg
     * accessor declared by a view that also extends a write/tracking surface is collected as a
     * field too. The live witness is
     * {@code PersonUpdatableView_.changes(TypeUtils.parameterizedType(EEnumSet.class, ...))}, which
     * comes from {@code PersonUpdatableView.changes()}.</p>
     */
    private static final Set<String> FRAMEWORK_ACCESSORS = Set.of(
            "isChanged", "changes", "changesBuilder", "changedValues", "clearChanges",
            "currentValue", "get", "set");

    /**
     * The framework interfaces whose declared accessors are never view fields. A method is
     * framework-owned when it is declared by — or inherited from — one of them.
     */
    private static final Set<String> FRAMEWORK_TYPES = Set.of(
            "ViewReader", "ViewWriter", "ViewChangeTracking",
            "hr.hrg.hipster.entity.api.ViewReader",
            "hr.hrg.hipster.entity.api.ViewWriter",
            "hr.hrg.hipster.entity.core.ViewChangeTracking");

    /**
     * Whether a zero-arg, non-void method is a view <em>field</em> accessor.
     *
     * <p>Two exclusions, both required by § 8.2/3.5:</p>
     * <ol>
     *   <li>a {@code default} method is implementation, not a field — this is what produced the
     *       bogus {@code toBuilder}/{@code toBuilderTracking} constants in the example;</li>
     *   <li>an accessor the view inherits from the read/write/tracking surface is framework
     *       plumbing, not a field — the {@code changes()} leak in {@code PersonUpdatableView_}.</li>
     * </ol>
     */
    private static boolean isFieldAccessor(MethodDeclaration method) {
        if (!method.getParameters().isEmpty()) {
            return false;
        }
        if (method.getType().isVoidType()) {
            return false;
        }
        if (method.isDefault()) {
            return false;
        }
        if (method.isStatic()) {
            return false;
        }
        String name = method.getNameAsString();
        if (!FRAMEWORK_ACCESSORS.contains(name)) {
            return true;
        }
        // The name is a framework name: exclude it only when it is not redeclared as a genuine
        // view field with a view's own return type. Declaring a real field that happens to be
        // called `get` is unusual but must stay representable; the safe reading is to exclude the
        // framework name (a view field named `changes` would otherwise silently become a field
        // constant that the array path can never fill).
        return false;
    }

    /** Whether any declared or inherited supertype of {@code decl} is a framework surface. */
    private static boolean extendsFrameworkSurface(ClassOrInterfaceDeclaration decl) {
        for (ClassOrInterfaceType type : decl.getExtendedTypes()) {
            if (FRAMEWORK_TYPES.contains(type.getNameAsString())) {
                return true;
            }
        }
        return false;
    }

    public static void generate(Path sourceRoot, Path outputDir, Path javaOutputRoot) throws IOException {
        // § 6.3/1.13: validate BEFORE the first write. A STRICT failure must not leave a
        // half-regenerated tree behind, and REPORT mode's output is most useful next to the source
        // root it describes rather than at the end of the pass.
        runValidation(sourceRoot);
        DivergenceReporter divergences = new DivergenceReporter();
        try {
            generate(sourceRoot, outputDir, javaOutputRoot, divergences);
        } finally {
            reportDivergences(divergences);
        }
    }

    /**
     * The {@code validate} subcommand (§ 6.3/1.13): run the entity rules over a source root and exit
     * non-zero when any of them reports.
     *
     * <pre>{@code
     * java -cp hipster-entity-tooling.jar hr.hrg.hipster.entity.tooling.EntityMetadataGenerator \
     *      validate [<source-root>] [--strict]
     * }</pre>
     *
     * <p>{@code --strict} promotes the advisory kinds (the R1 {@code allowReorder} escape hatch) to
     * failures. The default source root is the working directory, matching the other two subcommands'
     * {@code --repo} default.</p>
     *
     * @return the process exit code: 0 clean, 1 issues found, 2 usage error
     */
    public static int runValidateSubcommand(String[] args) throws IOException {
        Path sourceRoot = Path.of(".");
        boolean strict = false;
        for (String arg : args) {
            if ("--strict".equals(arg)) {
                strict = true;
            } else if (arg.startsWith("--")) {
                System.err.println("Unknown validate option: " + arg);
                printUsage();
                return 2;
            } else {
                sourceRoot = Path.of(arg);
            }
        }
        strictWarnings = strict;
        List<hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator.ValidationIssue> issues =
                new hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator().validate(sourceRoot);
        lastValidationIssues = issues;
        if (issues.isEmpty()) {
            System.out.println("Validation: no issues in " + sourceRoot);
            return 0;
        }
        System.out.println("Validation: " + issues.size() + " issue(s) in " + sourceRoot
                + (strict ? " (strict)" : ""));
        for (hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator.ValidationIssue issue : issues) {
            System.out.println("  validation: " + issue.file + " :: " + issue.message);
        }
        // Without `--strict` an advisory-only report is not a failure (R1.3: an `allowReorder` escape
        // hatch goes to the log without failing), which is what makes this subcommand usable in a
        // pre-commit hook before a project has settled every ledger.
        return blockingIssues(issues).isEmpty() ? 0 : 1;
    }

    /**
     * The divergence report of the most recent generation pass (plan.dsflash § 8.7/3.20).
     *
     * <p>Populated by {@link #generate(Path, Path)} and readable afterwards, so a test or a build
     * step can assert on what a regeneration actually changed — an appended constant, a retired
     * field, an accepted reorder. Without this the ledger planner's entries would be computed and
     * then dropped, which is precisely the "failures are diagnostics, not silence" rule (§ 4.5/G5)
     * inverted.</p>
     */
    private static DivergenceReporter lastDivergences = new DivergenceReporter();

    /** The divergences produced by the most recent {@link #generate(Path, Path)} call. */
    public static List<String> lastDivergences() {
        return lastDivergences.entries();
    }

    private static void reportDivergences(DivergenceReporter divergences) {
        lastDivergences = divergences;
        if (divergences.isEmpty()) {
            return;
        }
        // Printed, not thrown: a divergence is a report about what the generator did to committed
        // source, and the plan makes it reviewable rather than fatal. The uniform DEC-022 shape is
        // what a build step greps.
        System.out.println("hipster-entity generator divergences (" + divergences.size() + "):");
        System.out.println(divergences.render());
    }

    /**
     * The pass's divergence reporter, reachable from the recursive property collector so an addon
     * diagnostic can be recorded where it is detected rather than threaded through four call sites.
     * {@code null} outside a pass.
     */
    private static DivergenceReporter activeDivergences;

    /** Every compilation unit the current pass parsed, retained for lookups that need a non-interface. */
    private static final List<CompilationUnit> parsedCompilationUnits = new ArrayList<>();

    /**
     * One parsed source file, with the two facts a property reader needs from it
     * (plan.dsflash § 8.2/3.6).
     *
     * <p>The pass is split into "parse every file" and "read every interface" so the second half can
     * consult an index of the <em>whole</em> source set. Reading while walking cannot: a property name
     * may refer to a type declared in a file that has not been visited yet, which is exactly the
     * cross-package case the import table alone cannot resolve.</p>
     */
    private record ParsedUnit(String packageName, Map<String, String> importTable, CompilationUnit cu) {
    }

    /**
     * Every type the pass's own source declares, by simple name (plan.dsflash § 8.2/3.6).
     *
     * <p>This is the offline substitute for JavaParser's symbol solver: it does not answer "what does
     * this name mean in that file", it answers the weaker and still sufficient "does the source set
     * declare a type of this name, and where". A view in {@code b.hr} may hold an accessor inherited
     * from an addon in {@code a.hr}, and that addon's accessor may name one of its own package's types
     * without an import — {@code LocalThing thing();} where {@code LocalThing} is
     * {@code a.hr.LocalThing}. The import table has no entry for it (nothing was imported), yet the
     * generated {@code b.hr.X_} that writes {@code LocalThing.class} needs the import. Only the
     * package the accessor was declared in can disambiguate it, and that is what this index supplies.
     * </p>
     *
     * <p>Keys are simple names and values are fully-qualified names, nested types included
     * ({@code p.Outer.Inner} is indexed under {@code Inner}), sorted so a report is reproducible.</p>
     */
    private static Map<String, List<String>> declaredTypesBySimpleName(List<ParsedUnit> units) {
        Map<String, TreeSet<String>> byName = new TreeMap<>();
        for (ParsedUnit unit : units) {
            for (TypeDeclaration<?> type : unit.cu().findAll(TypeDeclaration.class)) {
                type.getFullyQualifiedName().ifPresent(fqn -> {
                    int dot = fqn.lastIndexOf('.');
                    String simpleName = dot < 0 ? fqn : fqn.substring(dot + 1);
                    if (!simpleName.isEmpty()) {
                        byName.computeIfAbsent(simpleName, __ -> new TreeSet<>()).add(fqn);
                    }
                });
            }
        }
        Map<String, List<String>> index = new TreeMap<>();
        byName.forEach((name, fqns) -> index.put(name, List.copyOf(fqns)));
        return index;
    }

    /**
     * The one declaration of {@code simpleName} that a file of {@code declaringPackage} can mean
     * without an import, or {@code null} when the name is not the declaring package's to claim.
     *
     * <p>Java's own resolution order leaves at most one candidate here: same-package types win over
     * imports and over {@code java.lang}. Two candidates can only mean two nested types of different
     * outer types in the same package, and then emitting either would be a guess — so the caller is
     * told about the ambiguity instead.</p>
     */
    private static List<String> declaringPackageCandidates(String simpleName, String declaringPackage,
                                                           Map<String, List<String>> declaredTypes) {
        if (declaringPackage == null || declaringPackage.isEmpty()) {
            return List.of();
        }
        String prefix = declaringPackage + ".";
        return declaredTypes.getOrDefault(simpleName, List.of()).stream()
                .filter(fqn -> fqn.startsWith(prefix))
                .toList();
    }

    public static void generate(Path sourceRoot, Path outputDir, Path javaOutputRoot, DivergenceReporter divergences)
            throws IOException {
        activeDivergences = divergences;
        parsedCompilationUnits.clear();
        try {
            generateInternal(sourceRoot, outputDir, javaOutputRoot, divergences);
        } finally {
            activeDivergences = null;
        }
    }

    private static void generateInternal(Path sourceRoot, Path outputDir, Path javaOutputRoot,
                                         DivergenceReporter divergences) throws IOException {
        // Before the first write, and before anything is parsed: generated source must never land in
        // a module's metadata root, whatever entry point asked for it.
        rejectJavaOutputUnderJcodebuddy(javaOutputRoot);
        Map<String, InterfaceInfo> interfaceMap = new HashMap<>();

        // Pass 1: parse every file. Reading an interface is deferred to pass 2 because a property's
        // type may be declared in a file this walk has not reached yet, and the resolution that needs
        // is against the whole source set (plan.dsflash § 8.2/3.6).
        List<ParsedUnit> units = new ArrayList<>();
        // Files JavaParser could not read cleanly. Collected rather than reported inline so the
        // report is emitted once per file, not once per marker iteration — and collected at all
        // because the silent version of this skip is the failure F-23 records: an unparseable file
        // contributes no interfaces and no declared types, so the pass produces a PARTIAL tree with
        // no error and the only symptom is an absent file in a diff.
        List<String> unparsedFiles = new ArrayList<>();
        Files.walk(sourceRoot)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(filePath -> {
                    try {
                        String source = Files.readString(filePath);
                        SourceReader.Read read = SourceReader.readText(source);
                        if (!read.readable()) {
                            // Fail safe: the file contributes nothing, and the report says which one.
                            unparsedFiles.add(sourceRoot.relativize(filePath).toString().replace('\\', '/'));
                            return;
                        }
                        CompilationUnit cu = read.unit();
                        parsedCompilationUnits.add(cu);

                        String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
                        // The declaring unit's import table, carried to the property reader because that
                        // is the only place a type name can be resolved correctly: an inherited accessor's
                        // declared type comes from its own supertype's source, so resolving a whole view's
                        // property list against the view's own imports would attribute the wrong imports
                        // to it (plan.dsflash § 8.2/3.6).
                        units.add(new ParsedUnit(packageName, importTableOf(cu), cu));

                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

        // The pass-wide type index. Only the declaring package can disambiguate an unimported simple
        // name, so the index is consulted with the accessor's own package, never the view's.
        Map<String, List<String>> declaredTypes = declaredTypesBySimpleName(units);

        // The pass's type-parameter index (follow-up plan § 2.1). Computed here, from the units just
        // parsed, so a bare name that is a type parameter is recognized without a hand-maintained
        // list: `ID` was special-cased by name, and a list of one is the shape that produced F-43.
        typeParameterNames = TypeLiterals.typeParameterNames(
                units.stream().map(ParsedUnit::cu).toList());

        // Pass 2: read every interface the pass parsed.
        for (ParsedUnit unit : units) {
            String packageName = unit.packageName();
            Map<String, String> importTable = unit.importTable();
            for (ClassOrInterfaceDeclaration decl : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!decl.isInterface()) {
                    continue;
                }
                // Discovery never descends into a nested type of a view file (§ 4.5/G8
                // rule 2): the generator's own emitted nested Write/tracking surfaces
                // must not become a discovery input on the next pass.
                if (isNested(decl)) {
                    continue;
                }

                InterfaceInfo info = new InterfaceInfo(
                        packageName,
                        decl.getNameAsString(),
                        decl.getExtendedTypes().stream()
                                .map(ClassOrInterfaceType::asString)
                                .collect(Collectors.toList()),
                        decl.getMethods().stream()
                                .filter(EntityMetadataGenerator::isFieldAccessor)
                                .map(method -> parseProperty(method, packageName, importTable, declaredTypes))
                                .collect(Collectors.toList()),
                        parseViewAnnotation(decl),
                        parseEntityBaseIdType(decl),
                        decl.getBegin().map(pos -> pos.line).orElse(-1),
                        nestedRecordComponents(decl),
                        decl.isPublic(),
                        decl
                );
                interfaceMap.put(getQualifiedName(packageName, info.name()), info);
            }
        }

        // Sorted, so two runs over the same tree visit the markers in the same order. A HashMap here
        // made the pass order depend on hash iteration: the SAME view can be claimed by more than one
        // marker (the discovery predicate's `@View` half admits an annotated view to every marker's
        // list), and the pass-wide dedup below means the first claim wins — so an unstable order
        // produced byte-different output on different runs. That is what made the example's
        // `PersonSummary_.id` alternate between `Long.class` (the real marker's type) and
        // `Object.class` (the doc-sample `Auditable<ID>` marker's type parameter) from run to run.
        Map<String, List<InterfaceInfo>> packageToMarkers = new java.util.TreeMap<>();

        // A marker is an interface that reaches `EntityBase` WITHOUT passing through another marker.
        // That is the precise reading of "the package's marker" (§ 4.5/G8 rule 0), and it is what
        // makes the example's `PaymentMethod extends PaymentMethodEntity extends EntityBase<Long>` a
        // marker root rather than a view. Testing only the DIRECT `extends` clause classified
        // `PaymentMethod` as a view, so the generator emitted a `PaymentMethod_` carrying none of the
        // discriminator wiring the hand-written root provides, and the polymorphic family lost its
        // hand-off (§ 9/4.9). Testing mere reachability would be too broad the other way: it would
        // make every entity view a marker and hide all of them from discovery.
        List<InterfaceInfo> markers = interfaceMap.values().stream()
                .filter(info -> isMarkerEntityOrRoot(info, interfaceMap))
                .toList();

        for (InterfaceInfo info : markers) {
            boolean derivedFromAnotherMarker = markers.stream()
                    .anyMatch(other -> other != info && isDerivedFrom(info, other, interfaceMap));
            if (!derivedFromAnotherMarker) {
                packageToMarkers.computeIfAbsent(info.packageName(), __ -> new ArrayList<>()).add(info);
            }
        }

        // Every view this pass emitted, keyed by simple name and by qualified name. The mapper phase
        // runs after the marker loop because a mapping is a relationship BETWEEN views, which may live
        // under different markers; the registry is also what makes a mapper reference only
        // materializations that actually exist — a view filtered out by the `packages` knob is absent
        // here, so a request naming it is reported rather than half-generated.
        Map<String, ViewMapperGenerator.ViewRef> emittedViews = new LinkedHashMap<>();

        // § 11/6.5's precondition: which of this project's views are generated at a tracking level.
        // Collected once, before the marker loop, because a nested tracked field can live in a
        // different package (and under a different marker) than the view that holds it.
        Map<String, TrackableType> trackableTypes = collectTrackableTypes(interfaceMap);
        // Views the author annotated but did not give a tracking level. Only these are worth telling
        // about: a nested view the author never asked to track is the normal case.
        Set<String> nonTrackableViewNames = interfaceMap.values().stream()
                .filter(info -> info.view() != null && !trackableTypes.containsKey(info.name()))
                .map(InterfaceInfo::name)
                .collect(Collectors.toSet());

        // One emission per view for the WHOLE pass, not per marker. The discovery predicate's `@View`
        // half admits an annotated view to every marker's list — `PersonSummary` carries `@View`, so it
        // is claimed both by `person.entity.Person` (its real marker, id type `Long`) and by
        // `example.Auditable` (id type the type parameter `ID`). With the set scoped to the marker loop
        // the same file was emitted twice, once per marker, from two different property lists, and the
        // last write won. The comment further down has always said "one emission per view, even when
        // two markers apply"; this is that rule actually implemented. Together with the sorted marker
        // order above, the winner is now stable rather than hash-order dependent.
        Set<String> alreadyFound = new HashSet<>();

        for (List<InterfaceInfo> roots : packageToMarkers.values()) {
            if (System.getProperty("hipster.traceMarkers") != null) {
                System.out.println("TM roots=" + roots.stream()
                        .map(r -> r.packageName() + "." + r.name()).toList());
            }
            for (InterfaceInfo marker : roots) {
                String entityName = marker.name().endsWith("Entity") ? marker.name().substring(0, marker.name().length() - 6) : marker.name();

                // The view predicate is marker-derivation **or** a @View annotation (plan.dsflash
                // § 4.5/G8 rule 0). The @View half is not decoration: `PersonCreateForm` carries a
                // bare @View and extends nothing marker-derived, so a marker-only predicate
                // silently excludes it and the `PersonCreateForm_` the plan expects is never
                // emitted.
                //
                // The @View half is additionally restricted to a view that actually reaches an
                // entity root: an `@View`-annotated interface with no path to `EntityBase` has no
                // ordinals, no identity and no `ViewMeta` type arguments, so the array-backed
                // creator the metadata enum would otherwise emit cannot typecheck. The live cases
                // are the example's documentation samples (`person.iface.Person`,
                // `person.record.Person`), which § 4.3/X3 keeps OUT of the generator's scan
                // precisely because they are tutorial narrative. Requiring an entity root makes the
                // predicate right on its own merits, so the doc samples stay inert even when the
                // `packages` filter is not applied.
                // Predicate: marker-derivation **OR** a @View annotation, exactly as § 4.5/G8 rule 0
                // states. The two halves must be composed with OR and neither may require the
                // other: `PersonCreateForm` is a live view that extends NOTHING, so requiring
                // marker-derivation of an annotated view would drop precisely the view the @View
                // seed exists to find. Symmetrically, requiring an entity root of an annotated view
                // would drop it too, because it has no supertypes at all.
                //
                // What the entity-root requirement is actually for is the *doc samples*, and they are
                // already excluded twice over: `person.iface.Person` is package-private (and cannot
                // carry a public generated type), and X3's `packages` knob keeps both sample
                // packages out of generation. So it is not expressed here — an earlier revision did,
                // and it silently removed `PersonCreateForm_` from the census.
                // `alreadyFound` is hoisted above the marker loop on purpose: see the note there. A set
                // scoped to this loop made the same view emit once per marker that claimed it.
                // Every entity marker is excluded, not just the one being iterated. A marker derives
                // from `EntityBase` itself, so from another marker's point of view it looks like a
                // derived view — the example's `PaymentMethod` (a marker in its own package) showed
                // up in the view lists of both `example.Auditable` and `person.entity.Person`, which
                // generated a `PaymentMethod_` with none of its hand-written discriminator wiring.
                // § 4.5/G8 rule 0 says the marker is not a view; that is a property of the type, not
                // of the loop.
                Set<String> markerNames = markers.stream()
                        .map(m -> getQualifiedName(m.packageName(), m.name()))
                        .collect(Collectors.toSet());
                List<ViewMeta> views = interfaceMap.values().stream()
                        .filter(i -> !markerNames.contains(getQualifiedName(i.packageName(), i.name())))
                        .filter(i -> !isFrameworkSurface(i))
                        // A package-private interface cannot carry a public generated type, and the
                        // emitted metadata enum is public.
                        .filter(InterfaceInfo::isPublic)
                        // The `@View` half exists because `PersonCreateForm` carries a bare @View and
                        // extends nothing marker-derived, so a marker-only predicate would drop
                        // precisely the view the seed is for. Taken literally it also admitted a view
                        // that *is* marker-derived to EVERY marker's list — `PersonSummary` derives
                        // from `person.entity.Person`, so it was also claimed by `example.Auditable`,
                        // and under that marker its inherited `id` resolved to `Auditable`'s type
                        // parameter instead of `Long`. Which one won depended on marker order.
                        //
                        // The refinement: an annotated view is a fallback candidate only when NO marker
                        // claims it by derivation. A view with a real marker always belongs to that
                        // marker. A view with no marker at all is still generated exactly once — the
                        // pass-wide dedup takes the first marker in sorted package order, and such a
                        // view has no entity root, so no proxy and no id contract is involved.
                        .filter(i -> isDerivedFrom(i, marker, interfaceMap)
                                || (i.view() != null && markers.stream()
                                        .noneMatch(other -> isDerivedFrom(i, other, interfaceMap))))
                        .filter(i -> {
                            // One emission per view for the whole pass (§ 4.5/G8): see the hoisted
                            // `alreadyFound`. A set scoped to this loop emitted the same file once per
                            // claiming marker, from different property lists, and the last write won.
                            return alreadyFound.add(getQualifiedName(i.packageName(), i.name()));
                        })
                        .map(i -> new ViewMeta(i.name(), i.extendsTypes(),
                                i.view() == null ? GenLevel.META : i.view().gen(),
                                i.view() == null ? "" : i.view().discriminatorField(),
                                i.view() == null ? List.of() : i.view().addons(),
                                i.properties(), i.lineNumber()))
                        .collect(Collectors.toList());

                List<EntityFieldMeta> allFields = collectEntityFields(views, marker, interfaceMap);
                EntityMeta entityMeta = new EntityMeta(entityName, marker.packageName(), marker.name(), marker.entityBaseIdType(), views, allFields);
                String json = toJson(entityMeta);

                Files.createDirectories(outputDir);
                Path outFile = outputDir.resolve(entityName + ".metadata.json");
                Files.writeString(outFile, json);

                for (ViewMeta view : views) {
                    InterfaceInfo viewInfo = findViewInfo(interfaceMap, marker.packageName(), view.name());
                    List<Property> fullProperties = collectViewProperties(viewInfo, marker, interfaceMap);
                    // The generated file goes into the package the VIEW actually lives in, not the
                    // marker's: a view may be declared in a different package from its entity
                    // marker (the example's `person.entity.PersonAuditable` is marker-derived from
                    // `example.Auditable`), and the emitted META references the view as a class
                    // literal. Using the marker's package produced `example.PersonAuditable_`
                    // referring to a non-existent `example.PersonAuditable`.
                    String viewPackage = viewInfo != null ? viewInfo.packageName() : marker.packageName();
                    // X3's `packages` knob filters GENERATION, not indexing: a view outside the
                    // filter emits nothing, but every source was still indexed, so its supertypes
                    // and addons resolved.
                    if (!isGenerationTarget(viewPackage)) {
                        continue;
                    }

                    // The materialization is chosen from the resolved GenLevel, never hardcoded
                    // (§ 8.5/3.13). Level order: META < RECORD < WRITABLE < BUILDER <
                    // BUILDER_TRACKED < BUILDER_ALL, so each step is a superset of the one below.
                    boolean wantsRecord = view.gen() != GenLevel.META && view.gen() != GenLevel.WRITABLE;
                    boolean wantsTrackingBuilder =
                            view.gen() == GenLevel.BUILDER_TRACKED || view.gen() == GenLevel.BUILDER_ALL;

                    // R1: a field's ordinal is defined by the field enum's constant order, not by the
                    // interface's declaration order, and the two diverge as soon as a constant is
                    // retired. Everything that maps a field to an ordinal — the builders' mutable
                    // fields and setters, the record's components, the JDBC binder's ORDINALS — is
                    // driven by this list. The field ENUM itself keeps the declaration-ordered
                    // accessors, because the ledger planner is the thing that decides where the
                    // tombstones go.
                    // § 12.3/7.9–7.12: the constraints are filtered ONCE, before anything is emitted, so
                    // a constraint that cannot be honoured is reported exactly once and appears in no
                    // artifact. Filtering the declaration-ordered list first and deriving the ledger
                    // order from it afterwards keeps the record, the builders and the validator from
                    // disagreeing about which constraints exist.
                    fullProperties = ValidationGenerator.reportAndFilter(view.name(), fullProperties,
                            divergences);
                    List<Property> ordinalProperties = ledgerOrderedProperties(
                            javaOutputRoot, viewPackage, view.name(), fullProperties);

                    // § 8.4/3.10: if the view already declares a matching nested record, do NOT emit
                    // a second one — target it from create() instead.
                    List<String> nestedComponents = nestedRecordComponents(viewInfo, interfaceMap);
                    boolean nestedRecordUsable = ViewRecordGenerator.hasNestedRecord(
                            nestedComponents, ordinalProperties.stream().map(Property::name).toList());
                    if (nestedRecordUsable) {
                        divergences.report("nested_record_reused",
                                viewPackage + "." + view.name(),
                                "the view declares a nested record whose components match the field order",
                                nestedComponents.toString(),
                                "no top-level record is emitted; create() targets the nested one",
                                "keep the nested record in sync with the field enum");
                    }

                    // The array-backed read proxy needs the view to be an `EntityBase`, because
                    // `createRead`'s type parameter is bound by `EntityBase<ID>`. A view discovered
                    // by the @View seed need not extend anything at all — `PersonCreateForm`, the
                    // live case, extends nothing — so for such a view the creator must be a concrete
                    // record rather than a proxy. That is also the honest reading of the level
                    // ladder: a view with no entity root has no array contract to be proxied over.
                    boolean hasEntityRoot = reachesEntityRoot(viewInfo, interfaceMap)
                            || isDerivedFromMarker(viewInfo, marker, interfaceMap);

                    String creatorBody;
                    if (wantsRecord || !hasEntityRoot) {
                        if (nestedRecordUsable) {
                            creatorBody = nestedRecordCreatorBody(view, ordinalProperties);
                        } else {
                            creatorBody = ViewRecordGenerator
                                    .generate(javaOutputRoot, viewPackage, view, ordinalProperties)
                                    .creatorBody();
                        }
                    } else {
                        creatorBody = proxyCreatorBody(view);
                    }

                    generateViewPropertyEnum(javaOutputRoot, viewPackage, view, fullProperties,
                            creatorBody, divergences, viewInfo, interfaceMap);

                    if (generateAdapters) {
                        // DRAFT / EXPLORATION, and opt-in only: reached exclusively through
                        // `--adapters`. Generated adapters are ordinary committed Java in the view's
                        // own package (G5) and use java.sql only, so no JDBC driver or connection-pool
                        // dependency enters any library POM. The example project does not enable this.
                        ViewAdapterGenerator.generate(javaOutputRoot, viewPackage, view, ordinalProperties);
                    }
                    if (wantsTrackingBuilder) {
                        ViewTrackingBuilderGenerator.generate(javaOutputRoot, viewPackage, view,
                                ordinalProperties, trackableTypes, nonTrackableViewNames, divergences);
                    }
                    // BUILDER and everything above it emit the plain builder as well. The ladder is
                    // cumulative (each level is a superset of the one below), so a level that
                    // includes tracking must not DROP the untracked builder a lower level provides —
                    // that is what the cumulative-ladder assertion in AllLevelsCompileTest checks,
                    // and BUILDER_ALL is documented as "both builders in one pass" (§ 8.6/3.17).
                    if (view.gen() == GenLevel.BUILDER
                            || view.gen() == GenLevel.BUILDER_TRACKED
                            || view.gen() == GenLevel.BUILDER_ALL) {
                        ViewBuilderGenerator.generate(javaOutputRoot, viewPackage, view, ordinalProperties,
                                nestedRecordUsable, divergences);
                    }
                    // § 8.2/G2 + § 8.6/3.17a: the two builder entry points are `default` methods on
                    // the VIEW interface — the one file the developer owns. The emitter recognises an
                    // existing method by shape and only writes when one is actually missing, so a
                    // hand-written pair (the example's) survives regeneration byte for byte. The view
                    // file is looked up under `sourceRoot`, not `javaOutputRoot`, because the view's
                    // interface is indexed input, not generated output.
                    ViewInterfaceGenerator.generate(sourceRoot, viewPackage, view.name(),
                            ViewInterfaceGenerator.entryPointsFor(view.gen(), view.name()), divergences);

                    // § 12.3/7.11: the imperative half of the validation contract. Emitted only when
                    // there is something mechanical to check, so an unconstrained view gains no file.
                    ValidationGenerator.generate(javaOutputRoot, viewPackage, view, ordinalProperties, divergences);

                    // The mapper phase's catalogue entry (§ 12.2/7.5). `recordConstruction` is the only
                    // thing a mapper needs to know about the target's materialization, and it is known
                    // exactly here: a level that emits neither a nested nor a top-level record leaves it
                    // null, and the mapper then goes through the view's own META.create.
                    String recordConstruction = null;
                    if (wantsRecord || !hasEntityRoot) {
                        recordConstruction = nestedRecordUsable
                                ? view.name() + ".Record"
                                : view.name() + "Record";
                    }
                    ViewMapperGenerator.ViewRef reference = new ViewMapperGenerator.ViewRef(
                            viewPackage == null || viewPackage.isBlank()
                                    ? view.name()
                                    : viewPackage + "." + view.name(),
                            viewPackage, view.name(), ordinalProperties, recordConstruction);
                    emittedViews.put(reference.qualifiedName(), reference);
                    emittedViews.putIfAbsent(view.name(), reference);
                }
            }
        }

        // Reported once per file, after the marker loop, because the scan happens once. A file the
        // generator could not read is a fact about the pass, not about one marker.
        for (String unparsed : unparsedFiles) {
            divergences.report("source_not_parsed", unparsed,
                    "the source file could not be parsed, so it contributed no interfaces and no "
                            + "declared types to this pass",
                    "unparseable source", "a readable compilation unit",
                    "fix the syntax: nothing is generated for this file, and its types are invisible "
                            + "to every other file's resolution");
        }

        generateRequestedMappers(javaOutputRoot, emittedViews, divergences);
    }

    /**
     * Emits one mapper per {@code --mapper} request (plan.dsflash § 12.2/7.5–7.8).
     *
     * <p>Resolution is by simple name first, then by qualified name, so a request can be written the
     * way a developer thinks of the pair ({@code PersonSummary:PersonDto}) without the generator
     * guessing which of two same-named views in different packages was meant: an ambiguous simple name
     * matches the first registration and the qualified form is available when it matters.</p>
     */
    private static void generateRequestedMappers(Path javaOutputRoot,
                                                 Map<String, ViewMapperGenerator.ViewRef> emittedViews,
                                                 DivergenceReporter divergences) throws IOException {
        for (String request : mapperRequests) {
            String[] parts = request.split(":");
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                divergences.report("mapper_request_malformed", request,
                        "the request is not <sourceView>:<targetView>[:<ClassName>]",
                        request, "<Source>:<Target>[:<ClassName>]",
                        "rewrite the --mapper flag");
                continue;
            }
            String sourceName = parts[0].trim();
            String targetName = parts[1].trim();
            ViewMapperGenerator.ViewRef source = emittedViews.get(sourceName);
            ViewMapperGenerator.ViewRef target = emittedViews.get(targetName);
            if (source == null) {
                divergences.report("mapper_view_not_found", sourceName,
                        "no view of that name was emitted in this pass",
                        sourceName, "an emitted view",
                        "check the name and the --packages filter");
                continue;
            }
            if (target == null) {
                divergences.report("mapper_view_not_found", targetName,
                        "no view of that name was emitted in this pass",
                        targetName, "an emitted view",
                        "check the name and the --packages filter");
                continue;
            }
            String className = parts.length > 2 && !parts[2].isBlank()
                    ? parts[2].trim()
                    : ViewMapperGenerator.defaultClassName(source.simpleName(), target.simpleName());
            String methodName = ViewMapperGenerator.defaultMethodName(target.simpleName());
            ViewMapperGenerator.Result result = ViewMapperGenerator.generate(
                    javaOutputRoot, className, methodName, source, target, divergences);
            if (javaOutputRoot != null) {
                System.out.println("Generated mapper " + result.className() + "." + result.methodName()
                        + " -> " + result.file());
            }
        }
    }

    /**
     * The view's fields in <strong>ledger order</strong> — the order the field enum's constants
     * occupy — with a {@code RETIRED} placeholder for every tombstoned constant (plan.dsflash § 4.6/R1,
     * § 4.7/DR-2).
     *
     * <p>This exists because a field's ordinal is defined by the enum and not by the interface, and
     * the two stop agreeing the moment R1 retires a constant. Every emitter computed the ordinal from
     * the index of the field in the interface's resolved list, while the enum's {@code create()} and
     * the runtime {@code values[field.ordinal()]} use the ledger. Those agree exactly while no
     * tombstone precedes a live field; retire a middle accessor and the enum says "email is ordinal 3"
     * while the binder says "email is ordinal 2" — so the binder reads the retired slot and writes it
     * into the email column. Nothing about the generated code looks wrong, and no compiler catches it.</p>
     *
     * <p>Making the ordinal space explicit in the one place that can see both the interface and the
     * committed enum is the fix. A retired constant becomes a placeholder property of kind
     * {@code RETIRED}, which {@link ViewAdapterGenerator#isWritable} already excludes: the tombstone
     * keeps its slot in {@code get(int)}, in the record's components and in the positional array, and
     * gets no setter, no read accessor and no column — exactly the R1.4 contract.</p>
     *
     * <p>When the enum is missing, unreadable, or carries no {@code entityFieldEnum} marker, the
     * declaration order <em>is</em> the ledger — a marker-less enum is bootstrapped from the resolved
     * fields (§ 4.5/G7) — so the list is returned unchanged.</p>
     */
    private static List<Property> ledgerOrderedProperties(Path javaOutputRoot, String viewPackage,
                                                          String viewName, List<Property> accessors) {
        Path packageDir = viewPackage == null || viewPackage.isBlank()
                ? javaOutputRoot
                : javaOutputRoot.resolve(viewPackage.replace('.', '/'));
        Path enumFile = packageDir.resolve(viewName + "_.java");
        if (!Files.exists(enumFile)) {
            return accessors;
        }
        try {
            // The shared read (SourceReader), for the reason F-34 records: JavaParser returns a partial
            // unit for broken source, and a partial unit whose constant list failed to parse would look
            // like an empty ledger and silently renumber everything. An unreadable enum therefore keeps
            // the declaration order and the ledger path reports it separately.
            SourceReader.Read read = SourceReader.read(enumFile);
            if (!read.readable()) {
                return accessors;
            }
            CompilationUnit cu = read.unit();
            if (!hr.hrg.hipster.entity.tooling.validation.EnumConstantOrderChecker.readHeader(cu).marked()) {
                return accessors;
            }
            List<String> existing = new ArrayList<>();
            for (com.github.javaparser.ast.body.EnumDeclaration declaration
                    : cu.findAll(com.github.javaparser.ast.body.EnumDeclaration.class)) {
                for (com.github.javaparser.ast.body.EnumConstantDeclaration constant
                        : declaration.getEntries()) {
                    existing.add(constant.getNameAsString());
                }
            }
            if (existing.isEmpty()) {
                return accessors;
            }

            Map<String, Property> byName = new LinkedHashMap<>();
            for (Property property : accessors) {
                byName.put(property.name(), property);
            }
            List<Property> ordered = new ArrayList<>();
            for (String name : existing) {
                Property property = byName.remove(name);
                ordered.add(property != null ? property : retiredPlaceholder(name));
            }
            // A genuinely new field is appended, exactly as the ledger planner appends its constant.
            ordered.addAll(byName.values());
            return ordered;
        } catch (IOException | RuntimeException ignored) {
            // No ordering information is better than a wrong one; the declaration order is what every
            // pass used before this method existed, and it is correct whenever no tombstone is present.
            return accessors;
        }
    }

    /** The placeholder that preserves a retired constant's slot in every positional artifact. */
    private static Property retiredPlaceholder(String name) {
        return new Property(name, "java.lang.Object", "RETIRED", null, null, null, -1, List.of(), List.of());
    }

    /**
     * The views whose generated tracking builder a deep walk can reach (plan.dsflash § 11/6.5).
     *
     * <p>Keyed by simple name, because that is how a field's declared type refers to them. The
     * criterion is the <strong>requested generation level</strong>: {@code BUILDER_TRACKED} or
     * {@code BUILDER_ALL} is what makes the pass emit a {@code <View>BuilderTracking} implementing
     * {@code ViewChangeTracking}, and a nested value has to be such an implementation for the walk to
     * find anything.</p>
     *
     * <p>Two things this deliberately is not:</p>
     * <ul>
     *   <li><strong>Not "the interface declares the contract."</strong> G8 rule 1 excludes any
     *       interface extending a framework surface ({@code ViewReader}, {@code ViewWriter},
     *       {@code ViewChangeTracking}) from discovery, so a rule phrased that way could never match a
     *       generated view — only a hand-written fixture. See {@link TrackableType}.</li>
     *   <li><strong>Not the resolved level.</strong> {@code DEFAULT} never resolves to a tracking
     *       level ({@code GenLevelResolver} stops at {@code BUILDER}), so reading the annotation's own
     *       level before the marker loop is exactly equivalent and available without resolving
     *       anything.</li>
     * </ul>
     *
     * <p>Nothing is reported for a nested view that is simply not at a tracking level — that is the
     * normal case and warning about it would drown the report. The classification reports the one
     * case where the author asked for tracking on the parent and cannot get it for a field.</p>
     */
    private static Map<String, TrackableType> collectTrackableTypes(Map<String, InterfaceInfo> interfaceMap) {
        Map<String, TrackableType> trackable = new LinkedHashMap<>();
        for (InterfaceInfo info : interfaceMap.values()) {
            if (info.view() == null) {
                continue;
            }
            GenLevel requested = info.view().gen();
            if (requested != GenLevel.BUILDER_TRACKED && requested != GenLevel.BUILDER_ALL) {
                continue;
            }
            String packageName = info.packageName() == null || info.packageName().isBlank()
                    ? ""
                    : info.packageName() + ".";
            trackable.put(info.name(), new TrackableType(
                    info.name(),
                    packageName + info.name(),
                    packageName + info.name() + "_",
                    declaresIdentifiable(info, interfaceMap, new HashSet<>())));
        }
        return trackable;
    }

    /** Whether the interface extends {@code Identifiable}, directly or through a supertype. */
    private static boolean declaresIdentifiable(InterfaceInfo info, Map<String, InterfaceInfo> interfaceMap,
                                                Set<String> visited) {
        if (info == null || !visited.add(getQualifiedName(info.packageName(), info.name()))) {
            return false;
        }
        for (String ext : info.extendsTypes()) {
            String simple = simpleNameOf(ext.replaceAll("<.*>$", "").trim());
            if ("Identifiable".equals(simple)) {
                return true;
            }
            InterfaceInfo parent = findViewInfo(interfaceMap, info.packageName(), simple);
            if (parent != null && declaresIdentifiable(parent, interfaceMap, visited)) {
                return true;
            }
        }
        return false;
    }

    private static Property parseProperty(MethodDeclaration method, String declaringPackage,
                                         Map<String, String> importTable,
                                         Map<String, List<String>> declaredTypes) {
        String name = method.getNameAsString();
        String type = method.getType().asString();
        String fieldKind = null;
        String column = null;
        String relation = null;
        String expression = null;

        Optional<AnnotationExpr> fsOpt = method.getAnnotationByName("FieldSource");
        if (fsOpt.isPresent()) {
            AnnotationExpr fs = fsOpt.get();
            if (fs.isSingleMemberAnnotationExpr()) {
                fieldKind = extractEnumValue(fs.asSingleMemberAnnotationExpr().getMemberValue().toString());
            } else if (fs.isNormalAnnotationExpr()) {
                for (MemberValuePair pair : fs.asNormalAnnotationExpr().getPairs()) {
                    switch (pair.getName().asString()) {
                        case "kind" -> fieldKind = extractEnumValue(pair.getValue().toString());
                        case "column" -> column = stripQuotes(pair.getValue().toString());
                        case "relation" -> relation = stripQuotes(pair.getValue().toString());
                        case "expression" -> expression = stripQuotes(pair.getValue().toString());
                    }
                }
            }
        }
        int lineNumber = method.getBegin().map(pos -> pos.line).orElse(-1);
        return new Property(name, type, fieldKind, column, relation, expression, lineNumber,
                ValidationGenerator.constraintsOn(method),
                typeImportsFor(name, type, declaringPackage, importTable, declaredTypes));
    }

    /**
     * The imports a declared type needs in a generated file (plan.dsflash § 8.2/3.6).
     *
     * <p>The declared type is emitted as the author wrote it, which is what keeps generated code
     * readable — and which is exactly why the import has to travel with it. {@code import a.hr.Node;}
     * plus {@code Node head()} reaches the emitter as the bare {@code Node}, and a generated file in
     * another package that writes {@code private Node head;} without the import does not compile. The
     * generated builders for a view in {@code b.hr} holding a view type from {@code a.hr} were
     * precisely that: uncompilable, with no diagnostic, because nothing in the pass looked at the
     * declaring unit's import table.</p>
     *
     * <p><strong>Three sources, in Java's own resolution order.</strong> A simple name is resolved
     * from the declaring unit's import table first; failing that, from the pass's own declared types
     * <em>of the declaring package</em>, which is what covers an accessor inherited from an addon in
     * another package that names one of its own package's types without an import (§ 4.5/G6) and a
     * nested type such as {@code Outer.Inner}; failing both, nothing is emitted, because the name is
     * then either a {@code java.lang} type, a type variable, or a type from outside the source set —
     * all of which the compile gate reports better than a guess would. A name that the declaring
     * package declares twice is the one genuinely ambiguous case, and it is reported rather than
     * guessed (§ 8.7/3.20).</p>
     *
     * <p>On-demand ({@code import a.b.*;}) imports are deliberately not resolved: an offline reader
     * cannot tell which of several packages a name came from, and the compiler will say so.</p>
     */
    private static List<String> typeImportsFor(String propertyName, String declaredType, String declaringPackage,
                                               Map<String, String> importTable,
                                               Map<String, List<String>> declaredTypes) {
        List<String> imports = new ArrayList<>();
        if (declaredType == null) {
            return imports;
        }
        for (String simpleName : simpleTypeNames(declaredType)) {
            String resolved = importTable == null ? null : importTable.get(simpleName);
            if (resolved != null) {
                boolean samePackage = declaringPackage != null
                        && resolved.equals(declaringPackage + "." + simpleName);
                if (!samePackage && !imports.contains(resolved)) {
                    imports.add(resolved);
                }
                continue;
            }
            // The emitters add the JDK imports themselves, and adding a same-named source type beside
            // one would be a duplicate single-type import — a compile error, not a fix.
            if (JdkImportSupport.isKnownJdkType(simpleName)) {
                continue;
            }
            List<String> candidates = declaringPackageCandidates(simpleName, declaringPackage, declaredTypes);
            if (candidates.size() == 1) {
                imports.add(candidates.get(0));
            } else if (candidates.size() > 1 && activeDivergences != null) {
                activeDivergences.report("type_ambiguous", declaringPackage + "." + propertyName,
                        "the declared type `" + declaredType + "` names " + simpleName
                                + " without an import and the declaring package declares more than one type of that name",
                        String.join(", ", candidates),
                        "a fully-qualified type name in the view source",
                        "qualify the type, or rename one of the colliding types");
            }
        }
        return imports;
    }

    /** Every capitalized identifier in a declared type: {@code Map<String, List<Long>>} yields three. */
    private static List<String> simpleTypeNames(String declaredType) {
        List<String> names = new ArrayList<>();
        for (String token : declaredType.split("[<>,\\[\\]\\s?&]+")) {
            String name = token.trim();
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                name = name.substring(dot + 1);
            }
            // A qualified spelling already carries its package, so it needs no import.
            if (!name.isEmpty() && Character.isUpperCase(name.charAt(0)) && !token.contains(".")) {
                names.add(name);
            }
        }
        return names;
    }

    /** The simple-name to fully-qualified-name map of one compilation unit's non-static imports. */
    private static Map<String, String> importTableOf(CompilationUnit cu) {
        Map<String, String> table = new LinkedHashMap<>();
        for (var importDeclaration : cu.getImports()) {
            if (importDeclaration.isStatic() || importDeclaration.isAsterisk()) {
                continue;
            }
            String qualified = importDeclaration.getNameAsString();
            int dot = qualified.lastIndexOf('.');
            if (dot > 0) {
                table.put(qualified.substring(dot + 1), qualified);
            }
        }
        return table;
    }

    private static String extractEnumValue(String value) {
        // Handle FieldKind.DERIVED or just DERIVED
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    private static String stripQuotes(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.isEmpty() ? null : v;
    }

    private static List<EntityFieldMeta> collectEntityFields(List<ViewMeta> views, InterfaceInfo marker, Map<String, InterfaceInfo> interfaceMap) {
        // Merge all fields from all views into entity-wide map
        LinkedHashMap<String, EntityFieldMeta> fieldMap = new LinkedHashMap<>();

        // id field is always first
        String idType = marker != null && marker.entityBaseIdType() != null ? toFullTypeName(marker.entityBaseIdType()) : "java.lang.Object";
        List<String> idViews = views.stream().map(ViewMeta::name).collect(Collectors.toList());
        EntityFieldMeta idField = new EntityFieldMeta("id", idType, "COLUMN", null, null, null, -1, idViews);
        for (String vn : idViews) idField.typeByView.put(vn, idType);
        fieldMap.put("id", idField);

        for (ViewMeta view : views) {
            InterfaceInfo viewInfo = interfaceMap.get(getQualifiedName(marker.packageName(), view.name()));
            List<Property> fullProps = collectViewProperties(viewInfo, marker, interfaceMap);
            for (Property prop : fullProps) {
                String propKind = prop.fieldKind() != null ? prop.fieldKind() : "COLUMN";
                if (fieldMap.containsKey(prop.name())) {
                    EntityFieldMeta existing = fieldMap.get(prop.name());
                    if (!existing.views.contains(view.name())) {
                        existing.views.add(view.name());
                    }
                    existing.typeByView.put(view.name(), prop.type());
                    // Non-derived field takes priority over derived for primary type
                    if ("DERIVED".equals(existing.fieldKind) && !"DERIVED".equals(propKind)) {
                        existing.type = prop.type();
                        existing.fieldKind = propKind;
                        existing.column = prop.column();
                        existing.relation = prop.relation();
                        existing.expression = prop.expression();
                    }
                } else {
                    List<String> viewList = new ArrayList<>();
                    viewList.add(view.name());
                    EntityFieldMeta fm = new EntityFieldMeta(prop.name(), prop.type(), propKind, prop.column(), prop.relation(), prop.expression(), prop.lineNumber(), viewList);
                    fm.typeByView.put(view.name(), prop.type());
                    fieldMap.put(prop.name(), fm);
                }
            }
        }

        return new ArrayList<>(fieldMap.values());
    }

    /**
     * Reads {@code @View} through the shared, shape-blind {@link ViewAnnotationReader}
     * (plan.dsflash § 8.1/3.2, § 4.5/G9) — never by casting to a {@code NormalAnnotationExpr},
     * because the bare {@code @View} marker form is live on {@code PersonDto} and
     * {@code PersonUpdateForm} and a shape-restricted parser drops both views and their enums.
     *
     * <p>{@code DEFAULT} is resolved by {@link GenLevelResolver} (the single owner, § 4.7/DR-3);
     * this method supplies the field names rule 1 needs.</p>
     */
    private static ViewAttributes parseViewAnnotation(ClassOrInterfaceDeclaration decl) {
        Optional<AnnotationExpr> viewOpt = decl.getAnnotationByName("View");
        if (viewOpt.isEmpty()) {
            return null;
        }
        ViewAnnotationReader.Parsed parsed = ViewAnnotationReader.parse(viewOpt.get());
        ViewAttributes attributes = parsed.attributes();

        List<String> fieldNames = decl.getMethods().stream()
                .filter(EntityMetadataGenerator::isFieldAccessor)
                .map(MethodDeclaration::getNameAsString)
                .collect(Collectors.toList());

        GenLevelResolver.Resolved resolved = GenLevelResolver.resolve(attributes.gen(), decl, fieldNames);
        return new ViewAttributes(resolved.level(), attributes.discriminatorField(), attributes.addons());
    }

    private static String parseEntityBaseIdType(ClassOrInterfaceDeclaration decl) {
        for (ClassOrInterfaceType ext : decl.getExtendedTypes()) {
            if ("EntityBase".equals(ext.getNameAsString())) {
                if (ext.getTypeArguments().isPresent()) {
                    return ext.getTypeArguments().get().stream().findFirst().map(Object::toString).orElse(null);
                }
            }
        }
        return null;
    }

    private static String getQualifiedName(String pkg, String name) {
        return (pkg.isEmpty() ? "" : pkg + ".") + name;
    }

    /** Reads a {@code gen} value back from the metadata JSON, defaulting to META. */
    private static GenLevel parseGenLevel(String text) {
        if (text != null) {
            for (GenLevel level : GenLevel.values()) {
                if (level.name().equals(text)) {
                    return level;
                }
            }
        }
        return GenLevel.META;
    }

    /**
     * Whether an interface is an entity-marker <strong>root</strong>.
     *
     * <p>Two ways, and they are the same idea applied to different depths:</p>
     * <ul>
     *   <li>it declares {@code EntityBase<Id>} among its own supertypes (the common case — the
     *       example's {@code PaymentMethod} after the hierarchy was flattened, and every
     *       {@code *Entity} marker);</li>
     *   <li>or it reaches such an interface through a chain of supertypes that contains no other
     *       marker — i.e. it <em>is</em> the first thing between {@code EntityBase} and the views.
     *       The example's {@code PaymentMethod extends PaymentMethodEntity extends
     *       EntityBase<Long>} is the case, where {@code PaymentMethodEntity} is the declaring
     *       interface.</li>
     * </ul>
     *
     * <p>Deliberately <strong>not</strong> "reaches {@code EntityBase} transitively": that would
     * also be true of every view, and would hide the whole view set from discovery.</p>
     */
    private static boolean isMarkerEntityOrRoot(InterfaceInfo info, Map<String, InterfaceInfo> interfaceMap) {
        if (info.isMarkerEntity()) {
            return true;
        }
        Set<String> visited = new HashSet<>();
        return isMarkerEntityOrRootRecursive(info, interfaceMap, visited);
    }

    private static boolean isMarkerEntityOrRootRecursive(InterfaceInfo info, Map<String, InterfaceInfo> interfaceMap,
                                                         Set<String> visited) {
        if (info == null || !visited.add(getQualifiedName(info.packageName(), info.name()))) {
            return false;
        }
        for (String extName : info.extendsTypes()) {
            String simple = extName.replaceAll("<.*>$", "").trim();
            String simpleName = simpleNameOf(simple);
            if ("EntityBase".equals(simpleName)) {
                return true;
            }
            InterfaceInfo parent = interfaceMap.containsKey(simple)
                    ? interfaceMap.get(simple)
                    : findViewInfo(interfaceMap, info.packageName(), simpleName);
            if (parent == null) {
                continue;
            }
            // A parent that is itself a marker is the marker; this interface is a view under it.
            if (parent.isMarkerEntity()) {
                continue;
            }
            if (isMarkerEntityOrRootRecursive(parent, interfaceMap, visited)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the view is (transitively) derived from the given marker — the "belongs to this
     * entity" test, as distinct from {@link #reachesEntityRoot} which asks "is this an entity at
     * all".
     */
    private static boolean isDerivedFromMarker(InterfaceInfo info, InterfaceInfo marker,
                                               Map<String, InterfaceInfo> interfaceMap) {
        if (info == null) {
            return false;
        }
        return isDerivedFrom(info, marker, interfaceMap);
    }

    /**
     * Whether an interface has a path (through its declared supertypes) to {@code EntityBase}.
     *
     * <p>This is the "is this an entity at all" test, and it decides which creator a view's metadata
     * enum can carry: the array-backed proxy requires {@code EntityBase<ID>}, so a view without one
     * is materialized by a concrete record instead. It is deliberately <strong>not</strong> part of
     * the discovery predicate — `PersonCreateForm` extends nothing and is still a view (§ 4.5/G8
     * rule 0).</p>
     */
    private static boolean reachesEntityRoot(InterfaceInfo info, Map<String, InterfaceInfo> interfaceMap) {
        Set<String> visited = new HashSet<>();
        return reachesEntityRootRecursive(info, interfaceMap, visited);
    }

    private static boolean reachesEntityRootRecursive(InterfaceInfo info, Map<String, InterfaceInfo> interfaceMap,
                                                      Set<String> visited) {
        if (info == null || !visited.add(getQualifiedName(info.packageName(), info.name()))) {
            return false;
        }
        if (info.entityBaseIdType() != null) {
            return true; // this IS an entity marker
        }
        for (String extName : info.extendsTypes()) {
            if (extName.contains("<")) {
                extName = extName.substring(0, extName.indexOf('<'));
            }
            int dot = extName.lastIndexOf('.');
            String simple = dot >= 0 ? extName.substring(dot + 1) : extName;
            if ("EntityBase".equals(simple.trim())) {
                return true;
            }
            InterfaceInfo parent = interfaceMap.get(getQualifiedName(info.packageName(), extName));
            if (parent == null) {
                // Try the dotted form the source may carry.
                parent = findViewInfo(interfaceMap, info.packageName(), simple.trim());
            }
            if (reachesEntityRootRecursive(parent, interfaceMap, visited)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds a view's {@link InterfaceInfo}, preferring the marker's package and falling back to a
     * simple-name search.
     *
     * <p>The fallback is required because a view may be declared in a different package from the
     * marker it derives from: the example's {@code paymentMethod.entity.PaymentMethodAuditable} is
     * marker-derived from {@code example.Auditable}, and the pre-fix lookup
     * ({@code interfaceMap.get(marker.package + "." + view.name)}) simply missed it, which made the
     * emitter fall back to the marker's package and emit a META referring to a class that does not
     * exist.</p>
     */
    private static InterfaceInfo findViewInfo(Map<String, InterfaceInfo> interfaceMap, String markerPackage, String viewName) {
        InterfaceInfo direct = interfaceMap.get(getQualifiedName(markerPackage, viewName));
        if (direct != null) {
            return direct;
        }
        for (InterfaceInfo info : interfaceMap.values()) {
            if (info.name().equals(viewName)) {
                return info;
            }
        }
        return null;
    }

    /**
     * The discriminator value a concrete polymorphic view declares.
     *
     * <p>§ 9/4.9 requires generation to supply each subclass's {@code discriminatorValue}. The
     * example declares it the way a hand-written sealed hierarchy naturally does — a
     * {@code default} accessor overriding an accessor the family's root declares abstract, and
     * returning a plain string literal:</p>
     *
     * <pre>{@code
     * public sealed interface PaymentMethod extends EntityBase<Long> {
     *     String type();                                  // the root declares the accessor
     * }
     * @View()
     * public non-sealed interface PayPalPaymentMethod extends PaymentMethod {
     *     default String type() { return "PAYPAL"; }       // the view declares the value
     * }
     * }</pre>
     *
     * <p><strong>Recognised by shape, not by name.</strong> The generator does not look for a field
     * called {@code type}: it looks for a {@code default} no-arg method whose body is a single
     * {@code return} of a string literal <em>and</em> whose name overrides an abstract accessor
     * declared by a marker interface in the view's supertype chain. That is what makes the accessor
     * the discriminator rather than an ordinary computed property, and it means a family may name its
     * discriminator whatever it likes (the {@code PolymorphicGenerationTest} fixture names it
     * {@code kind}). An explicit {@code @View(discriminatorField = "…")} still wins when present, as
     * the declarative spelling of the same fact.</p>
     *
     * <p>Deliberately <strong>no {@code "type"} fallback</strong>: an earlier version defaulted the
     * accessor name to the literal {@code "type"}, which is a naming convention the generator would
     * have had to publish and which silently mis-read any family that named the field otherwise.</p>
     *
     * @param view         the concrete view, as discovered by the pass
     * @param interfaceMap every parsed interface in the pass, keyed by qualified name
     * @return the literal, or {@code null} when the view declares no discriminator
     */
    private static String discriminatorValueOf(InterfaceInfo view, Map<String, InterfaceInfo> interfaceMap) {
        if (view == null || view.declaration() == null) {
            return null;
        }
        String explicit = view.view() == null ? null : view.view().discriminatorField();
        boolean explicitGiven = explicit != null && !explicit.isBlank();

        for (MethodDeclaration method : view.declaration().getMethods()) {
            if (!method.isDefault()
                    || !method.getParameters().isEmpty()
                    || method.getType().isVoidType()) {
                continue;
            }
            if (explicitGiven) {
                if (!method.getNameAsString().equals(explicit)) {
                    continue;
                }
            } else if (!overridesMarkerAccessor(view, method.getNameAsString(), interfaceMap)) {
                continue;
            }
            var body = method.getBody().orElse(null);
            if (body == null || body.getStatements().size() != 1) {
                continue;
            }
            var statement = body.getStatement(0);
            if (statement.isReturnStmt()) {
                var expression = statement.asReturnStmt().getExpression().orElse(null);
                if (expression instanceof StringLiteralExpr literal) {
                    return literal.asString();
                }
            }
        }
        return null;
    }

    /**
     * Whether {@code methodName} overrides an abstract, no-arg, non-void accessor declared by a
     * <em>marker</em> interface in the view's supertype chain.
     *
     * <p>The marker requirement is what separates a discriminator from an ordinary {@code default}
     * property: only the family's root declares the accessor that every member must answer, so only
     * there does a literal-carrying override mean "this is my discriminant".</p>
     */
    private static boolean overridesMarkerAccessor(InterfaceInfo view, String methodName,
                                                   Map<String, InterfaceInfo> interfaceMap) {
        return overridesMarkerAccessorRecursive(view, methodName, interfaceMap, new HashSet<>());
    }

    private static boolean overridesMarkerAccessorRecursive(InterfaceInfo info, String methodName,
                                                            Map<String, InterfaceInfo> interfaceMap,
                                                            Set<String> visited) {
        if (info == null || !visited.add(getQualifiedName(info.packageName(), info.name()))) {
            return false;
        }
        for (String extName : info.extendsTypes()) {
            String simple = simpleNameOf(extName.replaceAll("<.*>$", "").trim());
            InterfaceInfo parent = findViewInfo(interfaceMap, info.packageName(), simple);
            if (parent == null) {
                continue;
            }
            if (parent.isMarkerEntity() && declaresAbstractAccessor(parent, methodName)) {
                return true;
            }
            if (overridesMarkerAccessorRecursive(parent, methodName, interfaceMap, visited)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the interface declares the named accessor with no body — i.e. requires an answer. */
    private static boolean declaresAbstractAccessor(InterfaceInfo info, String methodName) {
        ClassOrInterfaceDeclaration decl = info.declaration();
        if (decl == null) {
            return false;
        }
        for (MethodDeclaration method : decl.getMethods()) {
            if (!method.getNameAsString().equals(methodName)
                    || method.isDefault()
                    || method.isStatic()
                    || method.getBody().isPresent()
                    || !method.getParameters().isEmpty()
                    || method.getType().isVoidType()) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Whether the marker's hand-written field enum exists and declares permitted subtypes.
     *
     * <p>The enum is a <strong>separate file</strong> from the marker interface, so it cannot be
     * found in the marker's own compilation unit — an earlier version looked there and therefore
     * always answered "no", which silently dropped every discriminant. The search walks the parsed
     * units the pass already holds.</p>
     */
    private static boolean rootDeclaresPermittedSubtypes(InterfaceInfo marker, Map<String, InterfaceInfo> interfaceMap) {
        String rootEnumName = marker.name() + "_";
        Set<CompilationUnit> units = new HashSet<>();
        for (InterfaceInfo info : interfaceMap.values()) {
            if (info.declaration() != null) {
                info.declaration().findCompilationUnit().ifPresent(units::add);
            }
        }
        // The enum is neither an interface nor in the marker's own unit, so the pass's retained
        // units are the only place it can be found.
        units.addAll(parsedCompilationUnits);
        for (CompilationUnit cu : units) {
            for (var type : cu.getTypes()) {
                if (type instanceof com.github.javaparser.ast.body.EnumDeclaration enumDecl
                        && enumDecl.getNameAsString().equals(rootEnumName)) {
                    return enumDecl.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).stream()
                            .filter(creation -> creation.getType().getNameAsString().equals("DefaultViewMeta"))
                            .anyMatch(creation -> creation.getArguments().stream()
                                    .anyMatch(argument -> argument instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr array
                                            && !array.getValues().isEmpty()));
                }
            }
        }
        return false;
    }
    /**
     * Whether this interface is a write/tracking <em>surface</em> rather than a view
     * (plan.dsflash § 4.5/G8 rule 1). The live witness is
     * {@code person.entity.PersonSummary.Write extends PersonSummary, ViewWriter}: it derives from
     * the marker, so the marker-only predicate discovered it as a view and produced the orphan
     * {@code Write_.java} with exactly {@code PersonSummary_}'s constants.
     */
    private static boolean isFrameworkSurface(InterfaceInfo info) {
        for (String ext : info.extendsTypes()) {
            String simple = ext.contains("<") ? ext.substring(0, ext.indexOf('<')) : ext;
            int dot = simple.lastIndexOf('.');
            if (dot >= 0) {
                simple = simple.substring(dot + 1);
            }
            if (FRAMEWORK_TYPES.contains(simple.trim())) {
                return true;
            }
        }
        return false;
    }

    /** A declaration nested inside another type — the generator's own emitted output lives there. */
    private static boolean isNested(ClassOrInterfaceDeclaration decl) {
        return decl.getParentNode()
                .filter(parent -> parent instanceof com.github.javaparser.ast.body.TypeDeclaration)
                .isPresent();
    }

    private static boolean isDerivedFrom(InterfaceInfo candidate, InterfaceInfo marker, Map<String, InterfaceInfo> interfaceMap) {
        if (candidate.name().equals(marker.name())) {
            return false;
        }

        Set<String> visited = new HashSet<>();
        return isDerivedFromRecursive(candidate, marker, interfaceMap, visited);
    }

    private static boolean isDerivedFromRecursive(InterfaceInfo candidate, InterfaceInfo marker, Map<String, InterfaceInfo> interfaceMap, Set<String> visited) {
        if (visited.contains(candidate.name())) {
            return false;
        }
        visited.add(candidate.name());

        for (String extName : candidate.extendsTypes()) {
            if (extName.contains("<")) {
                extName = extName.substring(0, extName.indexOf('<'));
            }

            if (extName.equals(marker.name()) || extName.equals(marker.packageName() + "." + marker.name())) {
                return true;
            }

            InterfaceInfo parent = interfaceMap.get(getQualifiedName(candidate.packageName(), extName));
            if (parent != null && isDerivedFromRecursive(parent, marker, interfaceMap, visited)) {
                return true;
            }
        }

        return false;
    }

    private static String toJson(EntityMeta entityMeta) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        appendJsonField(sb, "entityName", entityMeta.entityName(), true);
        appendJsonField(sb, "package", entityMeta.packageName(), true);
        appendJsonField(sb, "markerInterface", entityMeta.markerInterface(), true);
        appendJsonField(sb, "idType", entityMeta.idType(), true, 2);

        sb.append("  \"views\": [\n");
        for (int i = 0; i < entityMeta.views().size(); i++) {
            ViewMeta view = entityMeta.views().get(i);
            sb.append("    {\n");
            appendJsonField(sb, "name", view.name(), true, 6);
            appendJsonField(sb, "lineNumber", view.lineNumber(), true, 6);
            sb.append("      \"extends\": [");

            sb.append(view.extendsTypes().stream().map(EntityMetadataGenerator::escapeJson).map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")));
            sb.append("],\n");
            appendJsonField(sb, "gen", view.genName(), true, 6);
            appendJsonField(sb, "discriminatorField", view.discriminatorField(), true, 6);
            sb.append("      \"addons\": [");
            sb.append(view.addons().stream().map(EntityMetadataGenerator::escapeJson).map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")));
            sb.append("],\n");
            sb.append("      \"properties\": [\n");
            for (int j = 0; j < view.properties().size(); j++) {
                Property prop = view.properties().get(j);
                sb.append("        {\n");
                appendJsonField(sb, "name", prop.name(), true, 8);
                sb.append("        \"type\": ");
                boolean hasFieldKind = prop.fieldKind() != null;
                appendJsonType(sb, parseTypeDescriptor(prop.type()), true, 0);
                appendJsonField(sb, "lineNumber", prop.lineNumber(), hasFieldKind, 8);
                if (prop.fieldKind() != null) {
                    appendJsonField(sb, "fieldKind", prop.fieldKind(), prop.column() != null || prop.relation() != null || prop.expression() != null, 8);
                    if (prop.column() != null) {
                        appendJsonField(sb, "column", prop.column(), prop.relation() != null || prop.expression() != null, 8);
                    }
                    if (prop.relation() != null) {
                        appendJsonField(sb, "relation", prop.relation(), prop.expression() != null, 8);
                    }
                    if (prop.expression() != null) {
                        appendJsonField(sb, "expression", prop.expression(), false, 8);
                    }
                }
                sb.append("        }");
                if (j < view.properties().size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("      ]\n");
            sb.append("    }");
            if (i < entityMeta.views().size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");

        // Entity-wide field summary
        sb.append("  \"allFields\": [\n");
        for (int i = 0; i < entityMeta.allFields().size(); i++) {
            EntityFieldMeta f = entityMeta.allFields().get(i);
            sb.append("    {\n");
            appendJsonField(sb, "name", f.name, true, 6);
            sb.append("      \"type\": ");
            appendJsonType(sb, parseTypeDescriptor(f.type), true, 0);
            appendJsonField(sb, "lineNumber", f.lineNumber, true, 6);
            appendJsonField(sb, "fieldKind", f.fieldKind, true, 6);
            if (f.column != null) {
                appendJsonField(sb, "column", f.column, true, 6);
            }
            if (f.relation != null) {
                appendJsonField(sb, "relation", f.relation, true, 6);
            }
            if (f.expression != null) {
                appendJsonField(sb, "expression", f.expression, true, 6);
            }
            sb.append("      \"views\": [");
            sb.append(f.views.stream().map(v -> "\"" + escapeJson(v) + "\"").collect(Collectors.joining(", ")));
            sb.append("],\n");
            // typeByView: always present, shows type per view
            sb.append("      \"typeByView\": {\n");
            int tvIdx = 0;
            for (Map.Entry<String, String> entry : f.typeByView.entrySet()) {
                sb.append("        \"" + escapeJson(entry.getKey()) + "\": ");
                appendJsonType(sb, parseTypeDescriptor(entry.getValue()), tvIdx < f.typeByView.size() - 1, 0);
                tvIdx++;
            }
            sb.append("      }\n");
            sb.append("    }");
            if (i < entityMeta.allFields().size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}");
        return sb.toString();
    }

    private static void appendJsonField(StringBuilder sb, String key, String value, boolean trailingComma) {
        appendJsonField(sb, key, value, trailingComma, 2);
    }

    private static void appendJsonField(StringBuilder sb, String key, String value, boolean trailingComma, int indent) {
        String indentStr = " ".repeat(indent);
        sb.append(indentStr).append("\"").append(key).append("\": ");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(escapeJson(value)).append("\"");
        }
        if (trailingComma) sb.append(",");
        sb.append("\n");
    }

    private static void appendJsonField(StringBuilder sb, String key, Boolean value, boolean trailingComma, int indent) {
        String indentStr = " ".repeat(indent);
        sb.append(indentStr).append("\"").append(key).append("\": ");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append(value);
        }
        if (trailingComma) sb.append(",");
        sb.append("\n");
    }

    private static void appendJsonField(StringBuilder sb, String key, int value, boolean trailingComma, int indent) {
        String indentStr = " ".repeat(indent);
        sb.append(indentStr).append("\"").append(key).append("\": ").append(value);
        if (trailingComma) sb.append(",");
        sb.append("\n");
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Emits the {@code META} level for one view: the field enum in the shape
     * plan.dsflash § 8.3/3.7 requires ({@code implements FieldDef}, {@code javaType()},
     * {@code forName}, {@code NAME_MAPPER}, {@code META}) plus the DEC-021 header.
     *
     * <p><strong>Correction to the pre-3.7 behaviour:</strong> this used to route through
     * {@code withPropertyEnumMode()}, which emits {@code getPropertyType()}/{@code getPropertyName()}
     * and does <em>not</em> implement {@code FieldDef} — exactly what produced the broken
     * {@code hipster-entity-example} enums (§ 2.4). {@code propertyEnumMode} is retained only for
     * the tooling's own internal {@code Property} model, never for a view enum.</p>
     */
    /**
     * The creator expression for a view with no concrete record: the array-backed read proxy, which
     * is what {@code META}/{@code WRITABLE} levels materialize on.
     */
    private static String proxyCreatorBody(ViewMeta view) {
        return "(Object[] values) -> ArrayBackedViewProxyFactory.createRead("
                + view.name() + ".class, "
                + "new EntityReadArray<" + view.name() + ", " + view.name() + "_>("
                + view.name() + "_.class, values), "
                + "NAME_MAPPER)";
    }

    /** The creator expression targeting an existing nested {@code <View>.Record}. */
    private static String nestedRecordCreatorBody(ViewMeta view, List<Property> allProperties) {
        StringBuilder sb = new StringBuilder();
        sb.append("(Object[] values) -> new ").append(view.name()).append(".Record(");
        for (int i = 0; i < allProperties.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('(').append(ViewTrackingBuilderGenerator.boxedType(allProperties.get(i).type()))
                    .append(") values[").append(i).append(']');
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * The component names of a usable nested {@code record} on the view, or {@code null}.
     *
     * <p>Read from the parsed source rather than assumed: § 8.4/3.10 says to recognize an existing
     * nested record <em>by shape</em> and generate {@code create()} against it, and § 4.5/G1 uses the
     * same component list to resolve {@code GenLevel.DEFAULT} to {@code RECORD}.</p>
     */
    private static List<String> nestedRecordComponents(InterfaceInfo viewInfo, Map<String, InterfaceInfo> interfaceMap) {
        if (viewInfo == null) {
            return null;
        }
        return viewInfo.nestedRecordComponents();
    }

    /**
     * The component names of the view's nested {@code record}, or {@code null} when it declares none.
     *
     * <p>Read from the syntax tree, not guessed: both the {@code DEFAULT} resolution rule
     * (§ 4.5/G1 rule 1) and the {@code RECORD} level's "recognize an existing nested record and do
     * not emit a second one" rule (§ 8.4/3.10) compare this list against the field enum order.</p>
     */
    private static List<String> nestedRecordComponents(ClassOrInterfaceDeclaration decl) {
        for (com.github.javaparser.ast.body.BodyDeclaration<?> member : decl.getMembers()) {
            if (member instanceof com.github.javaparser.ast.body.RecordDeclaration record) {
                return record.getParameters().stream()
                        .map(parameter -> parameter.getNameAsString())
                        .collect(Collectors.toList());
            }
        }
        return null;
    }

    private static void generateViewPropertyEnum(Path outputDir, String packageName, ViewMeta view,
                                                 List<Property> fullProperties, String creatorBody,
                                                 DivergenceReporter divergences,
                                                 InterfaceInfo viewInfo,
                                                 Map<String, InterfaceInfo> interfaceMap)
            throws IOException {
        FieldBoilerplateGenerator.Builder builder = FieldBoilerplateGenerator
                .builder(packageName, view.name(), fullProperties)
                .withEnumTypeName(view.name() + "_")
                .withMetaCreatorBody(creatorBody)
                // § 2.1 of the follow-up plan: the field enum resolves a type name to a class literal, so
                // it must know which bare names are type parameters and therefore have none.
                .withTypeParameterNames(typeParameterNames)
                .withAdditionalImports(
                        "hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory",
                        "hr.hrg.hipster.entity.core.EntityReadArray")
                // The append-only ledger planner's entries (appended / retired / bootstrap-dropped
                // constants) flow into the pass's divergence report (R1.3, § 8.3/3.7a).
                .withDivergenceSink(divergences::addAll);

        // § 9/4.9: a concrete polymorphic view's META supplies the discriminator VALUE, read by shape
        // from the view's own `default <field>() { return "…"; }` (see discriminatorValueOf).
        //
        // The discriminator FIELD slot is deliberately left as a literal `null`. It belongs to the
        // root's hand-written enum, which is the artifact that binds the family together, and
        // DefaultViewMeta's fifth parameter is typed as the view's OWN field enum: a subclass could
        // only fill it by naming a constant of the root enum, which is a different type. An earlier
        // version emitted that reference and failed to compile; widening the parameter to FieldDef
        // instead broke every hand-written call site. Emitting `null` is also what the committed
        // example's generated subclasses do, so regeneration is a no-op.
        String discriminatorValue = discriminatorValueOf(viewInfo, interfaceMap);
        if (discriminatorValue != null) {
            builder = builder.withDiscriminatorValue(discriminatorValue);
            // The permitted-subtype list belongs to the ROOT only; a concrete subclass carries its
            // own value and an empty array, which is what `DefaultViewMeta.permittedSubtypes()`'s
            // documented default is for.
        }

        builder.generate(outputDir);
    }

    private static String toEnumConstant(String propertyName) {
        return propertyName
                .replaceAll("[^A-Za-z0-9]", "_")
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toUpperCase();
    }

    /** The simple names an entity id legitimately uses; anything else single-identifier is a type parameter. */
    private static final Set<String> KNOWN_ID_TYPE_NAMES = Set.of(
            "Long", "Integer", "Short", "Byte", "String", "Object", "BigInteger", "BigDecimal",
            "UUID", "Character", "Boolean", "Double", "Float",
            "long", "int", "short", "byte", "char", "boolean", "double", "float");

    /**
     * The marker's id type as something that can appear in a generated declaration.
     *
     * <p>A generic marker reports its own type parameter — {@code example.Auditable<ID>} says its id
     * is {@code ID} — which is correct in the marker's source and meaningless in generated code: the
     * field enum's {@code classLiteral} maps that name to {@code java.lang.Object}, while a record
     * component or a builder field would be declared {@code ID id} and fail to compile. Resolving the
     * argument the view actually binds ({@code Auditable<Long>}) is § 8.2/3.6's symbol work; until
     * then {@code java.lang.Object} is the honest answer, and it is the same answer the metadata
     * already gives for that name, so the declaration and the enum agree.</p>
     */
    private static String emitSafeIdType(String idType) {
        String erased = idType.replaceAll("<.*>$", "").trim();
        if (erased.equals(simpleNameOf(erased)) && !KNOWN_ID_TYPE_NAMES.contains(erased)) {
            return "java.lang.Object";
        }
        return toFullTypeName(idType);
    }

    /**
     * The id type a view binds through {@code Identifiable<X>}, or {@code null} when it binds none.
     *
     * <p>For a markerless view this is the only knowable answer to "what is the id type?": the view
     * declares {@code extends Identifiable<Long>}, so the generated record's {@code id} component has
     * to be {@code Long} or the record does not implement the interface it claims to.</p>
     */
    private static String identifiableIdType(InterfaceInfo info, Map<String, InterfaceInfo> interfaceMap,
                                             Set<String> visited) {
        if (info == null || !visited.add(getQualifiedName(info.packageName(), info.name()))) {
            return null;
        }
        for (String ext : info.extendsTypes()) {
            String simple = simpleNameOf(ext.replaceAll("<.*>$", "").trim());
            if ("Identifiable".equals(simple)) {
                List<String> arguments = ViewTrackingBuilderGenerator.typeArguments(ext);
                if (arguments.size() == 1) {
                    return emitSafeIdType(arguments.get(0));
                }
            }
            String inherited = identifiableIdType(
                    findViewInfo(interfaceMap, info.packageName(), simple), interfaceMap, visited);
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    private static List<Property> collectViewProperties(InterfaceInfo viewInfo, InterfaceInfo marker, Map<String, InterfaceInfo> interfaceMap) {
        LinkedHashMap<String, Property> merged = new LinkedHashMap<>();

        // The `id` slot's type comes from, in order: the marker the view ACTUALLY derives from; the
        // `Identifiable<X>` the view (or a supertype) binds; `java.lang.Object`.
        //
        // The middle case matters for a view discovered by the `@View` seed alone. Such a view has no
        // marker, so there is no marker id type to borrow — and borrowing one is wrong twice over: the
        // claiming marker may be unrelated (`PersonCreateForm` extends nothing, so any marker may claim
        // it), and when that marker is generic it hands over a type parameter (`Auditable<ID>`), which
        // is not a class and cannot be declared in a record component. What IS knowable is the view's
        // own `Identifiable<Long>`: reading it is the small, targeted piece of type resolution § 8.2/3.6
        // asks for, applied only where a declaration cannot be emitted without it.
        boolean derivesFromMarker = marker != null && viewInfo != null
                && isDerivedFrom(viewInfo, marker, interfaceMap);
        String idType;
        if (derivesFromMarker && marker.entityBaseIdType() != null) {
            idType = emitSafeIdType(marker.entityBaseIdType());
        } else {
            String identified = identifiableIdType(viewInfo, interfaceMap, new HashSet<>());
            idType = identified != null ? identified : "java.lang.Object";
        }
        merged.put("id", new Property("id", idType, -1));

        Set<String> visited = new HashSet<>();
        collectInterfaceProperties(viewInfo, merged, interfaceMap, visited);

        // Addons are appended AFTER every own and inherited accessor (plan.dsflash § 4.5/G6).
        // This is the only placement compatible with R1: ordinals are append-only, so an addon field
        // may never be inserted into the middle of an existing run. An addon accessor whose name
        // already exists is skipped and the view's own declaration wins — a duplicate enum constant
        // would not compile.
        if (viewInfo != null && viewInfo.view() != null) {
            for (String addon : viewInfo.view().addons()) {
                InterfaceInfo addonInfo = findViewInfo(interfaceMap,
                        viewInfo.packageName(), addon);
                if (addonInfo == null) {
                    // Reported rather than silently skipped: an unresolvable addon is almost always a
                    // typo, and G6 requires the diagnostic.
                    activeDivergences.report("unresolved_addon", viewInfo.name(),
                            "@View(addons = " + addon + ".class) cannot be resolved to an indexed interface",
                            addon, "an interface in the indexed source", "fix the addon reference");
                    continue;
                }
                LinkedHashMap<String, Property> addonProperties = new LinkedHashMap<>();
                collectInterfaceProperties(addonInfo, addonProperties, interfaceMap, new HashSet<>());
                for (Property property : addonProperties.values()) {
                    if (merged.containsKey(property.name())) {
                        activeDivergences.report("addon_field_collision", viewInfo.name() + "." + property.name(),
                                "the addon " + addon + " declares an accessor the view already has",
                                property.name(), "the view's own declaration wins",
                                "no action: the addon field was skipped");
                        continue;
                    }
                    merged.put(property.name(), property);
                }
            }
        }

        return dropUnresolvedTypeParameters(viewInfo, new ArrayList<>(merged.values()));
    }

    /**
     * Removes accessors whose declared type is a type parameter, and reports each one
     * (plan.dsflash § 2.1 of the follow-up).
     *
     * <p>A type parameter is not a field type: it has no class literal for the field enum, and no
     * declaration for a record component or a builder field (a builder cannot repeat the view's
     * <em>method</em> type parameter, so {@code T value;} in the builder is an unbound symbol). The
     * first version of this fix only changed the class literal, which left the enum correct and the
     * builders uncompilable — the compile gate in {@code UnresolvedTypeNameTest} is what makes that
     * difference visible.</p>
     *
     * <p>Dropping the accessor is the honest answer because there is nothing the generator could emit
     * for it: a view that wants such a field has to name a concrete type, which is exactly what the
     * report line says. Dropping it also keeps the ordinal space consistent, because every emitter is
     * driven by this one list.</p>
     */
    private static List<Property> dropUnresolvedTypeParameters(InterfaceInfo viewInfo, List<Property> properties) {
        List<Property> resolvable = new ArrayList<>(properties.size());
        for (Property property : properties) {
            if (!TypeLiterals.isUnresolved(property.type(), typeParameterNames)) {
                resolvable.add(property);
                continue;
            }
            if (activeDivergences != null) {
                activeDivergences.report("type_unresolved",
                        (viewInfo == null ? "?" : viewInfo.name()) + "." + property.name(),
                        "the accessor's declared type is a type parameter of this source set, so it has "
                                + "no class literal and no declarable field type",
                        property.type() + " " + property.name() + "()",
                        "the accessor is not a field: name a concrete type instead",
                        "replace the type parameter with the concrete type the accessor returns, or "
                                + "bind it in the view's supertype");
            }
        }
        return resolvable;
    }

    private static void collectInterfaceProperties(InterfaceInfo current, LinkedHashMap<String, Property> merged, Map<String, InterfaceInfo> interfaceMap, Set<String> visited) {
        if (current == null || !visited.add(current.name())) {
            return;
        }

        for (String extName : current.extendsTypes()) {
            // Strip the type arguments FIRST. The example's `PersonAuditable extends Person,
            // Auditable<Long>` is the case: `extName` is the text `Auditable<Long>`, which contains
            // no dot, but an earlier version tested `extName.contains(".")` on the raw text and then
            // only stripped generics on the unqualified branch — so a parameterized supertype was
            // looked up verbatim under its generic spelling and never found, and the view silently
            // lost every accessor it inherited from that supertype.
            String simple = extName.replaceAll("<.*>$", "").trim();
            InterfaceInfo parent = interfaceMap.get(simple);
            if (parent == null) {
                parent = interfaceMap.get(getQualifiedName(current.packageName(), simple));
            }
            if (parent == null) {
                // A dotted declaration (`addon.other.Auditable`): try the simple name last, which is
                // what makes a cross-package supertype resolvable without an import table.
                parent = findViewInfo(interfaceMap, current.packageName(), simpleNameOf(simple));
            }
            collectInterfaceProperties(parent, merged, interfaceMap, visited);
        }

        for (Property prop : current.properties()) {
            if (merged.containsKey(prop.name())) {
                merged.remove(prop.name());
            }
            merged.put(prop.name(), prop);
        }
    }

    /** {@code addon.other.Auditable} &rarr; {@code Auditable}. */
    private static String simpleNameOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private static String typeExpression(String rawType) {
        String type = rawType.trim();
        if (type.endsWith("[]")) {
            String element = typeExpression(type.substring(0, type.length() - 2));
            return "java.lang.reflect.Array.newInstance(" + element + ", 0).getClass()";
        }

        int genericStart = type.indexOf('<');
        if (genericStart < 0) {
            if (isPrimitiveType(type)) {
                return boxedPrimitiveClass(type) + ".class";
            }
            String literal = classLiteral(type);
            return literal + ".class";
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

    private static boolean isBoxedPrimitiveType(String typeName) {
        return switch (typeName) {
            case "Byte", "Short", "Integer", "Long", "Float", "Double", "Boolean", "Character",
                 "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
                 "java.lang.Float", "java.lang.Double", "java.lang.Boolean", "java.lang.Character" -> true;
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

    private static String unboxedPrimitiveType(String boxedTypeName) {
        return switch (boxedTypeName) {
            case "java.lang.Byte" -> "byte";
            case "java.lang.Short" -> "short";
            case "java.lang.Integer" -> "int";
            case "java.lang.Long" -> "long";
            case "java.lang.Float" -> "float";
            case "java.lang.Double" -> "double";
            case "java.lang.Boolean" -> "boolean";
            case "java.lang.Character" -> "char";
            default -> null;
        };
    }

    /**
     * Every type-parameter name declared in the source set being generated (see
     * {@link TypeLiterals#typeParameterNames}).
     *
     * <p>A pass-level value, not a per-view one: it is a property of the tree that was read, and the
     * emitters that turn a type name into a class literal are static helpers. It is recomputed at the
     * start of every pass, so two passes over the same tree agree.</p>
     */
    private static Set<String> typeParameterNames = Set.of();

    /**
     * The literal a {@code .class} expression uses for a source type name.
     *
     * <p>Delegates to {@link TypeLiterals}, the one implementation, so the two emitters cannot drift
     * apart again (the follow-up plan's § 2.1). The type-parameter set travels with the pass; see
     * {@link #typeParameterNames}.</p>
     */
    private static String classLiteral(String typeName) {
        return TypeLiterals.classLiteral(typeName, typeParameterNames);
    }

    private static String toFullTypeName(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return rawType;
        }

        String type = rawType.trim();
        if (type.endsWith("[]")) {
            String element = toFullTypeName(type.substring(0, type.length() - 2));
            return element + "[]";
        }

        int genericStart = type.indexOf('<');
        if (genericStart < 0) {
            return classLiteral(type);
        }

        String raw = type.substring(0, genericStart).trim();
        String args = type.substring(genericStart + 1, type.lastIndexOf('>'));
        String[] generics = splitGenerics(args);
        StringBuilder builder = new StringBuilder();
        builder.append(classLiteral(raw)).append("<");
        for (int i = 0; i < generics.length; i++) {
            builder.append(toFullTypeName(generics[i]));
            if (i < generics.length - 1) builder.append(", ");
        }
        builder.append(">");
        return builder.toString();
    }
}



