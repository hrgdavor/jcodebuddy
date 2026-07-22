#!/usr/bin/env bun
/**
 * Generates person-samples.json — 30 000 PersonSummary records in the exact
 * field order produced by EntityJacksonViewSerializer (enum-ordinal order):
 *   id, firstName, lastName, age, departmentName, metadata
 *
 * The positional deserializer depends on this order; do not reorder.
 */

import { mkdir } from "node:fs/promises";
import path from "node:path";

const rootDir = path.resolve(import.meta.dir, "..");
const outDir  = path.join(rootDir, "hipster-entity-test", "src", "test", "resources", "data");
const outFile = path.join(outDir, "person-samples.json");

const FIRST_NAMES = [
    "Alice", "Bob", "Carol", "David", "Emma",
    "Frank", "Grace", "Henry", "Iris",  "Jack",
    "Kate",  "Liam",  "Maya",  "Noah",  "Olivia",
    "Peter", "Quinn", "Rachel","Sam",   "Tina"
];
const LAST_NAMES = [
    "Smith",   "Johnson", "Williams","Brown",  "Jones",
    "Miller",  "Davis",   "Wilson",  "Taylor", "Anderson",
    "Martin",  "Moore",   "White",   "Harris", "Thompson",
    "Garcia",  "Collins", "Lee",     "Walker", "Hall"
];
const DEPARTMENTS = [
    "Engineering", "Marketing", "Sales",    "Finance",  "HR",
    "Operations",  "Legal",     "Research", "Product",  "Support"
];

const N = 30_000;

const records = [];
for (let i = 1; i <= N; i++) {
    // deterministic values — reproducible across runs, spread across all pools
    const firstName      = FIRST_NAMES[i % FIRST_NAMES.length];
    const lastName       = LAST_NAMES[(i * 3) % LAST_NAMES.length];
    const age            = 20 + ((i * 7 + 13) % 46);          // 20-65
    const departmentName = DEPARTMENTS[(i * 13) % DEPARTMENTS.length];

    // Field order MUST match PersonSummaryField enum ordinal order:
    // 0:id  1:firstName  2:lastName  3:age  4:departmentName  5:metadata
    records.push({ id: i, firstName, lastName, age, departmentName, metadata: null });
}

await mkdir(outDir, { recursive: true });
await Bun.write(outFile, JSON.stringify(records));

const bytes = (await Bun.file(outFile).arrayBuffer()).byteLength;
console.log(`Written ${N.toLocaleString()} records to:`);
console.log(`  ${outFile}`);
console.log(`  Size: ${(bytes / 1024).toFixed(1)} KB`);
