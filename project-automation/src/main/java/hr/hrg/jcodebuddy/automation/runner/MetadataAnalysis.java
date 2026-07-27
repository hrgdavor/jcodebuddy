package hr.hrg.jcodebuddy.automation.runner;

import hr.hrg.watch2.server.metadata.MetadataProvider;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class MetadataAnalysis {
    private final Path projectRoot;
    private final MetadataProvider provider;

    public MetadataAnalysis(Path projectRoot, MetadataProvider provider) {
        this.projectRoot = projectRoot;
        this.provider = provider;
    }

    public void scan() throws IOException {
        Files.walk(projectRoot)
            .filter(p -> p.toString().endsWith(".java"))
            .forEach(path -> {
                try {
                    String relPath = projectRoot.relativize(path).toString().replace('\\', '/');
                    String checksum = sha1(path);
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("checksum", checksum);
                    meta.put("path", relPath);
                    MetadataProvider.CacheEntry entry = new MetadataProvider.CacheEntry(
                        checksum, null, relPath, meta);
                    provider.get(relPath);
                    if (provider instanceof InMemoryMetadataCacheProvider im) {
                        im.put(checksum, entry);
                        im.put(relPath, entry);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
    }

    private static String sha1(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream is = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) md.update(buf, 0, n);
            }
            return String.format("%040x", new BigInteger(1, md.digest()));
        } catch (Exception e) {
            return "unknown";
        }
    }
}
