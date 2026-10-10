/**
 * The page's jsx6 markup.
 *
 * Both components are **function components** — a function that receives the props object and returns
 * DOM — and that is a measured choice rather than a style. A `JsxW` subclass extends `HTMLElement`, so
 * the JSX runtime's class path (`new tag(attr, children, parent)`) throws `Illegal constructor`: a
 * plain `HTMLElement` may only be built through the element registry. Registering the class and using
 * the tag name does not avoid it either — `document.createElement` refuses a custom element whose
 * class sets attributes or children from its constructor, which is exactly what a template does. The
 * function form is the one the stack's own docs open with, and it composes with the editor element
 * (`jsx6-nodditor`, which nodditor registers itself) without touching that rule.
 */
import { signal } from '@jsx6/signal'

import { locationAttribute } from './navigate.js'

/**
 * The sidebar: one row per context — the name selects the block, and a second button opens the context's
 * source in an editor.
 *
 * The source button carries the joined location as `data-location`, the same attribute the blocks carry, so
 * the page's one delegated listener navigates for both and nothing here knows about hosts.
 *
 * @param {{contexts: Array, onSelect: Function}} attr
 */
function Sidebar({ contexts, onSelect }) {
  return (
    <aside class="graph-sidebar">
      <h1>hipster-ioc contexts</h1>
      <p class="graph-hint">Choose a context to select it in the graph; “source” opens its declaration.</p>
      <ul class="graph-list">
        {(contexts ?? []).map((context) => (
          <li>
            <button
              type="button"
              class="graph-list-item"
              title={context.id}
              onclick={() => onSelect(context)}
            >
              {context.simpleName || context.id}
            </button>
            <button
              type="button"
              class="graph-list-source"
              title={context.location
                ? `${context.location.path}:${context.location.line}`
                : 'no source location: the class index does not know this context'}
              disabled={!context.location}
              data-location={locationAttribute(context.location)}
            >
              source
            </button>
          </li>
        ))}
      </ul>
    </aside>
  )
}

/**
 * The page shell: sidebar, the editor element, and the status line.
 *
 * The editor arrives as a **node the caller built**, not as `<jsx6-nodditor …/>`. That is a measured
 * consequence of the custom-element contract: the JSX runtime creates a string tag through
 * `document.createElement(tag, options)`, and passing options makes the browser refuse the upgrade
 * (`The result must not have attributes` / `must not have children`) unless the element's class defers
 * its DOM build — which is exactly what was reported to `jsx6` and fixed in its `JsxW` (the constructor
 * now builds only when the element is already connected, and otherwise waits for `connectedCallback`).
 * Building the element here and inserting it as an existing node works on both sides of that fix, and it
 * is what lets `main.js` hold the reference it later hands the graph to — the render → inspect → wire
 * order nodditor's host guide requires.
 *
 * The status is a signal read as a child, so updating it rewrites one text node and leaves the rest of
 * the page alone — the binding the stack's own example demonstrates, and it is published on `globalThis`
 * because the page updates it after wiring the graph.
 *
 * `navigable` and `navHint` say what navigation this page has (step 3.11a): the class on the root lets the
 * stylesheet mark the elements that lead somewhere, and the hint tells a reader on a `file://` page why a
 * click does nothing.
 *
 * @param {{contexts: Array, onSelect: Function, status: string, editor: Element, navigable?: boolean, navHint?: string}} attr
 */
export function GraphShell(attr) {
  const status = signal(attr.status ?? 'loading contexts.json …')
  globalThis.__HIPSTER_IOC_STATUS__ = status

  return (
    <div class={attr.navigable ? 'graph-app graph-navigable' : 'graph-app'}>
      <Sidebar contexts={attr.contexts ?? []} onSelect={attr.onSelect ?? (() => {})} />
      <main class="graph-stage">{attr.editor}</main>
      <footer class="graph-status">
        <span class="graph-nav-hint">{attr.navHint ?? ''}</span>
        <span class="graph-status-line">{status}</span>
      </footer>
    </div>
  )
}
