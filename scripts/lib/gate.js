/**
 * The recorded gate, as a value: the module set, the flag that keeps it honest, and how an argument list becomes a
 * Maven command line.
 *
 * It lives in its own file because three scripts need exactly this and used to spell it out separately
 * (`mvn-jdk25.cmd`, `gen.cmd`, `run-demo.cmd`), which is how a gate drifts: the follow-up notes' D-21 added
 * `clean` to one launcher and not its POSIX twin, and the "gate" quietly stopped being the gate. One definition,
 * imported by all three, is the structural fix; `GateContractTest` asserts the constants below are still what the
 * notes recorded.
 */

/**
 * The module set the `hipster-entity` shortcut expands to. A Maven profile cannot narrow a reactor, so
 * `-pl` is the mechanism.
 *
 * `jcodebuddy-core` is named rather than left to arrive transitively through `hipster-entity-tooling`'s
 * dependency on it. It would be built either way, but a module reached only as a dependency is one whose
 * tests nobody chose to run — and this module is the engine every consumer reads. Being in the gate is a
 * decision, so it is written down.
 *
 * `jcodebuddy-generated` is named for the same reason, and it is the sharpest case of it: it holds the
 * generated-code vocabulary and its parser (DEC-035), the one thing in this reactor that a tool outside the
 * reactor reads without wanting anything else. Step 3.0l made it a leaf so that stays true; the gate keeps
 * its tests running now that it is no longer a package inside a module that is already in the set.
 *
 * **Step 3.0k added the migrated consumers, and the test for whether a module belongs here is whether it holds
 * part of the engine's contract:**
 *
 * - `jcodebuddy-meta` owns the provider contract every metadata client reads
 *   (`MetadataProvider`/`CacheEntry`), and since 3.0j its `parse` default is the engine-backed one — a change
 *   that breaks the engine's parse or the provider's shape must fail here rather than in a consumer's build.
 * - `jcodebuddy-meta-mcp` is the MCP tool surface over that provider, which is what a client actually calls.
 * - `project-automation` is the dev-time pass, and since 3.0j it is the first caller of the per-file cache
 *   (DEC-041) — the module where "a warm rebuild parses nothing" stops being an engine-level claim.
 * - `hipster-ioc-api` and `hipster-ioc-tooling` are the generator's API and its model-driven implementation
 *   (step 3.0e part two): the generator parses nothing now, so the tooling's tests are the ones that notice if
 *   the model stops answering what it used to. **`hipster-ioc-api` has no test classes** and is here for its
 *   build: it is the API the generated source implements against.
 * - `hipster-ioc-test` has no test classes either, and is in the set for its **compile**: it is where generated
 *   context source lands, so a generator change that emits something that does not compile fails here. Two
 *   modules in for their compile is a deliberate price, not an oversight — a module that only builds still fails
 *   the gate when the code it holds stops compiling.
 *
 * **The cost, stated rather than implied:** a larger gate is a slower gate. These were chosen because a change
 * that breaks them is a change to the engine's contract, not because they compile — the webview, watch,
 * `merge-java` and arena families stay out for exactly that reason, and so do the two `jcodebuddy-builder*`
 * codegen modules that step 3.0n renamed into the family: a JCodeBuddy name is not the test, holding the engine's
 * contract is.
 */
export const GATE_MODULES = [
  'jcodebuddy-core',
  'jcodebuddy-generated',
  'jcodebuddy-meta',
  'jcodebuddy-meta-mcp',
  'hipster-entity-api',
  'hipster-entity-core',
  'hipster-entity-tooling',
  'hipster-entity-jackson',
  'hipster-entity-test',
  'hipster-entity-example',
  'hipster-ioc-api',
  'hipster-ioc-tooling',
  'hipster-ioc-test',
  'project-automation',
].join(',');

/**
 * F-47's other half: `clean` removes yesterday's class files, and this stops the compiler plugin from deciding
 * *within* one run that a module's sources are up to date. A gate that can be satisfied by a previous revision's
 * classes is worth less than the seconds it costs.
 */
export const INCREMENTAL_OFF = '-Dmaven.compiler.useIncrementalCompilation=false';

/**
 * The build cache (`.mvn/extensions.xml`) is turned off for the gate, explicitly and on every invocation.
 *
 * A cache hit restores a previous revision's outputs, so it is the same *class* of hazard `clean` and
 * {@link INCREMENTAL_OFF} exist to prevent, and "the cache is content-hashed, so a hit is sound" is an argument
 * the gate must not have to win. Iterating is what `.mvn/` is for (`bun scripts/mvn-fast.js`); the recorded gate
 * stays the thing that verifies a commit with no reuse at all.
 */
export const BUILD_CACHE_OFF = '-Dmaven.build.cache.enabled=false';

/**
 * The `-pl` selector list for a module-name list.
 *
 * The modules moved into group folders (`hipster-entity/hipster-entity-api`, `jcodebuddy/jcodebuddy-core`),
 * so a bare directory name no longer resolves from the root. `-pl` accepts `[groupId]:artifactId`, which is
 * location-independent — and it keeps `GATE_MODULES` the recorded list of *names* that `GateContractTest`
 * asserts, rather than turning a list of modules into a list of paths that every move invalidates.
 */
export function moduleSelectors(modules) {
  return modules.split(',').map((name) => `:${name}`).join(',');
}

/** The recorded default goal list: the gate is `clean test`, and `clean` is not optional. */
export const DEFAULT_GOALS = ['clean', 'test'];

/** A `-D` token with no `=` means a shell split a property before the script saw it. */
export function splitProperty(args) {
  return args.find((arg) => arg.startsWith('-D') && !arg.includes('='));
}

/** The message that refusal prints, kept next to the rule so the two cannot disagree. */
export function splitPropertyAdvice(script, fragment) {
  return [
    `[${script}] ERROR: '${fragment}' is a -D token with no value: a shell split the property`,
    `[${script}]   before this script saw it. Quote it at the call site, and give boolean properties a value:`,
    `[${script}]     bun scripts/mvn-jdk25.js hipster-entity test "-Dtest=SomeTest"`,
    `[${script}]     bun scripts/mvn-jdk25.js hipster-entity package "-DskipTests=true"`,
    `[${script}]   Refusing to run rather than silently building the whole reactor.`,
  ].join('\n');
}

/**
 * Turns a script's arguments into a Maven argument list.
 *
 * @param {string[]} argv the arguments after the script name
 * @param {object} [options] `modules` overrides the set; `defaultGoals` overrides `clean test`
 * @returns {{args: string[], shortcut: boolean, error: string|null}}
 */
export function buildGateArgs(argv, options = {}) {
  const modules = options.modules ?? GATE_MODULES;
  const defaultGoals = options.defaultGoals ?? DEFAULT_GOALS;

  const shortcut = argv.length === 0 || argv[0] === 'hipster-entity';
  if (!shortcut) {
    // Free-form: the caller's own arguments, with the JDK pinned, the incremental path disabled and the build
    // cache off. Callers that want the cache are asking for the fast path, not the gate.
    const bad = splitProperty(argv);
    return bad
      ? { args: [], shortcut, error: bad }
      : { args: [INCREMENTAL_OFF, BUILD_CACHE_OFF, ...argv], shortcut, error: null };
  }

  const rest = argv[0] === 'hipster-entity' ? argv.slice(1) : argv;
  const bad = splitProperty(rest);
  if (bad) {
    return { args: [], shortcut, error: bad };
  }
  const goals = rest.length === 0 ? defaultGoals : rest;
  return {
    args: ['-o', '-pl', moduleSelectors(modules), '-am', INCREMENTAL_OFF, BUILD_CACHE_OFF, ...goals],
    shortcut,
    error: null,
  };
}
