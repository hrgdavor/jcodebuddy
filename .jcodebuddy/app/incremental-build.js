// ../../.jsx6/libs/jsx6/src/errorCodes.js
var JSX6E1_NULL_TAG = 1;
var JSX6E2_UNSUPPORTED_TAG = 2;
var JSX6E7_REQUIRE_FUNC = 7;
var JSX6E8_REQUIRE_PARENT = 8;
var JSX6E9_LISTENER_MUST_BE_FUNC = 9;
var JSX6E10_CONTEXT_REQUIRED = 10;
var JSX6E15_MULTIPLE_VERSIONS = 15;

// ../../.jsx6/libs/jsx6/src/core.js
var TRANS = {};
var Group = class {
  constructor(obj) {
    for (let p in obj) this[p] = obj[p];
  }
};
function t(code, ...rest) {
  if (code instanceof Array) {
    if (rest.length) {
      const tmp = [code[0]];
      for (let i = 1; i < code.length; i++) {
        tmp.push(rest[i - 1]);
        tmp.push(code[i]);
      }
      code = tmp;
    }
    code = code.join("");
  }
  return TRANS[code] || code;
}
var errorMessage = (c) => t(errCode(c));
var throwErr = (c, ...info) => {
  const code = errCode(c);
  let msg = t(code);
  if (msg != code) msg = code + " " + msg;
  console.error(msg, ...info);
  throw new Error(msg);
};
var isFunc = (f) => typeof f === "function";
var isStr = (s) => typeof s === "string";
var isObj = (o) => typeof o === "object";
var isArray = (a) => a instanceof Array;
var isNode = (obj) => obj?.nodeType !== void 0;
var requireFunc = (func, err = JSX6E7_REQUIRE_FUNC) => {
  if (!func || !isFunc(func)) {
    throwErr(err, { func });
  }
  return func;
};
var errCode = (c) => "JSX6E" + c;

// ../../.jsx6/libs/jsx6/src/toDomNode.js
function toDomNode(n) {
  return !n || isNode(n) ? n : n.el || n;
}

// ../../.jsx6/libs/signal/src/observe.js
var subscribeSymbol = Symbol.for("signalSubscribe");
var triggerSymbol = Symbol.for("signalTrigger");
var observeNow = ($signal, callback) => observe($signal, callback, true);
var observe = ($signal, callback, trigger = false) => _observe($signal, callback, trigger, true);
function _observe(obj, callback, trigger = false, passValue = false) {
  let bindingSub;
  let value;
  let unsubscribe;
  if (obj) {
    bindingSub = obj[subscribeSymbol];
    if (bindingSub) {
      if (callback) {
        const wrapped = passValue ? () => callback(obj()) : () => callback();
        unsubscribe = bindingSub(wrapped);
      }
      if (passValue) value = obj();
    } else {
      bindingSub = obj.then || obj.subscribe;
      if (bindingSub && callback) {
        const wrapped = passValue ? callback : () => callback();
        bindingSub.call(obj, wrapped);
      } else {
        if (passValue) value = obj;
      }
    }
  }
  if (trigger) callback(value);
  return unsubscribe;
}

// ../../.jsx6/libs/signal/src/track.js
var trackState = (
  /** @type {{ collector: Set<Function> | null }} */
  { collector: null }
);

// ../../.jsx6/libs/signal/src/trace.js
var signalsTraced = false;
var traceOptions = {
  /** Capture a creation site per signal. This is the expensive part of an enabled session. */
  origin: true,
  /** Attach the listener set, so a debugger can answer "what depends on this". */
  listeners: true
};
var metaSymbol = Symbol.for("signalMeta");
var mergeValueSymbol = Symbol.for("signalMergeValue");
var LIBRARY_FILES = /[\\/](?:signal|computed|state|track|observe|debug|trace|trace-signal)\.js:\d+:\d+\)?$/;
var markFile = (() => {
  try {
    return String(new Error().stack).split("\n")[1]?.match(/\(?([^()\s]+?):\d+:\d+\)?$/)?.[1] || "";
  } catch {
    return "";
  }
})();
var lineFile = (line) => line.match(/\(?([^()\s]+?):\d+:\d+\)?$/)?.[1] || "";
var isInternal = (line) => {
  if (!line || line.includes("new Error")) return true;
  if (markFile && line.includes(markFile)) return true;
  return LIBRARY_FILES.test(line);
};
var siteOfLine = (line, raw) => {
  const m = line.match(/\(?([^()\s]+?):(\d+):(\d+)\)?$/);
  if (!m) return null;
  return { text: `${m[1]}:${m[2]}:${m[3]}`, file: m[1], line: +m[2], column: +m[3], raw };
};
var extraInternal = [];
var captureFrom = (raw, skip = 0) => {
  const stack = String(raw || "");
  const lines = stack.split("\n").slice(1 + skip);
  for (const line of lines) {
    if (isInternal(line)) continue;
    let own = false;
    for (const file of extraInternal) {
      if (line.includes(file)) {
        own = true;
        break;
      }
    }
    if (own) continue;
    const site = siteOfLine(line, stack);
    if (site) return site;
  }
  for (const line of lines) {
    if (!line || line.includes("new Error")) continue;
    if (markFile && lineFile(line) === markFile) continue;
    const site = siteOfLine(line, stack);
    if (site) return site;
  }
  return { text: "", file: "", line: 0, column: 0, raw: stack };
};
var isComputed = ($signal) => Boolean(
  /** @type {any} */
  $signal?.__computed
);
var attachTrace = ($signal, info = {}) => {
  if (!signalsTraced || !$signal) return void 0;
  const record = {
    signal: $signal,
    kind: info.kind || (isComputed($signal) ? "computed" : "signal"),
    origin: info.origin && traceOptions.origin ? typeof info.origin === "object" ? info.origin : captureFrom(new Error().stack) : null,
    listeners: traceOptions.listeners ? info.listeners || null : null,
    getter: info.getter || null,
    deps: info.deps || null
  };
  Object.defineProperty($signal, metaSymbol, { value: record, configurable: true });
  return record;
};

// ../../.jsx6/libs/signal/src/signal.js
var ValueSymbol = Symbol.for("signalValue");
function signal(value, name) {
  return prepareSignal(value, name).$signal;
}
function prepareSignal(value, name) {
  const listeners = /* @__PURE__ */ new Set();
  const origin = name && signalsTraced ? captureFrom(new Error().stack) : null;
  function setValue2(v) {
    if (v === value) return;
    value = v;
    return true;
  }
  const $signal = (...args) => {
    if (args.length === 0) {
      const collector = trackState.collector;
      if (collector !== null) collector.add($signal);
      return value;
    }
    if (setValue2(args[0])) {
      fireChanged();
      return true;
    }
  };
  Object.defineProperty($signal, ValueSymbol, { get: $signal });
  Object.defineProperty($signal, "value", { get: $signal, configurable: true });
  if (name) {
    $signal.label = name;
    Object.defineProperty($signal, "name", { value: name });
  }
  const fireChanged = () => {
    listeners.forEach(runFuncNoArg);
  };
  $signal[subscribeSymbol] = (u) => {
    if (typeof u != "function") throw "listener must be a function";
    listeners.add(u);
    return () => listeners.delete(u);
  };
  $signal[triggerSymbol] = fireChanged;
  $signal[Symbol.toPrimitive] = $signal.get = () => value;
  if (origin) attachTrace($signal, { kind: "signal", listeners, origin });
  return { $signal, fireChanged, listeners, setValue: setValue2 };
}
var runFuncNoArg = (f) => {
  try {
    f();
  } catch (e) {
    console.error(e, f);
  }
};

// ../../.jsx6/libs/signal-dom/index.js
var anim = (func) => func();
if (typeof document !== "undefined") {
  anim = window.requestAnimationFrame.bind(window);
}
var isNode2 = (obj) => obj?.nodeType !== void 0;
function setAttribute(node, attrName, newValue) {
  if (newValue === false || newValue === void 0) newValue = null;
  if (newValue === true) newValue = attrName;
  if (!isNode2(node) && isNode2(node?.el)) node = node.el;
  if (node.getAttribute(attrName) !== newValue) {
    if (newValue === null) {
      node.removeAttribute(attrName);
    } else {
      node.setAttribute(attrName, newValue);
    }
  }
}

// ../../.jsx6/libs/jsx6/src/mapProp.js
function mapProp(obj, callback, asArray) {
  if (obj) {
    if (isArray(obj)) {
      return obj.map(callback);
    } else if (isObj(obj)) {
      const out = asArray ? [] : {};
      if (asArray) {
        for (const p in obj) {
          out.push(callback(obj[p], p, obj));
        }
      } else {
        for (const p in obj) {
          out[p] = callback(obj[p], p, obj);
        }
      }
      return out;
    }
  }
}

// ../../.jsx6/libs/jsx6/src/setValue.js
var setValueFilterSymbol = Symbol.for("setValueFilterSymbol");
function applySetValueFilter(value, source) {
  let filter = source[setValueFilterSymbol];
  return filter ? filter(value) : value;
}
function setValue(obj, value) {
  if (obj === null || obj === void 0) return;
  value = applySetValueFilter(value, obj);
  if (isFunc(obj.setValue)) return obj.setValue(value);
  if (isFunc(obj)) return setValue(obj(), value);
  if (isNode(obj)) {
    if (value === void 0 || value === null) value = "";
    if (obj.tagName === "INPUT" && obj.type === "checkbox") {
      obj.checked = value;
    } else {
      obj.value = value;
    }
  } else {
    value = value || {};
    mapProp(obj, (o, p) => {
      if (o) setValue(o, value[p]);
    });
  }
}

// ../../.jsx6/libs/jsx6/src/getValue.js
var getValueFilterSymbol = Symbol.for("getValueFilterSymbol");
function applyGetValueFilter(value, source) {
  let filter = source[getValueFilterSymbol];
  return filter ? filter(value) : value;
}
function getValue(obj) {
  if (obj === null || obj === void 0) return obj;
  let value = obj.value;
  if (isFunc(obj.getValue)) {
    value = obj.getValue();
  } else if (isFunc(obj)) {
    value = getValue(obj());
  } else if (isNode(obj)) {
    if (obj.tagName === "INPUT" && obj.type === "checkbox") {
      value = obj.checked;
    }
  } else {
    if (isObj(obj)) return mapProp(obj, getValue);
  }
  return applyGetValueFilter(value, obj);
}

// ../../.jsx6/libs/jsx6/src/dispose.js
var jsxDisposeSymbol = Symbol.for("jsx6_dispose");
function addDisposer(node, dispose) {
  if (!node || typeof node !== "object" || typeof dispose !== "function") return dispose;
  let list = node[jsxDisposeSymbol];
  if (!list) {
    list = [];
    node[jsxDisposeSymbol] = list;
  }
  list.push(dispose);
  return dispose;
}
function release(node) {
  const list = node[jsxDisposeSymbol];
  if (!list || list.length === 0) return 0;
  node[jsxDisposeSymbol] = [];
  let count = 0;
  for (const dispose of list) {
    try {
      dispose();
      count++;
    } catch (error) {
      console.error("jsx6 dispose failed", error);
    }
  }
  return count;
}
function disposeNode(node) {
  return walkDispose(node);
}
function walkDispose(node) {
  if (!node || typeof node !== "object") return 0;
  if (node instanceof Array) {
    let count2 = release(node);
    for (const child of node) count2 += walkDispose(child);
    return count2;
  }
  const el = toDomNode(node);
  let count = 0;
  if (el !== node) {
    count += release(node);
  }
  if (!el || typeof el !== "object") return count;
  const children = el.childNodes;
  if (children) {
    const snapshot = Array.from(children);
    for (const child of snapshot) count += walkDispose(child);
  }
  count += release(el);
  return count;
}

// ../../.jsx6/libs/jsx6/src/directives.js
var directives = {};
function addDirective(key, directive) {
  directives[key] = directive;
}
addDirective("x-if", (el, a, $signal, self) => {
  let updater = (v) => setAttribute(el, "hidden", !v);
  addDisposer(el, observeNow($signal, updater));
});
addDirective("x-else", (el, a, $signal, self) => {
  let updater = (v) => setAttribute(el, "hidden", !!v);
  addDisposer(el, observeNow($signal, updater));
});
addDirective("x-enabled", (el, a, $signal, self) => {
  let updater = (v) => setAttribute(el, "disabled", !v);
  addDisposer(el, observeNow($signal, updater));
});
addDirective("x-disabled", (el, a, $signal, self) => {
  let updater = (v) => setAttribute(el, "disabled", !!v);
  addDisposer(el, observeNow($signal, updater));
});
addDirective("x-readonly", (el, a, $signal, self) => {
  let updater = (v) => setAttribute(el, "readonly", !!v);
  addDisposer(el, observeNow($signal, updater));
});
addDirective("x-value", (el, a, $signal, self) => {
  let updater = (v) => setValue(el, v);
  addDisposer(el, observeNow($signal, updater));
  const onInput = (e) => $signal(getValue(el));
  el.addEventListener?.("input", onInput);
  addDisposer(el, () => el.removeEventListener?.("input", onInput));
});
addDirective("x-filter", (el, a, filters, self) => {
  if (isArray(filters)) {
    if (filters[0]) el[getValueFilterSymbol] = requireFunc(filters[0]);
    if (filters[1]) el[setValueFilterSymbol] = requireFunc(filters[1]);
  } else {
    el[getValueFilterSymbol] = requireFunc(filters);
  }
});

// ../../.jsx6/libs/jsx6/src/remove.js
function remove(child) {
  const _child = toDomNode(child);
  disposeNode(_child);
  const parent = _child?.parentNode;
  try {
    parent.removeChild(_child);
  } catch (error) {
    console.error(`failed to remove child: ${describe(_child)} (parent: ${describe(parent)})`);
    throw error;
  }
}
var describe = (node) => node ? `${node.nodeName || node.constructor?.name || typeof node}` : String(node);

// ../../.jsx6/libs/jsx6/src/jsx2dom.js
var markerSymbol = Symbol.for("jsx2dom_marker");
var scopeSymbol = Symbol.for("jsx2dom_scope");
if (globalThis[markerSymbol]) console.warn(errorMessage(JSX6E15_MULTIPLE_VERSIONS), globalThis[markerSymbol]);
globalThis[markerSymbol] = new Error("marker");
var getScope = () => globalThis[scopeSymbol];
var setScope = (s) => globalThis[scopeSymbol] = s;
function toDom(tag, attr, children) {
  if (!tag) return children;
  let oncreate = attr?.oncreate;
  if (oncreate) delete attr.oncreate;
  let out;
  if (isStr(tag)) {
    out = factories.Element(tag);
    insertAttr(attr, out);
    insert(out, children);
  } else {
    if (isFunc(tag)) {
      const { p } = attr;
      const parent = getScope();
      let innerScope = {};
      try {
        setScope(innerScope);
        if (tag.prototype) {
          out = new tag(attr, children, parent);
        } else {
          out = tag(attr, children, innerScope, parent);
        }
        if (p) setPropGroup(parent, out, p);
      } finally {
        setScope(parent);
      }
    } else if (isNode(tag)) {
      out = tag;
    } else if (isObj(tag)) {
      out = nodeFromObservable(tag) || throwErr(JSX6E2_UNSUPPORTED_TAG, tag);
    } else {
      throwErr(JSX6E2_UNSUPPORTED_TAG, tag);
    }
  }
  if (oncreate) {
    if (isArray(oncreate)) {
      oncreate.forEach((f) => f(out, getScope()));
    } else {
      oncreate(out, getScope());
    }
  }
  return out;
}
function nodeFromObservable(obj) {
  const textNode = factories.Text("");
  const out = [textNode];
  const updater = (r) => {
    if (r instanceof Array && r.length === 1) r = r[0];
    let node = toDomNode(r);
    const parent = textNode.parentNode;
    if (parent) {
      while (out.length > 1) {
        const toRemove = out.shift();
        remove(toRemove);
      }
    } else if (out.length > 1) {
      out.length = 1;
      out[0] = textNode;
    }
    if (isFunc(node)) node = node(parent);
    if (isNode(node)) {
      updateTextNode(textNode, "");
      if (parent) insert(parent, node, textNode);
      out.length = 2;
      out[0] = node;
      out[1] = textNode;
    } else if (r instanceof Array) {
      updateTextNode(textNode, "");
      if (parent) r = insert(parent, r, textNode);
      out.length = r.length;
      for (let i = 0; i < r.length; i++) out[i] = r[i];
      out.push(textNode);
    } else {
      updateTextNode(textNode, factories.TextValue(r));
    }
  };
  addDisposer(textNode, observeNow(obj, updater));
  return out;
}
function updateTextNode(node, text) {
  if (node.textContent !== text) node.textContent = text;
}
var TextValue = (v) => {
  if (v === null || v === void 0) return "";
  if (!isStr(v)) return "" + v;
  return v;
};
var makeAttrUpdater = (node, attr, func) => {
  const out = () => {
    setAttribute(node, attr, func());
  };
  out.node = node;
  out.attr = attr;
  out.func = func;
  return out;
};
function insertAttr(attr, out, self, component) {
  if (!attr) return;
  if (!self) self = getScope();
  for (let a in attr) {
    let value = attr[a];
    if (a[0] === "o" && a[1] === "n") {
      if (isFunc(value)) {
        const eventName = a.substring(2).toLowerCase();
        const listener = value.bind(self);
        out.addEventListener(eventName, listener);
        if (typeof out.removeEventListener === "function") {
          addDisposer(out, () => out.removeEventListener(eventName, listener));
        }
      } else {
        throwErr(JSX6E9_LISTENER_MUST_BE_FUNC, attr);
      }
      value = void 0;
    } else if (a === "key") {
      out.loopKey = value;
      if (!out.$key) {
        out.$key = value;
      }
      if (component) {
        if (!component.$key) {
          component.$key = value;
        }
        component.loopKey = value;
      }
    } else if (a[0] === "x") {
      let directive = directives[a];
      if (directive) {
        directive(out, a, value, self);
        value = null;
      }
    } else if (a === "p") {
      setPropGroup(self, component || out, value);
    }
    if (value !== void 0) {
      if (isFunc(value)) {
        let updater = makeAttrUpdater(out, a, value);
        addDisposer(out, observeNow(value, updater));
      } else if (out.setAttribute) {
        setAttribute(out, a, value);
      }
    }
  }
}
function setPropGroup(self, part, path) {
  if (isFunc(path)) {
    return path(part);
  }
  if (isStr(path)) path = path.split(".");
  const [$group, $key] = path;
  if (self === void 0) throw throwErr(JSX6E10_CONTEXT_REQUIRED);
  if ($key) {
    if (!self[$group]) self[$group] = new Group();
    self[part.$group = $group][part.$key = $key] = part;
  } else {
    self[part.$key = $group] = part;
  }
}
var forInsertFuncObj = (newChild) => {
  let maybe = nodeFromObservable(newChild);
  if (maybe?.length === 1) maybe = maybe[0];
  return maybe || factories.Text(factories.TextValue(newChild));
};
function forInsert(newChild) {
  if (newChild instanceof Array) {
    return newChild.map((c) => forInsert(c));
  }
  if (isFunc(newChild)) {
    newChild = forInsertFuncObj(newChild);
  } else if (!isNode(newChild)) {
    if (isNode(newChild.el)) {
      newChild = newChild.el;
    } else {
      newChild = forInsertFuncObj(newChild);
    }
  }
  return newChild;
}
function insert(parent, newChild, before = void 0) {
  if (newChild === void 0 || newChild === null) return;
  if (!parent) throwErr(JSX6E8_REQUIRE_PARENT, { parent, newChild, before });
  if (newChild instanceof Array) {
    return newChild.map((c) => insert(parent, c, before));
  }
  const _parent = parent.insertBefore ? parent : toDomNode(parent);
  if (!_parent.insertBefore) console.error("missing insertBefore", _parent, parent);
  let _newChild;
  try {
    _newChild = forInsert(newChild);
    if (_newChild instanceof Array) {
      _newChild.forEach((c) => _parent.insertBefore(c, toDomNode(before)));
    } else {
      _parent.insertBefore(_newChild, toDomNode(before));
    }
  } catch (error) {
    console.error("parent", parent, "newChild", newChild, "before", before, "forInsert", _newChild);
    throw error;
  }
  return _newChild;
}
var factories = {
  TextValue,
  Text: (t2) => document.createTextNode(t2),
  Svg: (t2) => t2 ? document.createElementNS("http://www.w3.org/2000/svg", t2) : throwErr(JSX6E1_NULL_TAG),
  Html: (t2, o) => t2 ? document.createElement(t2, o) : throwErr(JSX6E1_NULL_TAG),
  Element: (t2, o) => factories.Html(t2, o)
};

// ../../.jsx6/libs/jsx-runtime/index.js
function jsx(tag, { children, ...attr }) {
  if (tag === Fragment) return children;
  return toDom(tag, attr, children);
}
var Fragment = (attr, children) => children;

// src/index.jsx
var TOOL = "jcodebuddy-incremental-build";
var MAX_LINES = 2e3;
var LOG_LIMIT = 200;
var TOKEN_KEY = "jcodebuddy.token";
function resolveToken() {
  const fromUrl = new URLSearchParams(location.search).get("token") || "";
  if (fromUrl) {
    try {
      localStorage.setItem(TOKEN_KEY, fromUrl);
      const clean = new URL(location.href);
      clean.searchParams.delete("token");
      history.replaceState(null, "", clean);
    } catch {
    }
    return fromUrl;
  }
  try {
    return localStorage.getItem(TOKEN_KEY) || "";
  } catch {
    return "";
  }
}
var TOKEN = resolveToken();
var $chips = signal("connecting\u2026");
var $headline = signal("");
insert(document.getElementById("page"), /* @__PURE__ */ jsx("div", { class: "wrap", children: [
  /* @__PURE__ */ jsx("header", { class: "head", children: [
    /* @__PURE__ */ jsx("div", { children: [
      /* @__PURE__ */ jsx("h1", { children: "JCodeBuddy Incremental Build" }),
      /* @__PURE__ */ jsx("p", { class: "sub", children: [
        "live tool endpoint ",
        /* @__PURE__ */ jsx("code", { children: TOOL })
      ] })
    ] }),
    /* @__PURE__ */ jsx("p", { class: "chips", children: $chips })
  ] }),
  /* @__PURE__ */ jsx("p", { class: "status", children: $headline }),
  /* @__PURE__ */ jsx("div", { class: "bar", children: [
    /* @__PURE__ */ jsx("button", { onclick: () => command("build.rebuild"), children: "Rebuild" }),
    /* @__PURE__ */ jsx("button", { onclick: () => command("build.watch.stop"), children: "Stop Watch" }),
    /* @__PURE__ */ jsx("button", { onclick: () => command("build.watch.start"), children: "Start Watch" }),
    /* @__PURE__ */ jsx("button", { onclick: () => command("build.refreshErrors"), children: "Refresh Errors" }),
    /* @__PURE__ */ jsx("button", { onclick: () => command("build.refreshRoots"), children: "Refresh Roots" }),
    /* @__PURE__ */ jsx("button", { onclick: () => command("build.listClasspath"), children: "Detailed Classpath" })
  ] }),
  /* @__PURE__ */ jsx("div", { class: "bar", children: [
    /* @__PURE__ */ jsx("button", { onclick: () => show("build.roots"), children: "List Roots" }),
    /* @__PURE__ */ jsx("button", { onclick: () => show("build.diagnostics"), children: "Diagnostics" }),
    /* @__PURE__ */ jsx("button", { onclick: clearView, children: "Clear View" })
  ] }),
  /* @__PURE__ */ jsx("pre", { class: "info", id: "info" }),
  /* @__PURE__ */ jsx("pre", { class: "log", id: "log" }),
  /* @__PURE__ */ jsx("footer", { class: "foot", id: "foot" })
] }));
var logElement = document.getElementById("log");
var infoElement = document.getElementById("info");
var footElement = document.getElementById("foot");
var cursor = 0;
var polling = false;
var statusTimer;
var stream;
if (!TOKEN) {
  $chips("token required");
  $headline(
    "Open this page once with ?token=<host token> (or open it from the IDE, which injects it). The token is remembered in localStorage, so later links without it keep working. Without a token the host refuses every tool call, because a call can change the build."
  );
  for (const button of document.querySelectorAll("button")) {
    button.disabled = true;
  }
} else {
  start();
}
function start() {
  void refreshStatus();
  void catchUp().then(openStream);
  statusTimer = setInterval(refreshStatus, 3e3);
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) {
      void refreshStatus();
    }
  });
  window.addEventListener("beforeunload", () => {
    clearInterval(statusTimer);
    if (stream) {
      stream.close();
    }
  });
}
async function call(method, params = {}) {
  const response = await fetch(`/api/tools/${TOOL}/call`, {
    method: "POST",
    cache: "no-store",
    headers: { "Content-Type": "application/json", "X-WebView-Token": TOKEN },
    body: JSON.stringify({ method, params })
  });
  let body = {};
  try {
    body = await response.json();
  } catch {
  }
  if (!response.ok) {
    throw new Error(body.message || body.error || `HTTP ${response.status}`);
  }
  return body.result;
}
async function command(method) {
  try {
    await call(method, {});
    $headline(`${method}: accepted`);
    void refreshStatus();
  } catch (error) {
    noteFailure(error);
  }
}
async function show(method) {
  try {
    const result = await call(method, {});
    infoElement.textContent = format(method, result);
  } catch (error) {
    noteFailure(error);
  }
}
function format(method, value) {
  if (method === "build.roots") {
    const roots = value ?? [];
    if (roots.length === 0) {
      return "no Java source roots";
    }
    return roots.map((root) => `${root.test ? "TEST" : "MAIN"} [${root.module}] ${root.root} -> ${root.output}`).join("\n");
  }
  if (method === "build.diagnostics") {
    const diagnostics = value ?? [];
    if (diagnostics.length === 0) {
      return "no diagnostics";
    }
    return diagnostics.map((entry) => `${entry.file}:${entry.line}:${entry.column}  ${entry.message}`).join("\n");
  }
  return JSON.stringify(value, null, 2);
}
async function refreshStatus() {
  if (polling || document.hidden) {
    return;
  }
  polling = true;
  try {
    const status = await call("build.status", {});
    $chips(
      `${status.enabled ? "enabled" : "disabled"} \xB7 ${status.running ? "running" : "stopped"} \xB7 watch: ${status.watch} \xB7 batch ${status.batchMarker}`
    );
    const diagnostics = status.diagnostics ?? {};
    const daemon = status.daemon ?? {};
    $headline(
      `${status.failedFiles ?? 0} file(s) waiting for retry \xB7 ${diagnostics.files ?? 0} file(s) with diagnostics \xB7 daemon ${daemon.root || "not attached"}`
    );
    footElement.textContent = `build log: ${status.log?.file ?? ""} \u2014 ${status.log?.size ?? 0} line(s) retained, newest sequence ${status.log?.last ?? 0}`;
  } catch (error) {
    noteFailure(error);
  } finally {
    polling = false;
  }
}
async function catchUp() {
  try {
    const page = await call("build.log", { limit: LOG_LIMIT });
    appendLines(page.lines ?? []);
    cursor = Number(page.next ?? cursor);
  } catch (error) {
    noteFailure(error);
  }
}
function openStream() {
  const url = `/api/tools/${TOOL}/events?topic=log&since=${cursor}&token=${encodeURIComponent(TOKEN)}`;
  stream = new EventSource(url);
  stream.addEventListener("tool-event", (message) => {
    let payload;
    try {
      payload = JSON.parse(message.data);
    } catch {
      return;
    }
    if (payload.topic !== "log" || !payload.data) {
      return;
    }
    const line = payload.data;
    const sequence = Number(line.sequence);
    if (!Number.isFinite(sequence)) {
      return;
    }
    if (sequence < cursor) {
      logElement.textContent = "";
      cursor = 0;
      $headline("the tool restarted; the log view was reloaded");
    }
    if (sequence <= cursor) {
      return;
    }
    appendLines([line]);
    cursor = sequence;
  });
  stream.onopen = () => {
    void refreshStatus();
  };
  stream.onerror = () => {
    $chips("stream disconnected \u2014 reconnecting\u2026");
  };
}
function appendLines(entries) {
  if (entries.length === 0) {
    return;
  }
  const atBottom = logElement.scrollHeight - logElement.scrollTop - logElement.clientHeight < 16;
  for (const entry of entries) {
    logElement.append(document.createTextNode(`${entry.text}
`));
  }
  while (logElement.childNodes.length > MAX_LINES) {
    logElement.firstChild.remove();
  }
  if (atBottom) {
    logElement.scrollTop = logElement.scrollHeight;
  }
}
function clearView() {
  logElement.textContent = "";
  infoElement.textContent = "";
  $headline("view cleared; the host keeps its log buffer");
}
function noteFailure(error) {
  const message = error instanceof Error ? error.message : String(error);
  $chips("not connected");
  $headline(
    `${message} \u2014 is the incremental build enabled in the IDE, and is this page served by the host that proxies .jcodebuddy/ipc?`
  );
}
//# sourceMappingURL=incremental-build.js.map
