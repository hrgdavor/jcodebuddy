package hr.hrg.jcodebuddy.engine.query;

import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ClassRecord;
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
 * <h3>What is not covered, and says so</h3>
 *
 * <p>{@link #membersOf(String)} and {@link #annotationsOf(String)} are on the plan's list for this step and are
 * <strong>not answerable from the model</strong>: a row carries no members and no annotations. They return
 * {@link NotCovered} — a reported gap naming what would be needed and what to read instead — rather than an empty
 * list, which would read as "this type has no such member". Growing the index that far is a format change to
 * DEC-029 and is scheduled as its own step.</p>
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
     * Members of {@code fqn} — <strong>not covered</strong>: a row carries no members (plan step 3.0h's record,
     * and the index change is scheduled as its own step).
     */
    public NotCovered membersOf(String fqn) {
        return new NotCovered("members of " + fqn,
                "a class index row records a declaration's kind, modifiers, file and relations, not its members",
                "parse the declaring file through the engine's source path (SourceReader + TreeQueries), or grow"
                        + " the index — a DEC-029 format change, scheduled as step 3.0r");
    }

    /** Annotations on {@code fqn} — <strong>not covered</strong>, for the same reason as {@link #membersOf}. */
    public NotCovered annotationsOf(String fqn) {
        return new NotCovered("annotations on " + fqn,
                "a class index row records no annotations, and reading them from the tree for every query is the"
                        + " parsing work this query surface exists to avoid",
                "parse the declaring file through the engine's source path, or grow the index (step 3.0r)");
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
}
