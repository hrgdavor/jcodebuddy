/**
 * microlighter 2.2.0 (MIT) — https://github.com/davatron5000/microlighter
 * https://raw.githubusercontent.com/davatron5000/microlighter/main/src/highlight.js
 *
 * Copied verbatim from webview/examples/self-contained/index.html, which
 * carries the same code inline together with the full inlining notes. This file
 * is the ASSET form: a plain <script> exposing `window.microlighter`, so a page
 * needs no module loader and no bundler.
 *
 * Bundled grammars (all seven, inlined below):
 *   javascript  json  java  html  markdown   <- the five a project page needs
 *   css         (html embeds it)             <- dependency
 *   yaml        (markdown front matter)      <- dependency
 *
 * Four changes from upstream, and only four:
 *   1. `await import('./grammars/x.js')` -> the inlined registry IS the loader
 *   2. `import`/`export` -> one IIFE exposing `window.microlighter`
 *   3. capability guards for CSS.highlights / Highlight / unresolved includes
 *   4. Highlight.add() is fed ranges from a single text node (already guaranteed)
 */
window.microlighter = (function () {
  'use strict';

  /* ---------------------------------------------------------------- grammars */

  var GRAMMARS = [
    // ---- grammars/json.js -------------------------------------------------
    {
      name: 'json',
      grammar: {
        scopeName: 'source.json',
        patterns: [
          { match: '"(?:\\\\.|[^"\\\\])*"(?=\\s*:)', name: 'entity.name.key' },
          { match: '"(?:\\\\.|[^"\\\\])*"(?=\\s*[,}\\]])', name: 'string.quoted.double' },
          { match: '\\b(?:true|false)\\b', name: 'constant.language.boolean' },
          { match: '\\bnull\\b', name: 'constant.language' },
          { match: '(?<![\\w.])-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b', name: 'constant.numeric' }
        ]
      }
    },

    // ---- grammars/yaml.js -------------------------------------------------
    {
      name: 'yaml',
      grammar: {
        scopeName: 'source.yaml',
        patterns: [
          { match: '#.*$', name: 'comment.line.number-sign' },
          { match: '^(?:---|\\.\\.\\.)\\s*$|^%YAML\\b.*$', name: 'keyword.control.document' },
          { match: '^\\s*(?:-\\s+)?([^#\\s][^\\r\\n:#]*?)(?=\\s*:)', captures: { 1: { name: 'entity.name.key' } } },
          { match: '[&*][a-zA-Z_][\\w-]*|![^\\s]+', name: 'entity.name.anchor' },
          { match: "(['\"])(?:\\\\.|(?!\\1)[^\\\\\\r\\n]|\\r?\\n[ \\t]+)*\\1", name: 'string.quoted' },
          { match: '(?<=:\\s)[|>][-+]?\\s*$', name: 'keyword.control.block-scalar' },
          { match: '\\b(?:true|false|yes|no|on|off)\\b', name: 'constant.language.boolean' },
          { match: '\\bnull\\b|~', name: 'constant.language' },
          { match: '(?<![\\w.-])-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:e[+-]?\\d+)?\\b', name: 'constant.numeric' }
        ]
      }
    },

    // ---- grammars/css.js --------------------------------------------------
    {
      name: 'css',
      grammar: {
        scopeName: 'source.css',
        patterns: [
          { include: '#comments' },
          { include: '#strings' },
          { include: '#keyframes' },
          { include: '#at-rule-block' },
          { include: '#at-rule-statement' },
          { include: '#rule-set' }
        ],
        repository: {
          comments: { begin: '/\\*', end: '\\*/', name: 'comment.block' },
          strings: { match: "(['\"])(?:\\\\.|(?!\\1)[^\\\\\\r\\n])*\\1", name: 'string.quoted' },
          keyframes: {
            begin: '(@(?:-\\w+-)?keyframes)\\s+([a-zA-Z_-][\\w-]*)\\s*\\{',
            end: '\\}',
            beginCaptures: {
              1: { name: 'keyword.control.at-rule' },
              2: { name: 'entity.name.animation' }
            },
            patterns: [{ include: '$self' }]
          },
          'at-rule-block': {
            begin: '(@(?:container|counter-style|document|font-face|font-feature-values|font-palette-values|layer|media|page|position-try|scope|starting-style|supports|view-transition)\\b)[^{;]*\\{',
            end: '\\}',
            beginCaptures: { 1: { name: 'keyword.control.at-rule' } },
            patterns: [{ include: '$self' }]
          },
          'at-rule-statement': {
            begin: '(@(?:charset|custom-media|import|namespace|property)\\b)',
            end: ';',
            beginCaptures: { 1: { name: 'keyword.control.at-rule' } },
            patterns: [
              { include: '#comments' },
              { include: '#strings' },
              { include: '#values' }
            ]
          },
          'rule-set': {
            begin: '([^\\s@{};<][^@{};<]*?)\\s*\\{',
            end: '\\}',
            beginCaptures: { 1: { name: 'entity.name.selector' } },
            patterns: [
              { include: '#comments' },
              { include: '#strings' },
              { include: '#keyframes' },
              { include: '#at-rule-block' },
              { include: '#at-rule-statement' },
              { include: '#rule-set' },
              { include: '#declarations' },
              { include: '#values' }
            ]
          },
          declarations: {
            match: '(--[a-zA-Z0-9_-]+|[a-zA-Z-][\\w-]*)(\\s*:)',
            captures: { 1: { name: 'support.type.property-name' } }
          },
          values: {
            patterns: [
              { match: '!important\\b', name: 'keyword.other.important' },
              {
                match: '\\b(var)\\(\\s*(--[a-zA-Z0-9_-]+)',
                captures: {
                  1: { name: 'entity.name.function' },
                  2: { name: 'variable.other.custom-property' }
                }
              },
              { match: '\\b([a-zA-Z_-][\\w-]*)(?=\\()', captures: { 1: { name: 'entity.name.function' } } },
              { match: '#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{4}|[0-9a-fA-F]{3})\\b', name: 'constant.other.color' },
              { match: '(?<![\\w.-])-?(?:\\d*\\.\\d+|\\d+)(?:[eE][+-]?\\d+)?(?:%|[a-zA-Z]+)?\\b', name: 'constant.numeric' },
              { match: '\\b(?:auto|currentColor|inherit|initial|none|normal|revert|revert-layer|transparent|unset)\\b', name: 'constant.language' },
              { match: '(?:[+*/]|(?<=\\s)-(?!-))', name: 'keyword.operator' }
            ]
          }
        }
      }
    },

    // ---- grammars/javascript.js ------------------------------------------
    {
      name: 'javascript',
      grammar: {
        scopeName: 'source.js',
        patterns: [
          { include: '#comments' },
          { include: '#strings' },
          { include: '#template' },
          { include: '#regexp' },
          { include: '#jsx-closing-tag' },
          { include: '#jsx-tag' },
          { include: '#class-declaration' },
          { include: '#function-declaration' },
          { include: '#variable-declaration' },
          { include: '#keywords' },
          { include: '#numbers' },
          { include: '#constants' },
          { include: '#decorators' },
          { include: '#private-fields' },
          { include: '#properties' },
          { include: '#functions' },
          { include: '#built-ins' },
          { include: '#operators' }
        ],
        repository: {
          comments: {
            patterns: [
              { match: '//.*$', name: 'comment.line' },
              { begin: '/\\*', end: '\\*/', name: 'comment.block' }
            ]
          },
          strings: {
            patterns: [
              { match: "'(?:\\\\.|[^'\\\\\\r\\n])*'", name: 'string.quoted.single' },
              { match: '"(?:\\\\.|[^"\\\\\\r\\n])*"', name: 'string.quoted.double' }
            ]
          },
          template: {
            begin: '`',
            end: '(?<!\\\\)(?:\\\\\\\\)*`',
            name: 'string.quoted.template',
            patterns: [{ include: '#template-interpolation' }]
          },
          'template-interpolation': {
            begin: '\\$\\{',
            end: '\\}',
            patterns: [
              { include: '#balanced-braces' },
              { include: '$self' }
            ]
          },
          'balanced-braces': {
            begin: '\\{',
            end: '\\}',
            patterns: [
              { include: '#balanced-braces' },
              { include: '$self' }
            ]
          },
          regexp: {
            match: '(?<![\\w)$\\]])/(?![/*])(?:\\\\.|\\[(?:\\\\.|[^\\]\\\\])*\\]|[^/\\\\\\r\\n])+/[dgimsuvy]*',
            name: 'string.regexp'
          },
          'jsx-closing-tag': {
            match: '</([A-Za-z][\\w.-]*)\\s*>',
            captures: { 1: { name: 'entity.name.tag' } }
          },
          'jsx-tag': {
            begin: '<([A-Za-z][\\w.-]*)',
            end: '/?>',
            beginCaptures: { 1: { name: 'entity.name.tag' } },
            patterns: [
              { include: '#jsx-expression' },
              { include: '#strings' },
              { include: '#jsx-boolean-attributes' },
              { include: '#jsx-attributes' }
            ]
          },
          'jsx-expression': {
            begin: '\\{',
            end: '\\}',
            patterns: [
              { include: '#balanced-braces' },
              { include: '$self' }
            ]
          },
          'jsx-attributes': {
            match: '\\b([a-zA-Z_:][\\w:.-]*)(?=\\s*(?:=|/?>))',
            captures: { 1: { name: 'entity.other.attribute-name' } }
          },
          'jsx-boolean-attributes': {
            match: '\\b([a-zA-Z_:][\\w:.-]*)(?=\\s+(?:[a-zA-Z_:][\\w:.-]*\\s*=|/?>))',
            captures: { 1: { name: 'entity.other.attribute-name' } }
          },
          'class-declaration': {
            match: '\\b(class)\\s+([A-Za-z_$][\\w$]*)',
            captures: {
              1: { name: 'storage.type.class' },
              2: { name: 'entity.name.type.class' }
            }
          },
          'function-declaration': {
            match: '\\b(?:(async)\\s+)?(function)\\s*(\\*)?\\s*([A-Za-z_$][\\w$]*)?',
            captures: {
              1: { name: 'storage.modifier.async' },
              2: { name: 'storage.type.function' },
              4: { name: 'entity.name.function' }
            }
          },
          'variable-declaration': {
            match: '\\b(const|let|var)\\s+([A-Za-z_$][\\w$]*)',
            captures: {
              1: { name: 'storage.type.variable' },
              2: { name: 'variable.other.readwrite' }
            }
          },
          keywords: {
            match: '\\b(?:as|assert|async|await|break|case|catch|class|const|continue|debugger|default|delete|do|else|export|extends|finally|for|from|function|get|if|implements|import|in|instanceof|interface|let|new|of|package|private|protected|public|return|set|static|super|switch|this|throw|try|typeof|var|void|while|with|yield)\\b',
            name: 'keyword.control'
          },
          numbers: {
            match: '(?<![\\w$])(?:0[xX][0-9a-fA-F](?:_?[0-9a-fA-F])*n?|0[bB][01](?:_?[01])*n?|0[oO][0-7](?:_?[0-7])*n?|\\d(?:_?\\d)*n|(?:\\d(?:_?\\d)*(?:\\.\\d(?:_?\\d)*)?|\\.\\d(?:_?\\d)*)(?:[eE][+-]?\\d(?:_?\\d)*)?)(?![\\w$])',
            name: 'constant.numeric'
          },
          constants: {
            patterns: [
              { match: '\\b(?:false|true)\\b', name: 'constant.language.boolean' },
              { match: '\\b(?:Infinity|NaN|null|undefined)\\b', name: 'constant.language' }
            ]
          },
          decorators: { match: '@[A-Za-z_$][\\w$]*', name: 'entity.name.decorator' },
          'private-fields': { match: '#[A-Za-z_$][\\w$]*', name: 'variable.other.private' },
          properties: { match: '(?<=\\.)[A-Za-z_$][\\w$]*|[A-Za-z_$][\\w$]*(?=\\s*:)', name: 'entity.name.property' },
          functions: { match: '\\b[A-Za-z_$][\\w$]*(?=\\s*\\()', name: 'entity.name.function' },
          'built-ins': {
            match: '\\b(?:Array|BigInt|Boolean|console|customElements|Date|document|Error|JSON|Map|Math|Number|Object|Promise|Reflect|RegExp|Set|String|Symbol|WeakMap|WeakSet|window)\\b',
            name: 'support.class'
          },
          operators: {
            match: '(?:=>|===|!==|==|!=|<=|>=|\\?\\?|\\?\\.|\\+\\+|--|&&|\\|\\||\\*\\*|[+*%&|^!~?:-])',
            name: 'keyword.operator'
          }
        }
      }
    },

    // ---- grammars/java.js -------------------------------------------------
    {
      name: 'java',
      grammar: {
        scopeName: 'source.java',
        patterns: [
          { include: '#comments' },
          { include: '#strings' },
          { include: '#annotations' },
          { include: '#class-declaration' },
          { include: '#keywords' },
          { include: '#builtin-types' },
          { include: '#constants' },
          { include: '#numbers' },
          { include: '#operators' },
          { include: '#function-declaration' }
        ],
        repository: {
          comments: {
            patterns: [
              { match: '//.*$', name: 'comment.line.double-slash' },
              { begin: '/\\*', end: '\\*/', name: 'comment.block' }
            ]
          },
          strings: {
            patterns: [
              { begin: '"""', end: '"""', name: 'string.quoted.triple' },
              { match: '"(?:\\\\.|[^"\\\\\\r\\n])*"', name: 'string.quoted.double' },
              { match: "'(?:\\\\.|[^'\\\\\\r\\n])'", name: 'string.quoted.char' }
            ]
          },
          annotations: { match: '@[A-Za-z_][\\w.]*', name: 'entity.name.decorator' },
          'class-declaration': {
            match: '\\b(class|interface|enum|record)\\s+([A-Za-z_]\\w*)',
            captures: {
              1: { name: 'storage.type.class' },
              2: { name: 'entity.name.type.class' }
            }
          },
          keywords: {
            match: '\\b(?:abstract|assert|break|case|catch|continue|default|do|else|extends|final|finally|for|goto|if|implements|import|instanceof|native|new|non-sealed|package|permits|private|protected|public|return|sealed|static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|var|volatile|while|yield)\\b',
            name: 'keyword.control'
          },
          'builtin-types': {
            match: '\\b(?:boolean|byte|char|double|float|int|long|short|void|Boolean|Byte|Character|Double|Float|Integer|Long|Short|String|Object|List|Map|Set|ArrayList|HashMap|HashSet|Optional)\\b',
            name: 'support.class.builtin'
          },
          constants: {
            patterns: [
              { match: '\\b(?:true|false)\\b', name: 'constant.language.boolean' },
              { match: '\\bnull\\b', name: 'constant.language' }
            ]
          },
          numbers: {
            match: '(?<![\\w.])(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|\\d(?:_?\\d)*(?:\\.\\d(?:_?\\d)*)?(?:[eE][+-]?\\d+)?)[lLfFdD]?\\b',
            name: 'constant.numeric'
          },
          operators: {
            match: '(?:->|::|&&|\\|\\||==|!=|<=|>=|<<=?|>>>?=?|[+\\-*/%&|^!~<>=]=?)',
            name: 'keyword.operator'
          },
          'function-declaration': {
            match: '\\b([A-Za-z_]\\w*)(?=\\s*\\()',
            name: 'entity.name.function'
          }
        }
      }
    },

    // ---- grammars/html.js -------------------------------------------------
    {
      name: 'html',
      grammar: {
        scopeName: 'text.html.basic',
        dependencies: ['css', 'json', 'javascript'],
        patterns: [
          { include: '#comments' },
          { include: '#doctype' },
          { include: '#embedded-css' },
          { include: '#embedded-json' },
          { include: '#embedded-javascript' },
          { include: '#raw-text' },
          { include: '#tags' },
          { include: '#entities' }
        ],
        repository: {
          comments: { begin: '<!--', end: '-->', name: 'comment.block' },
          doctype: { match: '<![Dd][Oo][Cc][Tt][Yy][Pp][Ee]\\b[^>]*>', name: 'keyword.control.doctype' },
          'embedded-css': {
            begin: '<([Ss][Tt][Yy][Ll][Ee])\\b(?:"[^"]*"|\'[^\']*\'|[^\'">])*>',
            end: '</([Ss][Tt][Yy][Ll][Ee])[ \\t]*>',
            beginCaptures: { 1: { name: 'entity.name.tag' } },
            endCaptures: { 1: { name: 'entity.name.tag' } },
            contentName: 'source.css.embedded.html',
            patterns: [{ include: 'source.css' }]
          },
          'embedded-json': {
            begin: '<([Ss][Cc][Rr][Ii][Pp][Tt])\\b(?=[^>]*\\b[Tt][Yy][Pp][Ee]\\s*=\\s*([\'"])(?:application/(?:ld\\+)?json|importmap|speculationrules)\\2)(?:"[^"]*"|\'[^\']*\'|[^\'">])*>',
            end: '</([Ss][Cc][Rr][Ii][Pp][Tt])[ \\t]*>',
            beginCaptures: { 1: { name: 'entity.name.tag' } },
            endCaptures: { 1: { name: 'entity.name.tag' } },
            contentName: 'source.json.embedded.html',
            patterns: [{ include: 'source.json' }]
          },
          'embedded-javascript': {
            begin: '<([Ss][Cc][Rr][Ii][Pp][Tt])\\b(?:(?![^>]*\\b[Tt][Yy][Pp][Ee]\\s*=)|(?=[^>]*\\b[Tt][Yy][Pp][Ee]\\s*=\\s*([\'"])(?:module|(?:text|application)/(?:java|ecma)script)\\2))(?:"[^"]*"|\'[^\']*\'|[^\'">])*>',
            end: '</([Ss][Cc][Rr][Ii][Pp][Tt])[ \\t]*>',
            beginCaptures: { 1: { name: 'entity.name.tag' } },
            endCaptures: { 1: { name: 'entity.name.tag' } },
            contentName: 'source.js.embedded.html',
            patterns: [{ include: 'source.js' }]
          },
          'raw-text': {
            begin: '<([Tt][Ee][Xx][Tt][Aa][Rr][Ee][Aa]|[Tt][Ii][Tt][Ll][Ee])\\b(?:"[^"]*"|\'[^\']*\'|[^\'">])*>',
            end: '</([Tt][Ee][Xx][Tt][Aa][Rr][Ee][Aa]|[Tt][Ii][Tt][Ll][Ee])[ \\t]*>',
            beginCaptures: { 1: { name: 'entity.name.tag' } },
            endCaptures: { 1: { name: 'entity.name.tag' } },
            contentName: 'string.unquoted.raw-text'
          },
          tags: {
            begin: '<(/?)([a-zA-Z][\\w.-]*)',
            end: '>',
            beginCaptures: { 2: { name: 'entity.name.tag' } },
            patterns: [
              { include: '#inline-css' },
              { include: '#inline-javascript' },
              { include: '#attributes' },
              { include: '#unquoted-attributes' },
              { include: '#boolean-attributes' }
            ]
          },
          'inline-css': {
            begin: '[ \\t]+(style)\\s*=\\s*([\'"])',
            end: '\\2',
            beginCaptures: {
              1: { name: 'entity.other.attribute-name' },
              2: { name: 'punctuation.definition.string.begin' }
            },
            endCaptures: { 0: { name: 'punctuation.definition.string.end' } },
            contentName: 'source.css.embedded.inline',
            patterns: [
              { include: 'source.css#comments' },
              { include: 'source.css#strings' },
              { include: 'source.css#declarations' },
              { include: 'source.css#values' }
            ]
          },
          'inline-javascript': {
            begin: '[ \\t]+(on[a-zA-Z][\\w-]*)\\s*=\\s*([\'"])',
            end: '\\2',
            beginCaptures: {
              1: { name: 'entity.other.attribute-name' },
              2: { name: 'punctuation.definition.string.begin' }
            },
            endCaptures: { 0: { name: 'punctuation.definition.string.end' } },
            contentName: 'source.js.embedded.inline',
            patterns: [{ include: 'source.js' }]
          },
          attributes: {
            begin: '[ \\t]+([a-zA-Z_:][\\w:.-]*)\\s*=\\s*([\'"])',
            end: '\\2',
            beginCaptures: {
              1: { name: 'entity.other.attribute-name' },
              2: { name: 'punctuation.definition.string.begin' }
            },
            endCaptures: { 0: { name: 'punctuation.definition.string.end' } },
            contentName: 'string.quoted.attribute-value',
            patterns: [{ include: '#entities' }]
          },
          'unquoted-attributes': {
            match: '[ \\t]+([a-zA-Z_:][\\w:.-]*)\\s*=\\s*([^\\s"\'`=<>]+)',
            captures: {
              1: { name: 'entity.other.attribute-name' },
              2: { name: 'string.unquoted.attribute-value' }
            }
          },
          'boolean-attributes': {
            match: '[ \\t]+([a-zA-Z_:][\\w:.-]*)(?=[ \\t]*/?>|[ \\t]+[a-zA-Z_:])',
            captures: { 1: { name: 'entity.other.attribute-name' } }
          },
          entities: { match: '&(?:#\\d+|#x[0-9a-fA-F]+|[a-zA-Z][\\w]+);', name: 'constant.character.entity' }
        }
      }
    },

    // ---- grammars/markdown.js --------------------------------------------
    {
      name: 'markdown',
      grammar: {
        scopeName: 'text.html.markdown',
        dependencies: ['yaml'],
        patterns: [
          { include: '#front-matter' },
          { include: '#comments' },
          { include: '#fenced-code' },
          { include: '#headings' },
          { include: '#quotes' },
          { include: '#lists' },
          { include: '#links' },
          { include: '#inline-code' }
        ],
        repository: {
          'front-matter': {
            begin: '^[ \\t]*---[ \\t]*$',
            end: '^[ \\t]*(?:---|\\.\\.\\.)[ \\t]*$',
            patterns: [{ include: 'source.yaml' }]
          },
          comments: { begin: '<!--', end: '-->', name: 'comment.block.html' },
          'fenced-code': { begin: '^[ \\t]*(```+|~~~+).*$', end: '^[ \\t]*\\1[ \\t]*$', name: 'string.unquoted.fenced-code' },
          headings: { match: '^[ \\t]{0,3}#{1,6}[ \\t]+(.+)$', captures: { 1: { name: 'entity.name.section' } } },
          quotes: { match: '^[ \\t]*>.*$', name: 'comment.blockquote' },
          lists: { match: '^[ \\t]*(?:[-+*]|\\d+[.)])(?=[ \\t]+)', name: 'keyword.control.list' },
          links: { match: '!?\\[([^\\]]+)\\](\\([^\\s)]+(?:\\s+[\'"][^\'"]*[\'"])?\\))', captures: { 1: { name: 'entity.name.link' }, 2: { name: 'string.other.link' } } },
          'inline-code': { match: '(`+)(.+?)\\1', captures: { 2: { name: 'string.other.raw' } } }
        }
      }
    }
  ];

  /* ------------------------------------------------- grammar registry (1/2) */

  var LANGUAGES = {};
  var SCOPES = new Map();
  GRAMMARS.forEach(function (entry) {
    LANGUAGES[entry.name] = entry.grammar;
    SCOPES.set(entry.grammar.scopeName, entry.grammar);
  });

  var ALIASES = {
    docker: 'dockerfile', gql: 'graphql', js: 'javascript', jsx: 'javascript',
    md: 'markdown', py: 'python', rb: 'ruby', sass: 'scss', sh: 'bash',
    shell: 'bash', ts: 'typescript', yml: 'yaml', zsh: 'bash'
  };

  function normalizeLanguage(language, aliases) {
    return (aliases && aliases[language]) || ALIASES[language] || language;
  }

  /* ------------------------------------------------------- scope -> category */
  // Upstream highlight.js, unchanged: flatten a TextMate scope to the one
  // semantic CSS highlight category it belongs to.
  function getCategory(scope) {
    var parts = scope.split('.');
    var first = parts[0], second = parts[1], third = parts[2];
    var last = parts[parts.length - 1];

    if (first === 'markup' && ['quote', 'inserted', 'deleted', 'raw'].indexOf(second) !== -1) return second;
    if (first === 'entity' && second === 'name') return third;
    if (scope.indexOf('constant.character.entity') === 0) return 'character-entity';
    if (parts.indexOf('numeric') !== -1) return 'numeric';
    if (scope.indexOf('support.type.property-name') === 0) return 'property';
    if (parts.indexOf('attribute-value') !== -1) return 'attribute-value';
    if (scope.indexOf('string.other.link') === 0) return 'link';

    if (['doctype', 'at-rule', 'important', 'regexp', 'boolean',
         'symbol', 'operator', 'attribute-name'].indexOf(last) !== -1) return last;

    if (['comment', 'string', 'constant', 'storage', 'keyword',
         'variable', 'punctuation', 'entity', 'support'].indexOf(first) !== -1) return first;

    return undefined;
  }

  /* ------------------------------------------------------------------ engine */

  var highlights = new Map();
  var noMatch = { indices: [[Infinity, Infinity]] };

  function addRange(node, start, end, scope) {
    var category = getCategory(scope);
    if (!category || start === end || start === undefined || end === undefined) return;

    var range = new Range();
    range.setStart(node, start);
    range.setEnd(node, end);

    if (!highlights.has(category)) highlights.set(category, new Highlight());
    highlights.get(category).add(range);
  }

  function addCaptures(node, match, captures) {
    Object.keys(captures || {}).forEach(function (index) {
      var offsets = match.indices[+index];
      if (offsets) addRange(node, offsets[0], offsets[1], captures[index].name);
    });
  }

  function escapeRegex(value) {
    return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }

  function expandEnd(pattern, beginMatch) {
    return pattern.replace(/\\(\d+)/g, function (reference, index) {
      return beginMatch[index] === undefined ? reference : escapeRegex(beginMatch[index]);
    });
  }

  function getRules(rule) {
    if (!rule) return [];
    if (Array.isArray(rule)) return rule;
    if (rule.match || rule.begin || rule.include) return [rule];
    return rule.patterns || [];
  }

  function getLanguage(codeBlock) {
    var pre = codeBlock.parentElement;
    var fromCode = Array.prototype.slice.call(codeBlock.classList)
      .filter(function (c) { return c.indexOf('language-') === 0; })[0];
    var fromPre = pre ? Array.prototype.slice.call(pre.classList)
      .filter(function (c) { return c.indexOf('language-') === 0; })[0] : undefined;
    var language = (fromCode && fromCode.slice('language-'.length))
      || codeBlock.dataset.language
      || (fromPre && fromPre.slice('language-'.length))
      || (pre && pre.dataset.language)
      || (pre && pre.getAttribute('lang'))
      || '';
    return language.toLowerCase();
  }

  /* --------------------------------------------- grammar registry (2/2) */
  // Upstream loads grammars with `await import('./grammars/x.js')`. A single
  // file has no module loader, so the registry is already complete and the
  // loader is the identity function. Known-but-missing scopes are still
  // tracked so an include that cannot resolve is skipped, exactly as upstream
  // skips a grammar whose dynamic import failed.
  function createGrammarLoader(grammars) {
    var attempted = new Set();
    return function () {
      return Promise.resolve(grammars);
    };
  }

  /* ------------------------------------------------ public API: highlightAll */

  function highlightAll(options) {
    options = options || {};
    var root = options.root || document;
    var selector = options.selector || 'pre > code';
    var aliases = options.languageAliases;

    if (typeof CSS === 'undefined' || !CSS.highlights || typeof Highlight !== 'function') {
      // No Custom Highlight API: report the blocks we would have highlighted
      // rather than throwing, so a page can show an honest status.
      return Promise.resolve(Array.prototype.filter.call(root.querySelectorAll(selector), getLanguage));
    }

    var grammars = { languages: LANGUAGES, scopes: SCOPES };
    var loadGrammars = createGrammarLoader(grammars);

    var codeBlocks = Array.prototype.filter.call(root.querySelectorAll(selector), getLanguage);
    var languages = codeBlocks.map(function (codeBlock) {
      return normalizeLanguage(getLanguage(codeBlock), aliases);
    });

    return loadGrammars(languages).then(function () {
      var regexes = new Map();
      var matches = new Map();

      // Drop the ranges of the previous scan before creating replacements.
      highlights.forEach(function (ranges, category) {
        if (CSS.highlights.get(category) === ranges) CSS.highlights.delete(category);
        ranges.clear();
      });

      function exec(pattern, node, start, end) {
        var match = matches.get(pattern);

        if (!match || match.indices[0][0] < start) {
          if (!regexes.has(pattern)) regexes.set(pattern, new RegExp(pattern, 'dgm'));
          var regex = regexes.get(pattern);
          regex.lastIndex = start;
          match = regex.exec(node.data) || noMatch;
          matches.set(pattern, match);
        }

        return match.indices[0][0] < end && match.indices[0][1] <= end ? match : null;
      }

      function resolveInclude(include, grammar, baseGrammar) {
        if (include === '$self') return { grammar: grammar, rules: grammar.patterns };
        if (include === '$base') return { grammar: baseGrammar, rules: baseGrammar.patterns };

        if (include[0] === '#') {
          return { grammar: grammar, rules: getRules(grammar.repository && grammar.repository[include.slice(1)]) };
        }

        var split = include.split('#');
        var scopeName = split[0], repositoryName = split[1];
        var includedGrammar = grammars.scopes.get(scopeName);
        if (!includedGrammar) return null;

        return {
          grammar: includedGrammar,
          rules: repositoryName
            ? getRules(includedGrammar.repository && includedGrammar.repository[repositoryName])
            : includedGrammar.patterns
        };
      }

      function expandRules(rules, grammar, baseGrammar, activeIncludes) {
        activeIncludes = activeIncludes || new Set();
        var expanded = [];

        rules.forEach(function (rule) {
          if (rule.include) {
            var includeKey = grammar.scopeName + ':' + rule.include;
            if (activeIncludes.has(includeKey)) return;

            var included = resolveInclude(rule.include, grammar, baseGrammar);
            if (!included) return;

            var nestedIncludes = new Set(activeIncludes);
            nestedIncludes.add(includeKey);
            expanded.push.apply(expanded, expandRules(included.rules, included.grammar, baseGrammar, nestedIncludes));
            return;
          }

          if (rule.match || (rule.begin && rule.end)) expanded.push({ grammar: grammar, rule: rule });
        });

        return expanded;
      }

      function nextRule(node, contexts, start, end) {
        var winner = null;

        contexts.forEach(function (context) {
          var pattern = context.rule.match || context.rule.begin;
          var match = exec(pattern, node, start, end);
          if (!match) return;

          if (!winner || match.indices[0][0] < winner.match.indices[0][0]) {
            winner = { grammar: context.grammar, rule: context.rule, match: match };
          }
        });

        return winner;
      }

      function scanRegion(node, rules, start, end, grammar, baseGrammar, closing) {
        baseGrammar = baseGrammar || grammar;
        var contexts = expandRules(rules, grammar, baseGrammar);
        var cursor = start;

        while (cursor < end) {
          var candidate = nextRule(node, contexts, cursor, end);
          var endMatch = closing ? exec(closing.pattern, node, cursor, end) : null;
          var candidateStart = candidate ? candidate.match.indices[0][0] : Infinity;
          var endStart = endMatch ? endMatch.indices[0][0] : Infinity;

          if (endMatch && (endStart < candidateStart || (endStart === candidateStart && !(closing && closing.applyEndPatternLast)))) {
            return { contentEnd: endStart, end: endMatch.indices[0][1], match: endMatch };
          }

          if (!candidate) return { contentEnd: end, end: end, match: null };

          var rule = candidate.rule, match = candidate.match, ruleGrammar = candidate.grammar;
          if (rule.match) {
            if (rule.name) addRange(node, match.indices[0][0], match.indices[0][1], rule.name);
            addCaptures(node, match, rule.captures);
            cursor = match.indices[0][1] > cursor ? match.indices[0][1] : cursor + 1;
            continue;
          }

          var nested = scanRegion(
            node,
            rule.patterns || [],
            match.indices[0][1],
            end,
            ruleGrammar,
            baseGrammar,
            { pattern: expandEnd(rule.end, match), applyEndPatternLast: rule.applyEndPatternLast }
          );

          if (rule.name) addRange(node, match.indices[0][0], nested.end, rule.name);
          if (rule.contentName) addRange(node, match.indices[0][1], nested.contentEnd, rule.contentName);
          addCaptures(node, match, rule.beginCaptures || rule.captures);
          if (nested.match) addCaptures(node, nested.match, rule.endCaptures || rule.captures);

          cursor = nested.end > cursor ? nested.end : cursor + 1;
        }

        return { contentEnd: end, end: end, match: null };
      }

      codeBlocks.forEach(function (codeBlock, index) {
        var grammar = grammars.languages[languages[index]];
        if (!grammar) return;

        codeBlock.normalize();
        var node = codeBlock.firstChild;
        // microlighter highlights a block only when the whole block is ONE text
        // node: no nested elements, no whitespace-only siblings.
        if (!node || node.nodeType !== Node.TEXT_NODE || node.nextSibling) return;

        matches = new Map();
        scanRegion(node, grammar.patterns, 0, node.data.length, grammar);
      });

      highlights.forEach(function (ranges, category) {
        if (ranges.size) CSS.highlights.set(category, ranges);
      });

      return codeBlocks;
    });
  }

  return {
    highlightAll: highlightAll,
    grammars: LANGUAGES,
    normalizeLanguage: normalizeLanguage,
    getCategory: getCategory
  };
})();
