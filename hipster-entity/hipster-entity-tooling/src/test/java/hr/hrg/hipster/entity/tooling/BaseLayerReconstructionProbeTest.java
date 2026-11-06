package hr.hrg.hipster.entity.tooling;

import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ClassRecord;
import hr.hrg.jcodebuddy.engine.index.ContentHash;
import hr.hrg.jcodebuddy.engine.index.FileMetadata;
import hr.hrg.jcodebuddy.engine.index.MemberRecord;
import hr.hrg.jcodebuddy.engine.index.SourceSlice;
import hr.hrg.jcodebuddy.engine.index.TypeAnnotation;
import hr.hrg.jcodebuddy.engine.index.TypeRelation;
import hr.hrg.jcodebuddy.engine.source.SourceReader;
import hr.hrg.jcodebuddy.engine.source.TreeQueries;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.tree.J;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * E10 of {@code plans/hipster-entity-mvp.md}: the feasibility probe for a cache-backed parse path.
 *
 * <p>It answers one question with evidence rather than opinion: given a real {@link FileMetadata} entry —
 * the stored base layer of DEC-041, round-tripped through its own JSON so nothing kept in memory can
 * flatter it — which facts the generator needs for one view can be <em>reconstructed</em>, and which
 * cannot?</p>
 *
 * <p>The probe compares each fact against the same fact read from the parsed unit, so "present" means
 * "the reconstructed value equals the value the LST path produces", never "a plausible value was
 * produced". Every row it prints is one line of the feasibility table (verdict / fact / base-layer
 * source), and the assertions are the rows the reconstruction depends on. The {@code RANGE_ONLY} rows are
 * the interesting ones: the fact is <strong>not</strong> in the base layer, but a recorded range points at
 * its written form — which is how DEC-041 D2 says a consumer recovers it (the entry holds positions, the
 * hash says whether they are current, and the slice is the read).</p>
 *
 * <p>Two views are probed, because they are the two shapes the generator branches on: a simple view with a
 * nested record and an inherited marker ({@code PersonSummary}), and a polymorphic member whose
 * discriminator is a method BODY ({@code PayPalPaymentMethod}).</p>
 */
@Tag("generator")
class BaseLayerReconstructionProbeTest {

    private static final String PERSON_SUMMARY =
            "hr/hrg/hipster/entityexample/person/entity/PersonSummary.java";
    private static final String PAYPAL =
            "hr/hrg/hipster/entityexample/paymentMethod/entity/PayPalPaymentMethod.java";
    private static final String PAYMENT_METHOD =
            "hr/hrg/hipster/entityexample/paymentMethod/entity/PaymentMethod.java";
    private static final String PERSON =
            "hr/hrg/hipster/entityexample/person/entity/Person.java";

    /** The accessor names the generator never treats as fields (EntityMetadataGenerator.FRAMEWORK_ACCESSORS). */
    private static final List<String> FRAMEWORK_ACCESSORS = List.of(
            "isChanged", "changes", "changesBuilder", "changedValues", "clearChanges",
            "currentValue", "get", "set");

    /** One row of the feasibility table: the fact, the base-layer source it was read from, and the verdict. */
    private record Row(Verdict verdict, String fact, String source, String base, String lst) {
    }

    private enum Verdict {
        /** The base layer keeps the fact and the reconstructed value equals the parsed one. */
        PRESENT,
        /** The fact is absent from the base layer, but a recorded RANGE points at its written form. */
        RANGE_ONLY,
        /** The fact is absent from the base layer and no recorded range points at it. */
        MISSING
    }

    private final List<Row> rows = new ArrayList<>();

    @Test
    void baseLayerCarriesEverythingTheSimpleViewNeedsApartFromFactsItsRangesPointAt() throws Exception {
        ExamplePassFixture fixture = ExamplePassFixture.shared();
        Path file = fixture.sourceRoot().resolve(PERSON_SUMMARY);
        String source = Files.readString(file);
        J.CompilationUnit unit = SourceReader.readText(source).unit();
        ClassIndex index = ClassIndex.forPass(fixture.reportDir(), fixture.moduleRoot(),
                fixture.sourceRoot());
        FileMetadata entry = storedEntry(fixture, file, source, unit);
        ClassRecord summary = only(entry, "hr.hrg.hipster.entityexample.person.entity.PersonSummary");
        J.ClassDeclaration declaration = declarationOf(unit, "PersonSummary");

        // --- identity, modifiers, line ---
        present("package name", "entry.types[i].fqn() minus its last segment",
                packageOf(summary.fqn()), TreeQueries.packageName(unit));
        present("simple name", "entry.types[i].fqn() last segment",
                simpleNameOf(summary.fqn()), declaration.getSimpleName());
        present("`public` modifier", "entry.types[i].modifiers()",
                String.valueOf(summary.modifiers().contains("public")),
                String.valueOf(declaration.hasModifier(J.Modifier.Type.Public)));
        present("view lineNumber (declaration start, @View included)",
                "entry.types[i].span().start() + the source",
                String.valueOf(lineAt(source, summary.span().start())),
                String.valueOf(TreeQueries.declarationLineOf(declaration, source)));
        Assertions.assertNotNull(summary.span(),
                "without the type span the declaration line is not recoverable");
        Assertions.assertNotEquals(summary.line(), TreeQueries.declarationLineOf(declaration, source),
                "the class-index line is the NAME line, so a reconstruction that used it would move every"
                        + " annotated view's recorded lineNumber by one");

        // --- @View attributes ---
        TypeAnnotation view = annotationNamed(summary.annotations(), "View");
        J.Annotation lstView = TreeQueries.annotationNamed(declaration, "View");
        present("@View presence", "entry.types[i].annotations[].name()",
                String.valueOf(view != null), String.valueOf(lstView != null));
        present("@View arguments as written", "entry.types[i].annotations[].arguments()",
                String.valueOf(view.arguments()), String.valueOf(writtenArguments(lstView)));
        Assertions.assertEquals(List.of("gen = GenLevel.BUILDER_ALL"), view.arguments(),
                "a named annotation argument is stored as `name = value`, which is exactly the pair the"
                        + " generator's own reader branches on");

        // --- supertypes: the bare name is a row field, the written form is a range ---
        TypeRelation person = summary.relations().get(0);
        Assertions.assertNotNull(person.span(),
                "without a relation span every type argument is unreachable (Identifiable<Long> would read"
                        + " as Identifiable and the id type would silently become Object)");
        present("supertype name (arguments stripped)", "entry.types[i].relations[].name()",
                person.name(), "Person");
        present("supertype written form, type arguments included",
                "entry.types[i].relations[].span() sliced from the source",
                SourceSlice.read(index, summary, person).text(),
                TreeQueries.supertypeTexts(declaration).get(0));

        // --- members: accessors, their annotations and positions ---
        List<MemberRecord> accessors = accessors(summary);
        present("accessor names", "entry.types[i].members[] (kind=method, parameters empty)",
                accessors.stream().map(MemberRecord::name).toList().toString(),
                fieldAccessorNames(declaration).toString());
        MemberRecord age = member(accessors, "age");
        present("accessor declared type", "entry.types[i].members[].type()",
                age.type(), TreeQueries.typeText(
                        TreeQueries.methodsOf(declaration).get(0).getReturnTypeExpression()));
        present("accessor line (the NAME line)", "entry.types[i].members[].line()",
                String.valueOf(age.line()),
                String.valueOf(TreeQueries.methodLineOf(TreeQueries.methodsOf(declaration).get(0),
                        "PersonSummary", source)));
        Assertions.assertEquals(17, age.line(), "the member row carries the accessor's name line");

        TypeAnnotation fieldSource = annotationNamed(age.annotations(), "FieldSource");
        present("@FieldSource arguments", "entry.types[i].members[].annotations[]",
                String.valueOf(fieldSource.arguments()),
                String.valueOf(writtenArguments(
                        TreeQueries.annotationNamed(TreeQueries.methodsOf(declaration).get(0), "FieldSource"))));
        Assertions.assertEquals(List.of("kind = FieldKind.DERIVED",
                        "expression = \"YEAR(NOW()) - YEAR(birthDate)\""),
                fieldSource.arguments(),
                "a member's arguments keep their written form, quotes included, so kind/column/relation/"
                        + "expression are decodable from the base layer");

        TypeAnnotation notNull = annotationNamed(member(accessors, "metadata").annotations(), "NotNull");
        J.Annotation notNullLst = TreeQueries.annotationNamed(methodNamed(declaration, "metadata"), "NotNull");
        present("constraint annotation name", "entry.types[i].members[].annotations[].name()",
                notNull.name(), TreeQueries.annotationName(notNullLst));
        present("constraint arguments", "entry.types[i].members[].annotations[].arguments()",
                String.valueOf(notNull.arguments()), String.valueOf(writtenArguments(notNullLst)));
        Assertions.assertEquals("NotNull", notNull.name(),
                "the stored name is the SIMPLE name: the namespace is not a stored fact, so a constraint"
                        + " outside ValidationGenerator.RECOGNISED could not be recognised by prefix alone");
        Assertions.assertNotNull(notNull.span(),
                "the annotation range is what recovers the qualified name the base layer does not store");

        // --- the facts that are not in the base layer, and the ranges that reach them ---
        List<String> lstComponents = lstNestedRecordComponents(declaration);
        listRanged("nested record component names",
                "NO base field (MemberRecord.Kind.NESTED holds the name only); the nested type's own row"
                        + " carries the range the components sit in",
                String.valueOf(lstComponents));
        ClassRecord nestedRecord = nestedRecord(entry, summary.fqn());
        Assertions.assertNotNull(nestedRecord,
                "the nested record IS a row of the same file, with its own declaration range");
        String header = SourceSlice.read(index, nestedRecord, nestedRecord.span()).text();
        present("nested record components recovered from the nested row's range",
                "entry.types[] nested row .span() sliced, then decoded",
                String.valueOf(recordComponents(header)), String.valueOf(lstComponents));
        present("nested Write interface", "entry.types[] where enclosing = PersonSummary and kind = interface",
                String.valueOf(nestedInterfaces(entry, summary.fqn())), "[Write]");

        // --- the marker's id type: a type argument, reachable only through the relation range ---
        Path personFile = fixture.sourceRoot().resolve(PERSON);
        String personSource = Files.readString(personFile);
        J.CompilationUnit personUnit = SourceReader.readText(personSource).unit();
        FileMetadata personEntry = storedEntry(fixture, personFile, personSource, personUnit);
        ClassRecord marker = only(personEntry, "hr.hrg.hipster.entityexample.person.entity.Person");
        TypeRelation entityBase = marker.relations().stream()
                .filter(r -> "EntityBase".equals(r.name())).findFirst().orElseThrow();
        present("EntityBase<Id> type argument", "entry.types[].relations[] span sliced",
                entityBaseArgument(SourceSlice.read(index, marker, entityBase).text()),
                entityBaseArgument(TreeQueries.supertypeTexts(declarationOf(personUnit, "Person")).get(0)));
        Assertions.assertEquals("Long",
                entityBaseArgument(SourceSlice.read(index, marker, entityBase).text()),
                "the marker's id type is recovered from the relation's recorded range");

        print("PersonSummary (" + file + ")");
    }

    @Test
    void theDiscriminatorValueIsAMethodBodyAndTheBaseLayerKeepsOnlyItsRange() throws Exception {
        ExamplePassFixture fixture = ExamplePassFixture.shared();
        Path file = fixture.sourceRoot().resolve(PAYPAL);
        String source = Files.readString(file);
        J.CompilationUnit unit = SourceReader.readText(source).unit();
        ClassIndex index = ClassIndex.forPass(fixture.reportDir(), fixture.moduleRoot(),
                fixture.sourceRoot());
        FileMetadata entry = storedEntry(fixture, file, source, unit);
        ClassRecord row = only(entry,
                "hr.hrg.hipster.entityexample.paymentMethod.entity.PayPalPaymentMethod");
        J.ClassDeclaration declaration = declarationOf(unit, "PayPalPaymentMethod");
        J.MethodDeclaration typeMethod = methodNamed(declaration, "type");
        MemberRecord typeMember = member(row.members(), "type");

        present("`default` modifier on the discriminator accessor", "entry.types[].members[].modifiers()",
                String.valueOf(typeMember.modifiers().contains("default")),
                String.valueOf(TreeQueries.isDefaultMethod(typeMethod)));
        present("no-parameter / non-void shape", "members[].parameters(), members[].type()",
                typeMember.parameters().size() + " params / " + typeMember.type(),
                "0 params / String");
        present("has a body", "members[].hasBody()",
                String.valueOf(typeMember.hasBody()), String.valueOf(typeMethod.getBody() != null));
        listRanged("the returned literal (the discriminator VALUE)",
                "NO base field (a body is not base metadata); the member's own range covers it",
                "PAYPAL");

        String bodyText = SourceSlice.read(index, row, typeMember).text();
        Assertions.assertNotNull(bodyText, "a member's range must be readable");
        present("discriminator literal recovered from the member's range",
                "entry.types[].members[].span() sliced, then decoded",
                String.valueOf(discriminatorLiteral(bodyText)), "PAYPAL");
        Assertions.assertTrue(bodyText.contains("\"PAYPAL\""),
                "a member's range covers its whole declaration, body included, so the literal is reachable: "
                        + bodyText);

        // The abstract accessor the discriminator overrides is a MEMBER fact, and the base layer has it.
        Path family = fixture.sourceRoot().resolve(PAYMENT_METHOD);
        String familySource = Files.readString(family);
        J.CompilationUnit familyUnit = SourceReader.readText(familySource).unit();
        FileMetadata familyEntry = storedEntry(fixture, family, familySource, familyUnit);
        ClassRecord root = only(familyEntry,
                "hr.hrg.hipster.entityexample.paymentMethod.entity.PaymentMethod");
        MemberRecord rootType = member(root.members(), "type");
        present("abstract accessor, as the base layer records it",
                "members[].modifiers/hasBody/parameters/type",
                rootType.modifiers() + " hasBody=" + rootType.hasBody() + " params="
                        + rootType.parameters().size() + " type=" + rootType.type(),
                "[] hasBody=false params=0 type=String");
        Assertions.assertFalse(rootType.hasBody(),
                "`hasBody == false` is the recorded fact that tells an abstract accessor from a default one");

        print("PayPalPaymentMethod (" + file + ")");
    }

    // ── the two decoders a faithful reuse needs, kept narrow on purpose (null means "parse instead") ──

    /**
     * A record header's component names, decoded from the declaration slice.
     *
     * <p>Narrow on purpose, because a wide decoder is how a reconstruction becomes a second Java parser:
     * it splits the parenthesised list at TOP-LEVEL commas only (so a generic component's
     * {@code Map<String, List<Long>>} stays one entry) and takes the last identifier of each entry as the
     * name (so an annotation or a modifier does not matter). Anything it cannot read returns {@code null},
     * which is the caller's signal to parse the file instead.</p>
     */
    private static List<String> recordComponents(String header) {
        if (header == null) {
            return null;
        }
        int open = indexOfTopLevel(header, '(', 0);
        if (open < 0) {
            return null;
        }
        int close = matching(header, open, '(', ')');
        if (close < 0) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (String entry : splitTopLevel(header.substring(open + 1, close))) {
            String name = lastIdentifier(entry);
            if (name == null) {
                return null;
            }
            names.add(name);
        }
        return names;
    }

    /** The string literal a {@code default X f() { return "…"; }} body returns, or {@code null}. */
    private static String discriminatorLiteral(String declarationText) {
        if (declarationText == null) {
            return null;
        }
        int brace = indexOfTopLevel(declarationText, '{', 0);
        if (brace < 0) {
            return null;
        }
        int close = matching(declarationText, brace, '{', '}');
        if (close < 0) {
            return null;
        }
        String body = stripComments(declarationText.substring(brace + 1, close)).trim();
        if (!body.startsWith("return ") || !body.endsWith(";")) {
            return null;
        }
        String expression = body.substring("return ".length(), body.length() - 1).trim();
        if (expression.length() < 2 || !expression.startsWith("\"") || !expression.endsWith("\"")) {
            return null;
        }
        return unescape(expression.substring(1, expression.length() - 1));
    }

    private static int indexOfTopLevel(String text, char wanted, int from) {
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (depth == 0 && c == wanted) {
                return i;
            }
            if (c == '(' || c == '<' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == '>' || c == ']' || c == '}') {
                depth = Math.max(0, depth - 1);
            }
        }
        return -1;
    }

    private static int matching(String text, int open, char opening, char closing) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == opening) {
                depth++;
            } else if (c == closing) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String text) {
        List<String> entries = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '<' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == '>' || c == ']' || c == '}') {
                depth = Math.max(0, depth - 1);
            }
            if (c == ',' && depth == 0) {
                entries.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.toString().isBlank()) {
            entries.add(current.toString());
        }
        return entries;
    }

    private static String lastIdentifier(String entry) {
        String text = stripComments(entry).trim();
        int end = text.length();
        while (end > 0 && !Character.isJavaIdentifierPart(text.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        return start == end ? null : text.substring(start, end);
    }

    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    private static String unescape(String literal) {
        StringBuilder out = new StringBuilder(literal.length());
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c != '\\' || i + 1 >= literal.length()) {
                out.append(c);
                continue;
            }
            char next = literal.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case '0' -> out.append('\0');
                case '\'' -> out.append('\'');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case 'u' -> {
                    if (i + 4 >= literal.length()) {
                        return null;
                    }
                    out.append((char) Integer.parseInt(literal.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> {
                    return null;
                }
            }
        }
        return out.toString();
    }

    /** {@code x.y.EntityBase<Long>} → {@code Long} ({@code null} when the text is not that shape). */
    private static String entityBaseArgument(String written) {
        if (written == null) {
            return null;
        }
        String text = written.trim();
        int generic = text.indexOf('<');
        if (generic < 0 || !text.endsWith(">")) {
            return null;
        }
        String raw = text.substring(generic + 1, text.length() - 1).trim();
        return raw.isEmpty() ? null : raw;
    }

    /** The entry as it is stored and read back, so the probe measures the serialized base layer. */
    private static FileMetadata storedEntry(ExamplePassFixture fixture, Path file, String source,
                                            J.CompilationUnit unit) throws Exception {
        String moduleRelative = ClassIndex.moduleRelative(fixture.moduleRoot(), file);
        List<String> problems = new ArrayList<>();
        FileMetadata written = FileMetadata.of(moduleRelative, ContentHash.of(file), Files.size(file),
                Files.getLastModifiedTime(file).toMillis(), false, ClassIndex.factsOf(unit, source),
                TreeQueries.importLines(unit));
        FileMetadata read = FileMetadata.parse(written.toJson(), problems);
        Assertions.assertNotNull(read, "the entry must round-trip through its own JSON: " + problems);
        return read;
    }

    private static ClassRecord only(FileMetadata entry, String fqn) {
        return entry.types().stream().filter(t -> t.fqn().equals(fqn)).findFirst()
                .orElseThrow(() -> new AssertionError(fqn + " is not in " + entry.path()));
    }

    private static ClassRecord nestedRecord(FileMetadata entry, String outerFqn) {
        for (ClassRecord row : entry.types()) {
            if (outerFqn.equals(row.enclosing()) && "record".equals(row.kind())) {
                return row;
            }
        }
        return null;
    }

    private static List<String> nestedInterfaces(FileMetadata entry, String outerFqn) {
        List<String> names = new ArrayList<>();
        for (ClassRecord row : entry.types()) {
            if (outerFqn.equals(row.enclosing()) && "interface".equals(row.kind())) {
                names.add(simpleNameOf(row.fqn()));
            }
        }
        return names;
    }

    private static J.ClassDeclaration declarationOf(J.CompilationUnit unit, String simpleName) {
        for (J.ClassDeclaration declaration : TreeQueries.typeDeclarations(unit)) {
            if (declaration.getSimpleName().equals(simpleName)) {
                return declaration;
            }
        }
        throw new AssertionError("no declaration named " + simpleName);
    }

    private static J.MethodDeclaration methodNamed(J.ClassDeclaration declaration, String name) {
        return TreeQueries.methodsOf(declaration).stream()
                .filter(method -> method.getSimpleName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no method named " + name));
    }

    /** The view's own field accessors: no parameters, non-void, not {@code default}. */
    private static List<MemberRecord> accessors(ClassRecord row) {
        List<MemberRecord> accessors = new ArrayList<>();
        for (MemberRecord member : row.members()) {
            if (member.kind() != MemberRecord.Kind.METHOD
                    || !member.parameters().isEmpty()
                    || "void".equals(member.type())
                    || member.modifiers().contains("default")
                    || member.modifiers().contains("static")
                    || FRAMEWORK_ACCESSORS.contains(member.name())) {
                continue;
            }
            accessors.add(member);
        }
        return accessors;
    }

    private static List<String> fieldAccessorNames(J.ClassDeclaration declaration) {
        List<String> names = new ArrayList<>();
        for (J.MethodDeclaration method : TreeQueries.methodsOf(declaration)) {
            if (TreeQueries.hasNoParameters(method) && !TreeQueries.isDefaultMethod(method)
                    && !TreeQueries.isStaticMethod(method) && !TreeQueries.isVoidReturn(method)
                    && !FRAMEWORK_ACCESSORS.contains(method.getSimpleName())) {
                names.add(method.getSimpleName());
            }
        }
        return names;
    }

    private static MemberRecord member(List<MemberRecord> members, String name) {
        return members.stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no member named " + name + " in "
                        + members.stream().map(MemberRecord::name).toList()));
    }

    private static TypeAnnotation annotationNamed(List<TypeAnnotation> annotations, String simpleName) {
        for (TypeAnnotation annotation : annotations) {
            if (annotation.simpleName().equals(simpleName)) {
                return annotation;
            }
        }
        return null;
    }

    /** The annotation's arguments as {@code TypeFacts} renders them, for a like-for-like comparison. */
    private static List<String> writtenArguments(J.Annotation annotation) {
        if (annotation == null || annotation.getArguments() == null) {
            return List.of();
        }
        List<String> arguments = new ArrayList<>();
        for (org.openrewrite.java.tree.Expression argument : annotation.getArguments()) {
            if (argument instanceof J.Empty) {
                continue;
            }
            arguments.add(TreeQueries.expressionText(argument));
        }
        return arguments;
    }

    /** A record's component names from the parsed declaration — the fact the base layer does not hold. */
    private static List<String> lstNestedRecordComponents(J.ClassDeclaration declaration) {
        if (declaration.getBody() == null) {
            return null;
        }
        for (org.openrewrite.java.tree.Statement member : declaration.getBody().getStatements()) {
            if (!(member instanceof J.ClassDeclaration nested)
                    || nested.getKind() != J.ClassDeclaration.Kind.Type.Record) {
                continue;
            }
            List<String> components = new ArrayList<>();
            List<org.openrewrite.java.tree.Statement> primary = nested.getPrimaryConstructor();
            if (primary != null) {
                for (org.openrewrite.java.tree.Statement component : primary) {
                    if (component instanceof J.VariableDeclarations declarations) {
                        declarations.getVariables().forEach(variable ->
                                components.add(variable.getName().getSimpleName()));
                    }
                }
            }
            return components;
        }
        return null;
    }

    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static String simpleNameOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private static int lineAt(String source, int offset) {
        if (offset < 0 || offset > source.length()) {
            return -1;
        }
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private void present(String fact, String source, String base, String lst) {
        rows.add(new Row(base.equals(lst) ? Verdict.PRESENT : Verdict.MISSING, fact, source, base, lst));
    }

    /**
     * A fact the base layer does not store, where a recorded range is what reaches it — the {@code RANGE_ONLY}
     * class the reuse path is allowed to depend on, and the one a decoder must validate before trusting.
     */
    private void listRanged(String fact, String source, String lst) {
        rows.add(new Row(Verdict.RANGE_ONLY, fact, source, "<not a base field>", lst));
    }

    /** Prints the table one row at a time: the report quotes it, and the test's output is the evidence. */
    private void print(String probed) {
        System.out.println("E10 base-layer reconstruction probe — " + probed);
        for (Row row : rows) {
            System.out.printf("  %-10s | %-58s | %-44s | %s%n", row.verdict(), row.fact(),
                    abbreviate(row.base()), row.source());
        }
        System.out.println("  " + rows.size() + " facts probed: " + count(Verdict.PRESENT)
                + " present, " + count(Verdict.RANGE_ONLY) + " range-only, "
                + count(Verdict.MISSING) + " missing");
    }

    private long count(Verdict verdict) {
        return rows.stream().filter(row -> row.verdict() == verdict).count();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "<null>";
        }
        return text.length() <= 110 ? text : text.substring(0, 107) + "...";
    }
}
