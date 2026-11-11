// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.jcodebuddy.compile;

/**
 * Approximate memory use of one shared compiler index. Byte-cache values are exact for the arrays
 * retained; index/package metadata is estimated from entry counts and string lengths, which is accurate
 * enough to see growth and compare configurations.
 *
 * @param indexedTypes    number of dependency type names in the JAR index
 * @param cachedJarBytes  bytes currently held in the bounded JAR hot cache
 * @param cachedJdkBytes  bytes currently held in the JDK class cache
 * @param estimatedBytes  total estimate, including index and package string overhead
 */
public record CompilerMemory(long indexedTypes, long cachedJarBytes, long cachedJdkBytes,
                             long estimatedBytes) {

    /** One-line form for logs, with values in KiB/MiB. */
    public String summary() {
        return "types=" + indexedTypes
                + ", jarHot=" + mib(cachedJarBytes)
                + ", jdk=" + mib(cachedJdkBytes)
                + ", estimated=" + mib(estimatedBytes);
    }

    private static String mib(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1fMiB", bytes / (1024.0 * 1024.0));
    }
}
