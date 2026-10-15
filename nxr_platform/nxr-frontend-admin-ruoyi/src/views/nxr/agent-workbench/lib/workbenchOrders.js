// Orders keep their full card group on outbound submission; returns are counted per card.
export function orderSelection(orders) {
  return Object.values(orders).filter(order => order.canSubmit)
}

export function orderReturnProgress(order) {
  if ([order.cardCount, order.deliveredCardCount, order.returnShippedCardCount].some(value => value == null)) return null
  const total = Number(order.cardCount)
  const delivered = Number(order.deliveredCardCount)
  const shipped = Number(order.returnShippedCardCount)
  if (!Number.isFinite(total) || !Number.isFinite(delivered) || !Number.isFinite(shipped)) return null
  return { total, delivered, shipped, complete: total > 0 && delivered === total }
}

export function validateOrderDraft(draft, maxCards) {
  if (!draft.clientName.trim()) return 'Enter the customer name.'
  if (!draft.phone.trim() && !draft.email.trim()) return 'Enter a phone number or email address.'
  if (!draft.cards.length || draft.cards.length > maxCards) return 'Check the card quantity.'
  for (const card of draft.cards) {
    if (['cardName', 'brandName', 'setName', 'officialCardNumber', 'languageCode', 'productType', 'category'].some(field => !String(card[field] || '').trim())) return 'Complete the required card fields.'
    if (!card.front.file && !card.front.id || !card.back.file && !card.back.id) return 'Photograph and attach both sides of every card.'
  }
  return ''
}

export function draftFingerprint(draft) {
  const photo = value => value.file ? { name: value.file.name, size: value.file.size, modified: value.file.lastModified } : { id: value.id }
  return { ...draft, cards: draft.cards.map(({ localId, front, back, ...fields }) => ({ ...fields, front: photo(front), back: photo(back) })) }
}

export function canCompleteExistingIntake(order) {
  return Boolean(order?.legacy && !order.gradingOrderId && ['expected', 'received', 'exception'].includes(order.intakeStatusCode))
}
