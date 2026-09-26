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
 * tests nobody chose to run — and this module holds the generated-code parser, which is the vocabulary
 * every other consumer of generated code reads. Being in the gate is a decision, so it is written down.
 */
export const GATE_MODULES = [
  'jcodebuddy-core',
  'hipster-entity-api',
  'hipster-entity-core',
  'hipster-entity-tooling',
  'hipster-entity-jackson',
  'hipster-entity-test',
  'hipster-entity-example',
].join(',');

/**
 * F-47's other half: `clean` removes yesterday's class files, and this stops the compiler plugin from deciding
 * *within* one run that a module's sources are up to date. A gate that can be satisfied by a previous revision's
 * classes is worth less than the seconds it costs.
 */
export const INCREMENTAL_OFF = '-Dmaven.compiler.useIncrementalCompilation=false';

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
    // Free-form: the caller's own arguments, with the JDK pinned and the incremental path disabled.
    const bad = splitProperty(argv);
    return bad
      ? { args: [], shortcut, error: bad }
      : { args: [INCREMENTAL_OFF, ...argv], shortcut, error: null };
  }

  const rest = argv[0] === 'hipster-entity' ? argv.slice(1) : argv;
  const bad = splitProperty(rest);
  if (bad) {
    return { args: [], shortcut, error: bad };
  }
  const goals = rest.length === 0 ? defaultGoals : rest;
  return { args: ['-o', '-pl', modules, '-am', INCREMENTAL_OFF, ...goals], shortcut, error: null };
}
