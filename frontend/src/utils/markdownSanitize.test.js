import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { createMarkdownSanitizer } from './markdownSanitize.js'
import { renderMathToHtml } from './mdMath.js'

test('the allowlist removes active markup and retains supported Markdown formatting', () => {
  const { window } = new JSDOM()
  try {
    const { document } = window
    const input = [
      '<h2 id="sample">Heading</h2>',
      '<table style="width: 500px"><colgroup><col style="width: 120px"></colgroup><tbody><tr><td colspan="1">Cell</td></tr></tbody></table>',
      '<font style="color: var(--brand); background-color: rgb(240, 255, 250); font-size: 1.2em; position: fixed" onfocus="">Sample</font>',
      '<a href="#sample">Jump</a><a href="https://example.test/" target="_blank" ping="/sample">External</a>',
      '<input type="checkbox" checked><input type="text" value="sample">',
      '<div data-line="12" data-tex="x^2" class="math-block">' + renderMathToHtml('\\underbrace{x+y}_{z}', { display: true }) + '</div>',
      '<style>p { color: red }</style><iframe title="synthetic frame" srcdoc="Sample"></iframe>',
      '<form><button>Button</button></form><div contenteditable="true">Body</div>',
    ].join('')
    const template = document.createElement('template')
    template.innerHTML = createMarkdownSanitizer(window)(input)
    const safe = template.content
    assert.equal(safe.querySelector('h2').id, 'sample')
    assert.equal(safe.querySelector('td').textContent, 'Cell')
    assert.equal(safe.querySelector('col').style.width, '120px')
    assert.equal(safe.querySelector('font').style.color, 'var(--brand)')
    assert.equal(safe.querySelector('font').style.backgroundColor, 'rgb(240, 255, 250)')
    assert.equal(safe.querySelector('font').style.fontSize, '1.2em')
    assert.equal(safe.querySelector('font').style.position, '')
    assert.equal(safe.querySelector('a').getAttribute('href'), '#sample')
    assert.equal(safe.querySelector('a[target="_blank"]').rel, 'noopener noreferrer')
    assert.ok(safe.querySelector('input[type="checkbox"]:checked:disabled'))
    assert.equal(safe.querySelector('.math-block').dataset.line, '12')
    assert.equal(safe.querySelector('.math-block').dataset.tex, 'x^2')
    assert.ok(safe.querySelector('.katex math'))
    assert.ok(safe.querySelector('.katex svg path'))
    assert.ok(!safe.querySelector('style, iframe, form, button, input[type="text"], [onfocus], [ping], [srcdoc], [contenteditable]'))
  } finally { window.close() }
})
