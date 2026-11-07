/**
 * A standalone ECJ incremental compiler for file-change triggered recompiles.
 *
 * <p>The one public entry point is {@link hr.hrg.jcodebuddy.compile.EcjIncrementalCompiler}:
 * construct it with a source root, an output directory and a classpath, call
 * {@link hr.hrg.jcodebuddy.compile.EcjIncrementalCompiler#index()} once, then call
 * {@link hr.hrg.jcodebuddy.compile.EcjIncrementalCompiler#compile(java.util.Set)} with the
 * files that changed.
 *
 * <p>This package is a benchmark and dev-time helper. It starts no watcher and has no
 * dependency on the retired {@code java-watch} modules; the change source is fs-meta,
 * which reports the paths a caller then passes to {@code compile}.
 */
package hr.hrg.jcodebuddy.compile;
