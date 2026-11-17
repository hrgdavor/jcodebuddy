package hr.hrg.hipster.sql.tooling.ledger;

import hr.hrg.hipster.sql.tooling.model.EntityModel;
import hr.hrg.hipster.sql.tooling.model.PropertyModel;
import hr.hrg.jcodebuddy.engine.MetadataJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The append-only ordinal ledger: the one place a property's persisted slot survives a pass.
 *
 * <p>It is deliberately separate from the entity metadata document. The metadata churns when a source
 * line or path moves; the ledger only changes when a field is added or retired, so it is the small,
 * reviewable file a project tracks.</p>
 */
public final class OrdinalLedger {

    private OrdinalLedger() {
    }

    /** One persisted slot. A retired entry keeps the ordinal of a field no longer in the interface. */
    public record Entry(String name, int ordinal, boolean retired) {
    }

    /** The ledger entries, sorted by ordinal. */
    public record Ledger(List<Entry> entries) {
        public Ledger {
            entries = List.copyOf(entries);
        }

        public static Ledger empty() {
            return new Ledger(List.of());
        }

        public int nextOrdinal() {
            return entries.stream().mapToInt(Entry::ordinal).max().orElse(-1) + 1;
        }

        public Map<String, Entry> byName() {
            Map<String, Entry> byName = new LinkedHashMap<>();
            for (Entry entry : entries) {
                byName.put(entry.name(), entry);
            }
            return byName;
        }
    }

    /** The entity with ordinals applied, the ledger to write, and what changed. */
    public record Assigned(EntityModel entity, Ledger next, List<String> divergences, boolean rejected) {
        public Assigned {
            divergences = List.copyOf(divergences);
        }
    }

    /**
     * Reads the ledger for one entity. A missing file is an empty ledger (a new entity); an
     * unreadable file is an error, never a silent bootstrap.
     */
    public static Ledger read(Path ledgerDir, String fqn) throws IOException {
        Path file = fileFor(ledgerDir, fqn);
        if (!Files.isRegularFile(file)) {
            return Ledger.empty();
        }
        ObjectMapper mapper = MetadataJson.mapper();
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("unreadable ledger " + file + ": " + e.getMessage(), e);
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode field : root.path("ledger").path("fields")) {
            entries.add(new Entry(
                    field.path("name").asText(),
                    field.path("ordinal").asInt(),
                    field.path("retired").asBoolean(false)));
        }
        entries.sort(Comparator.comparingInt(Entry::ordinal));
        return new Ledger(entries);
    }

    /**
     * Applies the previous ordinals: an existing field keeps its slot, a new field is appended, and
     * a disappeared field is refused unless the caller explicitly allows a renumbering.
     */
    public static Assigned assign(EntityModel entity, Ledger previous) {
        return assign(entity, previous, false);
    }

    public static Assigned assign(EntityModel entity, Ledger previous, boolean allowReorder) {
        Map<String, Entry> previousByName = previous.byName();
        int nextOrdinal = previous.nextOrdinal();
        boolean bootstrapping = previous.entries().isEmpty();
        List<String> divergences = new ArrayList<>();
        List<PropertyModel> properties = new ArrayList<>();
        Map<String, Entry> nextByName = new LinkedHashMap<>();

        java.util.Set<String> currentNames = new java.util.HashSet<>();
        for (PropertyModel property : entity.properties()) {
            currentNames.add(property.name());
        }
        List<Entry> removed = previous.entries().stream()
                .filter(entry -> !entry.retired() && !currentNames.contains(entry.name()))
                .toList();

        if (!removed.isEmpty() && !allowReorder) {
            for (Entry old : removed) {
                divergences.add("kind=field_removed, location=" + entity.fqn() + "." + old.name()
                        + ", ordinal=" + old.ordinal()
                        + ", action=refused; pass --allow-reorder to renumber");
            }
            return new Assigned(entity, previous, divergences, true);
        }

        if (!removed.isEmpty()) {
            return renumber(entity, previous, removed);
        }

        for (PropertyModel property : entity.properties()) {
            Entry existing = previousByName.get(property.name());
            int ordinal;
            if (bootstrapping) {
                ordinal = properties.size();
            } else if (existing != null && !existing.retired()) {
                ordinal = existing.ordinal();
            } else {
                ordinal = nextOrdinal++;
                divergences.add("kind=field_appended, location=" + entity.fqn() + "."
                        + property.name() + ", ordinal=" + ordinal
                        + ", action=appended (R1)");
            }
            nextByName.put(property.name(),
                    new Entry(property.name(), ordinal, false));
            properties.add(new PropertyModel(ordinal, property.name(), property.getterName(),
                    property.typeText(), property.transient_(), property.keepRest(),
                    property.primary(), property.annotations(), property.initial()));
        }
        if (bootstrapping) {
            divergences.add("kind=ledger_bootstrapped, location=" + entity.fqn()
                    + ", fields=" + properties.size());
        }

        for (Entry old : previous.entries()) {
            if (!nextByName.containsKey(old.name()) && !old.retired()) {
                nextByName.put(old.name(), new Entry(old.name(), old.ordinal(), true));
                divergences.add("kind=field_removed, location=" + entity.fqn() + "." + old.name()
                        + ", ordinal=" + old.ordinal()
                        + ", action=retained as a retired slot; renumbering is not automatic");
            }
        }

        List<Entry> nextEntries = new ArrayList<>(nextByName.values());
        nextEntries.sort(Comparator.comparingInt(Entry::ordinal));
        EntityModel nextEntity = new EntityModel(entity.packageName(), entity.simpleName(),
                entity.tableName(), entity.sourcePath(), entity.supertypes(), entity.imports(),
                properties, entity.skipped());
        return new Assigned(nextEntity, new Ledger(nextEntries), divergences, false);
    }

    /** Explicit renumbering: declaration order becomes the new dense layout. */
    private static Assigned renumber(EntityModel entity, Ledger previous, List<Entry> removed) {
        Map<String, Entry> oldByName = previous.byName();
        List<String> divergences = new ArrayList<>();
        List<PropertyModel> properties = new ArrayList<>();
        List<Entry> next = new ArrayList<>();
        for (PropertyModel property : entity.properties()) {
            int ordinal = properties.size();
            properties.add(new PropertyModel(ordinal, property.name(), property.getterName(),
                    property.typeText(), property.transient_(), property.keepRest(),
                    property.primary(), property.annotations(), property.initial()));
            next.add(new Entry(property.name(), ordinal, false));
            Entry old = oldByName.get(property.name());
            if (old != null && old.ordinal() != ordinal) {
                divergences.add("kind=ordinal_renumbered, location=" + entity.fqn() + "."
                        + property.name() + ", was=" + old.ordinal() + ", now=" + ordinal);
            }
        }
        for (Entry old : removed) {
            divergences.add("kind=field_dropped, location=" + entity.fqn() + "." + old.name()
                    + ", ordinal=" + old.ordinal() + ", action=renumbered by --allow-reorder");
        }
        EntityModel nextEntity = new EntityModel(entity.packageName(), entity.simpleName(),
                entity.tableName(), entity.sourcePath(), entity.supertypes(), entity.imports(),
                properties, entity.skipped());
        return new Assigned(nextEntity, new Ledger(next), divergences, false);
    }

    /** Writes the ledger atomically, so a failed pass cannot leave a half-written contract. */
    public static void write(Path ledgerDir, String fqn, Ledger ledger) throws IOException {
        Files.createDirectories(ledgerDir);
        Path file = fileFor(ledgerDir, fqn);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, render(fqn, ledger), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The stable JSON text, used by both the writer and {@code --check}. */
    public static String render(String fqn, Ledger ledger) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"format\": 1,\n");
        out.append("  \"entity\": ").append(quote(fqn)).append(",\n");
        out.append("  \"ledger\": {\n");
        out.append("    \"version\": 1,\n");
        out.append("    \"fields\": [");
        for (int i = 0; i < ledger.entries().size(); i++) {
            Entry entry = ledger.entries().get(i);
            out.append(i == 0 ? "\n" : ",\n");
            out.append("      { \"name\": ").append(quote(entry.name()))
                    .append(", \"ordinal\": ").append(entry.ordinal());
            if (entry.retired()) {
                out.append(", \"retired\": true");
            }
            out.append(" }");
        }
        if (!ledger.entries().isEmpty()) {
            out.append('\n').append("    ");
        }
        out.append("]\n");
        out.append("  }\n");
        out.append("}\n");
        return out.toString();
    }

    public static Path fileFor(Path ledgerDir, String fqn) {
        return ledgerDir.resolve(fqn.replace('.', '/') + ".ledger.json");
    }

    private static String quote(String value) {
        return "\"" + MetadataJson.escape(value) + "\"";
    }
}
