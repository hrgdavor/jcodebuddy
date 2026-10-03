package hr.hrg.jcodebuddy.engine.query;

import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ClassRecord;
import hr.hrg.jcodebuddy.engine.index.MemberRecord;
import hr.hrg.jcodebuddy.engine.index.TypeAnswer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The engine's own {@link TypeResolver}: a projection of the class index, so that a generator can be written as
 * a function of the project model rather than of the files it happens to be next to (plan step 3.0d).
 *
 * <p><strong>It reads the model and nothing else.</strong> No file is opened, no source is parsed and no
 * classpath is consulted here: a row already carries the type's kind, its fields with the types they were
 * written with, and its relations (steps 3.0b and 3.0r), so the answer was paid for by the pass that built the
 * index. That is what makes a generator testable without a filesystem and what makes its output reproducible —
 * the same index gives the same definition, whatever the working tree looks like at that moment.</p>
 *
 * <p><strong>An unknown name answers {@code null}, never an empty definition.</strong> A type this index does
 * not carry is a question with no answer, and the interface's contract makes the caller fail loudly; a
 * plausible-looking definition with no fields would instead put a wrong type into committed source, which is the
 * failure this whole boundary exists to prevent.</p>
 *
 * <p><strong>What it deliberately cannot do.</strong> It does not resolve a name to an FQN (a row records the
 * spelling the source used, and resolution needs the file's imports — 3.0h's rules), it does not consult the
 * classpath, and it does not answer for types outside the indexed modules. {@code resolve} therefore answers for
 * a name that <em>is</em> an FQN in the index; a caller that holds a simple name has
 * {@link MetadataQuery#answer(String)}'s resolution rules, which are the same ones this resolver's rows were
 * indexed under.</p>
 *
 * <p>Not a JDK type: {@code java.lang.String} has no row unless the modules searched index it, so a generator
 * asking for one gets {@code null} and must decide what that means for it. Reported as it is, because the
 * alternative — inventing a definition for anything with a dot in it — is exactly the confident wrong answer
 * this seam was built to avoid.</p>
 */
public final class IndexTypeResolver implements TypeResolver {

    private final MetadataQuery query;
    private final List<String> knownPackages;

    private IndexTypeResolver(MetadataQuery query) {
        this.query = query;
        this.knownPackages = packagesOf(query.rows());
    }

    /** A resolver over the modules a query searches. */
    public static IndexTypeResolver over(MetadataQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("a resolver needs a query; pass MetadataQuery.over(indexes)");
        }
        return new IndexTypeResolver(query);
    }

    /** A resolver over indexes, for a caller that holds them rather than a query over them. */
    public static IndexTypeResolver over(List<ClassIndex> indexes) {
        return over(MetadataQuery.over(indexes));
    }

    @Override
    public TypeDefinition resolve(String qualifiedName) {
        TypeAnswer answer = query.answer(qualifiedName);
        if (!answer.isFound()) {
            // Not "a type with nothing in it": a question with no answer. The caller fails loudly, which is the
            // interface's stated contract.
            return null;
        }
        ClassRecord row = answer.type();
        List<String> fields = new ArrayList<>();
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        for (MemberRecord member : row.members()) {
            if (member.kind() == MemberRecord.Kind.FIELD) {
                fields.add(member.name());
                fieldTypes.put(member.name(), member.type());
            }
        }
        return new TypeDefinition(row.fqn(), simpleNameOf(row.fqn()), row.kind(), fields, fieldTypes,
                row.relations());
    }

    /**
     * The packages the indexed types live in, sorted — the question a generator asks when it must
     * <em>choose</em> a name rather than look one up, which is why the index answers it at all.
     */
    @Override
    public List<String> knownPackages() {
        return knownPackages;
    }

    private static List<String> packagesOf(List<ClassRecord> rows) {
        Map<String, ClassRecord> byFqn = new LinkedHashMap<>();
        for (ClassRecord row : rows) {
            byFqn.put(row.fqn(), row);
        }
        Set<String> packages = new TreeSet<>();
        for (ClassRecord row : rows) {
            String name = packageOf(row, byFqn, new LinkedHashSet<>());
            if (!name.isEmpty()) {
                packages.add(name);
            }
        }
        return List.copyOf(packages);
    }

    /**
     * A row's package: for a top-level type the FQN without its last segment, and for a member type its
     * enclosing type's package.
     *
     * <p>The second case is the one that matters. {@code a.b.Outer.Inner} is in package {@code a.b}, but cutting
     * at the last dot answers {@code a.b.Outer} — a package that does not exist, offered to a generator as a
     * place to put a class. The index already knows the difference: a member type's row names its enclosing
     * type, so the package is the enclosing type's package, however many segments follow it.</p>
     *
     * <p>A table whose enclosing chain is broken (a name with no row) or cyclic cannot answer, and gets the empty
     * package rather than a guess or a stack overflow.</p>
     */
    private static String packageOf(ClassRecord row, Map<String, ClassRecord> byFqn, Set<String> seen) {
        String enclosing = row.enclosing();
        if (enclosing == null) {
            int lastDot = row.fqn().lastIndexOf('.');
            return lastDot < 0 ? "" : row.fqn().substring(0, lastDot);
        }
        ClassRecord parent = byFqn.get(enclosing);
        if (parent == null || !seen.add(row.fqn())) {
            return "";
        }
        return packageOf(parent, byFqn, seen);
    }

    private static String simpleNameOf(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
    }
}
