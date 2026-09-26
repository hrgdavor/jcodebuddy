#!/usr/bin/env bun
import { readFileSync, realpathSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { parseArgs } from 'node:util';

// --- Normalize PowerShell args (-Port -> --port, -CheckRefusals -> --checkRefusals) ---
const rawArgs = Bun.argv.slice(2).flatMap((arg) => {
  if (arg.startsWith('-') && !arg.startsWith('--')) {
    const key = arg.slice(1);
    // Convert -Port -> --port, -CheckRefusals -> --checkRefusals
    const camelKey = key.charAt(0).toLowerCase() + key.slice(1);
    return [`--${camelKey}`];
  }
  return [arg];
});

// A line-continuation character from the wrong shell arrives as a positional argument, and "Unexpected argument
// '^'" does not tell the reader that the fix is to join the lines or use their own shell's character. This is the
// one mistake every PowerShell user makes with a command copied out of a `cmd`-flavoured note, so it gets a
// sentence instead of a stack trace.
const continuation = rawArgs.find((arg) => arg === '^' || arg === '`' || arg === '\\');
if (continuation) {
  console.error(`the argument '${continuation}' is a line-continuation character, not an option.`);
  console.error('  cmd.exe uses ^, PowerShell uses ` (backtick), POSIX shells use \\ — and Bun receives whichever');
  console.error('  one your shell did not consume. Easiest fix: put the whole command on one line.');
  console.error('  A single line that works in PowerShell:');
  console.error("    bun run webview/tools/observe-edit-host.js --port 18882 --token obs-token "
    + '--file D:\\tmp\\obs\\Sample.java --find "int x = 1;" --replace "int x = 42;" '
    + '--checkRefusals --watchSeconds 20');
  process.exit(2);
}

// --- CLI Parsing ---------------------------------------------------------------------------------
const { values } = parseArgs({
  args: rawArgs,
  options: {
    port: { type: 'string', default: '18882' },
    token: { type: 'string' },
    file: { type: 'string' },
    find: { type: 'string' },
    replace: { type: 'string', default: 'OBSERVED' },
    target: { type: 'string', default: 'auto' },
    watchSeconds: { type: 'string', default: '0' },
    checkRefusals: { type: 'boolean', default: false },
    help: { type: 'boolean', default: false },
  },
  allowPositionals: false,
  strict: true,
});

if (values.help || !values.token || !values.file || !values.find) {
  console.log(`
Usage:
  bun run webview/tools/observe-edit-host.js --port <port> --token <token> --file <file> --find <text> [options]

Options:
  --port <number>         Host HTTP port (default: 18882)
  --token <string>        Host API authentication token (Required)
  --file <path>           File to edit (Required)
  --find <string>         Single-line text to locate (Required)
  --replace <string>      Replacement text (default: OBSERVED)
  --target <type>         auto | buffer | disk (default: auto)
  --watchSeconds <n>      Re-sample the file after n seconds, to tell the host's write from the IDE's own save
                          (default: 0, and worth setting to 20 for an IDE observation)
  --checkRefusals         Also test wrong-token (403) and stale-digest (409) refusals

The older PowerShell spellings (-Port, -CheckRefusals) are still accepted, so a command copied out of an
earlier note keeps working.
`);
  process.exit(values.help ? 0 : 1);
}

const targetLower = values.target.toLowerCase();
if (!['auto', 'buffer', 'disk'].includes(targetLower)) {
  console.error(`Invalid -Target '${values.target}'. Must be one of: auto, buffer, disk`);
  process.exit(1);
}

const port = parseInt(values.port, 10);
const base = `http://127.0.0.1:${port}`;

// --- Helper Functions ----------------------------------------------------------------------------
function show(label, value) {
  console.log(`--- ${label}`);
  const rendered = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
  console.log(rendered.trimEnd());
}

function digestOf(filePath) {
  const fileBuffer = readFileSync(filePath);
  const hash = createHash('sha256').update(fileBuffer).digest('hex').toLowerCase();
  return `sha256:${hash}`;
}

async function invokeHost(route, headers, bodyObj) {
  try {
    const response = await fetch(`${base}${route}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...headers,
      },
      body: JSON.stringify(bodyObj),
    });

    const responseText = await response.text();
    let data;
    try {
      data = JSON.parse(responseText);
    } catch {
      data = responseText;
    }

    if (!response.ok) {
      return { status: response.status, body: data };
    }
    return data;
  } catch (err) {
    return { status: 0, body: `<the host could not be reached: ${err.message}>` };
  }
}

// --- Main Script Execution -----------------------------------------------------------------------
async function main() {
  let fullPath;
  try {
    fullPath = realpathSync(values.file);
  } catch (err) {
    console.error(`Could not resolve file path '${values.file}': ${err.message}`);
    process.exit(1);
  }

  const before = digestOf(fullPath);
  const text = readFileSync(fullPath, 'utf-8');

  if (values.find.includes('\n') || values.find.includes('\r')) {
    console.error(`'${values.find}' spans lines; give a single-line needle`);
    process.exit(1);
  }

  const index = text.indexOf(values.find);
  if (index < 0) {
    console.error(`'${values.find}' does not appear in ${fullPath}`);
    process.exit(1);
  }

  const lineStart = text.lastIndexOf('\n', index - 1);
  const lineIndex = text.substring(0, index).split('\n').length - 1;
  const column = index - (lineStart < 0 ? -1 : lineStart);
  const endColumn = column + values.find.length;

  console.log(`host ${base}`);
  console.log(`file ${fullPath}`);
  console.log(`disk digest before  ${before}`);
  console.log(`edit: line ${lineIndex + 1}, columns ${column}..${endColumn}  '${values.find}' -> '${values.replace}'\n`);

  // --- Health Check ---
  try {
    const res = await fetch(`${base}/health`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const healthData = await res.json().catch(() => res.text());
    show('health', healthData);
  } catch (err) {
    console.error(`no host answered on ${base}/health - is the IDE running with its bridge on? (${err.message})`);
    process.exit(1);
  }

  const headers = { 'X-WebView-Token': values.token };
  const edit = {
    startLine: lineIndex + 1,
    startColumn: column,
    endLine: lineIndex + 1,
    endColumn: endColumn,
    newText: values.replace,
  };

  const normalizePath = (p) => p.replace(/\\/g, '/');

  const diffPayload = {
    filePath: normalizePath(fullPath),
    expectedDigest: before,
    edits: [edit],
    target: targetLower,
  };

  const applyPayload = {
    ...diffPayload,
    dryRun: false,
  };

  show('proposal (dryRun, nothing should change)', await invokeHost('/api/v1/diff', headers, diffPayload));

  const answer = await invokeHost('/api/v1/applyEdit', headers, applyPayload);
  show('applyEdit', answer);

  await Bun.sleep(300);

  const after = digestOf(fullPath);
  console.log('\ndisk digest immediately after  ' + after);

  // The IDE's own saving is the one thing that makes this observation confusing. IntelliJ saves modified
  // documents when its frame loses focus and when it goes idle, and running this script from a terminal is a
  // focus change - so the file can be written a few seconds *after* the host answered "buffer", by the editor
  // rather than by the host. The first JetBrains observation of gate (d) hit exactly that: the host said
  // buffer, the disk was unchanged, and then the IDE saved it. Sampling twice is what tells the two apart.
  const watchSeconds = parseInt(values.watchSeconds, 10) || 0;
  let savedLater = false;
  let later = null;
  if (watchSeconds > 0) {
    console.log(`waiting ${watchSeconds}s to see whether the IDE's own save writes it...`);
    await Bun.sleep(watchSeconds * 1000);
    later = digestOf(fullPath);
    savedLater = later !== after;
    console.log(`disk digest after ${watchSeconds}s        ${later}${savedLater ? '   <- moved' : ''}`);
  }

  if (values.checkRefusals) {
    console.log('');
    show(
      'applyEdit with a WRONG token (expect 403)',
      await invokeHost('/api/v1/applyEdit', { 'X-WebView-Token': 'definitely-wrong' }, applyPayload)
    );

    const stalePayload = {
      ...applyPayload,
      expectedDigest: 'sha256:' + '0'.repeat(64),
    };
    show('applyEdit with a STALE digest (expect 409 stale)', await invokeHost('/api/v1/applyEdit', headers, stalePayload));
  }

  const declaredTarget = answer && typeof answer === 'object' && answer.target ? answer.target : null;

  console.log('');
  if (before === after) {
    console.log('DISK UNCHANGED when the host answered - the host did not write the file.');
    if (savedLater) {
      console.log("The bytes moved only afterwards, so the IDE's own save wrote them: IntelliJ saves on frame");
      console.log('deactivation and when idle, and running this from a terminal is a focus change. That is the');
      console.log("editor saving, which the contract allows - 'unchanged until the editor saves'.");
    }
    if (declaredTarget === 'buffer') {
      console.log(`The host declared target 'buffer', so this is the buffer path: the change is in the editor,`);
      console.log('unsaved as far as the host is concerned, and in the editor\'s own undo stack.');
    }
    console.log('');
    console.log('NOW LOOK AT THE IDE:');
    console.log("  1. the file's editor shows the replacement, and the tab is marked modified");
    console.log('  2. one undo (Ctrl+Z) removes it and the tab goes clean again');
  } else {
    console.log('DISK CHANGED - this host wrote the file itself, so there is nothing unsaved to look at.');
    console.log('That is the correct outcome for a host with no editor attached (webviewd --host none), and for');
    console.log("target 'disk' anywhere. A buffer observation needs a host that has an editor: the JetBrains");
    console.log('plugin or the VS Code extension.');
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});