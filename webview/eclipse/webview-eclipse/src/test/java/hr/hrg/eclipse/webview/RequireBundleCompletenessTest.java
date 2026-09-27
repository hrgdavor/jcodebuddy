package hr.hrg.eclipse.webview;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The manifest's Require-Bundle list, checked against the code: every platform bundle the main
 * classes import from must be required, and the full set of twelve must be present. The check
 * reads the manifest text, not a parsed OSGi header, because the test runs without the platform.
 *
 * <p>The 2026 platform relocation means packages do not map to bundles by name — IPath lives in
 * equinox.common, not core.runtime, and IDocument in org.eclipse.text, not jface.text — so each
 * import is resolved by hand, most specific first.
 */
class RequireBundleCompletenessTest {

    private static final Set<String> ALL_BUNDLES = Set.of(
            "org.eclipse.swt.win32.win32.x86_64",
            "org.eclipse.ui.workbench",
            "org.eclipse.ui.workbench.texteditor",
            "org.eclipse.ui.ide",
            "org.eclipse.jface",
            "org.eclipse.jface.text",
            "org.eclipse.text",
            "org.eclipse.core.commands",
            "org.eclipse.core.resources",
            "org.eclipse.core.runtime",
            "org.eclipse.equinox.common",
            "org.eclipse.osgi");

    @Test
    void everyImportedPlatformBundleIsRequired() throws IOException {
        Set<String> required = Set.copyOf(requiredBundles());
        for (Path source : sources()) {
            for (String line : Files.readAllLines(source)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import org.eclipse") || trimmed.startsWith("import org.osgi")) {
                    String importName = trimmed.substring("import ".length(), trimmed.length() - 1);
                    String bundle = bundleFor(importName);
                    if (bundle == null) {
                        continue;
                    }
                    if (!required.contains(bundle)) {
                        fail("the import " + importName + " needs bundle " + bundle
                                + ", which the manifest does not require");
                    }
                }
            }
        }
    }

    @Test
    void allTwelveBundlesAreRequired() throws IOException {
        Set<String> required = Set.copyOf(requiredBundles());
        for (String bundle : ALL_BUNDLES) {
            assertTrue(required.contains(bundle), "the manifest does not require " + bundle);
        }
    }

    /**
     * The bundle a main-class import comes from, or null when the import is not a platform one.
     * Most specific first, because the relocation made package names and bundle names diverge.
     */
    private static String bundleFor(String importName) {
        if (importName.equals("org.eclipse.core.runtime.Plugin")) {
            return "org.eclipse.core.runtime";
        }
        if (importName.startsWith("org.eclipse.core.runtime.")) {
            // IPath, IAdaptable and CoreException live in equinox.common, not core.runtime.
            return "org.eclipse.equinox.common";
        }
        if (importName.startsWith("org.eclipse.jface.text.IDocument")) {
            return "org.eclipse.text";
        }
        if (importName.startsWith("org.eclipse.jface.text.")) {
            return "org.eclipse.jface.text";
        }
        if (importName.startsWith("org.eclipse.jface.")) {
            return "org.eclipse.jface";
        }
        if (importName.startsWith("org.eclipse.ui.texteditor.")) {
            return "org.eclipse.ui.workbench.texteditor";
        }
        if (importName.equals("org.eclipse.ui.part.ViewPart")) {
            return "org.eclipse.ui.workbench";
        }
        if (importName.startsWith("org.eclipse.ui.part.")) {
            return "org.eclipse.ui.ide";
        }
        if (importName.startsWith("org.eclipse.ui.ide.")) {
            return "org.eclipse.ui.ide";
        }
        if (importName.startsWith("org.eclipse.ui.")) {
            return "org.eclipse.ui.workbench";
        }
        if (importName.startsWith("org.eclipse.text.")) {
            return "org.eclipse.text";
        }
        if (importName.startsWith("org.eclipse.core.commands.")) {
            return "org.eclipse.core.commands";
        }
        if (importName.startsWith("org.eclipse.core.resources.")) {
            return "org.eclipse.core.resources";
        }
        if (importName.startsWith("org.eclipse.equinox.common.")) {
            return "org.eclipse.equinox.common";
        }
        if (importName.startsWith("org.eclipse.swt.")) {
            return "org.eclipse.swt.win32.win32.x86_64";
        }
        if (importName.startsWith("org.osgi.framework.")) {
            return "org.eclipse.osgi";
        }
        return null;
    }

    /** The bundle names in the manifest's Require-Bundle header, continuation lines included. */
    private static List<String> requiredBundles() throws IOException {
        Path manifest = Path.of("src/main/resources/META-INF/MANIFEST.MF");
        List<String> raw = Files.readAllLines(manifest);
        List<String> entries = new ArrayList<>();
        boolean inRequire = false;
        for (String line : raw) {
            String trimmed = line.trim();
            if (!inRequire && trimmed.startsWith("Require-Bundle:")) {
                inRequire = true;
                String rest = trimmed.substring("Require-Bundle:".length()).trim();
                if (!rest.isEmpty()) {
                    entries.add(rest);
                }
                continue;
            }
            if (inRequire) {
                if (line.startsWith(" ")) {
                    entries.add(trimmed);
                } else {
                    inRequire = false;
                }
            }
        }
        List<String> bundles = new ArrayList<>();
        for (String entry : entries) {
            for (String piece : entry.split(",")) {
                String name = piece.trim();
                int semi = name.indexOf(';');
                if (semi >= 0) {
                    name = name.substring(0, semi).trim();
                }
                if (!name.isEmpty()) {
                    bundles.add(name);
                }
            }
        }
        return bundles;
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> stream = Files.walk(Path.of("src/main/java"))) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        }
    }
}
