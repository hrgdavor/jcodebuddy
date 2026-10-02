// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.builder;

import org.junit.Test;
import hr.hrg.watch2.core.TransformationResult;
import static org.junit.Assert.*;

public class BuilderTransformationEngineTest {

    @Test
    public void testSurgicalGeneration() {
        BuilderTransformationEngine engine = new BuilderTransformationEngine("  ");
        String code = "public record User(String name) {}";

        TransformationResult result = engine.generate("User.java", code, 1);

        assertNotNull(result);
        assertFalse("Should have at least one edit", result.edits().isEmpty());

        String newCode = result.edits().get(0).newText();
        assertTrue("Output should contain Builder class", newCode.contains("public static class Builder"));
        assertTrue("Output should contain name field", newCode.contains("private String name;"));
    }

    @Test
    public void testEmptyResultOnInvalidSource() {
        BuilderTransformationEngine engine = new BuilderTransformationEngine("  ");
        TransformationResult result = engine.generate("Invalid.java", "this is not java", 1);
        assertTrue(result.edits().isEmpty());
    }
}
