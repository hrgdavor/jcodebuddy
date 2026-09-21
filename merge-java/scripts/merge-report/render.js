#!/usr/bin/env bun
/**
 * Renders a merge-java resolution report as one self-contained HTML file.
 *
 * Usage:
 *   bun run scripts/merge-report/render.js <report.json> <output.html>
 *
 * Contract (DEC-027/029):
 *   - the Java side owns the model and writes the JSON; this script owns
 *     presentation and never parses Java;
 *   - the output is a single file with framework-free vanilla JavaScript, no
 *     bundler, no node_modules, no CDN, no network at view time;
 *   - no link is written unless the target was verified to exist.
 */

import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { dirname, resolve, relative } from "node:path";

const [reportPath, outputPath] = process.argv.slice(2);

if (!reportPath || !outputPath) {
  console.error("usage: render.js <report.json> <output.html>");
  process.exit(2);
}

const report = JSON.parse(readFileSync(reportPath, "utf8"));
const outputDir = dirname(resolve(outputPath));

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

/**
 * Only emit a link when the target exists; an unverifiable candidate is dropped
 * and reported rather than written.
 */
function verifiedLink(targetPath, label) {
  const absolute = resolve(targetPath);
  if (!existsSync(absolute)) {
    console.warn(`dropped unverifiable link: ${targetPath}`);
    return escapeHtml(label);
  }
  const href = relative(outputDir, absolute).replaceAll("\\", "/");
  return `<a href="${escapeHtml(href)}">${escapeHtml(label)}</a>`;
}

function kindClass(kind) {
  switch (kind) {
    case "AUTO":
      return "auto";
    case "REVIEW":
      return "review";
    case "DEFERRED":
      return "deferred";
    default:
      return "manual";
  }
}

function renderRegion(region) {
  if (!region) return "unknown region";
  return region.startLine === region.endLine
    ? `line ${region.startLine}`
    : `lines ${region.startLine}-${region.endLine}`;
}

function renderFixPaths(fixPaths) {
  if (!fixPaths || fixPaths.length === 0) return "";
  const items = fixPaths
    .map((fixPath) => {
      const options = (fixPath.options || [])
        .map((option) => {
          const recommended = option === fixPath.recommended ? " recommended" : "";
          return `<li class="option${recommended}">${escapeHtml(option)}${
            recommended ? ' <span class="badge">recommended</span>' : ""
          }</li>`;
        })
        .join("");
      return `<div class="fixpath">
        <p class="fixpath-title">${escapeHtml(fixPath.description)}</p>
        <ul class="options">${options}</ul>
        <p class="why"><strong>Why:</strong> ${escapeHtml(fixPath.justification)}</p>
        <p class="why"><strong>Cost:</strong> ${escapeHtml(fixPath.impact)}</p>
      </div>`;
    })
    .join("");
  return `<section class="fixpaths"><h4>Options</h4>${items}</section>`;
}

function renderResolution(resolution) {
  return `<article class="resolution ${kindClass(resolution.kind)}">
    <header>
      <span class="kind">${escapeHtml(resolution.kind)}</span>
      <span class="type">${escapeHtml(resolution.type)}</span>
      <span class="region">${escapeHtml(renderRegion(resolution.region))}</span>
      <span class="strategy">${escapeHtml(resolution.strategy)}</span>
      ${
        resolution.verification && resolution.verification !== "NOT_RUN"
          ? `<span class="verification">verified: ${escapeHtml(resolution.verification)}</span>`
          : ""
      }
      ${
        resolution.independentlyApplicable
          ? '<span class="applicable">safe to apply independently</span>'
          : ""
      }
    </header>
    <p class="explanation">${escapeHtml(resolution.explanation)}</p>
    <pre class="code"><code>${escapeHtml(resolution.resolvedCode)}</code></pre>
    ${renderFixPaths(resolution.fixPaths)}
  </article>`;
}

function renderFile(file) {
  const resolutions = (file.resolutions || []).map(renderResolution).join("");
  const link = verifiedLink(file.filePath, file.filePath);
  return `<section class="file">
    <h2>${link}</h2>
    <p class="summary">${escapeHtml(file.summary)}</p>
    ${resolutions || '<p class="clean">No conflicts detected.</p>'}
  </section>`;
}

const summary = report.summary || {};
const files = (report.files || []).map(renderFile).join("");

const html = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>merge-java report</title>
<style>
  :root { color-scheme: light dark; }
  body { font-family: system-ui, sans-serif; margin: 0; padding: 2rem; line-height: 1.5; }
  h1 { margin-top: 0; }
  .counts { display: flex; flex-wrap: wrap; gap: 1rem; margin-bottom: 2rem; }
  .count { border: 1px solid #8884; border-radius: 6px; padding: .5rem 1rem; }
  .count strong { display: block; font-size: 1.4rem; }
  .file { border-top: 2px solid #8884; padding-top: 1rem; margin-top: 2rem; }
  .resolution { border: 1px solid #8884; border-left-width: 6px; border-radius: 6px;
                padding: 1rem; margin: 1rem 0; }
  .resolution.auto { border-left-color: #2e7d32; }
  .resolution.review { border-left-color: #f9a825; }
  .resolution.deferred { border-left-color: #1565c0; }
  .resolution.manual { border-left-color: #c62828; }
  header { display: flex; flex-wrap: wrap; gap: .5rem; align-items: center;
           font-size: .85rem; }
  header span { background: #8882; border-radius: 4px; padding: .1rem .5rem; }
  .kind { font-weight: 700; }
  pre.code { background: #8881; padding: .75rem; border-radius: 4px; overflow-x: auto; }
  .options { list-style: none; padding-left: 0; }
  .option { padding: .1rem 0; }
  .option.recommended { font-weight: 600; }
  .badge { background: #2e7d32; color: #fff; border-radius: 4px; padding: 0 .4rem;
           font-size: .75rem; }
  .why { margin: .25rem 0; font-size: .9rem; }
  .clean { color: #2e7d32; }
</style>
</head>
<body>
<h1>merge-java resolution report</h1>
<div class="counts">
  <div class="count"><strong>${summary.files ?? 0}</strong>files</div>
  <div class="count"><strong>${summary.autoResolutions ?? 0}</strong>automatic</div>
  <div class="count"><strong>${summary.applicableResolutions ?? 0}</strong>applicable</div>
  <div class="count"><strong>${summary.reviewResolutions ?? 0}</strong>need review</div>
  <div class="count"><strong>${summary.manualResolutions ?? 0}</strong>need a human</div>
  <div class="count"><strong>${summary.replayedDecisions ?? 0}</strong>replayed</div>
</div>
${summary.dryRun ? "<p><em>Dry run: nothing was written.</em></p>" : ""}
${files || "<p>No files in this report.</p>"}
</body>
</html>
`;

writeFileSync(outputPath, html, "utf8");
console.log(`wrote ${outputPath}`);
