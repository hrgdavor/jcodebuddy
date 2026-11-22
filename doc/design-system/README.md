# JCodeBuddy UI design system — proposal

Status: **proposal, 2026-11-22.** This is the starting point for a shared UI layer, not a
finished component library. It exists because the tools now have several independent page shells,
token sets and navigation implementations, and integration work is about to make that duplication
expensive.

The authority for *how* to build a jsx6 page remains
[`doc/jsx6-capability-assessment.md`](../jsx6-capability-assessment.md), the repository
[`AGENTS.md`](../../AGENTS.md) § 2, and the local checkout's
[`AGENTS.md`](../../.jsx6/AGENTS.md) and
[`docs/stack/agent-rules.md`](../../.jsx6/docs/stack/agent-rules.md). This document adds the
**shared composition layer** those pages do not currently have.

## 1. Audit of the current UI tools

The classification rule: a **document or minimal report** stays vanilla (DEC-027's amendment);
an **interactive or advanced page** uses jsx6; a relations/diagram page uses jsx6 + nodditor.

| Tool / page                                                   | Form                    | Build                                     | Verdict                    | Notes                                                                                |
| ------------------------------------------------------------- | ----------------------- | ----------------------------------------- | -------------------------- | ------------------------------------------------------------------------------------ |
| Entity reference page (`scripts/entity-html`)                 | vanilla, self-contained | no bundler                                | **allowed**                | The explicit minimal-report class; `HtmlRenderBoundaryTest` keeps it framework-free. |
| Merge review (`merge-java/review`)                            | jsx6 + signal           | esbuild, local checkout aliases           | **compliant**              | Per-conflict review, edit/accept/reject, export; the advanced-page class.            |
| Entity browser (`project-automation/entity-browser`)          | jsx6                    | esbuild, shared jsx6 assets               | **compliant**              | Record navigation, filtering and drill-down; the recorded served-page exception.     |
| Compiler console (`project-automation/compiler-console`)      | jsx6                    | esbuild, local checkout aliases           | **compliant as a page**    | Live `/api/tools` console; **standalone gap**: it has no `file://` path, unlike a self-contained report. |
| Hipster IoC graph (`hipster-ioc/hipster-ioc-graph`)           | jsx6 + nodditor         | copied sources + checkout bundle, inlined | **compliant**              | The diagram/relations class; no network at view time.                                |
| ChatApp client (`proto/entity-ioc-playground/chatapp-client`) | jsx6                    | esbuild, local checkout aliases           | **compliant**              | Interactive forms and live chat; a server-backed application client, not a report.   |
| Markdown app (`scripts/app-markdown.js`)                      | vanilla                 | no bundler                                | **allowed, watch item**    | Document shell and file viewer; now also carries hash routing and the SPA hook. It remains simple enough for vanilla, but it is the first page to migrate when the design system lands. |
| Integrated Markdown view (`webview/markdown-view`)            | vanilla                 | no bundler                                | **allowed**                | Document rendering, anchors and a small toolbar; minimal by intent.                  |
| Agent dashboard (`jcodebuddy-agent/web`)                      | jsx6 + signal           | esbuild, local checkout aliases           | **compliant (migrated)**   | The old vanilla dashboard was replaced; served standalone and via `.jcodebuddy/app/jcodebuddy-agent.html` + `/api/tools`. |
| Demo pages (`demo/`)                                          | vanilla                 | none                                      | **not a tool page**        | Interactive demos; if one is promoted to a tool, it must move to jsx6.               |
| WebView kit examples (`webview/kit/examples`)                 | vanilla                 | none                                      | **examples, not products** | They demonstrate the embedded-host contract; they are not page-building precedents.  |

### What is already right

- Every jsx6 page builds JSX with `jsx: 'automatic'` and `jsxImportSource: '@jsx6'`.
- Every jsx6 page resolves `@jsx6/*` from the **local checkout** (`JCODEBUDDY_JSX6_DIR`, default
  `.jsx6`) rather than from a remembered npm version.
- No React, Svelte, Vue, Solid, Preact, Lit or Angular import is present in the product pages.
- Java-producing tools parse through **OpenRewrite**, not JavaParser: the agent's builders delegate to
  `jwa-builder`'s OpenRewrite processors, and entity/IoC tooling uses `SourceReader`/`TreeQueries`.
  `bun scripts/check-no-javaparser.js` guards the production boundary.
- The vanilla reports are deliberate, not omissions: the entity reference is the explicit
  minimal-report class, and the Markdown views render documents rather than managing application
  state.

### Findings

1. **The last interactive vanilla page has been migrated.** The agent dashboard is now a jsx6 page and
   uses the tool-socket/webview path. The remaining vanilla pages are the deliberate document/report
   class; the next UI work is the shared system, not another ad-hoc page.
2. **No shared navigation.** Entity browser uses a hash-selected FQN, the IoC graph has its own
   block/navigation scanner, merge review has block actions, ChatApp has forms and room selection,
   and the app shell has query/hash + `window.jcodebuddyNavIntercept`. Each is correct in isolation;
   none can be reused.
3. **No shared composition or form primitives.** Buttons, fields, toolbars, panels, status lines,
   empty/error states and modal/confirm patterns are reimplemented in page CSS and JSX.
4. **Tokens are duplicated.** Each page defines its own `--bg`, `--surface`, `--line`, `--accent`
   and related variables, with different names and light/dark choices. The visual result is close
   but not one system.
5. **The jsx6 build definition is copied five times** (`entity-browser`, `compiler-console`,
   `merge-java/review`, `chatapp-client`, and the new `jcodebuddy-agent/web`). The copies have
   drifted in comments and aliasing; a shared build helper is a natural part of the UI package.
6. **The compiler console has no standalone path.** It is served and needs `/api/tools`. That may
   be acceptable as a host-only tool, but it is an exception to “a UI must be usable standalone”
   and should be recorded or fixed.
7. **Two jsx6 packages keep only `tsconfig-custom.json`.** `compiler-console` and `chatapp-client`
   have no editor/`tsc` `tsconfig.json` with `"jsx": "preserve"`; the build side is correct, but the
   two-config split the stack asks for is incomplete.

## 2. What the design system is

The design system is a **composition and behaviour layer**, not a stylesheet with a logo:

```text
tokens  →  primitives  →  patterns  →  pages
             button          app shell       a tool
             field           page header
             panel           form
             table           split view
             status          navigation
```

It is built with jsx6 and `@jsx6/signal`, and it must not become a second framework. A page using
it still writes ordinary jsx6 JSX and binds signals; the system supplies the shared shapes, tokens
and navigation adapter.

### Package boundary

Proposed home: **`webview/ui/`**, package name **`@jcodebuddy/ui`** (private until it is deliberately
published). It is a reusable JCodeBuddy library, not a `project-automation` module and not a
driver project's private code. It consumes jsx6 through the local checkout and does not depend on
any `project-automation`.

The package should own:

- semantic tokens and light/dark themes;
- primitives and composed patterns;
- the navigation module and its host adapter;
- the shared jsx6 build definition (one `esbDef`, not four copies);
- accessibility and visual-regression fixtures.

It must not own a tool's data model, its API calls or its project-specific pages.

## 3. Foundations

### Semantic tokens

Tokens are named for purpose, never for a page:

```css
--jcb-color-canvas
--jcb-color-surface
--jcb-color-surface-raised
--jcb-color-text
--jcb-color-text-muted
--jcb-color-line
--jcb-color-accent
--jcb-color-success
--jcb-color-warning
--jcb-color-danger
--jcb-color-code-bg
--jcb-space-1 … --jcb-space-8
--jcb-radius-sm|md|lg|pill
--jcb-font-ui|mono
--jcb-shadow-1|2
--jcb-focus-ring
```

A page sets a **theme**, not colors: `light`, `dark`, or an app-supplied theme object. The JCEF
host and a standalone browser use the same tokens; the host can inject its IDE theme values later.

### Typographic and layout rules

- one page title, one section hierarchy, no page-specific font stacks;
- a content width and a dense mode (console/review) are tokens, not ad-hoc max-widths;
- focus rings are part of the primitive, not an afterthought;
- line height and code size are shared;
- all spacing comes from the scale.

## 4. Composition

### App shell

```jsx
<AppShell
  title="Compiler console"
  nav={nav}
  status={<ConnectionStatus state={$connection} />}
  toolbar={<Toolbar>…</Toolbar>}
>
  <PageHeader title="Build" subtitle={$headline} />
  <SplitView side={<LogView … />} main={<BuildPanel … />} />
</AppShell>
```

`AppShell` owns the page frame, the navigation region, the toolbar slot, the content region and
the status/footer region. A page supplies **slots**, not its own shell markup.

### Views and panels

- `PageHeader` — title, subtitle, breadcrumbs, actions.
- `Panel` / `Card` — a titled region with optional actions and footer.
- `SplitView` — resizable sidebar/content; the entity browser and graph pages both want this.
- `DataTable` — columns, selection, empty state, dense mode.
- `LogView` — virtualisable log/list surface for the compiler console.
- `TreeView` / `List` — selection, keyboard, empty state.
- `EmptyState` / `ErrorState` / `LoadingState` — every page uses the same wording pattern.
- `CodeBlock` / `DiffView` — shared language/line-number/diff presentation.

### Forms

Forms are composed, not styled per page:

```jsx
<Form onSubmit={save}>
  <Field label="Username" hint="No password for the playground">
    <TextInput value={$username} />
  </Field>
  <Field label="Room">
    <Select value={$room} options={$rooms} />
  </Field>
  <FormActions>
    <Button type="submit" disabled={$saving}>Create</Button>
    <Button variant="secondary" type="button">Cancel</Button>
  </FormActions>
</Form>
```

The form primitives own the label/description/error relationship, required and disabled states,
submit pending/error states, and keyboard behaviour. Validation is data passed to `Field`, not a
page-specific red border.

### Buttons, status and feedback

- `Button` variants: primary, secondary, quiet, danger; sizes: normal, compact.
- `IconButton` with an accessible label.
- `Status` / `Badge` / `Pill` for connection, build, provenance and confidence states.
- `Toast` and `InlineNotice` for transient and persistent feedback.
- `ConfirmDialog` for destructive actions; no page invents a modal from scratch.

## 5. Navigation as a module

Navigation is a first-class part of the design system, not a per-page `location.hash` handler.
The full API is in [`navigation.md`](navigation.md); the short version:

```js
const nav = createNavigation({
  mode: 'query' | 'hash' | 'path' | 'memory' | 'auto',
  home: { kind: 'home' },
  routes: [
    { id: 'readme', kind: 'file', match: p => p.endsWith('.md'), integration: 'inline' },
    { id: 'ioc', kind: 'page', path: 'hipster-ioc.html', integration: 'standalone' },
  ],
});
```

The module:

- parses and formats URLs for the selected mode;
- exposes `current`, `navigate(intent)`, `href(intent)` and `onLocationChange`;
- can be replaced or configured by an app (`routes`, `mode`, custom `parse`/`format`);
- works standalone (`history`/hash), inside the app shell (`jcodebuddyNavIntercept`) and as a
  standalone page (a normal link);
- treats **standalone** as the default for a page route and **inline** as an explicit app choice.

This is what lets one app open another app, embed an integrated version, or leave it standalone,
without every page inventing its own navigation rule.

## 6. Integration modes

The design system recognises three page integration levels:

| Level        | Meaning                                               | Navigation                                                                       |
| ------------ | ----------------------------------------------------- | -------------------------------------------------------------------------------- |
| `standalone` | a complete page under `/app/<page>`                   | normal document navigation; the host does not assume shared state                |
| `inline`     | the app renders the target inside its shell           | `jcodebuddyNavIntercept` returns `true`; URL is updated with `history.pushState` |
| `embed`      | the app embeds another app/page (iframe or component) | explicit, app-owned; still has a URL and a fallback link                         |

The **default is `standalone`**. A route may declare `inline` or `embed`; a build may then choose not
to emit the standalone page. Existing report pages remain standalone until that choice is made.

## 7. Migration plan

1. **Extract tokens + `AppShell`, `Button`, `Field`, `TextInput`, `Status`, `Panel`** from the
   closest current implementations (ChatApp for forms, compiler console for shell/status, entity
   browser for list/table, merge review for field/edit actions).
2. **Extract the four `esbDef` copies** into the UI package's build helper; keep the page builds
   thin.
3. **Implement `createNavigation` and a `NavProvider`**; use it first in the app shell, then in the
   entity browser and graph page.
4. **Migrate one page at a time**, starting with the compiler console (small, jsx6, clear shell)
   and ChatApp (forms), then entity browser and merge review. Each migration keeps its existing
   behaviour tests.
5. **Migrate the remaining pages, starting with the compiler console** (small, jsx6, clear shell)
   and ChatApp (forms), then entity browser and merge review. Each migration keeps its existing
   behaviour tests.
6. **Prepare the agent's per-tool split.** Its `AgentMethods` and page transport are now shared;
   a later split can publish narrower tool descriptors without changing the page contract.
7. **Add visual and keyboard fixtures** to the UI package; screenshots at a fixed viewport, light
   and dark, and a focus-order check.
8. **Make the design system the only place** that defines color, spacing, button, field and
   navigation patterns; a page-level raw color or ad-hoc nav handler becomes a review failure.

## 8. Open decisions

- Whether `@jcodebuddy/ui` is published beside the Java artifacts or stays a source-aliased
  in-repo package.
- Whether the first navigation mode defaults to `auto` (follow the host's `navigation.json`) or
  requires an explicit mode.
- Whether JCEF injects IDE theme tokens into the page, or the design system ships its own two
  themes and mirrors IDE colors later.
- Whether the agent's per-tool split keeps one socket with narrower capabilities or publishes one
  socket per tool.
- The component API freeze point: the system should stabilise `Button`/`Field`/`Panel`/`Nav` before
  migrating the reports.

## 9. References

- [jsx6 capability assessment](../jsx6-capability-assessment.md)
- [jsx6 agent rules](../../.jsx6/docs/stack/agent-rules.md)
- [webview app contract](../../webview/doc/webview-app-contract.md), especially
  **Optional SPA navigation** and **Standalone app pages are the default**
- [navigation module](navigation.md)
