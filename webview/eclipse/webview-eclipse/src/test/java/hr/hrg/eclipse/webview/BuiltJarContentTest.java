package hr.hrg.eclipse.webview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

/**
 * The built bundle, read as a zip: the manifest carries the identity the dropins install needs,
 * the jar embeds the core and Gson at its classes' own locations, and the descriptor sits at its
 * OSGi location. The jar is built at process-test-classes, so it exists by the time this test
 * runs — which is exactly why the build departs from the plan's prepare-package unpacking.
 */
class BuiltJarContentTest {

    private static final Path JAR = Path.of("target/webview-eclipse-1.0-SNAPSHOT.jar");

    private static final String[] REQUIRED_BUNDLES = {
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
            "org.eclipse.osgi"
    };

    @Test
    void theJarExists() {
        assertTrue(Files.isRegularFile(JAR), "the bundle jar was not built: " + JAR);
    }

    @Test
    void theManifestCarriesTheBundleIdentity() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
            assertTrue(manifest != null, "the manifest is missing from the jar");
            // The jar plugin re-wraps long header values at 72 bytes, splitting names across
            // continuation lines, so the attributes are read the way a manifest parser reads
            // them — joined values, not raw text.
            try (InputStream in = zip.getInputStream(manifest)) {
                Attributes main = new Manifest(in).getMainAttributes();
                assertEquals("hr.hrg.eclipse.webview;singleton:=true",
                        main.getValue("Bundle-SymbolicName"));
                assertEquals("1.0.0", main.getValue("Bundle-Version"));
                assertEquals("hr.hrg.eclipse.webview.WebViewPlugin",
                        main.getValue("Bundle-Activator"));
                assertEquals("JavaSE-21", main.getValue("Bundle-RequiredExecutionEnvironment"));
                Set<String> required = new HashSet<>();
                for (String piece : main.getValue("Require-Bundle").split("[,\\s]+")) {
                    int semi = piece.indexOf(';');
                    required.add(semi >= 0 ? piece.substring(0, semi) : piece);
                }
                for (String bundle : REQUIRED_BUNDLES) {
                    assertTrue(required.contains(bundle), "the manifest does not require " + bundle);
                }
            }
        }
    }

    @Test
    void theJarEmbedsTheCoreAndGson() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            assertTrue(hasEntry(zip, "hr/hrg/webview/core/HostHealth.class"),
                    "webview-core is not embedded in the jar");
            assertTrue(hasEntry(zip, "com/google/gson/Gson.class"), "Gson is not embedded in the jar");
        }
    }

    @Test
    void theDescriptorIsAtItsOsgiLocation() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            assertTrue(hasEntry(zip, "plugin.xml"), "plugin.xml is not at the jar root");
        }
    }

    @Test
    void theJarHasNoModuleInfo() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            assertTrue(!hasEntry(zip, "module-info.class"), "module-info.class must not be in the jar");
        }
    }

    private static boolean hasEntry(ZipFile zip, String name) {
        return zip.getEntry(name) != null;
    }
}
