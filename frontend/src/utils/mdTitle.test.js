import test from 'node:test'
import assert from 'node:assert/strict'
import { stripLeadingDocTitle } from './mdTitle.js'

test('export only removes an exact first heading and preserves the remaining Markdown', () => {
  for (const source of ['# Sample methods\nBody', '# Sam\nBody', '# Sample!\nBody', '    # Sample\nBody', '## Sample\nBody']) {
    assert.equal(stripLeadingDocTitle(source, 'Sample'), source)
  }
  const body = '    code\r\n\r\n$$\r\nx-y\r\n$$'
  assert.equal(stripLeadingDocTitle('# Sample #\r\n\r\n' + body, 'Sample'), body)
})
