#!/usr/bin/env node
// Phase 0 evidence probe: reads Zed's own sqlite databases and prints any row that mentions the
// scratch file, so the caret position Zed persisted after `window/showDocument` can be compared
// with the position the stub asked for — evidence from Zed, not from the stub's own log.
//
//   node probe-zed-db.mjs [needle] [dataDir]
//
// Defaults: needle "Sample", dataDir %LOCALAPPDATA%\Zed. Read-only; Zed may keep the DB open.

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { DatabaseSync } from 'node:sqlite';

const needle = process.argv[2] ?? 'Sample';
const dataDir = process.argv[3] ?? path.join(process.env.LOCALAPPDATA ?? path.join(os.homedir(), 'AppData', 'Local'), 'Zed');
const dbRoot = path.join(dataDir, 'db');

if (!fs.existsSync(dbRoot)) {
  console.log(`no db directory at ${dbRoot}`);
  process.exit(0);
}

const databases = [];
for (const entry of fs.readdirSync(dbRoot)) {
  const file = path.join(dbRoot, entry, 'db.sqlite');
  if (fs.existsSync(file)) databases.push({ entry, file, size: fs.statSync(file).size });
}
databases.sort((a, b) => b.size - a.size);

console.log(`data dir : ${dataDir}`);
console.log(`needle   : ${needle}`);
console.log(`databases: ${databases.map((d) => `${d.entry} (${d.size} B)`).join(', ')}`);

for (const database of databases) {
  let db;
  try {
    db = new DatabaseSync(database.file, { readOnly: true });
  } catch (error) {
    console.log(`\n== ${database.entry}: cannot open (${error.message})`);
    continue;
  }

  const tables = db.prepare("select name from sqlite_master where type = 'table' order by name").all().map((r) => r.name);
  console.log(`\n== ${database.entry}: ${tables.length} tables`);

  const hits = [];
  for (const table of tables) {
    let columns;
    try {
      columns = db.prepare(`pragma table_info(${JSON.stringify(table)})`).all().map((c) => c.name);
    } catch {
      continue;
    }
    if (columns.length === 0) continue;
    const textColumns = columns.filter((c) => !/^(id|.*_id|.*_rowid)$/i.test(c));
    if (textColumns.length === 0) continue;
    const where = textColumns.map((c) => `cast(${c} as text) like ?`).join(' or ');
    const params = textColumns.map(() => `%${needle}%`);
    let rows;
    try {
      rows = db.prepare(`select * from ${table} where ${where} limit 10`).all(...params);
    } catch {
      continue;
    }
    for (const row of rows) hits.push({ table, row });
  }

  if (hits.length === 0) {
    console.log('   no rows mention the needle');
  }
  for (const hit of hits) {
    console.log(`   [${hit.table}] ${JSON.stringify(hit.row)}`);
  }
  db.close();
}
