import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

export async function resolve(specifier, context, nextResolve) {
  if (specifier.endsWith('?raw')) {
    const result = await nextResolve(specifier.slice(0, -4), context)
    return { ...result, url: result.url + '?raw' }
  }
  if (specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier)) {
    return nextResolve(specifier + '.js', context)
  }
  return nextResolve(specifier, context)
}

export async function load(url, context, nextLoad) {
  if (!url.endsWith('?raw')) return nextLoad(url, context)
  const css = await readFile(fileURLToPath(url.slice(0, -4)), 'utf8')
  return { format: 'module', source: 'export default ' + JSON.stringify(css), shortCircuit: true }
}
