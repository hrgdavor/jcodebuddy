# Navigation module — design

Status: **proposal, 2026-11-22.** Part of the [UI design system](README.md). This is the contract
that lets tools share location handling without forcing one URL spelling, one shell, or one
integration style.

## 1. Why a module

The current pages each solve a different part of the same problem:

- the entity browser selects a type with `location.hash`;
- the IoC graph carries `data-location` and click-through handlers;
- the compiler console cleans a token out of the address bar;
- merge review has block actions, not routes;
- ChatApp has forms and room selection;
- the app shell has query/hash routing and `window.jcodebuddyNavIntercept`.

They are all correct locally and all incompatible. A shared navigation module makes the page
describe **where it can go** and lets the shell/host decide **how that location is spelled and
performed**.

## 2. Intent

Navigation is expressed as an intent, not as a URL string:

```js
{
  kind: 'file' | 'page' | 'home' | 'external',
  path: 'README.md',          // file targets, project-relative
  page: 'hipster-ioc.html',   // page targets, app-relative
  href: 'https://…',          // external targets
  line: 12,                   // optional source position
  column: 1,
  fragment: 'L12',
  params: {}                  // app-defined route parameters
}
```

A route matches an intent; the navigation module formats and applies it. A page never builds
`?openFile=` or `#openFile=` by hand.

## 3. API

```js
import { createNavigation } from '@jcodebuddy/ui/navigation';

const nav = createNavigation({
  // Follow the host's navigation.json when present; explicit modes override it.
  mode: 'auto',                 // 'auto' | 'query' | 'hash' | 'path' | 'memory'
  host: window.jcodebuddyNavIntercept,
  home: { kind: 'home' },
  routes: [
    {
      id: 'readme',
      kind: 'file',
      match: (intent) => intent.path?.endsWith('.md'),
      integration: 'inline',
    },
    {
      id: 'ioc',
      kind: 'page',
      page: 'hipster-ioc.html',
      integration: 'standalone',
    },
  ],
});

nav.current         // signal: the current intent/route
nav.href(intent)    // string for an <a href>
nav.navigate(intent)// perform: inline hook, history push, or full navigation
nav.link(intent)    // JSX helper: <a href=… onclick=…>
nav.onChange(fn)    // subscription; returns a disposer
nav.mode()          // current effective mode
```

`current` is a jsx6 signal. `navigate` and `href` are pure enough to test without a browser; the
module takes `location`, `history` and the host hook as injected capabilities for tests.

## 4. Modes

| Mode     | URL spelling                        | Who owns it                             |
| -------- | ----------------------------------- | --------------------------------------- |
| `query`  | `/app/?openFile=README.md`          | the default                             |
| `hash`   | `/app/#openFile=README.md`          | the app's `navigation.json` opt-in      |
| `path`   | `/app/file/README.md`               | a host/app that serves path routes      |
| `memory` | no URL change                       | tests, embedded panels, temporary tools |
| `auto`   | follow host signal; query otherwise | the recommended page default            |

`auto` matters because a page can be used standalone (no host signal) and then served by a host
that chose hash routing. The app does not need two code paths.

A custom app may override `parse`/`format`:

```js
const nav = createNavigation({
  mode: 'custom',
  parse: (url) => …,
  format: (intent) => …,
  routes: […],
});
```

That is the “user-defined navigation” escape hatch; the built-in modes are presets, not a closed
enum.

## 5. Route integration

Each route declares how its target is shown:

```js
{ id: 'incremental-build', kind: 'page', page: 'incremental-build.html', integration: 'standalone' }
{ id: 'chat',              kind: 'page', page: 'chatapp.html',           integration: 'standalone' }
```

| Integration  | Behaviour                                                                                       |
| ------------ | ----------------------------------------------------------------------------------------------- |
| `standalone` | normal document navigation to `/app/<page>`; the current page is left                           |
| `inline`     | app calls `jcodebuddyNavIntercept`; if the app returns `true`, render in place and push the URL |
| `embed`      | app renders an embedded view (iframe/component) and keeps a fallback link                       |
| `external`   | ordinary link; never intercepted                                                                |

The **default is `standalone`**. A build may choose not to emit the standalone page once an
`inline`/`embed` version exists; the module then emits the integrated target instead. This is how
“open another app”, “show an integrated version” and “leave it standalone” become route
configuration rather than page-specific code.

## 6. Host contract

The module does not invent a second host contract. It uses the app contract:

- `window.jcodebuddyNavIntercept(target)` is called only when the app is already showing; return
  `true`/`"intercept"`/a URL string to render in place, `false` to let the host load a document.
- The host changes the actual URL with `history.pushState` after an intercept.
- The app root's `navigation.json` chooses the default URL spelling (`query` or `hash`).
- A page without the hook or the module still gets ordinary, full navigation.

The module's `host` option is that function. In a standalone page it is absent, so `navigate`
uses `history.pushState` or a normal `location` assignment.

## 7. Standalone and integrated

The navigation module must not make the host a requirement:

- **Standalone:** open the page with `file://` or a local server, use the same route table, and
  use `hash`/`memory` when there is no host. No `/app/` route or token is required.
- **App shell:** `mode: 'auto'` follows the host signal; inline routes are intercepted.
- **Embedded:** a parent app can pass a navigation object and an `embed` route; the child still has
  a URL and a fallback page.

The existing policy remains: **standalone is the default for additional apps**. Integration is a
decision per route, not a property every page gets automatically.

## 8. Composition with views

The page/route boundary is the design system's composition boundary:

```jsx
const nav = createNavigation({ … });
const route = nav.current;

<AppShell nav={nav}>
  <PageHeader title={route.title} />
  {route.id === 'readme' ? <MarkdownView path={route.path} /> : null}
  {route.id === 'ioc' ? <GraphView context={route.params.context} /> : null}
</AppShell>
```

Views receive a route intent, not a raw query string. A view renders; a route selects; the shell
composes. This is what lets the same view be standalone, a page under `/app/`, or embedded.

## 9. Forms and navigation

Forms follow the same boundary:

- a form is a view inside the current route; it does not parse the URL itself;
- submit success may `nav.navigate({ kind: 'page', page: '…' })`;
- validation errors stay in the form, not in the URL;
- a form that changes a filter may update route params through `nav.navigate({ params })`,
  depending on whether the filter is linkable.

The design system's `Form`/`Field` primitives own layout and validation presentation; the
navigation module owns the location.

## 10. Tests

The module should be tested as pure behaviour with injected `location`/`history`/host:

- parse/format round-trip for each built-in mode;
- `auto` follows a host signal and falls back to query;
- a standalone route performs a full navigation;
- an inline route calls the host hook, pushes the URL, and does not load a document;
- a host returning `false` falls back to full navigation;
- a custom route table and custom parser/formatter work;
- `onChange` fires once per location change and its disposer stops it;
- Back/Forward returns the previous intent.

Visual and keyboard tests belong to the design system's page fixtures, not to this module.

## 11. Migration

1. Implement `createNavigation` and its tests in `webview/ui/`.
2. Use it in the app shell for `openFile`, Home and source/page links; keep the host hook thin.
3. Move the entity browser's hash selection to a route; then the graph page.
4. Leave report pages standalone unless a route is explicitly marked `inline`/`embed`.
5. Migrate ChatApp forms to the design system's form primitives, using `nav.navigate` for room
   selection only if the room is linkable.

Open decisions are listed in the [design system README](README.md#8-open-decisions).
