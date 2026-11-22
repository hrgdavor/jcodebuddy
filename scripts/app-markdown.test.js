import { describe, expect, test } from 'bun:test'

import {
  approximateLine,
  lineFromFragment,
  renderMarkdown,
  resolveLink,
} from './app-markdown.js'

describe('app Markdown handler', () => {
  test('renders headings, fenced code and tables', () => {
    const html = renderMarkdown('# Title\n\n```java\nint x = 1;\n```\n\n| A | B |\n|---|---|\n| 1 | 2 |\n')
    expect(html).toContain('<h1 data-md-line="1" id="title">Title</h1>')
    expect(html).toContain('<pre data-md-line="3"><code class="language-java">int x = 1;</code></pre>')
    expect(html).toContain('<table>')
    expect(html).toContain('<td>1</td>')
  })

  test('resolves a relative source link and keeps its line fragment', () => {
    expect(resolveLink('../src/A.java#L12-L14', 'docs/README.md')).toMatchObject({
      kind: 'source',
      path: 'src/A.java',
      fragment: 'L12-L14',
      line: { line: 12, column: 1 },
    })
    expect(resolveLink('README.md#L2', 'docs/README.md')).toMatchObject({
      kind: 'doc',
      path: 'docs/README.md',
      fragment: 'L2',
    })
  })

  test('marks a source link for Ctrl+click navigation', () => {
    const html = renderMarkdown('[A.java](src/A.java#L12)', 'README.md')
    expect(html).toContain('data-source="src/A.java"')
    expect(html).toContain('data-fragment="L12"')
    expect(html).toContain('Ctrl+click')
  })

  test('protects link URLs from emphasis formatting', () => {
    const html = renderMarkdown('[doc](doc/business_logic/README.md)', 'README.md')
    expect(html).toContain('openFile=doc%2Fbusiness_logic%2FREADME.md')
    expect(html).toContain('data-source="doc/business_logic/README.md"')
    expect(html).not.toContain('business<em>logic')
  })

  test('maps rendered segments to source lines', () => {
    const html = renderMarkdown('first **bold**\nsecond\n\nnext', 'README.md')
    expect(html).toContain('<span data-md-line="1">first <strong>bold</strong></span>')
    expect(html).toContain('<span data-md-line="2">second</span>')
    expect(html).toContain('<p data-md-line="4"><span data-md-line="4">next</span></p>')
  })

  test('parses line fragments and approximates a named declaration', () => {
    expect(lineFromFragment('L12')).toEqual({ line: 12, column: 1 })
    expect(lineFromFragment('L12:5')).toEqual({ line: 12, column: 5 })
    expect(approximateLine('class A {\n  void hello() {\n  }\n}', 'A/hello')).toBe(2)
    expect(approximateLine('// #region add\nint x;\n', '#region add')).toBe(1)
  })
})
