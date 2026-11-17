package hr.hrg.hipster.sql.tooling;

import hr.hrg.hipster.sql.tooling.emit.AllEntitiesEmitter;
import hr.hrg.hipster.sql.tooling.emit.BuilderEmitter;
import hr.hrg.hipster.sql.tooling.emit.ImmutableEmitter;
import hr.hrg.hipster.sql.tooling.emit.MetaEmitter;
import hr.hrg.hipster.sql.tooling.emit.UpdateEmitter;
import hr.hrg.hipster.sql.tooling.emit.VisitorEmitter;
import hr.hrg.hipster.sql.tooling.ledger.OrdinalLedger;
import hr.hrg.hipster.sql.tooling.metadata.EntityMetadataWriter;
import hr.hrg.hipster.sql.tooling.model.EntityModel;
import hr.hrg.hipster.sql.tooling.read.EntityDiscovery;
import hr.hrg.hipster.sql.tooling.read.TypeOracle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The runnable slice of the port: discover entities, emit Visitors, and record the metadata + ordinal
 * ledger.
 *
 * <p>The first slice implements {@code --source-root}, {@code --java-out}, {@code --metadata-out},
 * {@code --ledger-out}, {@code --check} and {@code --version}. The remaining artifacts and options
 * are the next phases; an unknown flag fails rather than being ignored.</p>
 */
public final class HipsterSqlGenerator {

    private static final String VERSION = "0.2.0-slice";

    private HipsterSqlGenerator() {
    }

    public static void main(String[] args) {
        int exit;
        try {
            exit = run(args);
        } catch (Exception e) {
            System.err.println("[gen] " + e.getMessage());
            exit = 2;
        }
        System.exit(exit);
    }

    /** The CLI as a function, so tests can run it without exiting the JVM. */
    public static int run(String[] args) throws IOException {
        Path sourceRoot = null;
        Path javaOut = null;
        Path metadataOut = null;
        Path ledgerOut = null;
        boolean check = false;
        boolean allowReorder = false;
        boolean genUpdate = false;
        boolean genBuilder = false;
        boolean genMeta = false;
        boolean genJson = false;
        boolean genMongo = false;
        boolean genAnnotations = false;
        String classpath = null;
        List<String> packages = List.of();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--source-root" -> {
                    if (++i >= args.length) {
                        return usage("--source-root needs a value");
                    }
                    sourceRoot = Path.of(args[i]);
                }
                case "--java-out" -> {
                    if (++i >= args.length) {
                        return usage("--java-out needs a value");
                    }
                    javaOut = Path.of(args[i]);
                }
                case "--metadata-out" -> {
                    if (++i >= args.length) {
                        return usage("--metadata-out needs a value");
                    }
                    metadataOut = Path.of(args[i]);
                }
                case "--ledger-out" -> {
                    if (++i >= args.length) {
                        return usage("--ledger-out needs a value");
                    }
                    ledgerOut = Path.of(args[i]);
                }
                case "--classpath" -> {
                    if (++i >= args.length) {
                        return usage("--classpath needs a value");
                    }
                    classpath = args[i];
                }
                case "--packages" -> {
                    if (++i >= args.length) {
                        return usage("--packages needs a value");
                    }
                    packages = List.of(args[i].split(","));
                }
                case "--check" -> check = true;
                case "--allow-reorder" -> allowReorder = true;
                case "--gen-update" -> genUpdate = true;
                case "--gen-builder" -> genBuilder = true;
                case "--gen-meta" -> genMeta = true;
                case "--gen-json" -> genJson = true;
                case "--gen-mongo" -> genMongo = true;
                case "--gen-annotations" -> genAnnotations = true;
                case "--version" -> {
                    System.out.println("hipster-sql-tooling " + VERSION);
                    return 0;
                }
                case "--help", "-h" -> {
                    printUsage();
                    return 0;
                }
                default -> {
                    return usage("unknown argument: " + args[i]);
                }
            }
        }

        if (sourceRoot == null || javaOut == null) {
            return usage("both --source-root and --java-out are required");
        }
        if (genMongo || genAnnotations) {
            genMeta = true; // both are Meta sections
        }
        if (genMeta) {
            genUpdate = true; // Meta.getImplClasses() names the Update class
        }
        Path ledgerDir = ledgerOut != null
                ? ledgerOut
                : (metadataOut != null ? metadataOut.resolve("ledger") : null);

        TypeOracle oracle = TypeOracle.empty();
        if (classpath != null && !classpath.isBlank()) {
            List<Path> entries = new ArrayList<>();
            for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    entries.add(Path.of(entry));
                }
            }
            oracle = TypeOracle.of(entries);
        }
        EntityDiscovery.Result discovered = EntityDiscovery.discover(sourceRoot, oracle);
        List<String> divergences = new ArrayList<>(discovered.divergences());
        List<EntityModel> sourceEntities = discovered.entities();
        if (!packages.isEmpty()) {
            final List<String> packageFilter = packages;
            sourceEntities = sourceEntities.stream()
                    .filter(entity -> packageFilter.stream().anyMatch(pkg ->
                            entity.packageName().equals(pkg)
                                    || entity.packageName().startsWith(pkg + ".")))
                    .toList();
        }
        List<Prepared> prepared = new ArrayList<>();
        boolean failed = false;

        // Phase 1: read/assign every ledger. A refused or unreadable ledger stops the whole pass
        // before a single file is written, so a failed run cannot leave a half-updated tree.
        for (EntityModel original : sourceEntities) {
            EntityModel entity = original;
            OrdinalLedger.Ledger nextLedger = OrdinalLedger.Ledger.empty();
            if (ledgerDir != null) {
                try {
                    OrdinalLedger.Assigned assigned = OrdinalLedger.assign(original,
                            OrdinalLedger.read(ledgerDir, original.fqn()), allowReorder);
                    entity = assigned.entity();
                    nextLedger = assigned.next();
                    divergences.addAll(assigned.divergences());
                    if (assigned.rejected()) {
                        failed = true;
                        continue;
                    }
                } catch (IOException e) {
                    divergences.add("ledger_unreadable, location=" + original.fqn()
                            + ", cause=" + e.getMessage() + ", action=pass stopped, no renumbering");
                    failed = true;
                    continue;
                }
            }
            prepared.add(new Prepared(entity, nextLedger));
        }

        if (failed) {
            for (String divergence : divergences) {
                System.err.println("[gen] divergence: " + divergence);
            }
            System.out.println("[gen] entities=0 written=0 unchanged=0"
                    + (check ? " differing=0" : "")
                    + " divergences=" + divergences.size()
                    + " status=refused");
            return 1;
        }

        int written = 0;
        int unchanged = 0;
        int differing = 0;
        List<EntityModel> entities = new ArrayList<>();
        for (Prepared item : prepared) {
            EntityModel entity = item.entity();
            entities.add(entity);

            Status visitor = writeOrCompare(
                    javaOut.resolve(entity.packageName().replace('.', '/'))
                            .resolve(entity.visitorName() + ".java"),
                    VisitorEmitter.render(entity), check);
            written += visitor.written;
            unchanged += visitor.unchanged;
            differing += visitor.differing;

            Status immutable = writeOrCompare(
                    javaOut.resolve(entity.packageName().replace('.', '/'))
                            .resolve(entity.simpleName() + "Immutable.java"),
                    ImmutableEmitter.render(entity, genJson), check);
            written += immutable.written;
            unchanged += immutable.unchanged;
            differing += immutable.differing;

            if (genUpdate || genBuilder) {
                Status update = writeOrCompare(
                        javaOut.resolve(entity.packageName().replace('.', '/'))
                                .resolve(entity.simpleName() + "Update.java"),
                        UpdateEmitter.render(entity, genJson), check);
                written += update.written;
                unchanged += update.unchanged;
                differing += update.differing;
            }
            if (genBuilder) {
                Status builder = writeOrCompare(
                        javaOut.resolve(entity.packageName().replace('.', '/'))
                                .resolve(entity.simpleName() + "Builder.java"),
                        BuilderEmitter.render(entity), check);
                written += builder.written;
                unchanged += builder.unchanged;
                differing += builder.differing;
            }
            if (genMeta) {
                Status meta = writeOrCompare(
                        javaOut.resolve(entity.packageName().replace('.', '/'))
                                .resolve(entity.simpleName() + "Meta.java"),
                        MetaEmitter.render(entity, genJson, genMongo, genAnnotations), check);
                written += meta.written;
                unchanged += meta.unchanged;
                differing += meta.differing;
            }

            if (ledgerDir != null) {
                Status ledger = writeOrCompare(OrdinalLedger.fileFor(ledgerDir, entity.fqn()),
                        OrdinalLedger.render(entity.fqn(), item.nextLedger()), check);
                written += ledger.written;
                unchanged += ledger.unchanged;
                differing += ledger.differing;
            }
            if (metadataOut != null) {
                Status metadata = writeOrCompare(
                        EntityMetadataWriter.fileFor(metadataOut, entity.fqn()),
                        EntityMetadataWriter.render(entity, sourceRoot, javaOut,
                                genUpdate, genBuilder, genMeta, genJson, genMongo, genAnnotations),
                        check);
                written += metadata.written;
                unchanged += metadata.unchanged;
                differing += metadata.differing;
            }
        }

        if (genMeta) {
            Map<String, List<EntityModel>> byPackage = new LinkedHashMap<>();
            for (EntityModel entity : entities) {
                byPackage.computeIfAbsent(entity.packageName(), key -> new ArrayList<>()).add(entity);
            }
            for (Map.Entry<String, List<EntityModel>> entry : byPackage.entrySet()) {
                String packageName = entry.getKey();
                Status registry = writeOrCompare(
                        javaOut.resolve(packageName.replace('.', '/'))
                                .resolve("AllEntitiesInPackage.java"),
                        AllEntitiesEmitter.render(packageName, entry.getValue()), check);
                written += registry.written;
                unchanged += registry.unchanged;
                differing += registry.differing;
            }
        }

        if (metadataOut != null) {
            Status manifest = writeOrCompare(metadataOut.resolve("manifest.json"),
                    EntityMetadataWriter.renderManifest(entities, javaOut, genUpdate, genBuilder,
                            genMeta),
                    check);
            written += manifest.written;
            unchanged += manifest.unchanged;
            differing += manifest.differing;
        }

        for (String divergence : divergences) {
            System.err.println("[gen] divergence: " + divergence);
        }
        System.out.println("[gen] entities=" + entities.size()
                + " written=" + written
                + " unchanged=" + unchanged
                + (check ? " differing=" + differing : "")
                + " divergences=" + divergences.size());
        return check && differing > 0 ? 1 : 0;
    }

    private record Prepared(EntityModel entity, OrdinalLedger.Ledger nextLedger) {
    }

    private record Status(int written, int unchanged, int differing) {
    }

    private static Status writeOrCompare(Path target, String text, boolean check) throws IOException {
        boolean same = Files.isRegularFile(target) && Files.readString(target).equals(text);
        if (check) {
            if (same) {
                return new Status(0, 1, 0);
            }
            System.out.println("[check] differs: " + target);
            return new Status(0, 0, 1);
        }
        if (same) {
            return new Status(0, 1, 0);
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, text, StandardCharsets.UTF_8);
        return new Status(1, 0, 0);
    }

    private static int usage(String problem) {
        System.err.println("[gen] " + problem);
        printUsage();
        return 2;
    }

    private static void printUsage() {
        System.err.println("""
                usage: HipsterSqlGenerator --source-root <dir> --java-out <dir>
                       [--metadata-out <dir>] [--ledger-out <dir>] [--check] [--allow-reorder]
                       [--gen-update] [--gen-builder] [--gen-meta] [--gen-json] [--gen-mongo]
                       [--gen-annotations] [--classpath <path>] [--packages <list>] [--version]

                  --source-root   the source tree to read
                  --java-out      where generated .java is written
                  --metadata-out  where the entity metadata + manifest is written
                  --ledger-out    where the ordinal ledger is written (default <metadata-out>/ledger)
                  --check         compare instead of writing; exit 1 on a difference
                  --allow-reorder permit a field removal and renumber the ledger densely
                  --gen-update    also emit <Entity>Update
                  --gen-builder   also emit <Entity>Update and <Entity>Builder
                  --gen-meta      also emit <Entity>Meta and AllEntitiesInPackage (implies Update)
                  --gen-json      add the direct Jackson serializer to Immutable and Meta's mapper
                  --gen-mongo     make Meta a MongoEntityMeta with encode/decode (implies Meta)
                  --gen-annotations add written accessor annotations to Meta's ColumnMeta
                  --classpath     jars/dirs used by the simple-name type oracle
                  --packages      comma-separated packages to include
                  --version       print the generator identity
                """);
    }
}
