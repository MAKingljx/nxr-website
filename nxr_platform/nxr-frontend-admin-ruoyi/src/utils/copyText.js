export async function copyText(text, clipboard = globalThis.navigator?.clipboard, doc = globalThis.document) {
  if (typeof clipboard?.writeText === 'function') {
    try { await clipboard.writeText(text); return true } catch { /* Try the browser's legacy copy command. */ }
  }
  if (!doc?.body || typeof doc.execCommand !== 'function') return false
  let input
  const focused = doc.activeElement
  try {
    input = doc.createElement('textarea')
    input.value = text
    input.setAttribute('readonly', '')
    input.style.position = 'fixed'
    input.style.left = '-9999px'
    doc.body.appendChild(input)
    input.select()
    return doc.execCommand('copy') === true
  } catch {
    return false
  } finally {
    input?.remove()
    focused?.focus?.()
  }
}
