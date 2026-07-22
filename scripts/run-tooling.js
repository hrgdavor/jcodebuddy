/* global Bun */
import { readFileSync, existsSync } from 'fs';
import { dirname, join, resolve } from 'path';
import { fileURLToPath } from 'url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, '..');
const toolingModuleDir = join(repoRoot, 'hipster-entity-tooling');
const toolingPomPath = join(toolingModuleDir, 'pom.xml');

function usage() {
  console.log(`Usage: bun ${process.argv[1]} [options] <input-java-file> <output-directory>

Options:
  --build              Build the tooling jar before execution
  --force-build        Always rebuild the tooling jar even if it already exists
  --java <path>        Use an explicit java executable
  --mvn <path>         Use an explicit Maven executable
  -h, --help           Show this help message

Example:
  bun ${process.argv[1]} hipster-entity-example/src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary.java hipster-entity-tooling/target/person-summary-tooling-output

If the jar is missing, the script will build the tooling module automatically.
`);
}

function parsePomCoordinates(pomText) {
  const lines = pomText.split(/\r?\n/);
  let inParent = false;
  let artifactId = null;
  let version = null;
  let parentVersion = null;

  for (const rawLine of lines) {
    const line = rawLine.trim();

    if (line.startsWith('<parent')) {
      inParent = true;
      continue;
    }
    if (line.startsWith('</parent')) {
      inParent = false;
      continue;
    }

    if (inParent) {
      const parentVersionMatch = line.match(/<version>([^<]+)<\/version>/);
      if (parentVersionMatch) {
        parentVersion = parentVersionMatch[1].trim();
      }
      continue;
    }

    const artifactIdMatch = line.match(/<artifactId>([^<]+)<\/artifactId>/);
    if (artifactIdMatch && !artifactId) {
      artifactId = artifactIdMatch[1].trim();
      continue;
    }
    const versionMatch = line.match(/<version>([^<]+)<\/version>/);
    if (versionMatch && !version) {
      version = versionMatch[1].trim();
    }
  }

  if (!artifactId) {
    throw new Error(`Unable to parse artifactId from ${toolingPomPath}`);
  }
  if (!version) {
    if (!parentVersion) {
      throw new Error(`Unable to parse version from ${toolingPomPath}`);
    }
    version = parentVersion;
  }

  return { artifactId, version };
}

function parseArgs(args) {
  const opts = {
    command: 'run',
    build: false,
    forceBuild: false,
    java: null,
    mvn: null,
    input: null,
    output: null,
  };

  if (args[0] === 'build' || args[0] === 'run') {
    opts.command = args.shift();
  }

  while (args.length > 0) {
    const arg = args.shift();
    switch (arg) {
      case '-h':
      case '--help':
        usage();
        process.exit(0);
      case '--build':
        opts.build = true;
        break;
      case '--force-build':
        opts.forceBuild = true;
        break;
      case '--java':
        opts.java = args.shift();
        break;
      case '--mvn':
        opts.mvn = args.shift();
        break;
      default:
        if (opts.command === 'build') {
          throw new Error(`Unknown extra argument: ${arg}`);
        }
        if (!opts.input) {
          opts.input = arg;
        } else if (!opts.output) {
          opts.output = arg;
        } else {
          throw new Error(`Unknown extra argument: ${arg}`);
        }
    }
  }

  if (opts.command === 'run' && (!opts.input || !opts.output)) {
    usage();
    throw new Error('Missing required input or output path.');
  }

  return opts;
}

function findExecutable(explicitPath, name, fallbacks = []) {
  const candidates = [];
  const isWin = process.platform === 'win32';
  const extNames = isWin ? ['.exe', '.cmd', '.bat', ''] : [''];

  if (explicitPath) {
    const hasExtension = /\.[^\\/.]+$/.test(explicitPath);
    if (isWin && !hasExtension) {
      for (const ext of ['.exe', '.cmd', '.bat']) {
        const candidate = `${explicitPath}${ext}`;
        if (existsSync(candidate)) {
          return candidate;
        }
      }
    }
    return explicitPath;
  }

  if (name === 'mvn') {
    if (process.env.MAVEN_HOME) {
      extNames.forEach((ext) => candidates.push(join(process.env.MAVEN_HOME, 'bin', `${name}${ext}`)));
    }
    if (process.env.M2_HOME) {
      extNames.forEach((ext) => candidates.push(join(process.env.M2_HOME, 'bin', `${name}${ext}`)));
    }
  }

  if (name === 'java') {
    const javaHome = process.env.JAVA_HOME || process.env.JDK_HOME;
    if (javaHome) {
      extNames.forEach((ext) => candidates.push(join(javaHome, 'bin', `java${ext}`)));
    }
  }

  if (fallbacks.length > 0) {
    fallbacks.forEach((fallback) => candidates.push(fallback));
  }

  const pathDirs = process.env.PATH ? process.env.PATH.split(process.platform === 'win32' ? ';' : ':') : [];
  for (const dir of pathDirs) {
    extNames.forEach((ext) => candidates.push(join(dir, `${name}${ext}`)));
  }

  for (const candidate of candidates) {
    if (candidate && existsSync(candidate)) {
      return candidate;
    }
  }

  return name;
}

async function spawnCommand(cmd, args, cwd) {
  const proc = Bun.spawn({
    cmd: [cmd, ...args],
    cwd,
    stdout: 'inherit',
    stderr: 'inherit',
  });
  const result = await proc.exited;
  const exitCode = typeof result === 'object' ? result.exitCode : result;
  if (exitCode !== 0) {
    throw new Error(`${cmd} failed with exit code ${exitCode}`);
  }
}

async function buildToolingJar(mvnExecutable, repoRoot, jarPath, artifactId, version) {
  console.log(`Building tooling jar for ${artifactId}:${version}...`);
  await spawnCommand(mvnExecutable, ['-pl', 'hipster-entity-tooling', '-am', 'package', '-DskipTests'], repoRoot);
  if (!existsSync(jarPath)) {
    throw new Error(`Expected jar not found after build: ${jarPath}`);
  }
}

async function main() {
  if (!existsSync(toolingPomPath)) {
    throw new Error(`Missing tooling pom at ${toolingPomPath}`);
  }

  const opts = parseArgs(process.argv.slice(2));
  const pomText = readFileSync(toolingPomPath, 'utf8');
  const { artifactId, version } = parsePomCoordinates(pomText);
  const jarName = `${artifactId}-${version}.jar`;
  const jarPath = join(toolingModuleDir, 'target', jarName);
  const jarExists = existsSync(jarPath);
  const mvnExecutable = findExecutable(opts.mvn, 'mvn', ['mvnd']);
  const javaExecutable = findExecutable(opts.java, 'java');

  if (opts.command === 'build') {
    if (!jarExists || opts.forceBuild) {
      await buildToolingJar(mvnExecutable, repoRoot, jarPath, artifactId, version);
    } else {
      console.log(`Tooling jar already exists at ${jarPath}`);
    }
    return;
  }

  if (opts.build || opts.forceBuild) {
    await buildToolingJar(mvnExecutable, repoRoot, jarPath, artifactId, version);
  } else if (!jarExists) {
    throw new Error(`Tooling jar not found at ${jarPath}. Run 'bun ${process.argv[1]} build' first.`);
  }

  const inputPath = resolve(repoRoot, opts.input);
  const outputPath = resolve(repoRoot, opts.output);

  console.log(`Running tooling jar: ${jarPath}`);
  console.log(`Input: ${inputPath}`);
  console.log(`Output: ${outputPath}`);

  await spawnCommand(javaExecutable, ['-jar', jarPath, inputPath, outputPath], repoRoot);
  console.log('Tooling execution completed successfully.');
}

main().catch((error) => {
  console.error('ERROR:', error.message ?? error);
  process.exit(1);
});
