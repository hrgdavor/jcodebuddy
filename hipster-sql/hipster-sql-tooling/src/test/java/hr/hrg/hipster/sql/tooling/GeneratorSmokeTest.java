package hr.hrg.hipster.sql.tooling;

import hr.hrg.hipster.sql.tooling.emit.AllEntitiesEmitter;
import hr.hrg.hipster.sql.tooling.emit.BuilderEmitter;
import hr.hrg.hipster.sql.tooling.emit.ImmutableEmitter;
import hr.hrg.hipster.sql.tooling.emit.MetaEmitter;
import hr.hrg.hipster.sql.tooling.emit.UpdateEmitter;
import hr.hrg.hipster.sql.tooling.emit.VisitorEmitter;
import hr.hrg.hipster.sql.tooling.ledger.OrdinalLedger;
import hr.hrg.hipster.sql.tooling.model.EntityModel;
import hr.hrg.hipster.sql.tooling.model.PropertyModel;
import hr.hrg.hipster.sql.tooling.read.EntityDiscovery;
import hr.hrg.hipster.sql.tooling.read.TypeOracle;
import hr.hrg.hipster.sql.HipsterSql;
import hr.hrg.jcodebuddy.engine.MetadataJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.persistence.Column;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first slice's end-to-end test: discovery, model, emission, the CLI's write/check path, and a
 * real javac compile of the generated Visitors against the untouched runtime annotations.
 */
class GeneratorSmokeTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures/basic/src");

    @Test
    void discoversEntitiesAndAccessorsInDeclarationOrder() throws Exception {
        EntityDiscovery.Result result = EntityDiscovery.discover(FIXTURES);

        assertTrue(result.divergences().isEmpty(), result.divergences().toString());
        assertEquals(List.of("demo.Account", "demo.User"),
                result.entities().stream().map(EntityModel::fqn).toList());

        EntityModel user = entity(result, "demo.User");
        assertEquals("user_table", user.tableName());
        assertEquals(List.of("id", "name", "age", "something", "active", "rest"),
                user.properties().stream().map(PropertyModel::name).toList());
        assertEquals("List<String>", user.properties().get(1).typeText());

        EntityModel account = entity(result, "demo.Account");
        assertEquals("Account", account.tableName());
        assertEquals(List.of("email"), account.properties().stream().map(PropertyModel::name).toList());
    }

    @Test
    void aSkippedAccessorIsNotAProperty() throws Exception {
        EntityDiscovery.Result result = EntityDiscovery.discover(
                Path.of("src/test/resources/fixtures/skip/src"));
        EntityModel skip = entity(result, "skip.SkipEntity");
        assertEquals(List.of("name"), skip.properties().stream().map(PropertyModel::name).toList());
    }

    @Test
    void visitorHasTheExpectedShapeAndBaselineFormatting() throws Exception {
        EntityModel user = entity(EntityDiscovery.discover(FIXTURES), "demo.User");
        String text = VisitorEmitter.render(user);

        assertTrue(text.startsWith("// @generated file hr.hrg.hipster.sql.tooling.HipsterSqlGenerator"));
        assertTrue(text.contains("public interface UserVisitor {"));
        assertTrue(text.contains("void visit(Long id, List<String> name, int age, boolean something, boolean active);"));
        assertTrue(text.contains("import java.util.List;"));
        assertTrue(text.contains("/** Visitor for {@link User}. */"));
        assertFalse(text.contains("\r"), "LF is the default");
        assertTrue(text.endsWith("\n"));
        assertFalse(text.contains("getInternal"), "skip = true must be excluded");
        assertFalse(text.contains("rest"), "keepRest must be excluded");
    }

    @Test
    void immutableHasTheExpectedShapeAndBaselineFormatting() throws Exception {
        EntityModel user = entity(EntityDiscovery.discover(FIXTURES), "demo.User");
        String text = ImmutableEmitter.render(user);

        assertTrue(text.contains("public final class UserImmutable implements User, IEnumGetter {"));
        assertTrue(text.contains("private Long id;"));
        assertTrue(text.contains("public UserImmutable(Long id, List<String> name, int age, "
                + "boolean something, boolean active, String rest) {"));
        assertTrue(text.contains("public UserImmutable(User v) {"));
        assertTrue(text.contains("private String internal;"));
        assertTrue(text.contains("@Deprecated"));
        assertTrue(text.contains("@javax.persistence.Column(name = \"user_name\")"),
                "accessor annotations are copied as written");
        assertTrue(text.contains("public String getInternal() {"),
                "a skipped accessor is still implemented");
        assertFalse(text.contains("case 6:"), "a skipped accessor has no ordinal");
        assertTrue(text.contains("@Override\n    public Long getId() {\n        return id;\n    }"));
        assertTrue(text.contains("<T, E extends Key<T>> T getValue(E column)"));
        assertTrue(text.contains("case 2: return this.age;"));
        assertTrue(text.contains("import hr.hrg.hipster.entity.IEnumGetter;"));
        assertTrue(text.contains("import hr.hrg.hipster.sql.Key;"));
        assertFalse(text.contains("\r"), "LF is the default");
        assertTrue(text.endsWith("\n"));
    }

    @Test
    void updateAndBuilderHaveTheExpectedShape() throws Exception {
        EntityModel user = entity(EntityDiscovery.discover(FIXTURES), "demo.User");
        String update = UpdateEmitter.render(user);
        assertTrue(update.contains("public class UserUpdate implements User, IUpdatable {"));
        assertTrue(update.contains("protected long _changeSet0;"));
        assertTrue(update.contains("public UserUpdate setId(Long id) {"));
        assertTrue(update.contains("this._changeSet0 |= 1L;"));
        assertTrue(update.contains("return (_changeSet0 & (1L << _ordinal)) != 0;"));
        assertTrue(update.contains("case 2: this.age = (Integer) value; break;"));

        String builder = BuilderEmitter.render(user);
        assertTrue(builder.contains("public class UserBuilder extends UserUpdate implements User {"));
        assertTrue(builder.contains("public UserImmutable build() {"));
        assertTrue(builder.contains("setId(v); return this;"));
    }

    @Test
    void updateTracksChangesAndBuilderBuildsAnImmutable() throws Exception {
        Path classes = compileGenerated();
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> updateType = Class.forName("demo.UserUpdate", true, loader);
            Object update = updateType.getConstructor().newInstance();
            updateType.getMethod("setId", Long.class).invoke(update, 7L);

            assertEquals(Boolean.FALSE, updateType.getMethod("isEmpty").invoke(update));
            assertEquals(Boolean.TRUE, updateType.getMethod("isChanged", int.class).invoke(update, 0));
            assertEquals(Long.valueOf(7L), updateType.getMethod("getId").invoke(update));

            Class<?> builderType = Class.forName("demo.UserBuilder", true, loader);
            Object builder = builderType.getConstructor().newInstance();
            builderType.getMethod("id", Long.class).invoke(builder, 9L);
            Object immutable = builderType.getMethod("build").invoke(builder);
            assertEquals(Long.valueOf(9L), immutable.getClass().getMethod("getId").invoke(immutable));
        }
    }

    @Test
    void metaHasColumnsAndImplementationClasses() throws Exception {
        EntityModel user = entity(EntityDiscovery.discover(FIXTURES), "demo.User");
        String meta = MetaEmitter.render(user);
        assertTrue(meta.contains("public class UserMeta extends EntityMeta<User, Long, ColumnMeta, UserVisitor> {"));
        assertTrue(meta.contains("public final ColumnMeta<Long> id;"));
        assertTrue(meta.contains("return id;"), "getPrimaryColumn returns the @Id column");
        assertTrue(meta.contains("new ColumnMeta<Long>(0, \"id\", \"id\", \"getId\", false, this, Long.class, null, \"\", null);"));
        assertTrue(meta.contains("public Class[] getImplClasses() {"));
    }

    @Test
    void immutableWithJsonHasTheSerializerShape() throws Exception {
        EntityModel user = entity(EntityDiscovery.discover(FIXTURES), "demo.User");
        String text = ImmutableEmitter.render(user, true);
        assertTrue(text.contains("@JsonSerialize(using = DirectSerializer.class)"));
        assertTrue(text.contains("@JsonCreator"));
        assertTrue(text.contains("@JsonProperty(\"id\") Long id"));
        assertTrue(text.contains("public void serialize(JsonGenerator jgen, SerializerProvider provider)"));
        assertTrue(text.contains("jgen.writeNumber(id);"));
        assertTrue(text.contains("jgen.writeObject(name);"));
    }

    @Test
    void immutableSerializesThroughJackson() throws Exception {
        Path classes = compileGenerated();
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> type = Class.forName("demo.UserImmutable", true, loader);
            Object value = type.getConstructor(Long.class, List.class, int.class, boolean.class,
                            boolean.class, String.class)
                    .newInstance(7L, List.of("a"), 33, true, false, "rest");

            String json = new ObjectMapper().writeValueAsString(value);
            assertEquals("{\"id\":7,\"name\":[\"a\"],\"age\":33,\"something\":true,"
                    + "\"active\":false,\"rest\":\"rest\"}", json);
        }
    }

    @Test
    void metaHasJacksonAndMongoSectionsWhenAsked() throws Exception {
        EntityModel user = entity(EntityDiscovery.discover(FIXTURES), "demo.User");
        String meta = MetaEmitter.render(user, true, true);
        assertTrue(meta.contains("extends MongoEntityMeta<User, Long, ColumnMeta, UserVisitor> implements WithMapper"));
        assertTrue(meta.contains("private ObjectMapper mapper;"));
        assertTrue(meta.contains("public ObjectMapper getMapper()"));
        assertTrue(meta.contains("public User decode(BsonReader reader, DecoderContext decoderContext)"));
        assertTrue(meta.contains("public void encode(BsonWriter writer, User value, EncoderContext encoderContext)"));
    }

    @Test
    void metaEncodesAndDecodesAMongoDocument() throws Exception {
        Path classes = compileGenerated();
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> metaType = Class.forName("demo.AccountMeta", true, loader);
            Class<?> accountType = Class.forName("demo.Account", true, loader);
            Class<?> immutableType = Class.forName("demo.AccountImmutable", true, loader);
            Object meta = metaType.getConstructor(HipsterSql.class, int.class)
                    .newInstance(new HipsterSql(), 0);
            Object account = immutableType.getConstructor(String.class).newInstance("mongo@b");

            BsonDocument document = new BsonDocument();
            Object writer = new BsonDocumentWriter(document);
            metaType.getMethod("encode", org.bson.BsonWriter.class, accountType, EncoderContext.class)
                    .invoke(meta, writer, account, EncoderContext.builder().build());

            Object decoded = metaType.getMethod("decode", org.bson.BsonReader.class,
                            DecoderContext.class)
                    .invoke(meta, new BsonDocumentReader(document), DecoderContext.builder().build());
            assertEquals("mongo@b", decoded.getClass().getMethod("getEmail").invoke(decoded));
        }
    }

    @Test
    void updateSerializesThroughJackson() throws Exception {
        Path classes = compileGenerated();
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> type = Class.forName("demo.UserUpdate", true, loader);
            Object value = type.getConstructor(Long.class, List.class, int.class, boolean.class,
                            boolean.class, String.class)
                    .newInstance(7L, List.of("a"), 33, true, false, "rest");

            String json = new ObjectMapper().writeValueAsString(value);
            assertEquals("{\"id\":7,\"name\":[\"a\"],\"age\":33,\"something\":true,"
                    + "\"active\":false,\"rest\":\"rest\"}", json);
        }
    }

    @Test
    void keepRestMapMergesInsteadOfNesting() throws Exception {
        PropertyModel rest = new PropertyModel(0, "rest", "getRest", "Map<String,Object>",
                false, true, false, List.of(), "");
        EntityModel document = new EntityModel("demo", "Document", "document", "demo/Document.java",
                List.of(), java.util.Map.of(), List.of(rest), List.of());
        String text = ImmutableEmitter.render(document, true);
        assertTrue(text.contains("JacksonUtil.writeKeepMap(rest, jgen, provider);"));
        assertFalse(text.contains("jgen.writeFieldName(\"rest\");"));
    }

    @Test
    void metaCarriesGeneratedAnnotations() throws Exception {
        Path classes = compileGenerated();
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> metaType = Class.forName("demo.AccountMeta", true, loader);
            Object meta = metaType.getConstructor(HipsterSql.class, int.class)
                    .newInstance(new HipsterSql(), 0);
            Object column = metaType.getMethod("getColumn", String.class).invoke(meta, "email");
            Object annotation = column.getClass()
                    .getMethod("getAnnotation", Class.class)
                    .invoke(column, Column.class);
            assertNotNull(annotation, "the generated ColumnMeta carries the accessor annotation");
            assertEquals("email_col", annotation.getClass().getMethod("name").invoke(annotation));
        }
    }

    @Test
    void cliGeneratesMongoMetaWhenAsked() throws Exception {
        Path out = Files.createTempDirectory("hipster-sql-mongo-out");
        assertEquals(0, HipsterSqlGenerator.run(new String[]{
                "--source-root", FIXTURES.toString(),
                "--java-out", out.toString(),
                "--gen-mongo"}));
        String meta = Files.readString(out.resolve("demo/UserMeta.java"));
        assertTrue(meta.contains("extends MongoEntityMeta"));
        assertTrue(meta.contains("public void encode(BsonWriter writer"));
    }

    @Test
    void cliGeneratesJsonWhenAsked() throws Exception {
        Path out = Files.createTempDirectory("hipster-sql-json-out");
        assertEquals(0, HipsterSqlGenerator.run(new String[]{
                "--source-root", FIXTURES.toString(),
                "--java-out", out.toString(),
                "--gen-json"}));
        String immutable = Files.readString(out.resolve("demo/UserImmutable.java"));
        assertTrue(immutable.contains("@JsonSerialize(using = DirectSerializer.class)"));
    }

    @Test
    void cliGeneratesMetaAndThePackageRegistryWhenAsked() throws Exception {
        Path out = Files.createTempDirectory("hipster-sql-meta-gen-out");
        Path metadata = Files.createTempDirectory("hipster-sql-meta-gen-meta");
        assertEquals(0, HipsterSqlGenerator.run(new String[]{
                "--source-root", FIXTURES.toString(),
                "--java-out", out.toString(),
                "--metadata-out", metadata.toString(),
                "--gen-meta"}));

        assertTrue(Files.isRegularFile(out.resolve("demo/UserMeta.java")));
        assertTrue(Files.isRegularFile(out.resolve("demo/UserUpdate.java")),
                "Meta names the Update class, so it is implied");
        assertTrue(Files.isRegularFile(out.resolve("demo/AllEntitiesInPackage.java")));

        JsonNode user = MetadataJson.mapper().readTree(
                Files.readString(metadata.resolve("demo/User.metadata.json")));
        assertEquals(4, user.path("artifacts").size());
        assertEquals("UserMeta", user.path("artifacts").get(3).path("name").asText());
    }

    @Test
    void cliGeneratesUpdateAndBuilderWhenAsked() throws Exception {
        Path out = Files.createTempDirectory("hipster-sql-options-out");
        Path metadata = Files.createTempDirectory("hipster-sql-options-meta");
        assertEquals(0, HipsterSqlGenerator.run(new String[]{
                "--source-root", FIXTURES.toString(),
                "--java-out", out.toString(),
                "--metadata-out", metadata.toString(),
                "--gen-builder"}));

        assertTrue(Files.isRegularFile(out.resolve("demo/UserUpdate.java")));
        assertTrue(Files.isRegularFile(out.resolve("demo/UserBuilder.java")));
        JsonNode user = MetadataJson.mapper().readTree(
                Files.readString(metadata.resolve("demo/User.metadata.json")));
        assertEquals(4, user.path("artifacts").size());
        assertEquals("UserBuilder", user.path("artifacts").get(2).path("name").asText());
        assertEquals("UserUpdate", user.path("artifacts").get(3).path("name").asText());
    }

    @Test
    void cliWritesThenChecksWithoutADifference() throws Exception {
        Path out = Files.createTempDirectory("hipster-sql-visitors");
        int written = HipsterSqlGenerator.run(new String[]{
                "--source-root", FIXTURES.toString(),
                "--java-out", out.toString()});
        assertEquals(0, written);
        assertTrue(Files.isRegularFile(out.resolve("demo/UserVisitor.java")));
        assertTrue(Files.isRegularFile(out.resolve("demo/UserImmutable.java")));
        assertTrue(Files.isRegularFile(out.resolve("demo/AccountVisitor.java")));

        int checked = HipsterSqlGenerator.run(new String[]{
                "--source-root", FIXTURES.toString(),
                "--java-out", out.toString(),
                "--check"});
        assertEquals(0, checked);

        Path userVisitor = out.resolve("demo/UserVisitor.java");
        Files.writeString(userVisitor, "drift", StandardCharsets.UTF_8);
        assertEquals(1, HipsterSqlGenerator.run(new String[]{
                "--source-root", FIXTURES.toString(),
                "--java-out", out.toString(),
                "--check"}));
    }

    @Test
    void generatedVisitorsAndImmutablesCompileAgainstTheRuntimeAnnotations() throws Exception {
        assertNotNull(compileGenerated(), "the generated sources compile");
    }

    @Test
    void immutableReadsBackTheValuesItWasConstructedWith() throws Exception {
        Path classes = compileGenerated();
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> type = Class.forName("demo.UserImmutable", true, loader);
            Object empty = type.getConstructor().newInstance();
            assertEquals(Integer.valueOf(5), type.getMethod("getAge").invoke(empty),
                    "the initial value applies to the no-arg constructor");
            Object value = type.getConstructor(Long.class, List.class, int.class, boolean.class,
                            boolean.class, String.class)
                    .newInstance(7L, List.of("a"), 33, true, false, "rest");

            assertEquals(Long.valueOf(7L), type.getMethod("getId").invoke(value));
            assertEquals(Integer.valueOf(33), type.getMethod("getAge").invoke(value));
            assertEquals(Boolean.TRUE, type.getMethod("hasSomething").invoke(value));
            assertEquals(Long.valueOf(7L), type.getMethod("getValue", int.class).invoke(value, 0));
            Object sameId = type.getConstructor(Long.class, List.class, int.class, boolean.class,
                            boolean.class, String.class)
                    .newInstance(7L, List.of("b"), 44, false, true, "other");
            assertEquals(value, sameId, "equality is keyed by the @Id");
            assertEquals(value.hashCode(), sameId.hashCode());
        }
    }

    private static Path compileGenerated() throws Exception {
        EntityDiscovery.Result discovered = EntityDiscovery.discover(FIXTURES);
        Path source = Files.createTempDirectory("hipster-sql-visitor-src");
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(FIXTURES)) {
            sources.addAll(walk.filter(path -> path.toString().endsWith(".java")).toList());
        }
        for (EntityModel entity : discovered.entities()) {
            Path packageDir = source.resolve(entity.packageName().replace('.', '/'));
            Files.createDirectories(packageDir);
            Path visitor = packageDir.resolve(entity.visitorName() + ".java");
            Files.writeString(visitor, VisitorEmitter.render(entity), StandardCharsets.UTF_8);
            sources.add(visitor);
            Path immutable = packageDir.resolve(entity.simpleName() + "Immutable.java");
            Files.writeString(immutable, ImmutableEmitter.render(entity, true), StandardCharsets.UTF_8);
            sources.add(immutable);
            Path update = packageDir.resolve(entity.simpleName() + "Update.java");
            Files.writeString(update, UpdateEmitter.render(entity, true), StandardCharsets.UTF_8);
            sources.add(update);
            Path builder = packageDir.resolve(entity.simpleName() + "Builder.java");
            Files.writeString(builder, BuilderEmitter.render(entity), StandardCharsets.UTF_8);
            sources.add(builder);
            Path meta = packageDir.resolve(entity.simpleName() + "Meta.java");
            Files.writeString(meta, MetaEmitter.render(entity, true, true, true), StandardCharsets.UTF_8);
            sources.add(meta);
        }
        Path registry = source.resolve("demo/AllEntitiesInPackage.java");
        Files.createDirectories(registry.getParent());
        Files.writeString(registry, AllEntitiesEmitter.render("demo", discovered.entities()),
                StandardCharsets.UTF_8);
        sources.add(registry);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests run on a JDK, so javac is available");
        Path classes = Files.createTempDirectory("hipster-sql-visitor-classes");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(sources);
            List<String> options = List.of(
                    "-proc:none",
                    "--release", "8",
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", classes.toString());
            boolean compiled = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
            assertTrue(compiled, diagnostics.getDiagnostics().toString());
        }
        return classes;
    }

    @Test
    void writesTheEntityMetadataAndLedgerAndChecksThem() throws Exception {
        Path out = Files.createTempDirectory("hipster-sql-meta-out");
        Path metadata = Files.createTempDirectory("hipster-sql-metadata");
        Path ledger = Files.createTempDirectory("hipster-sql-ledger");
        int rc = HipsterSqlGenerator.run(new String[]{
                "--source-root", FIXTURES.toString(),
                "--java-out", out.toString(),
                "--metadata-out", metadata.toString(),
                "--ledger-out", ledger.toString()});
        assertEquals(0, rc);

        Path metadataFile = metadata.resolve("demo/User.metadata.json");
        assertTrue(Files.isRegularFile(metadataFile));
        String text = Files.readString(metadataFile);
        assertFalse(text.contains("public interface User {"), "metadata never contains source text");
        JsonNode root = MetadataJson.mapper().readTree(text);
        assertEquals("demo.User", root.path("entity").asText());
        assertEquals("user_table", root.path("table").asText());
        assertEquals(6, root.path("properties").size());
        assertEquals("id", root.path("primary").asText());
        assertTrue(root.path("properties").get(0).path("primary").asBoolean());
        assertEquals(2, root.path("properties").get(1).path("annotations").size());
        assertEquals("5", root.path("properties").get(2).path("initial").asText());
        assertTrue(root.path("location").path("line").asInt() > 0);
        assertTrue(root.path("properties").get(0).path("location").path("line").asInt() > 0);
        assertTrue(root.path("options").path("genVisitor").asBoolean());
        assertEquals(1, root.path("skippedAccessors").size());
        assertEquals("internal", root.path("skippedAccessors").get(0).path("name").asText());
        assertEquals("demo/UserVisitor.java", root.path("artifacts").get(0).path("file").asText());
        assertEquals("UserImmutable", root.path("artifacts").get(1).path("name").asText());
        assertEquals(1, root.path("inputs").size());

        JsonNode account = MetadataJson.mapper().readTree(
                Files.readString(metadata.resolve("demo/Account.metadata.json")));
        assertEquals("Auditable", account.path("supertypes").get(0).asText());
        assertEquals(2, account.path("inputs").size(), "the entity and its supertype");

        Path ledgerFile = ledger.resolve("demo/User.ledger.json");
        assertTrue(Files.isRegularFile(ledgerFile));
        OrdinalLedger.Ledger read = OrdinalLedger.read(ledger, "demo.User");
        assertEquals(0, entry(read, "id").ordinal());
        assertEquals(5, entry(read, "rest").ordinal());

        int checked = HipsterSqlGenerator.run(new String[]{
                "--source-root", FIXTURES.toString(),
                "--java-out", out.toString(),
                "--metadata-out", metadata.toString(),
                "--ledger-out", ledger.toString(),
                "--check"});
        assertEquals(0, checked);
    }

    @Test
    void aNewFieldIsAppendedWithoutRenumberingTheExistingOnes() throws Exception {
        Path source = Files.createTempDirectory("hipster-sql-insert-src");
        try (Stream<Path> walk = Files.walk(FIXTURES)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                Path target = source.resolve(FIXTURES.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        Path out = Files.createTempDirectory("hipster-sql-insert-out");
        Path metadata = Files.createTempDirectory("hipster-sql-insert-meta");
        Path ledger = Files.createTempDirectory("hipster-sql-insert-ledger");
        assertEquals(0, run(source, out, metadata, ledger));

        Path user = source.resolve("demo/User.java");
        String text = Files.readString(user);
        Files.writeString(user, text.replace(
                "    List<String> getName();",
                "    String getNickname();\n\n    List<String> getName();"), StandardCharsets.UTF_8);
        assertEquals(0, run(source, out, metadata, ledger));

        OrdinalLedger.Ledger read = OrdinalLedger.read(ledger, "demo.User");
        assertEquals(2, entry(read, "age").ordinal(), "age must not move");
        assertEquals(6, entry(read, "nickname").ordinal(), "a new field is appended");
    }

    @Test
    void removingAFieldIsRefusedUnlessReorderIsAllowed() throws Exception {
        Path source = Files.createTempDirectory("hipster-sql-remove-src");
        try (Stream<Path> walk = Files.walk(FIXTURES)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                Path target = source.resolve(FIXTURES.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        Path out = Files.createTempDirectory("hipster-sql-remove-out");
        Path metadata = Files.createTempDirectory("hipster-sql-remove-meta");
        Path ledger = Files.createTempDirectory("hipster-sql-remove-ledger");
        assertEquals(0, run(source, out, metadata, ledger));

        Path user = source.resolve("demo/User.java");
        Files.writeString(user, Files.readString(user)
                .replace("    List<String> getName();\n\n", ""), StandardCharsets.UTF_8);

        assertEquals(1, run(source, out, metadata, ledger), "removal is refused by default");
        assertTrue(OrdinalLedger.read(ledger, "demo.User").entries().stream()
                        .anyMatch(entry -> entry.name().equals("name")),
                "the refused pass must not rewrite the ledger");

        assertEquals(0, HipsterSqlGenerator.run(new String[]{
                "--source-root", source.toString(),
                "--java-out", out.toString(),
                "--metadata-out", metadata.toString(),
                "--ledger-out", ledger.toString(),
                "--allow-reorder"}));
        OrdinalLedger.Ledger renumbered = OrdinalLedger.read(ledger, "demo.User");
        assertEquals(1, entry(renumbered, "age").ordinal(), "dense renumbering");
        assertTrue(renumbered.entries().stream().noneMatch(entry -> entry.name().equals("name")));
    }

    @Test
    void metaReadsARowFromResultSet() throws Exception {
        Path classes = compileGenerated();
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> metaType = Class.forName("demo.AccountMeta", true, loader);
            Object meta = metaType.getConstructor(HipsterSql.class, int.class)
                    .newInstance(new HipsterSql(), 0);

            try (Connection connection = DriverManager.getConnection(
                    "jdbc:h2:mem:meta-row;DB_CLOSE_DELAY=-1")) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("create table account(email varchar)");
                    statement.execute("insert into account values('a@b')");
                }
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery("select email from account")) {
                    assertTrue(rs.next());
                    Object account = metaType.getMethod("fromResultSet", ResultSet.class)
                            .invoke(meta, rs);
                    assertEquals("a@b", account.getClass().getMethod("getEmail").invoke(account));
                }
            }
        }
    }

    @Test
    void metaVisitsARowFromResultSet() throws Exception {
        Path classes = compileGenerated();
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> metaType = Class.forName("demo.AccountMeta", true, loader);
            Class<?> visitorType = Class.forName("demo.AccountVisitor", true, loader);
            Object meta = metaType.getConstructor(HipsterSql.class, int.class)
                    .newInstance(new HipsterSql(), 0);

            Object[] captured = new Object[1];
            Object visitor = Proxy.newProxyInstance(loader, new Class<?>[]{visitorType},
                    (proxy, method, args) -> {
                        if (method.getName().equals("visit")) {
                            captured[0] = args[0];
                        }
                        return null;
                    });

            try (Connection connection = DriverManager.getConnection(
                    "jdbc:h2:mem:meta-visit;DB_CLOSE_DELAY=-1")) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("create table account(email varchar)");
                    statement.execute("insert into account values('v@b')");
                }
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery("select email from account")) {
                    assertTrue(rs.next());
                    metaType.getMethod("visitResult", ResultSet.class, visitorType)
                            .invoke(meta, rs, visitor);
                }
            }
            assertEquals("v@b", captured[0]);
        }
    }

    @Test
    void wildcardNameOracleResolvesAnUnimportedType() throws Exception {
        Path source = Files.createTempDirectory("hipster-sql-oracle-src");
        Path demo = source.resolve("demo");
        Files.createDirectories(demo);
        Files.writeString(demo.resolve("Wild.java"), """
                package demo;

                import hr.hrg.hipster.entity.HipsterEntity;

                @HipsterEntity
                public interface Wild {
                    Thing getThing();
                }
                """, StandardCharsets.UTF_8);

        Path classpath = Files.createTempDirectory("hipster-sql-oracle-cp");
        Path a = classpath.resolve("a");
        Files.createDirectories(a);
        Files.writeString(a.resolve("Thing.class"), "", StandardCharsets.UTF_8);

        EntityDiscovery.Result result = EntityDiscovery.discover(source,
                TypeOracle.of(List.of(classpath)));
        EntityModel wild = entity(result, "demo.Wild");
        assertEquals("a.Thing", wild.imports().get("Thing"));
    }

    @Test
    void duplicateIdIsReportedAndTheFirstWins() throws Exception {
        Path source = Files.createTempDirectory("hipster-sql-duplicate-id");
        Path demo = source.resolve("demo");
        Files.createDirectories(demo);
        Files.writeString(demo.resolve("Duplicate.java"), """
                package demo;

                import javax.persistence.Id;
                import hr.hrg.hipster.entity.HipsterEntity;

                @HipsterEntity
                public interface Duplicate {
                    @Id Long getId();

                    @Id Long getOther();
                }
                """, StandardCharsets.UTF_8);

        EntityDiscovery.Result result = EntityDiscovery.discover(source);
        assertTrue(result.divergences().stream().anyMatch(line -> line.startsWith("multiple_id,")));
        EntityModel duplicate = entity(result, "demo.Duplicate");
        assertEquals("id", duplicate.primary().name());
    }

    @Test
    void cliFiltersPackages() throws Exception {
        Path out = Files.createTempDirectory("hipster-sql-packages-out");
        assertEquals(0, HipsterSqlGenerator.run(new String[]{
                "--source-root", FIXTURES.toString(),
                "--java-out", out.toString(),
                "--packages", "other"}));
        assertFalse(Files.isRegularFile(out.resolve("demo/UserVisitor.java")));

        assertEquals(0, HipsterSqlGenerator.run(new String[]{
                "--source-root", FIXTURES.toString(),
                "--java-out", out.toString(),
                "--packages", "demo"}));
        assertTrue(Files.isRegularFile(out.resolve("demo/UserVisitor.java")));
    }

    private static int run(Path source, Path out, Path metadata, Path ledger) throws Exception {
        return HipsterSqlGenerator.run(new String[]{
                "--source-root", source.toString(),
                "--java-out", out.toString(),
                "--metadata-out", metadata.toString(),
                "--ledger-out", ledger.toString()});
    }

    private static OrdinalLedger.Entry entry(OrdinalLedger.Ledger ledger, String name) {
        return ledger.entries().stream()
                .filter(entry -> entry.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static EntityModel entity(EntityDiscovery.Result result, String fqn) {
        return result.entities().stream()
                .filter(entity -> entity.fqn().equals(fqn))
                .findFirst()
                .orElseThrow();
    }
}
