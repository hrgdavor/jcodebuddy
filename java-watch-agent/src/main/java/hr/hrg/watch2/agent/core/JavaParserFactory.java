// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.core;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;

public class JavaParserFactory {
    private static final JavaParser INSTANCE;

    static {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        INSTANCE = new JavaParser(config);
    }

    public static JavaParser getParser() {
        return INSTANCE;
    }
}
