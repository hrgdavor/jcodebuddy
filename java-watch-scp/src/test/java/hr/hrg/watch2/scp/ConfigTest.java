// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.scp;

import org.junit.Test;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import static org.junit.Assert.assertEquals;

public class ConfigTest {

    @Test
    public void testOverridePriority() throws IOException {
        // 1. Create a temporary config file
        File tempConfig = File.createTempFile("sync-test", ".conf");
        try (FileWriter writer = new FileWriter(tempConfig)) {
            writer.write("host = config-host\n");
            writer.write("username = config-user\n");
            writer.write("password = config-pass\n");
            writer.write("[folder]\n");
            writer.write("local_dir = ./src\n");
        }

        // 2. Initial config with CLI overrides
        WatchScpConfig config = new WatchScpConfig();
        config.setHost("cli-host");
        config.setUsername("cli-user");
        // Password left empty to be filled by config

        // 3. Load from file
        WatchScpConfig.load(tempConfig.getAbsolutePath(), config);

        // 4. Verify CLI overrides took precedence for host and user, but password came
        // from config
        assertEquals("cli-host", config.getHost());
        assertEquals("cli-user", config.getUsername());
        assertEquals("config-pass", config.getPassword());

        tempConfig.delete();
    }

    @Test
    public void testHostPortParsing() {
        WatchScpConfig config = new WatchScpConfig();
        String arg = "example.com:2222";
        int colonIdx = arg.indexOf(':');
        if (colonIdx != -1) {
            config.setHost(arg.substring(0, colonIdx));
            config.setPort(Integer.parseInt(arg.substring(colonIdx + 1)));
        } else {
            config.setHost(arg);
        }

        assertEquals("example.com", config.getHost());
        assertEquals(2222, config.getPort());
    }

    @Test
    public void testSshConfigResolution() throws IOException {
        // Create a temporary directory to act as "home"
        File tempHome = new File(System.getProperty("java.io.tmpdir"), "sync-test-home-" + System.currentTimeMillis());
        tempHome.mkdirs();
        File sshDir = new File(tempHome, ".ssh");
        sshDir.mkdirs();
        File sshConfig = new File(sshDir, "config");

        try (FileWriter writer = new FileWriter(sshConfig)) {
            writer.write("Host myalias\n");
            writer.write("    HostName 1.2.3.4\n");
            writer.write("    User myuser\n");
            writer.write("    Port 2222\n");
            writer.write("    IdentityFile ~/.ssh/test_key\n");
        }

        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempHome.getAbsolutePath());
            WatchScpConfig config = new WatchScpConfig();
            config.setHost("myalias");
            config.resolveSshConfig();

            assertEquals("1.2.3.4", config.getHost());
            assertEquals("myuser", config.getUsername());
            assertEquals(2222, config.getPort());
            assertEquals(new File(tempHome, ".ssh/test_key").getAbsolutePath().replace('\\', '/').replace("//", "/"),
                    config.getKeyPath());
        } finally {
            System.setProperty("user.home", originalHome);
            // Cleanup omitted for brevity, it's a temp dir
        }
    }
}
