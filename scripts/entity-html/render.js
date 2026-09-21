/**
 * Renders the entity HTML index: one self-contained page, no external assets, no build step.
 *
 * <h3>The page's job</h3>
 * Answer two questions for every entity in a module, and answer them by clicking:
 *   1. *which artifacts does this entity have?* — one card per aspect (the `@View` interface, its
 *      generated field enum, record, builders, nested types), each opening at its declaration;
 *   2. *where is this field in each of them?* — a field-by-aspect table whose cells open the exact
 *      line of that member in that artifact.
 *
 * <h3>The link contract</h3>
 * Links are `data-*` attributes, not `href`s: the target path is relative to the link base, and a
 * real absolute path only exists once the page knows where it was loaded from — which is exactly what
 * the small script below computes from `location`. That single choice is what makes the page work
 * whether the IDE project root is the module or the monorepo above it (see DEC-027).
 *
 * In the JetBrains WebView Explorer the page is loaded into JCEF and the plugin injects
 * `window.openFile(path, line, column)` after every load; that is the primary path. When the page is
 * opened in an ordinary browser, or the bridge is not present, the HTTP endpoint
 * (`http://127.0.0.1:<port>/open?filePath=…`) is tried, and failing that the location is copied to
 * the clipboard so nothing is lost.
 */
import { ROLE_ORDER, ROLE_LABELS } from './links.js';

const ROLE_HINT = {
  accessor: 'the read side: the method a caller uses to read the field',
  annotation: 'the @FieldSource declaration, where a DERIVED/JOINED field is defined',
  'enum-constant': 'the field enum constant — the persisted ordinal (DEC-023 ledger)',
  'record-component': 'the record component in the immutable materialization',
  setter: 'the write side: a fluent setter or Write method',
  field: 'the field declaration the generated code stores the value in',
  'ordinal-slot': 'the switch arm that maps this ordinal to the field',
  'name-slot': 'the switch arm that maps this name to the enum constant',
};

/** HTML-escapes text for both element content and attribute values. */
export function esc(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** One clickable attribute group: the path, the line, and what is there. */
function linkAttrs(link) {
  return `data-open="${esc(link.path)}" data-line="${esc(link.line)}" data-member="${esc(link.member)}"`
    + ` data-role="${esc(link.role)}" data-where="${esc(link.where)}"`;
}

function aspectCard(aspect) {
  const kind = aspect.generated ? 'generated' : 'hand-written';
  const description = aspect.description ? `<div class="aspect-desc">${esc(aspect.description)}</div>` : '';
  return `<a class="aspect ${aspect.generated ? 'is-generated' : ''}" data-open="${esc(aspect.path)}"`
    + ` data-line="${esc(aspect.line)}" data-member="${esc(aspect.name)}" data-role="type"`
    + ` data-where="${esc(aspect.fqcn)}" title="open ${esc(aspect.fqcn)} at ${esc(aspect.path)}:${esc(aspect.line)}">`
    + `<div class="aspect-head"><span class="aspect-name">${esc(aspect.name)}</span>`
    + `<span class="kind kind-${esc(aspect.kind)}">${esc(aspect.kind)}</span>`
    + `<span class="kind kind-${aspect.generated ? 'generated' : 'manual'}">${kind}</span></div>`
    + description
    + `<div class="aspect-path">${esc(aspect.path)}:${esc(aspect.line)}</div>`
    + '</a>';
}

function fieldRow(field, columns) {
  const chips = [];
  if (field.fieldKind) {
    chips.push(`<span class="chip chip-${esc(field.fieldKind.toLowerCase())}">${esc(field.fieldKind)}</span>`);
  }
  if (field.column && field.column !== field.name) {
    chips.push(`<span class="chip">column ${esc(field.column)}</span>`);
  }
  if (field.relation) {
    chips.push(`<span class="chip" title="relation">&#8594; ${esc(field.relation)}</span>`);
  }
  if (field.expression) {
    chips.push(`<span class="chip chip-expr" title="expression">${esc(field.expression)}</span>`);
  }
  if (!field.declaredIn) {
    chips.push('<span class="chip chip-inherited" title="declared outside this source root">inherited</span>');
  }
  if (field.inLedger === false) {
    chips.push('<span class="chip chip-no-ledger" title="the metadata attributes this field to the view, '
      + 'but its field enum has no constant for it">not in ledger</span>');
  }

  const nameCell = field.declaredPath
    ? `<a class="field-name" data-open="${esc(field.declaredPath)}" data-line="${esc(field.declaredLine)}"`
      + ` data-member="${esc(field.name)}" data-role="accessor" data-where="${esc(field.declaredIn)}"`
      + ` title="open the declaring accessor in ${esc(field.declaredIn)}">${esc(field.name)}</a>`
    : `<span class="field-name no-link">${esc(field.name)}</span>`;

  const cells = columns.map((column) => {
    const link = field.links.get(column.id);
    if (!link) {
      return '<td class="empty" title="this field has no such slot in this artifact">&#183;</td>';
    }
    const hint = ROLE_HINT[column.role] ?? column.role;
    return `<td class="filled"><a class="cell" ${linkAttrs(link)}`
      + ` title="${esc(`${column.artifact} — ${hint}`)}\n${esc(`${link.path}:${link.line}`)}">`
      + `<span class="cell-role">${esc(column.roleLabel)}</span>`
      + `<span class="cell-line">${esc(link.line)}</span></a></td>`;
  }).join('');

  return `<tr class="field-row" data-name="${esc(field.name)}" data-search="${esc(field.name)} ${esc(field.typeText)} ${esc(field.fieldKind ?? '')}">`
    + `<th class="field-cell" scope="row">`
    + `<span class="ordinal" title="position in the field enum (DEC-023 ledger)">${field.ordinal ?? '&#183;'}</span>`
    + `<span class="field-body"><span class="field-line">${nameCell}`
    + `<span class="field-type" title="${esc(field.typeTitle)}">${esc(field.typeText)}</span></span>`
    + (chips.length ? `<span class="field-chips">${chips.join('')}</span>` : '')
    + (field.declaredIn ? `<span class="declared">declared in ${esc(field.declaredIn)}</span>` : '')
    + '</span></th>'
    + cells
    + '</tr>';
}

function viewSection(marker, view, index) {
  const header = view.path
    ? `<a class="view-name" data-open="${esc(view.path)}" data-line="${esc(view.line)}"`
      + ` data-member="${esc(view.name)}" data-role="type" data-where="${esc(view.fqcn)}"`
      + ` title="open ${esc(view.fqcn)}">${esc(view.name)}</a>`
    : `<span class="view-name no-link">${esc(view.name)}</span>`;

  const meta = [];
  meta.push(`<span class="chip chip-gen">${esc(view.gen)}</span>`);
  if (view.extendsTypes.length) {
    meta.push(`<span class="meta-item">extends ${esc(view.extendsTypes.join(', '))}</span>`);
  }
  if (view.addons.length) {
    meta.push(`<span class="meta-item">addons ${esc(view.addons.join(', '))}</span>`);
  }
  if (view.discriminatorField) {
    meta.push(`<span class="meta-item">discriminator ${esc(view.discriminatorField)}</span>`);
  }
  meta.push(`<span class="meta-item">${view.fields.length} fields &#183; ${view.aspects.length} artifacts</span>`);
  meta.push(view.path
    ? `<span class="meta-item path">${esc(view.path)}:${esc(view.line)}</span>`
    : '<span class="meta-item path">not found in the source root</span>');

  const table = view.columns.length === 0
    ? '<p class="no-columns">No field is locatable in any artifact of this view.</p>'
    : `<div class="table-scroll"><table class="matrix">
      <thead><tr><th class="corner" scope="col">field</th>${
  view.columns.map((column) => `<th scope="col" class="col-head" data-col="${column.id}">
        <a class="col-link" data-open="${esc(column.path)}" data-line="${esc(column.line)}"
           data-member="${esc(column.artifact)}" data-role="type" data-where="${esc(column.artifactFqcn)}"
           title="open ${esc(column.artifactFqcn)} at ${esc(column.path)}:${esc(column.line)}">
          <span class="col-artifact">${esc(column.artifact)}</span>
          <span class="col-role">${esc(column.roleLabel)}</span>
          <span class="col-line">${esc(column.path)}:${esc(column.line)}</span>
        </a></th>`).join('')
}</tr></thead>
      <tbody>${view.fields.map((field) => fieldRow(field, view.columns)).join('')}</tbody>
    </table></div>`;

  return `<section class="view" id="v-${marker.index}-${index}" data-search="${esc(view.name)} ${esc(view.fqcn)}">
    <div class="view-head">
      <h3>${header}</h3>
      <div class="view-meta">${meta.join('')}</div>
    </div>
    <div class="aspects">${view.aspects.map(aspectCard).join('')}</div>
    ${table}
  </section>`;
}

function markerSection(marker, index) {
  const views = marker.views.map((view, viewIndex) => viewSection({ ...marker, index }, view, viewIndex)).join('');
  const viewList = marker.views.map((view) => esc(view.name)).join(', ');
  const markerName = marker.markerPath
    ? `<a class="view-name" data-open="${esc(marker.markerPath)}" data-line="${esc(marker.markerLine)}"`
      + ` data-member="${esc(marker.markerInterface)}" data-role="type"`
      + ` data-where="${esc(`${marker.packageName}.${marker.markerInterface}`)}"`
      + ` title="open ${esc(marker.markerInterface)}">${esc(marker.markerInterface)}</a>`
    : `<code>${esc(marker.markerInterface)}</code>`;
  return `<section class="marker" id="m-${index}" data-search="${esc(marker.entityName)} ${esc(marker.packageName)} ${esc(viewList)}">
    <header class="marker-head">
      <h2><span class="marker-name">${esc(marker.entityName)}</span>
        <span class="marker-role">entity marker</span></h2>
      <div class="marker-meta">
        <span class="meta-item">${esc(marker.packageName)}</span>
        <span class="meta-item">marker ${markerName}</span>
        <span class="meta-item">id <code>${esc(marker.idType)}</code></span>
        <span class="meta-item">${marker.views.length} views &#183; ${marker.fieldCount} fields</span>
        <span class="meta-item path">${esc(marker.markerPath ?? marker.file)}</span>
      </div>
    </header>
    ${views}
  </section>`;
}

function navigation(page) {
  return `<nav id="nav"><div class="nav-title">Entities</div>${
    page.markers.map((marker, index) => `<a class="nav-entity" href="#m-${index}">
      <span class="nav-name">${esc(marker.entityName)}</span>
      <span class="nav-count">${marker.views.length}v &#183; ${marker.fieldCount}f</span>
    </a>
    <div class="nav-views">${marker.views.map((view, viewIndex) => `<a class="nav-view" href="#v-${index}-${viewIndex}"
      data-search="${esc(view.name)}">${esc(view.name)}</a>`).join('')}</div>`).join('')
  }</nav>`;
}

function generationFooter(page) {
  const parts = [];
  if (page.generation) {
    const g = page.generation;
    parts.push(`<span class="meta-item">run record <code>${esc(g.generator ?? '?')}</code>`
      + `${g.version ? ` ${esc(g.version)}` : ''}`
      + `${g.status ? ` &#183; status ${esc(g.status)}` : ''}`
      + `${g.startedAt ? ` &#183; ${esc(g.startedAt)}` : ''}`
      + `${Array.isArray(g.packages) && g.packages.length ? ` &#183; packages ${esc(g.packages.join(', '))}` : ''}</span>`);
  }
  parts.push(`<span class="meta-item">metadata ${page.metadataFiles.map((file) => `<code>${esc(file)}</code>`).join(' ')}</span>`);
  return parts.join('');
}

function legend() {
  return ROLE_ORDER.map((role) => `<li><span class="legend-role">${esc(ROLE_LABELS[role])}</span>`
    + `<span class="legend-hint">${esc(ROLE_HINT[role] ?? '')}</span></li>`).join('');
}

/** The page. Deterministic: no timestamps, no absolute paths, no run-dependent ordering. */
export function renderPage(page, stats, divergences) {
  const totalFields = page.markers.reduce((total, marker) => total + marker.fieldCount, 0);
  const totalViews = page.markers.reduce((total, marker) => total + marker.views.length, 0);
  const bridge = page.bridgePort > 0
    ? `<span class="meta-item">HTTP bridge fallback <code>127.0.0.1:${esc(page.bridgePort)}</code></span>`
    : '<span class="meta-item">HTTP bridge fallback disabled</span>';

  const errors = divergences.filter((item) => item.severity !== 'warning');
  const warnings = divergences.filter((item) => item.severity === 'warning');
  const divergenceBlock = divergences.length === 0
    ? '<p class="ok">Link check: every link was verified against the file and line it points at.</p>'
    : `${errors.length > 0 ? `<details class="problems" open><summary>${errors.length} unverified link(s)</summary>
      <pre>${esc(errors.map((item) => item.toString()).join('\n'))}</pre></details>` : ''}
      ${warnings.length > 0 ? `<details class="warnings"><summary>${warnings.length} metadata note(s)</summary>
      <pre>${esc(warnings.map((item) => item.toString()).join('\n'))}</pre></details>` : ''}`;

  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${esc(page.title)}</title>
<style>
:root {
  /* Turquoise: a deep teal-slate base, a single turquoise accent, and a semantic set. Every colour a
     rule uses comes from here — the few that used to be written inline are variables too, so retheming
     the page is one block rather than a search. */
  --bg: #04191b; --surface: #0a2427; --surface2: #0f3134; --border: #1c4a4e;
  /* --accent-dim is only ever a BACKGROUND (the active nav row) or a border, never text: at #1a7f78 the
     nav count on it measured 2.15:1, which is unreadable. #0b4f4c puts text at 6.7:1 and the dim count
     at 3.5:1. --accent stays the text/hover colour, where it measures 11.5:1 on the page background. */
  --text: #d8f2f0; --dim: #83b6b8; --accent: #2ee6d0; --accent-dim: #0b4f4c;
  --green: #5fd6a8; --yellow: #dcc46a; --orange: #e0a76c; --red: #e07a7a; --purple: #b48bdc;
  /* Inline-only shades: the tints and hovers the rules below need, named so they are not orphaned. */
  --header-a: #07272a; --head-cell: #0d2e31; --hover-accent: #14444a; --row-hover: #10393d;
  --filled: #0b2a2d; --filled-hover: #11373b; --empty-mark: #2b5a5e;
  /* Chip and state borders: one per semantic colour, so a chip's ring matches its text. */
  --border-green: #2a6a58; --border-yellow: #6a5c33; --border-orange: #6a5133; --border-red: #6a3a3a;
  --border-purple: #4f3f6a;
}
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: 'Segoe UI', system-ui, -apple-system, sans-serif; background: var(--bg); color: var(--text); }
a { color: inherit; text-decoration: none; }
code { font-family: 'Cascadia Code', 'JetBrains Mono', Consolas, monospace; font-size: .85em; color: var(--yellow); }
header.top { position: sticky; top: 0; z-index: 20; background: linear-gradient(180deg, var(--header-a), var(--bg));
  border-bottom: 1px solid var(--border); padding: .8rem 1.2rem .7rem; }
header.top h1 { font-size: 1.1rem; font-weight: 700; }
header.top h1 span { color: var(--accent); }
.top-sub { color: var(--dim); font-size: .82rem; margin-top: .2rem; display: flex; flex-wrap: wrap; gap: .9rem; }
.toolbar { display: flex; align-items: center; gap: .6rem; margin-top: .6rem; flex-wrap: wrap; }
#filter { flex: 1 1 260px; min-width: 200px; padding: .45rem .6rem; border-radius: 8px;
  border: 1px solid var(--border); background: var(--surface); color: var(--text); font-size: .9rem; }
#filter:focus { outline: none; border-color: var(--accent-dim); }
button.tool { padding: .4rem .7rem; border-radius: 8px; border: 1px solid var(--border);
  background: var(--surface); color: var(--text); font-size: .82rem; cursor: pointer; }
button.tool:hover { border-color: var(--accent-dim); }
.pill { font-size: .75rem; padding: .25rem .55rem; border-radius: 999px; border: 1px solid var(--border);
  background: var(--surface2); color: var(--dim); }
.pill.ready { color: var(--green); border-color: var(--border-green); }
.pill.absent { color: var(--orange); border-color: var(--border-orange); }
#layout { display: grid; grid-template-columns: 300px minmax(0, 1fr); align-items: start; }
#nav { position: sticky; top: 118px; max-height: calc(100vh - 130px); overflow: auto;
  padding: .9rem .6rem 2rem 1.2rem; border-right: 1px solid var(--border); font-size: .86rem; }
.nav-title { text-transform: uppercase; letter-spacing: .1em; font-size: .7rem; color: var(--dim); margin-bottom: .5rem; }
.nav-entity { display: flex; justify-content: space-between; gap: .4rem; padding: .32rem .45rem;
  border-radius: 6px; font-weight: 600; }
.nav-entity:hover { background: var(--surface2); }
.nav-entity.active { background: var(--accent-dim); }
.nav-count { color: var(--dim); font-weight: 400; font-size: .75rem; }
.nav-views { display: flex; flex-direction: column; padding-left: .8rem; margin: .1rem 0 .5rem;
  border-left: 1px solid var(--border); }
.nav-view { padding: .18rem .4rem; border-radius: 5px; color: var(--dim); }
.nav-view:hover { color: var(--text); background: var(--surface2); }
main { padding: 1rem 1.4rem 4rem; min-width: 0; }
.marker { margin-bottom: 2.4rem; }
.marker-head { border-bottom: 1px solid var(--border); padding-bottom: .5rem; margin-bottom: .9rem; }
.marker-head h2 { font-size: 1.25rem; display: flex; align-items: baseline; gap: .6rem; }
.marker-role { font-size: .72rem; text-transform: uppercase; letter-spacing: .08em; color: var(--accent); }
.marker-meta { display: flex; flex-wrap: wrap; gap: .8rem; margin-top: .35rem; font-size: .8rem; color: var(--dim); }
.view { background: var(--surface); border: 1px solid var(--border); border-radius: 10px;
  padding: .9rem 1rem 1.1rem; margin-bottom: 1.1rem; }
.view-head { display: flex; justify-content: space-between; gap: 1rem; flex-wrap: wrap; align-items: baseline; }
.view-head h3 { font-size: 1rem; }
.view-name { color: var(--text); border-bottom: 1px dotted var(--accent-dim); }
.view-name:hover { color: var(--accent); }
.view-name.no-link, .field-name.no-link { border-bottom: none; color: var(--dim); }
.view-meta { display: flex; flex-wrap: wrap; gap: .5rem; font-size: .78rem; color: var(--dim); align-items: center; }
.chip { display: inline-block; padding: .12rem .45rem; border-radius: 999px; font-size: .72rem;
  background: var(--surface2); border: 1px solid var(--border); color: var(--dim); }
.chip-gen { color: var(--accent); border-color: var(--accent-dim); }
.chip-column { color: var(--green); border-color: var(--border-green); }
.chip-derived { color: var(--orange); border-color: var(--border-orange); }
.chip-joined { color: var(--purple); border-color: var(--border-purple); }
.chip-inherited { color: var(--yellow); border-color: var(--border-yellow); }
.chip-no-ledger { color: var(--red); border-color: var(--border-red); }
.chip-expr { font-family: 'Cascadia Code', Consolas, monospace; }
.meta-item.path, .aspect-path, .col-line { font-family: 'Cascadia Code', Consolas, monospace; font-size: .74rem; }
.aspects { display: flex; flex-wrap: wrap; gap: .5rem; margin: .8rem 0 .2rem; }
.aspect { flex: 0 1 260px; background: var(--surface2); border: 1px solid var(--border); border-radius: 8px;
  padding: .5rem .6rem; display: block; }
.aspect:hover { border-color: var(--accent); }
.aspect.is-generated { border-left: 3px solid var(--green); }
.aspect-head { display: flex; flex-wrap: wrap; gap: .35rem; align-items: center; }
.aspect-name { font-weight: 600; font-size: .88rem; }
.aspect-desc { color: var(--dim); font-size: .76rem; margin-top: .25rem; }
.aspect-path { color: var(--dim); margin-top: .3rem; word-break: break-all; }
.kind { font-size: .68rem; padding: .05rem .35rem; border-radius: 4px; border: 1px solid var(--border); color: var(--dim); }
.kind-generated { color: var(--green); border-color: var(--border-green); }
.table-scroll { overflow-x: auto; margin-top: .8rem; border: 1px solid var(--border); border-radius: 8px; }
table.matrix { border-collapse: separate; border-spacing: 0; font-size: .78rem; min-width: 100%; }
table.matrix th, table.matrix td { border-bottom: 1px solid var(--border); border-right: 1px solid var(--border);
  padding: 0; vertical-align: top; }
table.matrix thead th { position: sticky; top: 0; background: var(--head-cell); z-index: 5; }
.col-head { min-width: 116px; max-width: 160px; }
.col-link { display: block; padding: .35rem .5rem; }
.col-link:hover { background: var(--hover-accent); }
.col-artifact { display: block; font-weight: 600; font-size: .8rem; word-break: break-all; }
.col-role { display: block; color: var(--accent); font-size: .72rem; }
.col-line { display: block; color: var(--dim); word-break: break-all; }
th.corner { position: sticky; left: 0; top: 0; z-index: 7; background: var(--head-cell); text-align: left;
  padding: .35rem .6rem; font-size: .74rem; text-transform: uppercase; letter-spacing: .07em; color: var(--dim); min-width: 250px; }
th.field-cell { position: sticky; left: 0; background: var(--surface); z-index: 3; text-align: left;
  padding: .45rem .6rem; font-weight: 400; border-right: 2px solid var(--border); min-width: 250px; }
.field-row:hover th.field-cell { background: var(--head-cell); }
.ordinal { display: inline-block; min-width: 1.5rem; color: var(--dim); font-size: .72rem; }
.field-body { display: inline-block; }
.field-line { display: block; }
.field-name { font-weight: 600; }
.field-name:hover { color: var(--accent); }
.field-type { color: var(--yellow); margin-left: .4rem; font-size: .78rem; }
.field-chips { display: block; margin-top: .15rem; }
.field-chips .chip { margin-right: .25rem; }
.declared { display: block; color: var(--dim); font-size: .72rem; margin-top: .15rem; }
td.filled { background: var(--filled); }
td.filled:hover { background: var(--filled-hover); }
.cell { display: block; padding: .4rem .5rem; }
.cell-role { display: block; color: var(--green); font-size: .72rem; }
.cell-line { display: block; color: var(--dim); font-size: .7rem; }
td.empty { color: var(--empty-mark); text-align: center; padding: .5rem; }
tr.field-row:hover td.filled { background: var(--row-hover); }
.no-columns { color: var(--dim); font-size: .82rem; margin-top: .8rem; }
footer.bottom { border-top: 1px solid var(--border); padding: 1.2rem 1.4rem 3rem; color: var(--dim); font-size: .82rem;
  display: grid; gap: .8rem; }
footer.bottom h4 { color: var(--text); font-size: .85rem; margin-bottom: .3rem; }
footer.bottom ul { list-style: none; display: grid; gap: .2rem; }
.legend-role { display: inline-block; min-width: 130px; color: var(--green); font-family: 'Cascadia Code', Consolas, monospace; }
.problems pre { white-space: pre-wrap; word-break: break-word; color: var(--orange); font-size: .76rem; margin-top: .4rem; }
.warnings pre { white-space: pre-wrap; word-break: break-word; color: var(--yellow); font-size: .76rem; margin-top: .4rem; }
.ok { color: var(--green); }
#toast { position: fixed; right: 1rem; bottom: 1rem; max-width: 70vw; background: var(--surface2); color: var(--text);
  border: 1px solid var(--accent-dim); border-left: 3px solid var(--accent); border-radius: 8px;
  padding: .55rem .7rem; font-size: .8rem; opacity: 0; transform: translateY(6px);
  transition: opacity .18s ease, transform .18s ease; pointer-events: none; z-index: 50; }
#toast.show { opacity: 1; transform: translateY(0); }
.hidden { display: none !important; }
@media (max-width: 980px) { #layout { grid-template-columns: 1fr; } #nav { position: static; max-height: none; border-right: none; } }
</style>
</head>
<body data-link-base="${esc(page.linkBase)}" data-bridge-port="${esc(page.bridgePort)}">
<header class="top">
  <h1>Entity reference <span>&#183; ${esc(page.moduleName)}</span></h1>
  <div class="top-sub">
    <span>${page.markers.length} entities &#183; ${totalViews} views &#183; ${totalFields} fields</span>
    <span>source root <code>${esc(page.sourceRootDisplay)}</code></span>
    <span>${stats.links} verified links of ${stats.checked} candidates</span>
    ${bridge}
    <span id="bridge-status" class="pill">IDE bridge: checking&#8230;</span>
  </div>
  <div class="toolbar">
    <input id="filter" type="search" placeholder="Filter entities, views and fields&#8230;  (press / )" autocomplete="off">
    <button class="tool" id="collapse">Collapse field tables</button>
    <button class="tool" id="expand">Expand all</button>
  </div>
</header>
<div id="layout">
${navigation(page)}
  <main>
${page.markers.map(markerSection).join('')}
  </main>
</div>
<footer class="bottom">
  <div>
    <h4>How a link opens</h4>
    <p>Every clickable name, cell and column header opens a Java file at a line. In the JetBrains
    <strong>WebView Explorer</strong> tool window the plugin exposes <code>window.openFile(path, line, column)</code>,
    which is what this page calls; the path is made absolute from this page's own location, so it works whether the
    IDE project is this module or the repository above it. Without that bridge the page falls back to the plugin's
    HTTP endpoint, and failing that it copies the location to the clipboard.</p>
  </div>
  <div>
    <h4>What each column means</h4>
    <ul>${legend()}</ul>
  </div>
  <div>
    <h4>Where this page comes from</h4>
    <p>The model is the generator's JSON metadata; the file and line of every link is resolved from the committed
    source and verified before it is written here (DEC-027). A cell is empty when the field genuinely has no such
    slot &#8212; a DERIVED field has no setter, a META view has no builder.</p>
    <p>${generationFooter(page)}</p>
  </div>
  <div>${divergenceBlock}</div>
  <div><span class="meta-item">renderer <code>${esc(page.identity)}</code></span></div>
</footer>
<div id="toast" role="status" aria-live="polite"></div>
<script>
(function () {
  var body = document.body;
  var LINK_BASE = body.getAttribute('data-link-base') || './';
  var BRIDGE_PORT = parseInt(body.getAttribute('data-bridge-port') || '0', 10);
  var toast = document.getElementById('toast');
  var toastTimer = null;

  function say(message, sticky) {
    toast.textContent = message;
    toast.classList.add('show');
    if (toastTimer) { clearTimeout(toastTimer); }
    if (!sticky) { toastTimer = setTimeout(function () { toast.classList.remove('show'); }, 2600); }
  }

  /**
   * The link base as an absolute file path, derived from where this page was loaded from.
   *
   * LINK_BASE is one relative path (or a file: URL when the page and the tree are on different
   * Windows drives), and it is resolved against the page's own directory — so the page works whether
   * the IDE project is this module or the repository above it.
   */
  function absoluteBase() {
    var href = window.location.href;
    var cut = href.lastIndexOf('/');
    if (cut < 0) { return null; }
    var dir = href.slice(0, cut + 1);
    var resolved;
    try { resolved = new URL(LINK_BASE, dir).href; } catch (error) { return null; }
    if (resolved.indexOf('file:') !== 0) { return null; }
    var path = decodeURIComponent(resolved.slice(5));
    while (path.charAt(0) === '/') { path = path.slice(1); }
    if (!/^[A-Za-z]:/.test(path)) { return null; }
    return path;
  }

  function absoluteTarget(relative) {
    var base = absoluteBase();
    if (base === null) { return null; }
    return base + relative;
  }

  function copy(text) {
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text);
        return true;
      }
    } catch (error) { /* clipboard blocked: the toast still shows the path */ }
    return false;
  }

  function open(link) {
    var relative = link.getAttribute('data-open');
    var line = parseInt(link.getAttribute('data-line') || '1', 10);
    var member = link.getAttribute('data-member') || '';
    var role = link.getAttribute('data-role') || '';
    var target = absoluteTarget(relative) || relative;
    if (typeof window.openFile === 'function') {
      window.openFile(target, line, 1);
      say('Opened ' + member + ' (' + role + ') at line ' + line + ' in the IDE.');
      return;
    }
    if (BRIDGE_PORT > 0) {
      var url = 'http://127.0.0.1:' + BRIDGE_PORT + '/open?filePath=' + encodeURIComponent(target)
        + '&line=' + line + '&column=1';
      // A hidden frame per click would accumulate; the previous one has already been delivered.
      var previous = document.getElementById('bridge-frame');
      if (previous) { previous.remove(); }
      var frame = document.createElement('iframe');
      frame.id = 'bridge-frame';
      frame.style.display = 'none';
      frame.setAttribute('aria-hidden', 'true');
      frame.src = url;
      document.body.appendChild(frame);
      say('Sent line ' + line + ' to the IDE bridge on port ' + BRIDGE_PORT + '.', true);
      return;
    }
    copy(target + ':' + line);
    say('No IDE bridge here. Location copied: ' + target + ':' + line, true);
  }

  document.addEventListener('click', function (event) {
    var link = event.target.closest ? event.target.closest('[data-open]') : null;
    if (!link) { return; }
    event.preventDefault();
    open(link);
  });

  var status = document.getElementById('bridge-status');
  if (typeof window.openFile === 'function') {
    status.textContent = 'IDE bridge: ready';
    status.className = 'pill ready';
  } else {
    status.textContent = BRIDGE_PORT > 0 ? 'IDE bridge: absent (HTTP fallback)' : 'IDE bridge: absent';
    status.className = 'pill absent';
  }

  var filter = document.getElementById('filter');
  function applyFilter() {
    var needle = filter.value.trim().toLowerCase();
    var markers = document.querySelectorAll('section.marker');
    markers.forEach(function (marker) {
      var markerText = (marker.getAttribute('data-search') || '').toLowerCase();
      var visibleViews = 0;
      marker.querySelectorAll('section.view').forEach(function (view) {
        var viewText = (view.getAttribute('data-search') || '').toLowerCase();
        var viewMatch = needle === '' || markerText.indexOf(needle) >= 0 || viewText.indexOf(needle) >= 0;
        var rows = view.querySelectorAll('tr.field-row');
        var visibleRows = 0;
        rows.forEach(function (row) {
          var rowText = (row.getAttribute('data-search') || '').toLowerCase();
          var show = viewMatch || rowText.indexOf(needle) >= 0;
          row.classList.toggle('hidden', !show);
          if (show) { visibleRows++; }
        });
        var visible = needle === '' || viewMatch || visibleRows > 0;
        view.classList.toggle('hidden', !visible);
        if (visible) { visibleViews++; }
      });
      marker.classList.toggle('hidden', visibleViews === 0);
    });
    document.querySelectorAll('#nav .nav-entity, #nav .nav-view').forEach(function (item) {
      var text = (item.textContent || '').toLowerCase();
      item.style.opacity = needle === '' || text.indexOf(needle) >= 0 ? '' : '.35';
    });
  }
  filter.addEventListener('input', applyFilter);
  document.addEventListener('keydown', function (event) {
    if (event.key === '/' && document.activeElement !== filter) {
      event.preventDefault();
      filter.focus();
    }
    if (event.key === 'Escape' && document.activeElement === filter) {
      filter.value = '';
      applyFilter();
      filter.blur();
    }
  });

  function setTables(collapsed) {
    document.querySelectorAll('table.matrix').forEach(function (table) {
      table.classList.toggle('hidden', collapsed);
    });
  }
  document.getElementById('collapse').addEventListener('click', function () { setTables(true); });
  document.getElementById('expand').addEventListener('click', function () { setTables(false); });

  document.querySelectorAll('#nav .nav-entity').forEach(function (item) {
    item.addEventListener('click', function () {
      document.querySelectorAll('#nav .nav-entity').forEach(function (other) { other.classList.remove('active'); });
      item.classList.add('active');
    });
  });
})();
</script>
</body>
</html>
`;
}
