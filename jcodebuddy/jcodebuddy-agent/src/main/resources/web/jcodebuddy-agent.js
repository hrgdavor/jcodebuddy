// ../../../.jsx6/libs/jsx6/src/errorCodes.js
var JSX6E1_NULL_TAG = 1;
var JSX6E2_UNSUPPORTED_TAG = 2;
var JSX6E7_REQUIRE_FUNC = 7;
var JSX6E8_REQUIRE_PARENT = 8;
var JSX6E9_LISTENER_MUST_BE_FUNC = 9;
var JSX6E10_CONTEXT_REQUIRED = 10;
var JSX6E15_MULTIPLE_VERSIONS = 15;

// ../../../.jsx6/libs/jsx6/src/core.js
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

// ../../../.jsx6/libs/jsx6/src/toDomNode.js
function toDomNode(n) {
  return !n || isNode(n) ? n : n.el || n;
}

// ../../../.jsx6/libs/signal/src/observe.js
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

// ../../../.jsx6/libs/signal/src/track.js
var trackState = (
  /** @type {{ collector: Set<Function> | null }} */
  { collector: null }
);

// ../../../.jsx6/libs/signal/src/trace.js
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

// ../../../.jsx6/libs/signal/src/signal.js
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

// ../../../.jsx6/libs/signal-dom/index.js
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

// ../../../.jsx6/libs/jsx6/src/mapProp.js
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

// ../../../.jsx6/libs/jsx6/src/setValue.js
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

// ../../../.jsx6/libs/jsx6/src/getValue.js
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

// ../../../.jsx6/libs/jsx6/src/dispose.js
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

// ../../../.jsx6/libs/jsx6/src/directives.js
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

// ../../../.jsx6/libs/jsx6/src/remove.js
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

// ../../../.jsx6/libs/jsx6/src/jsx2dom.js
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

// node_modules/diff/libesm/diff/base.js
var Diff = class {
  diff(oldStr, newStr, options = {}) {
    let callback;
    if (typeof options === "function") {
      callback = options;
      options = {};
    } else if ("callback" in options) {
      callback = options.callback;
    }
    const oldString = this.castInput(oldStr, options);
    const newString = this.castInput(newStr, options);
    const oldTokens = this.removeEmpty(this.tokenize(oldString, options));
    const newTokens = this.removeEmpty(this.tokenize(newString, options));
    return this.diffWithOptionsObj(oldTokens, newTokens, options, callback);
  }
  diffWithOptionsObj(oldTokens, newTokens, options, callback) {
    var _a;
    const done = (value) => {
      value = this.postProcess(value, options);
      if (callback) {
        setTimeout(function() {
          callback(value);
        }, 0);
        return void 0;
      } else {
        return value;
      }
    };
    const newLen = newTokens.length, oldLen = oldTokens.length;
    let editLength = 1;
    let maxEditLength = newLen + oldLen;
    if (options.maxEditLength != null) {
      maxEditLength = Math.min(maxEditLength, options.maxEditLength);
    }
    const maxExecutionTime = (_a = options.timeout) !== null && _a !== void 0 ? _a : Infinity;
    const abortAfterTimestamp = Date.now() + maxExecutionTime;
    const bestPath = [{ oldPos: -1, lastComponent: void 0 }];
    let newPos = this.extractCommon(bestPath[0], newTokens, oldTokens, 0, options);
    if (bestPath[0].oldPos + 1 >= oldLen && newPos + 1 >= newLen) {
      return done(this.buildValues(bestPath[0].lastComponent, newTokens, oldTokens));
    }
    let minDiagonalToConsider = -Infinity, maxDiagonalToConsider = Infinity;
    const execEditLength = () => {
      for (let diagonalPath = Math.max(minDiagonalToConsider, -editLength); diagonalPath <= Math.min(maxDiagonalToConsider, editLength); diagonalPath += 2) {
        let basePath;
        const removePath = bestPath[diagonalPath - 1], addPath = bestPath[diagonalPath + 1];
        if (removePath) {
          bestPath[diagonalPath - 1] = void 0;
        }
        let canAdd = false;
        if (addPath) {
          const addPathNewPos = addPath.oldPos - diagonalPath;
          canAdd = addPath && 0 <= addPathNewPos && addPathNewPos < newLen;
        }
        const canRemove = removePath && removePath.oldPos + 1 < oldLen;
        if (!canAdd && !canRemove) {
          bestPath[diagonalPath] = void 0;
          continue;
        }
        if (!canRemove || canAdd && removePath.oldPos < addPath.oldPos) {
          basePath = this.addToPath(addPath, true, false, 0, options);
        } else {
          basePath = this.addToPath(removePath, false, true, 1, options);
        }
        newPos = this.extractCommon(basePath, newTokens, oldTokens, diagonalPath, options);
        if (basePath.oldPos + 1 >= oldLen && newPos + 1 >= newLen) {
          return done(this.buildValues(basePath.lastComponent, newTokens, oldTokens)) || true;
        } else {
          bestPath[diagonalPath] = basePath;
          if (basePath.oldPos + 1 >= oldLen) {
            maxDiagonalToConsider = Math.min(maxDiagonalToConsider, diagonalPath - 1);
          }
          if (newPos + 1 >= newLen) {
            minDiagonalToConsider = Math.max(minDiagonalToConsider, diagonalPath + 1);
          }
        }
      }
      editLength++;
    };
    if (callback) {
      (function exec() {
        setTimeout(function() {
          if (editLength > maxEditLength || Date.now() > abortAfterTimestamp) {
            return callback(void 0);
          }
          if (!execEditLength()) {
            exec();
          }
        }, 0);
      })();
    } else {
      while (editLength <= maxEditLength && Date.now() <= abortAfterTimestamp) {
        const ret = execEditLength();
        if (ret) {
          return ret;
        }
      }
    }
  }
  addToPath(path, added, removed, oldPosInc, options) {
    const last = path.lastComponent;
    if (last && !options.oneChangePerToken && last.added === added && last.removed === removed) {
      return {
        oldPos: path.oldPos + oldPosInc,
        lastComponent: { count: last.count + 1, added, removed, previousComponent: last.previousComponent }
      };
    } else {
      return {
        oldPos: path.oldPos + oldPosInc,
        lastComponent: { count: 1, added, removed, previousComponent: last }
      };
    }
  }
  extractCommon(basePath, newTokens, oldTokens, diagonalPath, options) {
    const newLen = newTokens.length, oldLen = oldTokens.length;
    let oldPos = basePath.oldPos, newPos = oldPos - diagonalPath, commonCount = 0;
    while (newPos + 1 < newLen && oldPos + 1 < oldLen && this.equals(oldTokens[oldPos + 1], newTokens[newPos + 1], options)) {
      newPos++;
      oldPos++;
      commonCount++;
      if (options.oneChangePerToken) {
        basePath.lastComponent = { count: 1, previousComponent: basePath.lastComponent, added: false, removed: false };
      }
    }
    if (commonCount && !options.oneChangePerToken) {
      basePath.lastComponent = { count: commonCount, previousComponent: basePath.lastComponent, added: false, removed: false };
    }
    basePath.oldPos = oldPos;
    return newPos;
  }
  equals(left, right, options) {
    if (options.comparator) {
      return options.comparator(left, right);
    } else {
      return left === right || !!options.ignoreCase && left.toLowerCase() === right.toLowerCase();
    }
  }
  removeEmpty(array) {
    const ret = [];
    for (let i = 0; i < array.length; i++) {
      if (array[i]) {
        ret.push(array[i]);
      }
    }
    return ret;
  }
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  castInput(value, options) {
    return value;
  }
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  tokenize(value, options) {
    return Array.from(value);
  }
  join(chars) {
    return chars.join("");
  }
  postProcess(changeObjects, options) {
    return changeObjects;
  }
  get useLongestToken() {
    return false;
  }
  buildValues(lastComponent, newTokens, oldTokens) {
    const components = [];
    let nextComponent;
    while (lastComponent) {
      components.push(lastComponent);
      nextComponent = lastComponent.previousComponent;
      delete lastComponent.previousComponent;
      lastComponent = nextComponent;
    }
    components.reverse();
    const componentLen = components.length;
    let componentPos = 0, newPos = 0, oldPos = 0;
    for (; componentPos < componentLen; componentPos++) {
      const component = components[componentPos];
      if (!component.removed) {
        if (!component.added && this.useLongestToken) {
          let value = newTokens.slice(newPos, newPos + component.count);
          value = value.map(function(value2, i) {
            const oldValue = oldTokens[oldPos + i];
            return oldValue.length > value2.length ? oldValue : value2;
          });
          component.value = this.join(value);
        } else {
          component.value = this.join(newTokens.slice(newPos, newPos + component.count));
        }
        newPos += component.count;
        if (!component.added) {
          oldPos += component.count;
        }
      } else {
        component.value = this.join(oldTokens.slice(oldPos, oldPos + component.count));
        oldPos += component.count;
      }
    }
    return components;
  }
};

// node_modules/diff/libesm/util/string.js
function longestCommonPrefix(str1, str2) {
  let i;
  for (i = 0; i < str1.length && i < str2.length; i++) {
    if (str1[i] != str2[i]) {
      return str1.slice(0, i);
    }
  }
  return str1.slice(0, i);
}
function longestCommonSuffix(str1, str2) {
  let i;
  if (!str1 || !str2 || str1[str1.length - 1] != str2[str2.length - 1]) {
    return "";
  }
  for (i = 0; i < str1.length && i < str2.length; i++) {
    if (str1[str1.length - (i + 1)] != str2[str2.length - (i + 1)]) {
      return str1.slice(-i);
    }
  }
  return str1.slice(-i);
}
function replacePrefix(string, oldPrefix, newPrefix) {
  if (string.slice(0, oldPrefix.length) != oldPrefix) {
    throw Error(`string ${JSON.stringify(string)} doesn't start with prefix ${JSON.stringify(oldPrefix)}; this is a bug`);
  }
  return newPrefix + string.slice(oldPrefix.length);
}
function replaceSuffix(string, oldSuffix, newSuffix) {
  if (!oldSuffix) {
    return string + newSuffix;
  }
  if (string.slice(-oldSuffix.length) != oldSuffix) {
    throw Error(`string ${JSON.stringify(string)} doesn't end with suffix ${JSON.stringify(oldSuffix)}; this is a bug`);
  }
  return string.slice(0, -oldSuffix.length) + newSuffix;
}
function removePrefix(string, oldPrefix) {
  return replacePrefix(string, oldPrefix, "");
}
function removeSuffix(string, oldSuffix) {
  return replaceSuffix(string, oldSuffix, "");
}
function maximumOverlap(string1, string2) {
  return string2.slice(0, overlapCount(string1, string2));
}
function overlapCount(a, b) {
  let startA = 0;
  if (a.length > b.length) {
    startA = a.length - b.length;
  }
  let endB = b.length;
  if (a.length < b.length) {
    endB = a.length;
  }
  const map = Array(endB);
  let k = 0;
  map[0] = 0;
  for (let j = 1; j < endB; j++) {
    if (b[j] == b[k]) {
      map[j] = map[k];
    } else {
      map[j] = k;
    }
    while (k > 0 && b[j] != b[k]) {
      k = map[k];
    }
    if (b[j] == b[k]) {
      k++;
    }
  }
  k = 0;
  for (let i = startA; i < a.length; i++) {
    while (k > 0 && a[i] != b[k]) {
      k = map[k];
    }
    if (a[i] == b[k]) {
      k++;
    }
  }
  return k;
}
function segment(string, segmenter) {
  const parts = [];
  for (const segmentObj of Array.from(segmenter.segment(string))) {
    const segment2 = segmentObj.segment;
    if (parts.length && /\s/.test(parts[parts.length - 1]) && /\s/.test(segment2)) {
      parts[parts.length - 1] += segment2;
    } else {
      parts.push(segment2);
    }
  }
  return parts;
}
function trailingWs(string, segmenter) {
  if (segmenter) {
    return leadingAndTrailingWs(string, segmenter)[1];
  }
  let i;
  for (i = string.length - 1; i >= 0; i--) {
    if (!string[i].match(/\s/)) {
      break;
    }
  }
  return string.substring(i + 1);
}
function leadingWs(string, segmenter) {
  if (segmenter) {
    return leadingAndTrailingWs(string, segmenter)[0];
  }
  const match = string.match(/^\s*/);
  return match ? match[0] : "";
}
function leadingAndTrailingWs(string, segmenter) {
  if (!segmenter) {
    return [leadingWs(string), trailingWs(string)];
  }
  if (segmenter.resolvedOptions().granularity != "word") {
    throw new Error('The segmenter passed must have a granularity of "word"');
  }
  const segments = segment(string, segmenter);
  const firstSeg = segments[0];
  const lastSeg = segments[segments.length - 1];
  const head = /\s/.test(firstSeg) ? firstSeg : "";
  const tail = /\s/.test(lastSeg) ? lastSeg : "";
  return [head, tail];
}

// node_modules/diff/libesm/diff/word.js
var extendedWordChars = "a-zA-Z0-9_\\u{AD}\\u{C0}-\\u{D6}\\u{D8}-\\u{F6}\\u{F8}-\\u{2C6}\\u{2C8}-\\u{2D7}\\u{2DE}-\\u{2FF}\\u{1E00}-\\u{1EFF}";
var tokenizeIncludingWhitespace = new RegExp(`[${extendedWordChars}]+|\\s+|[^${extendedWordChars}]`, "ug");
var WordDiff = class extends Diff {
  equals(left, right, options) {
    if (options.ignoreCase) {
      left = left.toLowerCase();
      right = right.toLowerCase();
    }
    return left.trim() === right.trim();
  }
  tokenize(value, options = {}) {
    let parts;
    if (options.intlSegmenter) {
      const segmenter = options.intlSegmenter;
      if (segmenter.resolvedOptions().granularity != "word") {
        throw new Error('The segmenter passed must have a granularity of "word"');
      }
      parts = segment(value, segmenter);
    } else {
      parts = value.match(tokenizeIncludingWhitespace) || [];
    }
    const tokens = [];
    let prevPart = null;
    parts.forEach((part) => {
      if (/\s/.test(part)) {
        if (prevPart == null) {
          tokens.push(part);
        } else {
          tokens.push(tokens.pop() + part);
        }
      } else if (prevPart != null && /\s/.test(prevPart)) {
        if (tokens[tokens.length - 1] == prevPart) {
          tokens.push(tokens.pop() + part);
        } else {
          tokens.push(prevPart + part);
        }
      } else {
        tokens.push(part);
      }
      prevPart = part;
    });
    return tokens;
  }
  join(tokens) {
    return tokens.map((token, i) => {
      if (i == 0) {
        return token;
      } else {
        return token.replace(/^\s+/, "");
      }
    }).join("");
  }
  postProcess(changes, options) {
    if (!changes || options.oneChangePerToken) {
      return changes;
    }
    let lastKeep = null;
    let insertion = null;
    let deletion = null;
    changes.forEach((change) => {
      if (change.added) {
        insertion = change;
      } else if (change.removed) {
        deletion = change;
      } else {
        if (insertion || deletion) {
          dedupeWhitespaceInChangeObjects(lastKeep, deletion, insertion, change, options.intlSegmenter);
        }
        lastKeep = change;
        insertion = null;
        deletion = null;
      }
    });
    if (insertion || deletion) {
      dedupeWhitespaceInChangeObjects(lastKeep, deletion, insertion, null, options.intlSegmenter);
    }
    return changes;
  }
};
var wordDiff = new WordDiff();
function dedupeWhitespaceInChangeObjects(startKeep, deletion, insertion, endKeep, segmenter) {
  if (deletion && insertion) {
    const [oldWsPrefix, oldWsSuffix] = leadingAndTrailingWs(deletion.value, segmenter);
    const [newWsPrefix, newWsSuffix] = leadingAndTrailingWs(insertion.value, segmenter);
    if (startKeep) {
      const commonWsPrefix = longestCommonPrefix(oldWsPrefix, newWsPrefix);
      startKeep.value = replaceSuffix(startKeep.value, newWsPrefix, commonWsPrefix);
      deletion.value = removePrefix(deletion.value, commonWsPrefix);
      insertion.value = removePrefix(insertion.value, commonWsPrefix);
    }
    if (endKeep) {
      const commonWsSuffix = longestCommonSuffix(oldWsSuffix, newWsSuffix);
      endKeep.value = replacePrefix(endKeep.value, newWsSuffix, commonWsSuffix);
      deletion.value = removeSuffix(deletion.value, commonWsSuffix);
      insertion.value = removeSuffix(insertion.value, commonWsSuffix);
    }
  } else if (insertion) {
    if (startKeep) {
      const ws = leadingWs(insertion.value, segmenter);
      insertion.value = insertion.value.substring(ws.length);
    }
    if (endKeep) {
      const ws = leadingWs(endKeep.value, segmenter);
      endKeep.value = endKeep.value.substring(ws.length);
    }
  } else if (startKeep && endKeep) {
    const newWsFull = leadingWs(endKeep.value, segmenter), [delWsStart, delWsEnd] = leadingAndTrailingWs(deletion.value, segmenter);
    const newWsStart = longestCommonPrefix(newWsFull, delWsStart);
    deletion.value = removePrefix(deletion.value, newWsStart);
    const newWsEnd = longestCommonSuffix(removePrefix(newWsFull, newWsStart), delWsEnd);
    deletion.value = removeSuffix(deletion.value, newWsEnd);
    endKeep.value = replacePrefix(endKeep.value, newWsFull, newWsEnd);
    startKeep.value = replaceSuffix(startKeep.value, newWsFull, newWsFull.slice(0, newWsFull.length - newWsEnd.length));
  } else if (endKeep) {
    const endKeepWsPrefix = leadingWs(endKeep.value, segmenter);
    const deletionWsSuffix = trailingWs(deletion.value, segmenter);
    const overlap = maximumOverlap(deletionWsSuffix, endKeepWsPrefix);
    deletion.value = removeSuffix(deletion.value, overlap);
  } else if (startKeep) {
    const startKeepWsSuffix = trailingWs(startKeep.value, segmenter);
    const deletionWsPrefix = leadingWs(deletion.value, segmenter);
    const overlap = maximumOverlap(startKeepWsSuffix, deletionWsPrefix);
    deletion.value = removePrefix(deletion.value, overlap);
  }
}
var WordsWithSpaceDiff = class extends Diff {
  tokenize(value) {
    const regex = new RegExp(`(\\r?\\n)|[${extendedWordChars}]+|[^\\S\\n\\r]+|[^${extendedWordChars}]`, "ug");
    return value.match(regex) || [];
  }
};
var wordsWithSpaceDiff = new WordsWithSpaceDiff();
function diffWordsWithSpace(oldStr, newStr, options) {
  return wordsWithSpaceDiff.diff(oldStr, newStr, options);
}

// node_modules/diff/libesm/diff/line.js
var LineDiff = class extends Diff {
  constructor() {
    super(...arguments);
    this.tokenize = tokenize;
  }
  equals(left, right, options) {
    if (options.ignoreWhitespace) {
      if (!options.newlineIsToken || !left.includes("\n")) {
        left = left.trim();
      }
      if (!options.newlineIsToken || !right.includes("\n")) {
        right = right.trim();
      }
    } else if (options.ignoreNewlineAtEof && !options.newlineIsToken) {
      if (left.endsWith("\n")) {
        left = left.slice(0, -1);
      }
      if (right.endsWith("\n")) {
        right = right.slice(0, -1);
      }
    }
    return super.equals(left, right, options);
  }
};
var lineDiff = new LineDiff();
function diffLines(oldStr, newStr, options) {
  return lineDiff.diff(oldStr, newStr, options);
}
function tokenize(value, options) {
  if (options.stripTrailingCr) {
    value = value.replace(/\r\n/g, "\n");
  }
  const retLines = [], linesAndNewlines = value.split(/(\n|\r\n)/);
  if (!linesAndNewlines[linesAndNewlines.length - 1]) {
    linesAndNewlines.pop();
  }
  for (let i = 0; i < linesAndNewlines.length; i++) {
    const line = linesAndNewlines[i];
    if (i % 2 && !options.newlineIsToken) {
      retLines[retLines.length - 1] += line;
    } else {
      retLines.push(line);
    }
  }
  return retLines;
}

// ../../../.jsx6/libs/jsx-runtime/index.js
function jsx(tag, { children, ...attr }) {
  if (tag === Fragment) return children;
  return toDom(tag, attr, children);
}
var Fragment = (attr, children) => children;

// src/index.jsx
var TOOL = "jcodebuddy-agent";
var HOST_PAGE = location.pathname.startsWith("/app/");
var TOKEN_KEY = "jcodebuddy.token";
var initialQuery = new URLSearchParams(location.search);
var OPEN_BATCH = initialQuery.get("batch");
var selectedFileKey = initialQuery.get("file");
var wholeFile = initialQuery.get("full") === "1";
document.body.classList.toggle("batch-mode", Boolean(OPEN_BATCH));
var $badge = signal("connecting\u2026");
var $badgeClass = signal("badge");
var $summary = signal("");
var $error = signal("");
var $notice = signal("");
var tools = [];
var batches = [];
var refreshBusy = false;
var actionBusy = false;
var actionError = "";
var batchSignature = "";
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
var TOKEN = HOST_PAGE ? resolveToken() : "";
async function call(method, params = {}) {
  const response = HOST_PAGE ? await fetch(`/api/tools/${TOOL}/call`, {
    method: "POST",
    cache: "no-store",
    headers: {
      "Content-Type": "application/json",
      ...TOKEN ? { "X-WebView-Token": TOKEN } : {}
    },
    body: JSON.stringify({ method, params })
  }) : await fetch("/rpc", {
    method: "POST",
    cache: "no-store",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ method, params })
  });
  let body = {};
  try {
    body = await response.json();
  } catch {
  }
  if (!response.ok || body.error) {
    throw new Error(body.error || body.message || `HTTP ${response.status}`);
  }
  return body.result;
}
function setBadge(text, kind) {
  $badge(text);
  $badgeClass(`badge${kind ? ` ${kind}` : ""}`);
}
function textButton(label, className, onClick) {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = label;
  if (className) button.className = className;
  button.addEventListener("click", onClick);
  return button;
}
function toolLink(tool) {
  const link = document.createElement("a");
  link.className = "button-link";
  link.href = `${tool.id}.html`;
  link.textContent = `Open ${tool.title || tool.id}`;
  return link;
}
function renderTools() {
  const list = document.getElementById("tools");
  if (!list) return;
  list.replaceChildren();
  if (tools.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty";
    empty.textContent = "No tools registered.";
    list.append(empty);
    return;
  }
  for (const tool of tools) {
    const card = document.createElement("article");
    card.className = "card";
    const head = document.createElement("div");
    head.className = "card-head";
    const left = document.createElement("div");
    const title = document.createElement("div");
    title.className = "card-title";
    title.textContent = tool.title || tool.id;
    const meta = document.createElement("div");
    meta.className = "card-meta";
    meta.textContent = `${tool.description || "proposal tool"} \xB7 activation ${tool.activation || "explicit"}`;
    left.append(title, meta);
    head.append(left);
    const footer = document.createElement("div");
    footer.className = "card-actions";
    if (tool.page) footer.append(toolLink(tool));
    card.append(head, footer);
    list.append(card);
  }
}
function renderBatches() {
  const list = document.getElementById("batches");
  if (!list) return;
  const pageScroll = document.scrollingElement?.scrollTop ?? window.scrollY ?? 0;
  list.replaceChildren();
  if (batches.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty";
    empty.textContent = "No change batches. A proposal appears here when a tool previews a change.";
    list.append(empty);
    return;
  }
  for (const batch of batches) list.append(batchCard(batch));
  highlightDiffs(list);
  requestAnimationFrame(() => {
    if (document.scrollingElement) document.scrollingElement.scrollTop = pageScroll;
    else window.scrollTo(0, pageScroll);
  });
}
function batchFiles(batch) {
  return (batch?.proposals || []).flatMap((proposal) => (proposal.files || []).map((file) => ({
    proposal,
    file,
    key: `${proposal.proposalId}:${file.path}`
  })));
}
function batchUrl(fileKey) {
  const params = new URLSearchParams();
  params.set("batch", OPEN_BATCH);
  if (fileKey) params.set("file", fileKey);
  if (wholeFile) params.set("full", "1");
  return `${location.pathname}?${params.toString()}`;
}
function selectBatchFile(fileKey) {
  selectedFileKey = fileKey;
  history.replaceState(null, "", batchUrl(fileKey));
  renderBatchDetail();
}
function renderBatchDetail() {
  const view = document.getElementById("batch-view");
  if (!view) return;
  const pageScroll = document.scrollingElement?.scrollTop ?? window.scrollY ?? 0;
  view.replaceChildren();
  const batch = batches.find((candidate) => candidate.batchId === OPEN_BATCH);
  if (!batch) {
    view.append(
      textElement("h2", `Batch ${OPEN_BATCH} is no longer pending`),
      textElement("p", "It may have been accepted, rejected or replaced by a newer proposal.")
    );
    const back2 = document.createElement("a");
    back2.href = "./";
    back2.className = "button-link";
    back2.textContent = "\u2190 Back to catalog";
    view.append(back2);
    return;
  }
  const files = batchFiles(batch);
  if (!selectedFileKey || !files.some((entry) => entry.key === selectedFileKey)) {
    selectedFileKey = files[0]?.key || null;
  }
  const selected = files.find((entry) => entry.key === selectedFileKey) || null;
  const tools2 = [...new Set((batch.proposals || []).map((proposal) => proposal.tool))];
  const header = document.createElement("div");
  header.className = "batch-detail-head";
  const heading = document.createElement("div");
  const title = textElement("h2", `Batch ${batch.batchId}`);
  const meta = textElement("p", `${tools2.join(", ")} \xB7 ${files.length} file(s) \xB7 ${batch.allApplied ? "applied" : batch.anyApplied ? "partially applied" : "pending review"}${batch.summary ? ` \xB7 ${batch.summary}` : ""}`);
  meta.className = "card-meta";
  heading.append(title, meta);
  const links = document.createElement("div");
  links.className = "card-actions";
  const back = document.createElement("a");
  back.href = "./";
  back.className = "button-link";
  back.textContent = "\u2190 Catalog";
  links.append(back);
  const applyBatchButton = textButton("Apply batch", "primary", () => applyBatch(batch.batchId));
  applyBatchButton.disabled = batch.allApplied;
  const revertBatchButton = textButton("Revert batch", "danger", () => revertBatch(batch.batchId));
  revertBatchButton.disabled = !batch.anyApplied;
  links.append(applyBatchButton, revertBatchButton);
  header.append(heading, links);
  view.append(header);
  const state = document.createElement("div");
  state.className = `batch-state ${batch.allApplied ? "applied" : batch.anyApplied ? "partial" : "pending"}`;
  state.textContent = batch.allApplied ? "\u2713 Applied \u2014 the files are on disk; use Revert batch to undo the whole batch." : batch.anyApplied ? "Partially applied \u2014 some files are on disk; the rest is still reviewable." : "Pending review \u2014 preview only; no editor buffer has been changed.";
  view.append(state);
  const layout = document.createElement("div");
  layout.className = "batch-layout";
  const sidebar = document.createElement("aside");
  sidebar.className = "batch-files";
  const sideTitle = textElement("h3", "Files");
  sidebar.append(sideTitle);
  for (const entry of files) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `batch-file${entry.key === selectedFileKey ? " selected" : ""}${entry.file.applied ? " applied" : ""}`;
    const path = document.createElement("span");
    path.className = "batch-file-path";
    path.textContent = entry.file.path;
    const stateLabel = document.createElement("span");
    stateLabel.className = "batch-file-state";
    stateLabel.textContent = entry.file.applied ? "applied" : entry.proposal.tool;
    button.append(path, stateLabel);
    button.addEventListener("click", () => selectBatchFile(entry.key));
    sidebar.append(button);
  }
  const diffPanel = document.createElement("section");
  diffPanel.className = `batch-diff${batch.allApplied ? " applied" : ""}`;
  if (selected) {
    const fileHead = document.createElement("div");
    fileHead.className = "batch-diff-head";
    const fileTitle = textElement("strong", selected.file.path);
    const fileMeta = textElement("span", `${selected.proposal.tool} \xB7 ${selected.file.applied ? "applied" : "not applied"}`);
    fileMeta.className = "card-meta";
    fileHead.append(fileTitle, fileMeta);
    const fileActions = document.createElement("div");
    fileActions.className = "card-actions";
    const toggle = textButton(
      wholeFile ? "Changes only" : "Whole file",
      "",
      () => {
        wholeFile = !wholeFile;
        history.replaceState(null, "", batchUrl(selectedFileKey));
        renderBatchDetail();
      }
    );
    fileActions.append(
      textButton("Open file", "", () => openFile(selected.file.path)),
      toggle,
      selected.file.applied ? textButton("Revert file", "danger", () => revertFile(selected.proposal.proposalId, selected.file.path)) : textButton("Apply file", "primary", () => applyFile(selected.proposal.proposalId, selected.file.path))
    );
    diffPanel.append(
      fileHead,
      fileActions,
      proposalSummary(selected.proposal),
      diffRows(
        selected.file.before || "",
        selected.file.after || "",
        languageFor(selected.file.path),
        wholeFile
      )
    );
  } else {
    diffPanel.append(textElement("p", "No files in this batch."));
  }
  layout.append(sidebar, diffPanel);
  view.append(layout);
  highlightDiffs(diffPanel);
  requestAnimationFrame(() => {
    if (document.scrollingElement) document.scrollingElement.scrollTop = pageScroll;
    else window.scrollTo(0, pageScroll);
  });
}
function batchCard(batch) {
  const card = document.createElement("article");
  card.className = `card batch${batch.allApplied ? " applied" : ""}`;
  const head = document.createElement("div");
  head.className = "card-head";
  const left = document.createElement("div");
  const title = document.createElement("div");
  title.className = "card-title";
  const tools2 = [...new Set((batch.proposals || []).map((proposal) => proposal.tool))];
  title.textContent = `Batch ${batch.batchId} \xB7 ${tools2.join(", ")}`;
  const meta = document.createElement("div");
  meta.className = "card-meta";
  const fileCount = (batch.proposals || []).reduce((count, proposal) => count + (proposal.files?.length || 0), 0);
  meta.textContent = `${(batch.proposals || []).length} proposal(s) \xB7 ${fileCount} file(s) \xB7 ${batch.allApplied ? "applied" : batch.anyApplied ? "partially applied" : "review"}${batch.summary ? ` \xB7 ${batch.summary}` : ""}`;
  const toolCounts = document.createElement("div");
  toolCounts.className = "card-meta";
  toolCounts.textContent = (batch.proposals || []).map((proposal) => `${proposal.tool} (${proposal.files?.length || 0})`).join(" \xB7 ");
  left.append(title, meta, toolCounts);
  const state = document.createElement("div");
  state.className = `badge${batch.allApplied ? " live" : ""}`;
  state.textContent = batch.allApplied ? "applied" : "pending";
  head.append(left, state);
  card.append(head);
  const actionsRow = document.createElement("div");
  actionsRow.className = "card-actions";
  const open = document.createElement("a");
  open.className = "button-link";
  open.href = `?batch=${encodeURIComponent(batch.batchId)}`;
  open.textContent = "Open batch";
  actionsRow.append(
    open,
    textButton("Apply batch", "primary", () => applyBatch(batch.batchId)),
    textButton("Revert batch", "danger", () => revertBatch(batch.batchId))
  );
  card.append(actionsRow);
  return card;
}
function proposalSummary(proposal) {
  const box = document.createElement("div");
  box.className = "proposal-summary";
  if (proposal.summary) {
    const summary = textElement("p", proposal.summary);
    summary.className = "proposal-summary-line";
    box.append(summary);
  }
  if (proposal.details?.length) {
    const list = document.createElement("ul");
    list.className = "proposal-detail-list";
    for (const detail of proposal.details) {
      const item = document.createElement("li");
      item.textContent = detail;
      list.append(item);
    }
    box.append(list);
  }
  return box;
}
function languageFor(path) {
  const extension = String(path || "").toLowerCase().split(".").pop();
  if (extension === "js" || extension === "mjs" || extension === "cjs") return "javascript";
  if (extension === "md") return "markdown";
  if (["java", "json", "html", "css", "yml", "yaml"].includes(extension)) {
    return extension === "yml" ? "yaml" : extension;
  }
  return "java";
}
async function refresh() {
  if (refreshBusy) return;
  refreshBusy = true;
  try {
    const status = await call("agent.status");
    tools = await call("tool.list");
    batches = await call("tool.batches");
    setBadge(
      status.watching ? "watching" : "no fs-meta daemon",
      status.watching ? "live" : "warn"
    );
    $summary(
      `${status.tools?.length || tools.length} tool(s) \xB7 ${batches.length} change batch(es) \xB7 ${status.root}`
    );
    $error(status.configurationProblem || status.watchProblem || actionError || "");
    if (OPEN_BATCH) {
      document.getElementById("catalog-view").hidden = true;
      document.getElementById("batch-view").hidden = false;
      const nextBatchSignature = signatureOf(batches);
      if (nextBatchSignature !== batchSignature) {
        batchSignature = nextBatchSignature;
        renderBatchDetail();
      }
    } else {
      document.getElementById("catalog-view").hidden = false;
      document.getElementById("batch-view").hidden = true;
      renderTools();
      const nextBatchSignature = signatureOf(batches);
      if (nextBatchSignature !== batchSignature) {
        batchSignature = nextBatchSignature;
        renderBatches();
      }
    }
  } catch (error) {
    setBadge("disconnected", "bad");
    $error(
      `${error.message}${HOST_PAGE && !TOKEN ? " \u2014 open this page once with ?token=<host token> or from the IDE, which injects it." : " \u2014 is the agent running and is this page served by the host that proxies .jcodebuddy/ipc?"}`
    );
  } finally {
    refreshBusy = false;
  }
}
async function openFile(path) {
  try {
    if (HOST_PAGE && typeof window.openFile === "function") {
      window.openFile(path, 1, 1);
      $notice(`open requested: ${path}`);
      return;
    }
    const response = await fetch("/jump", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ uri: path, line: 1 })
    });
    const body = await response.json().catch(() => ({}));
    $notice(body.detail || body.reason || body.error || `jump: HTTP ${response.status}`);
  } catch (error) {
    $notice(`open failed: ${error.message}`);
  }
}
function signatureOf(value) {
  return (value || []).map((batch) => [
    batch.batchId,
    batch.allApplied ? "a" : "p",
    ...(batch.proposals || []).map((proposal) => [
      proposal.proposalId,
      proposal.mode,
      ...(proposal.files || []).map((file) => `${file.path}:${file.applied ? 1 : 0}`)
    ].join(":"))
  ].join("|")).join("~");
}
async function applyFile(proposalId, path) {
  if (actionBusy) return;
  actionBusy = true;
  $notice(`applying ${path}\u2026`);
  try {
    await call("tool.applyFile", { proposalId, path });
    $notice(`applied ${path}`);
    await refresh();
  } catch (error) {
    $error(error.message);
  } finally {
    actionBusy = false;
  }
}
async function revertFile(proposalId, path) {
  if (actionBusy) return;
  actionBusy = true;
  $notice(`reverting ${path}\u2026`);
  try {
    await call("tool.revertFile", { proposalId, path });
    $notice(`reverted ${path}`);
    await refresh();
  } catch (error) {
    $error(error.message);
  } finally {
    actionBusy = false;
  }
}
async function applyBatch(batchId) {
  if (actionBusy) return;
  actionBusy = true;
  actionError = "";
  $notice(`applying batch ${batchId}\u2026`);
  try {
    const result = await call("tool.applyBatch", { batchId });
    const failures = (result.proposals || []).flatMap((proposal) => (proposal.files || []).filter((file) => file.status !== "APPLIED" && file.status !== "already-applied"));
    if (result.status === "partial" || result.status === "refused" || failures.length > 0) {
      const detail = failures.map((file) => `${file.path}: ${file.message || file.status}`).join("; ");
      actionError = `batch ${batchId} ${result.status}: ${detail || "no file was applied"}`;
      $notice("");
    } else {
      $notice(`applied batch ${batchId}`);
    }
    await refresh();
  } catch (error) {
    actionError = error.message;
  } finally {
    actionBusy = false;
  }
}
async function revertBatch(batchId) {
  if (actionBusy) return;
  actionBusy = true;
  $notice(`reverting batch ${batchId}\u2026`);
  try {
    await call("tool.revertBatch", { batchId });
    $notice(`reverted batch ${batchId}`);
    await refresh();
  } catch (error) {
    $error(error.message);
  } finally {
    actionBusy = false;
  }
}
function diffRows(before, after, language = "java", wholeFile2 = true) {
  const lines = document.createElement("div");
  lines.className = "diff-lines";
  const side = document.createElement("div");
  side.className = "diff-side";
  const head = document.createElement("div");
  head.className = "diff-side-head";
  head.append(textElement("span", "Before"), textElement("span", "After"));
  side.append(head);
  const wordRanges = [];
  const rows = lineDiffRows(before, after);
  let oldLine = 1;
  let newLine = 1;
  for (const row of rows) {
    if (!wholeFile2 && row.kind === "same") {
      if (row.left !== null) oldLine++;
      if (row.right !== null) newLine++;
      continue;
    }
    const line = document.createElement("div");
    line.className = `side-line ${row.kind}`;
    const leftCell = sideCell(row.left, row.left === null ? null : oldLine, "left", language, row.kind);
    const rightCell = sideCell(row.right, row.right === null ? null : newLine, "right", language, row.kind);
    if (row.kind === "change") {
      addWordRanges(row.left, row.right, leftCell.code, rightCell.code, wordRanges);
    } else if (row.kind === "remove") {
      addFullRange(leftCell.code, "word-remove", wordRanges);
    } else if (row.kind === "add") {
      addFullRange(rightCell.code, "word-add", wordRanges);
    }
    line.append(leftCell, rightCell);
    if (row.left !== null) oldLine++;
    if (row.right !== null) newLine++;
    side.append(line);
  }
  lines._wordRanges = wordRanges;
  lines.append(side);
  return lines;
}
function textElement(tag, text) {
  const element = document.createElement(tag);
  element.textContent = text;
  return element;
}
function sideCell(text, lineNumber, side, language, kind) {
  const cell = document.createElement("div");
  cell.className = `side-cell ${side}${kind === "same" ? "" : ` ${kind}`}`;
  if (text === null) {
    cell.classList.add("empty");
    return cell;
  }
  const number = document.createElement("span");
  number.className = "ln";
  number.textContent = lineNumber === null ? "" : String(lineNumber);
  const code = document.createElement("code");
  code.className = `diff-code language-${language}`;
  code.textContent = text;
  cell.code = code;
  cell.append(number, code);
  return cell;
}
function addWordRanges(left, right, leftCode, rightCode, ranges) {
  if (!leftCode || !rightCode) return;
  let leftOffset = 0;
  let rightOffset = 0;
  for (const part of diffWordsWithSpace(left, right)) {
    const length = part.value.length;
    if (part.added) {
      addRange(rightCode, rightOffset, rightOffset + length, "word-add", ranges);
      rightOffset += length;
    } else if (part.removed) {
      addRange(leftCode, leftOffset, leftOffset + length, "word-remove", ranges);
      leftOffset += length;
    } else {
      leftOffset += length;
      rightOffset += length;
    }
  }
}
function addFullRange(code, kind, ranges) {
  if (code) addRange(code, 0, code.textContent.length, kind, ranges);
}
function addRange(code, start, end, kind, ranges) {
  if (code && end > start) ranges.push({ code, start, end, kind });
}
function lineDiffRows(before, after) {
  const left = normalizeDiffText(before);
  const right = normalizeDiffText(after);
  const parts = diffLines(left, right);
  const removed = [];
  const added = [];
  const rows = [];
  const flush = () => {
    const count = Math.max(removed.length, added.length);
    for (let index = 0; index < count; index++) {
      const beforeLine = index < removed.length ? removed[index] : null;
      const afterLine = index < added.length ? added[index] : null;
      rows.push({
        left: beforeLine,
        right: afterLine,
        kind: beforeLine !== null && afterLine !== null ? "change" : beforeLine !== null ? "remove" : "add"
      });
    }
    removed.length = 0;
    added.length = 0;
  };
  for (const part of parts) {
    const lines = part.value.split("\n");
    if (lines.length > 0 && lines[lines.length - 1] === "") lines.pop();
    if (part.added) added.push(...lines);
    else if (part.removed) removed.push(...lines);
    else {
      flush();
      for (const line of lines) rows.push({ left: line, right: line, kind: "same" });
    }
  }
  flush();
  if (rows.length === 1 && rows[0].left === "" && rows[0].right === "") return [];
  return rows;
}
function normalizeDiffText(text) {
  return String(text || "").replace(/\r\n?/g, "\n");
}
async function highlightDiffs(root) {
  const microlighter = window.microlighter;
  const hasHighlight = typeof CSS !== "undefined" && Boolean(CSS.highlights) && typeof Highlight === "function";
  if (microlighter && typeof microlighter.highlightAll === "function" && hasHighlight) {
    try {
      await microlighter.highlightAll({ root, selector: "code.diff-code" });
    } catch {
    }
  }
  if (!hasHighlight) return;
  const wordRanges = [];
  for (const lines of root.querySelectorAll(".diff-lines")) {
    if (Array.isArray(lines._wordRanges)) wordRanges.push(...lines._wordRanges);
  }
  for (const kind of ["word-add", "word-remove"]) CSS.highlights.delete(kind);
  const groups = /* @__PURE__ */ new Map();
  for (const entry of wordRanges) {
    const code = entry.code;
    const node = code?.firstChild;
    if (!code || !node || node.nodeType !== Node.TEXT_NODE) continue;
    const range = document.createRange();
    range.setStart(node, Math.min(entry.start, node.textContent.length));
    range.setEnd(node, Math.min(entry.end, node.textContent.length));
    if (range.collapsed) continue;
    if (!groups.has(entry.kind)) groups.set(entry.kind, new Highlight());
    groups.get(entry.kind).add(range);
  }
  for (const [kind, highlight] of groups) CSS.highlights.set(kind, highlight);
}
insert(document.getElementById("page"), /* @__PURE__ */ jsx("div", { class: "agent-shell", children: [
  /* @__PURE__ */ jsx("header", { class: "top", children: [
    /* @__PURE__ */ jsx("div", { children: [
      /* @__PURE__ */ jsx("h1", { children: "JCodeBuddy Agent" }),
      /* @__PURE__ */ jsx("p", { class: "sub", children: "Proposal batches, diffs and review for the JCodeBuddy code tools." })
    ] }),
    /* @__PURE__ */ jsx("span", { class: $badgeClass, children: $badge })
  ] }),
  /* @__PURE__ */ jsx("section", { class: "status", children: [
    /* @__PURE__ */ jsx("strong", { children: "Agent" }),
    /* @__PURE__ */ jsx("span", { class: "muted", children: $summary }),
    /* @__PURE__ */ jsx("p", { class: "error", children: $error }),
    /* @__PURE__ */ jsx("span", { class: "notice", children: $notice })
  ] }),
  /* @__PURE__ */ jsx("div", { id: "catalog-view", children: [
    /* @__PURE__ */ jsx("section", { children: [
      /* @__PURE__ */ jsx("div", { class: "section-head", children: /* @__PURE__ */ jsx("h2", { children: "Tools" }) }),
      /* @__PURE__ */ jsx("div", { class: "actions", id: "tools" })
    ] }),
    /* @__PURE__ */ jsx("section", { children: [
      /* @__PURE__ */ jsx("div", { class: "section-head", children: [
        /* @__PURE__ */ jsx("h2", { children: "Changes" }),
        /* @__PURE__ */ jsx("button", { type: "button", onclick: () => void refresh(), children: "Refresh" })
      ] }),
      /* @__PURE__ */ jsx("div", { class: "actions", id: "batches" })
    ] })
  ] }),
  /* @__PURE__ */ jsx("div", { id: "batch-view", hidden: true })
] }));
void refresh();
setInterval(() => {
  if (document.visibilityState !== "hidden") void refresh();
}, 1500);
//# sourceMappingURL=jcodebuddy-agent.js.map
