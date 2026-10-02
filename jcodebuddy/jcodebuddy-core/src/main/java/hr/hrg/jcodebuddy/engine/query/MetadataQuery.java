package hr.hrg.jcodebuddy.engine.query;

import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ClassRecord;
import hr.hrg.jcodebuddy.engine.index.TypeAnnotation;
import hr.hrg.jcodebuddy.engine.index.TypeAnswer;
import hr.hrg.jcodebuddy.engine.index.TypeRelation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The engine's query surface over the class indexes: one place where "what is this, and what is related to
 * what" is answered (DEC-037, plan step 3.0h).
 *
 * <p>Codegen, analysis, reporting and an LSP sidecar ask the same questions in different words, and each used to
 * answer them its own way — index lookups here, sibling-file reads there, classpath resolution in the merge
 * tool. This class is that one place. It queries the <strong>model</strong> (rows: FQN, kind, modifiers, package,
 * file, relations); it does not parse, write, or watch anything.</p>
 *
 * <h3>Several modules, one question</h3>
 *
 * <p>A class index is per module (DEC-029), and the case a single index cannot answer is the one that matters:
 * a relation that crosses a module boundary. So this query surface is built <em>over a set of indexes</em>, and
 * every answer is scoped by {@link #coverage()} — the modules that were searched. "Not found" therefore reads as
 * "not declared in these modules", never as "does not exist": that distinction is the whole reason 3.0f-3's
 * {@link TypeAnswer} exists, and it is kept at every query here.</p>
 *
 * <h3>Resolving a relation name, and refusing to guess</h3>
 *
 * <p>Relations are stored as written (DEC-029's relation amendment), so a row may say {@code implements Person}
 * where the type is {@code a.b.Person}. That gap was recorded in 3.0b and is closed here, by resolution against
 * the indexed world: the name is accepted when it is already an FQN a row declares, when it resolves against a
 * package of the declaring type, or when exactly <strong>one</strong> indexed type has that simple name. When two
 * types share the simple name the relation stays <strong>unresolved and is reported</strong>
 * ({@link RelationAnswer#unresolvedNames()}) instead of picking one — picking one would be the confident wrong
 * answer this engine keeps having to unlearn.</p>
 *
 * <h3>Annotations are answerable; members are not, yet</h3>
 *
 * <p>{@link #annotationsOf(String)} and {@link #annotatedWith(String)} answer from the model, because a row
 * carries the annotations written on a declaration — asked for directly on 2026-10-02, since annotation info is
 * the general fact a consumer projects its own meaning from. The names are the ones the source wrote, resolved
 * by the same rules as relations.</p>
 *
 * <p>{@link #membersOf(String)} still cannot be answered: a row records a declaration's kind, modifiers, file,
 * relations and annotations, but not what it contains. It returns {@link NotCovered} rather than an empty list,
 * which would read as "this type has no such member"; growing the index that far is a DEC-029 format change and
 * is scheduled as step 3.0r.</p>
 */
public final class MetadataQuery {

    /**
     * The answer to a relation question.
     *
     * @param subject         whether the asked name is declared in these modules at all. When it is
     *                        {@code NotIndexed}, {@link #related()} being empty means <em>cannot answer</em>, not
     *                        "nothing is related" — the distinction the step's gate asks for
     * @param related         the matching types, in a stable order; an empty list with a found subject is a fact
     * @param unresolvedNames relation names met while answering that name nothing in these modules, or that name
     *                        something ambiguous. Reported so a caller can see the answer's coverage rather than
     *                        trusting it blindly
     * @param coverage        which modules were searched, for a diagnostic
     */
    public record RelationAnswer(TypeAnswer subject, List<ClassRecord> related, List<String> unresolvedNames,
                                 String coverage) {

        public RelationAnswer {
            related = related == null ? List.of() : List.copyOf(related);
            unresolvedNames = unresolvedNames == null ? List.of() : List.copyOf(unresolvedNames);
        }

        /** Whether the question could be answered at all. */
        public boolean isAnswered() {
            return subject.isFound();
        }

        /** One line for a log or a diagnostic. */
        public String describe() {
            return subject.fqn() + ": " + (isAnswered()
                    ? related.size() + " related type(s)" + (unresolvedNames.isEmpty() ? ""
                            : ", " + unresolvedNames.size() + " relation name(s) unresolved " + unresolvedNames)
                    : "cannot answer — " + subject.describe()) + " [" + coverage + "]";
        }
    }

    /**
     * A question the engine's model cannot answer yet.
     *
     * <p>Returned instead of an empty list, because an empty list is indistinguishable from "there are none" and
     * that is exactly the collapse this engine has removed twice already (3.0f-3's answer contract, 3.0b's
     * always-written relations).</p>
     *
     * @param question          what was asked
     * @param reason            why the model cannot answer it
     * @param readThisInstead   the path that can answer it today
     */
    public record NotCovered(String question, String reason, String readThisInstead) {

        @Override
        public String toString() {
            return "not covered: " + question + " — " + reason + " (" + readThisInstead + ")";
        }
    }

    private final List<ClassIndex> modules;
    private final Map<String, ClassRecord> byFqn = new LinkedHashMap<>();
    private final Map<String, List<String>> bySimpleName = new LinkedHashMap<>();
    private final String coverage;

    private MetadataQuery(List<ClassIndex> modules) {
        this.modules = List.copyOf(modules);
        List<String> names = new ArrayList<>();
        for (ClassIndex module : this.modules) {
            names.add(module.moduleName() == null || module.moduleName().isBlank()
                    ? "(unnamed module)" : module.moduleName());
            for (ClassRecord row : module.rows()) {
                // First module wins, and a duplicate FQN across modules is the same fatal diagnostic a duplicate
                // inside one module is (DEC-029 § 8): the query surface must not pick a winner by iteration
                // order either, so it refuses the ambiguity loudly rather than by luck.
                ClassRecord previous = byFqn.putIfAbsent(row.fqn(), row);
                if (previous != null && !previous.path().equals(row.path())) {
                    throw new IllegalStateException("the name " + row.fqn() + " is declared in two modules ("
                            + previous.path() + " and " + row.path() + "); a query cannot choose one");
                }
            }
        }
        for (ClassRecord row : byFqn.values()) {
            bySimpleName.computeIfAbsent(simpleName(row.fqn()), key -> new ArrayList<>()).add(row.fqn());
        }
        this.coverage = String.join(", ", names);
    }

    /** The query surface over one module's index. */
    public static MetadataQuery over(ClassIndex... indexes) {
        return over(List.of(indexes));
    }

    /** The query surface over a project's indexes — the case a per-module index cannot answer alone. */
    public static MetadataQuery over(List<ClassIndex> indexes) {
        if (indexes == null || indexes.isEmpty()) {
            throw new IllegalArgumentException("a query needs the indexes it is about");
        }
        return new MetadataQuery(indexes);
    }

    /**
     * What the engine knows about a fully qualified name.
     *
     * <p>{@link TypeAnswer.NotIndexed} here means "not declared in {@link #coverage()}", which covers a JDK type,
     * a dependency, a type of a module the engine was not given, and a misspelling — the engine does not
     * distinguish those, and says so instead of answering "no".</p>
     */
    public TypeAnswer answer(String fqn) {
        ClassRecord row = fqn == null ? null : byFqn.get(fqn);
        return row != null ? TypeAnswer.found(row)
                : TypeAnswer.notIndexed(fqn, "not declared in these modules (" + coverage + "); it may be a JDK"
                        + " type, a dependency, a type of a module the engine was not given, or a misspelling");
    }

    /** Every indexed type of the given kind ({@code class} / {@code interface} / {@code enum} / {@code record}
     * / {@code annotation}), across the modules searched. */
    public List<ClassRecord> byKind(String kind) {
        return filter(row -> row.kind().equals(kind));
    }

    /** Every indexed type whose declaration carries the given modifier keyword. */
    public List<ClassRecord> byModifier(String modifier) {
        return filter(row -> row.modifiers().contains(modifier));
    }

    /**
     * Every indexed type in the given package.
     *
     * <p>Matched as a prefix of the FQN, which is the only thing a row can support: a nested type's FQN is
     * {@code a.b.Outer.Inner} and the row does not record which of its prefixes was the package (the declaring
     * file's package is not a row field either, though its path can be read). A caller that needs the declaring
     * package exactly should ask {@link #answer(String)} and read {@code path()}, which has no ambiguity.</p>
     */
    public List<ClassRecord> inPackage(String packageName) {
        String prefix = packageName == null || packageName.isEmpty() ? "" : packageName + ".";
        return filter(row -> row.fqn().startsWith(prefix) && row.fqn().indexOf('.', prefix.length()) < 0);
    }

    /** Every indexed type declared by the given source file, across the modules searched. */
    public List<ClassRecord> byPath(String moduleRelativePath) {
        return filter(row -> row.path().equals(moduleRelativePath));
    }

    /**
     * The types that declare {@code fqn} as a supertype — the reverse direction, resolved across modules.
     *
     * <p>This is the query 3.0b could not answer reliably and 3.0h exists to finish: a row that wrote
     * {@code implements Person} is found when the question is about {@code a.b.Person}, and a row that wrote
     * {@code implements a.b.Person} is found too. A relation that names nothing in these modules, or names
     * something ambiguous, is listed in {@link RelationAnswer#unresolvedNames()} rather than counted or
     * guessed.</p>
     */
    public RelationAnswer implementorsOf(String fqn) {
        return relatedTo(fqn, TypeRelation.Kind.IMPLEMENTS);
    }

    /** The types that declare {@code fqn} in an {@code extends} clause. */
    public RelationAnswer extendersOf(String fqn) {
        return relatedTo(fqn, TypeRelation.Kind.EXTENDS);
    }

    /**
     * Every subtype of {@code fqn}: extenders and implementors alike, which is the question "is A a subtype of
     * B" without caring which clause made it so.
     */
    public RelationAnswer subtypesOf(String fqn) {
        return relatedTo(fqn, null);
    }

    /** The supertypes {@code fqn}'s own row declares, resolved where the indexed world allows it. */
    public RelationAnswer supertypesOf(String fqn) {
        TypeAnswer subject = answer(fqn);
        if (!subject.isFound()) {
            return new RelationAnswer(subject, List.of(), List.of(), coverage);
        }
        List<ClassRecord> related = new ArrayList<>();
        Set<String> unresolved = new TreeSet<>();
        for (TypeRelation relation : subject.type().relations()) {
            String resolved = resolve(relation.name(), subject.type().fqn());
            ClassRecord row = resolved == null ? null : byFqn.get(resolved);
            if (row != null) {
                related.add(row);
            } else {
                unresolved.add(relation.name());
            }
        }
        return new RelationAnswer(subject, related, List.copyOf(unresolved), coverage);
    }

    /**
     * The annotations written on {@code fqn}, as the source wrote them, with their arguments (asked for
     * 2026-10-02).
     *
     * <p>An empty list with a found subject is a fact: the declaration carries no annotation. With a subject
     * that was not found there is nothing to report and {@link AnnotationAnswer#isAnswered()} is false, so a
     * caller cannot read "not in these modules" as "no annotations".</p>
     */
    public AnnotationAnswer annotationsOf(String fqn) {
        TypeAnswer subject = answer(fqn);
        return new AnnotationAnswer(subject, subject.isFound() ? subject.type().annotations() : List.of(),
                coverage);
    }

    /**
     * The types annotated with {@code annotationName} — the question a consumer asks to find every
     * {@code @View}, every {@code @Component}, every {@code @Deprecated}.
     *
     * <p>The question is answerable <strong>by name</strong> whether or not the annotation itself is declared in
     * these modules — a JDK annotation ({@code java.lang.Deprecated}) or one from a dependency is the normal
     * case, and refusing to answer because the annotation has no row here would be the wrong refusal. So
     * {@link AnnotatedAnswer#annotation()} reports whether the annotation type <em>is</em> declared in these
     * modules (useful, not required), while the matches come from the names the rows recorded. Matching accepts
     * the name as written, a qualified name asking about a qualified annotation, a simple name asking about
     * either, and the resolved form (3.0h's rules). Annotation names that resolve to nothing in these modules
     * are listed in {@link AnnotatedAnswer#unresolvedNames()} so the answer's coverage is visible.</p>
     */
    public AnnotatedAnswer annotatedWith(String annotationName) {
        List<ClassRecord> annotated = new ArrayList<>();
        Set<String> unresolved = new TreeSet<>();
        for (ClassRecord row : byFqn.values()) {
            boolean matches = false;
            for (TypeAnnotation annotation : row.annotations()) {
                if (annotationMatches(annotation, annotationName, row.fqn())) {
                    matches = true;
                } else if (resolve(annotation.name(), row.fqn()) == null) {
                    unresolved.add(annotation.name());
                }
            }
            if (matches) {
                annotated.add(row);
            }
        }
        return new AnnotatedAnswer(answer(annotationName), annotated, List.copyOf(unresolved), coverage);
    }

    /**
     * Whether a written annotation answers a question about {@code asked}.
     *
     * <p>Four ways, and the first two are the ones that matter in practice: the exact text the source wrote; the
     * simple name, which is how an annotation on the classpath is normally written and asked about; and then the
     * resolved form, so {@code @View} in package {@code a.b} answers a question about {@code a.b.View}.</p>
     */
    private boolean annotationMatches(TypeAnnotation annotation, String asked, String declaringFqn) {
        if (asked == null) {
            return false;
        }
        if (annotation.name().equals(asked)) {
            return true;
        }
        if (asked.indexOf('.') < 0 && annotation.simpleName().equals(asked)) {
            return true;
        }
        String resolved = resolve(annotation.name(), declaringFqn);
        return resolved != null && resolved.equals(asked);
    }

    /**
     * Members of {@code fqn} — <strong>not covered</strong>: a row carries no members (plan step 3.0h's record,
     * and the index change is scheduled as 3.0r).
     */
    public NotCovered membersOf(String fqn) {
        return new NotCovered("members of " + fqn,
                "a class index row records a declaration's kind, modifiers, file, relations and annotations, not"
                        + " its members",
                "parse the declaring file through the engine's source path (SourceReader + TreeQueries), or grow"
                        + " the index — a DEC-029 format change, scheduled as step 3.0r");
    }

    /** Which modules these answers are scoped to, for a diagnostic that must not overclaim. */
    public String coverage() {
        return coverage;
    }

    /** The rows of every module searched, in a stable order. */
    public List<ClassRecord> rows() {
        return List.copyOf(byFqn.values());
    }

    private RelationAnswer relatedTo(String fqn, TypeRelation.Kind kind) {
        TypeAnswer subject = answer(fqn);
        List<ClassRecord> related = new ArrayList<>();
        Set<String> unresolved = new TreeSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ClassRecord row : byFqn.values()) {
            for (TypeRelation relation : row.relations()) {
                if (kind != null && relation.kind() != kind) {
                    continue;
                }
                String resolved = resolve(relation.name(), row.fqn());
                if (resolved == null) {
                    unresolved.add(relation.name());
                } else if (resolved.equals(fqn) && seen.add(row.fqn())) {
                    related.add(row);
                }
            }
        }
        return new RelationAnswer(subject, related, List.copyOf(unresolved), coverage);
    }

    /**
     * The FQN a written relation name refers to, or {@code null} when the indexed world cannot say.
     *
     * <p>Three attempts, in the order that cannot be wrong first: the name as an FQN a row declares; the name
     * resolved against each package prefix of the declaring type (which is what makes a nested declaring type
     * work: {@code a.b.Outer.Inner}'s package is one of its prefixes); and finally a simple name that exactly one
     * indexed type has. Two candidates is not a tie to break — it is a question the engine cannot answer, and it
     * comes back as unresolved.</p>
     */
    private String resolve(String writtenName, String declaringFqn) {
        if (writtenName == null || writtenName.isEmpty()) {
            return null;
        }
        if (byFqn.containsKey(writtenName)) {
            return writtenName;
        }
        for (String prefix : prefixes(declaringFqn)) {
            String candidate = prefix + "." + writtenName;
            if (byFqn.containsKey(candidate)) {
                return candidate;
            }
        }
        List<String> sameSimpleName = bySimpleName.get(simpleName(writtenName));
        return sameSimpleName != null && sameSimpleName.size() == 1 ? sameSimpleName.get(0) : null;
    }

    /** Every package prefix of a declaring FQN, longest first: {@code a.b.Outer.Inner} gives {@code a.b.Outer},
     * {@code a.b}, {@code a}. */
    private static List<String> prefixes(String fqn) {
        List<String> prefixes = new ArrayList<>();
        int at = fqn.lastIndexOf('.');
        while (at > 0) {
            prefixes.add(fqn.substring(0, at));
            at = fqn.lastIndexOf('.', at - 1);
        }
        return prefixes;
    }

    private List<ClassRecord> filter(java.util.function.Predicate<ClassRecord> keep) {
        List<ClassRecord> matches = new ArrayList<>();
        for (ClassRecord row : byFqn.values()) {
            if (keep.test(row)) {
                matches.add(row);
            }
        }
        return List.copyOf(matches);
    }

    /** The last character after the last dot: how a relation is commonly written. */
    static String simpleName(String fqn) {
        int dot = fqn == null ? -1 : fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /**
     * The answer to an annotation question about one type.
     *
     * @param subject     whether the type is declared in these modules. When it is not, {@link #annotations()}
     *                    being empty means <em>cannot answer</em>, not "it carries none"
     * @param annotations the annotations as written, in declaration order; empty with a found subject is a fact
     * @param coverage    which modules were searched, for a diagnostic
     */
    public record AnnotationAnswer(TypeAnswer subject, List<TypeAnnotation> annotations, String coverage) {

        public AnnotationAnswer {
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
        }

        /** Whether the question could be answered at all. */
        public boolean isAnswered() {
            return subject.isFound();
        }

        /** One line for a log or a diagnostic. */
        public String describe() {
            return subject.fqn() + ": " + (isAnswered()
                    ? annotations.size() + " annotation(s) " + annotations.stream().map(TypeAnnotation::name).toList()
                    : "cannot answer — " + subject.describe()) + " [" + coverage + "]";
        }
    }

    /**
     * The answer to "which types are annotated with X".
     *
     * @param annotation      whether the annotation <em>type</em> is itself declared in these modules. Reported
     *                        because it is useful, and deliberately not a precondition: a JDK or dependency
     *                        annotation is the normal case and must still be answerable
     * @param annotated       the types carrying it, in a stable order
     * @param unresolvedNames annotation names met on the way that resolve to nothing in these modules
     * @param coverage        which modules were searched, for a diagnostic
     */
    public record AnnotatedAnswer(TypeAnswer annotation, List<ClassRecord> annotated, List<String> unresolvedNames,
                                  String coverage) {

        public AnnotatedAnswer {
            annotated = annotated == null ? List.of() : List.copyOf(annotated);
            unresolvedNames = unresolvedNames == null ? List.of() : List.copyOf(unresolvedNames);
        }

        /** Always true: the question is answered by scanning the names the rows recorded, whether or not the
         * annotation type has a row here. */
        public boolean isAnswered() {
            return true;
        }

        /** One line for a log or a diagnostic. */
        public String describe() {
            return annotation.fqn() + ": " + annotated.size() + " annotated type(s)"
                    + (annotation.isFound() ? "" : " (the annotation type itself is not declared in these modules)")
                    + (unresolvedNames.isEmpty() ? "" : ", " + unresolvedNames.size() + " annotation name(s)"
                            + " unresolved " + unresolvedNames)
                    + " [" + coverage + "]";
        }
    }
}
