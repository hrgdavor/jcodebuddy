package hr.hrg.hipster.sql.tooling.emit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects the imports a generated file needs from the type text it emits.
 *
 * <p>A type is kept as the source spelled it; the collector only decides which simple names need an
 * {@code import}. It consults the declaring file's single-type import table, skips {@code java.lang}
 * and same-package types, and never invents a name: a simple name with no import stays unresolved
 * and will fail compilation visibly rather than be guessed into another package.</p>
 */
public final class ImportCollector {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private final String packageName;
    private final SortedSet<String> imports = new TreeSet<>();

    public ImportCollector(String packageName) {
        this.packageName = packageName == null ? "" : packageName;
    }

    /**
     * Adds every imported simple name used in {@code typeText}. A token preceded by {@code .} is
     * part of an already-qualified name and is skipped.
     */
    public void collect(String typeText, Map<String, String> importTable) {
        if (typeText == null || typeText.isEmpty() || importTable.isEmpty()) {
            return;
        }
        Matcher matcher = IDENTIFIER.matcher(typeText);
        while (matcher.find()) {
            int start = matcher.start();
            if (start > 0 && typeText.charAt(start - 1) == '.') {
                continue;
            }
            String token = matcher.group();
            if (!Character.isUpperCase(token.charAt(0))) {
                continue;
            }
            String fqn = importTable.get(token);
            if (fqn == null || fqn.startsWith("java.lang.")) {
                continue;
            }
            int dot = fqn.lastIndexOf('.');
            String pkg = dot < 0 ? "" : fqn.substring(0, dot);
            if (!pkg.equals(packageName)) {
                imports.add(fqn);
            }
        }
    }

    /** Adds a fixed import the emitter knows it needs, under the same java.lang/package rules. */
    public void add(String fqn) {
        if (fqn == null || fqn.startsWith("java.lang.")) {
            return;
        }
        int dot = fqn.lastIndexOf('.');
        String pkg = dot < 0 ? "" : fqn.substring(0, dot);
        if (!pkg.equals(packageName)) {
            imports.add(fqn);
        }
    }

    /** The imports in sorted order, so two passes over one entity write identical bytes. */
    public List<String> imports() {
        return new ArrayList<>(imports);
    }
}
