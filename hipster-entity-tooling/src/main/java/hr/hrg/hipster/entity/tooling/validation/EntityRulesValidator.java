package hr.hrg.hipster.entity.tooling.validation;

import hr.hrg.hipster.entity.tooling.SourceReader;
import org.openrewrite.java.tree.J;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The registry of entity rules. Deliberately not a discovery mechanism: the rule list is a literal
 * array in the constructor, so a reader (or an IDE's find-usages) can see exactly which checks run.
 *
 * <p>It parses the whole source set first and then offers the units to every rule, so a tree-wide rule
 * can ask a question that spans files — see {@link EntityRule#validateAll}. The parse goes through
 * {@link hr.hrg.hipster.entity.tooling.SourceReader}, which owns the one configured parser: a bare
 * parser reads below the project's language level and cannot see switch expressions, records or
 * sealed types, which is most of what this repository generates (notes § 2.5). The rule this
 * validator inherits is {@link SourceReader}'s: "the parse returned something" is never "the file is
 * readable", so an unreadable file is reported, not treated as empty.</p>
 */
public class EntityRulesValidator {

    public static class ValidationIssue {
        public final Path file;
        public final String message;

        public ValidationIssue(Path file, String message) {
            this.file = file;
            this.message = message;
        }

        /**
         * The DEC-022 kind this issue carries, derived from the message's own prefix.
         *
         * <p>Every diagnostic in this package is written as {@code kind: explanation} — the same
         * convention {@code DivergenceReporter} renders — so the kind is already in the string. Parsing
         * it here rather than duplicating it into a field is deliberate: a field would be a second
         * place to keep in step with the message, and the messages are what a user reads. A message
         * with no {@code kind:} prefix is a plain violation, which keeps the older rules' wording
         * (e.g. {@code "Entity marker interface should not declare domain methods: …"}) valid.</p>
         */
        public String kind() {
            int colon = message.indexOf(':');
            if (colon <= 0) {
                return "violation";
            }
            String candidate = message.substring(0, colon).trim();
            // A kind is a single token of lower-case words joined by underscores; anything with a
            // space is prose, not a kind.
            return candidate.matches("[a-z][a-z0-9_]*") ? candidate : "violation";
        }

        /**
         * Whether this issue is advisory. The R1 {@code allowReorder} escape hatch is the only warning:
         * it is a deliberate, visible choice a project makes, so it must not fail a build by itself —
         * {@code --strict} is what promotes it. Every other kind is a violation.
         */
        public boolean isWarning() {
            return "enum_reorder_allowed".equals(kind());
        }

        @Override
        public String toString() {
            return file + ": " + message;
        }
    }

    private final List<EntityRule> rules;

    public EntityRulesValidator() {
        this.rules = Arrays.asList(
                new MarkerEntityRule(),
                new ViewInterfaceRule(),
                new ViewAnnotationRule(),
                new AuditableRule(),
                new EntityFieldEnumOrderRule()
        );
    }

    /** The rule list, in the order they run — exposed so a test can assert the registry is wired. */
    public List<EntityRule> rules() {
        return List.copyOf(rules);
    }

    public List<ValidationIssue> validate(Path moduleRoot) throws IOException {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<Path, J.CompilationUnit> units = new LinkedHashMap<>();

        if (!Files.exists(moduleRoot)) {
            issues.add(new ValidationIssue(moduleRoot, "source root does not exist"));
            return issues;
        }

        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(moduleRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !isGeneratedOrBuildOutput(moduleRoot, p))
                    .sorted()
                    .forEach(files::add);
        }

        // Parse first, rule second: a rule that needs the tree must not observe a half-built index,
        // and the order of `Files.walk` must never influence a diagnostic's content.
        List<SourceFilePair> readable = new ArrayList<>();
        for (Path file : files) {
            try {
                String source = Files.readString(file);
                // SourceReader owns the "is this readable at all" question. Asking it here
                // rather than re-deriving it from a parse result is the whole point of the
                // class: one place decides, so the F-34 failure (a partial parse mistaken
                // for a fresh file) cannot be reintroduced at a call site.
                SourceReader.Read read = SourceReader.readText(source);
                if (!read.readable()) {
                    issues.add(new ValidationIssue(file, "source_not_parsed: the file could not be read "
                            + "as Java at the configured language level; it was NOT treated as empty"));
                    continue;
                }
                units.put(file, read.unit());
                readable.add(new SourceFilePair(file, source));
            } catch (IOException e) {
                issues.add(new ValidationIssue(file, "IO error: " + e.getMessage()));
            }
        }

        for (EntityRule rule : rules) {
            rule.validateAll(units, issues);
        }

        // The field-enum ledger is the one rule that needs the source text as well as the tree,
        // because the DEC-021 marker is a comment and the LST carries no comment nodes. It is run
        // here rather than from `validateAll` so the header and the ledgers are read from the same
        // text — a tree cannot supply the header at all.
        for (SourceFilePair pair : readable) {
            EntityFieldEnumOrderRule.validateSource(pair.file(), pair.source(), issues);
        }
        return issues;
    }

    /** A parsed file and the text it was parsed from, so a rule can read comments as well as the tree. */
    private record SourceFilePair(Path file, String source) {
    }

    /**
     * Validates the source root that <strong>owns</strong> {@code directory}: the nearest
     * {@code src/main/java} or {@code src/test/java} ancestor, or {@code directory} itself when there
     * is none.
     *
     * <p>This exists so validation is never pointed at a repository root. Walking one validates every
     * module's {@code src/test/java} as well, and a test fixture that deliberately contains a broken
     * view — which several in this project do — is then reported as a defect in the product. That is
     * not a hypothetical: running the validator by hand over the example's
     * {@code src/main/java} was the measurement that exposed task 1.13, and pointing it at the repo
     * root instead produced issues from other modules' fixtures.</p>
     */
    public static Path owningSourceRoot(Path directory) {
        Path current = directory.toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path name = candidate.getFileName();
            if (name == null) {
                break;
            }
            if ("java".equals(name.toString())
                    && candidate.getParent() != null
                    && candidate.getParent().getFileName() != null
                    && ("main".equals(candidate.getParent().getFileName().toString())
                        || "test".equals(candidate.getParent().getFileName().toString()))
                    && candidate.getParent().getParent() != null
                    && "src".equals(candidate.getParent().getParent().getFileName().toString())) {
                return candidate;
            }
        }
        return current;
    }

    /**
     * Skips a build or generator output directory nested under the validated root.
     *
     * <p>Without this, validating a module root would also read {@code target/generated-sources} and
     * the parser would report the generator's own output as user source — the failure mode
     * {@code SourceReader} was introduced to stop (notes § 2.5), one layer up.</p>
     */
    private static boolean isGeneratedOrBuildOutput(Path root, Path file) {
        Path relative = root.relativize(file);
        for (Path segment : relative) {
            String name = segment.toString();
            if ("target".equals(name) || "generated-sources".equals(name)
                    || "node_modules".equals(name)) {
                return true;
            }
        }
        return false;
    }
}
