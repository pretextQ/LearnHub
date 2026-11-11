import createDOMPurify from 'dompurify'

const OPTIONS = {
  USE_PROFILES: { html: true, mathMl: true, svg: true },
  ADD_TAGS: ['annotation'],
  ADD_ATTR: ['target', 'language'],
  FORBID_TAGS: ['style', 'form', 'button', 'select', 'option', 'textarea', 'iframe', 'object', 'embed',
    'video', 'audio', 'source', 'track', 'foreignobject', 'annotation-xml', 'template', 'noscript', 'use', 'image'],
  FORBID_ATTR: ['srcdoc', 'srcset', 'ping', 'autofocus', 'contenteditable', 'form', 'formaction', 'action'],
}

// Retain table sizing, inline formatting and KaTeX layout. CSS rules and
// resource-loading declarations are not part of the Markdown format.
const STYLE_PROPERTY = /^(?:color|background-color|font(?:-(?:family|size|weight|style))?|text-align|text-decoration(?:-(?:line|style|color|thickness))?|white-space|vertical-align|line-height|letter-spacing|text-indent|(?:min-|max-)?(?:width|height)|top|left|margin(?:-(?:top|right|bottom|left))?|padding(?:-(?:top|right|bottom|left))?|border(?:-(?:top|right|bottom|left|collapse|spacing|color|style|width))?|table-layout|display)$/
const PLAIN_CSS = /^[\p{L}\p{N}#%.,+\- \t'"\/]+$/u
const COLOR_FUNCTION = /^(?:rgba?|hsla?)\([\d.,% \t+/-]+\)$/i
const THEME_VARIABLE = /^var\(--[a-z0-9-]+\)$/i

function inlineStyle(css) {
  return css.split(';').flatMap(declaration => {
    const split = declaration.indexOf(':')
    if (split < 0) return []
    const property = declaration.slice(0, split).trim().toLowerCase()
    const value = declaration.slice(split + 1).trim()
    if (!STYLE_PROPERTY.test(property)) return []
    return (PLAIN_CSS.test(value) || COLOR_FUNCTION.test(value) || THEME_VARIABLE.test(value))
      ? [property + ': ' + value] : []
  }).join('; ')
}

export function createMarkdownSanitizer(window) {
  const purify = createDOMPurify(window)
  if (!purify.isSupported) throw new Error('当前环境不支持 Markdown 预览')
  purify.addHook('uponSanitizeAttribute', (_node, attribute) => {
    if (attribute.attrName !== 'style') return
    attribute.attrValue = inlineStyle(attribute.attrValue)
    attribute.keepAttr = !!attribute.attrValue
  })
  purify.addHook('afterSanitizeAttributes', node => {
    if (node.localName === 'a') {
      const target = node.getAttribute('target')?.toLowerCase()
      if (target === '_blank') node.setAttribute('rel', 'noopener noreferrer')
      else if (target !== '_self') node.removeAttribute('target')
    } else if (node.localName === 'input') {
      if (node.getAttribute('type')?.toLowerCase() === 'checkbox') node.setAttribute('disabled', '')
      else node.remove()
    }
  })
  return html => purify.sanitize(html, OPTIONS)
}

let sanitize
let previousWindow
export function sanitizeMarkdownHtml(html) {
  const window = globalThis.window
  if (!sanitize || window !== previousWindow) {
    sanitize = createMarkdownSanitizer(window)
    previousWindow = window
  }
  return sanitize(String(html ?? ''))
}
