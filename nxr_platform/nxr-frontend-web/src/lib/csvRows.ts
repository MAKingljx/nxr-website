/** Reads quoted commas, escaped quotes and multiline cells without losing row contents. */
export function csvRows(source: string): string[][] {
  const text = source.replace(/^\uFEFF/, '')
  const rows: string[][] = []
  let row: string[] = [], value = '', quoted = false, closedQuote = false
  const cell = () => { row.push(value.trim()); value = ''; closedQuote = false }
  const record = () => { cell(); if (row.some(value => value !== '')) rows.push(row); row = [] }
  for (let index = 0; index < text.length; index++) {
    const char = text[index]!
    if (quoted) {
      if (char === '"' && text[index + 1] === '"') { value += '"'; index++ }
      else if (char === '"') { quoted = false; closedQuote = true }
      else value += char
    } else if (char === ',') cell()
    else if (char === '\n' || char === '\r') { if (char === '\r' && text[index + 1] === '\n') index++; record() }
    else if (char === '"' && !value.trim() && !closedQuote) { quoted = true; value = '' }
    else if (closedQuote && char.trim()) throw new Error('Unexpected text after a quoted CSV cell.')
    else value += char
  }
  if (quoted) throw new Error('A quoted CSV cell was not closed.')
  if (row.length || value.length || closedQuote) record()
  return rows
}
