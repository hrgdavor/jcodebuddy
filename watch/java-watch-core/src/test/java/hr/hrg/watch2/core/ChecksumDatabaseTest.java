// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.core;

import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChecksumDatabaseTest {

    @Test
    public void testDatabaseFormat() throws IOException {
        File tempFile = File.createTempFile("test-db", ".scpdb");
        try {
            ChecksumDatabase db = new ChecksumDatabase();
            long now = System.currentTimeMillis();
            db.setChecksum("src/Main.java", "123456789", now, 1000L);
            db.setChecksum("README.md", "987654321", now, 500L);

            db.save(tempFile);

            String content = Files.readString(tempFile.toPath());
            assertTrue("Format should use '\\t' separator: " + content,
                    content.contains("123456789\t" + now + "\t1000\tsrc/Main.java"));
            assertTrue("Format should use '\\t' separator: " + content,
                    content.contains("987654321\t" + now + "\t500\tREADME.md"));

            ChecksumDatabase db2 = new ChecksumDatabase();
            db2.load(tempFile);

            assertTrue(db2.needsUpload(null, "src/Main.java", "different", false, ChecksumDatabase.CheckMode.hash));
            assertFalse(db2.needsUpload(null, "src/Main.java", "123456789", false, ChecksumDatabase.CheckMode.hash));
            assertFalse(db2.needsUpload(null, "README.md", "987654321", false, ChecksumDatabase.CheckMode.hash));

        } finally {
            tempFile.delete();
        }
    }
}
