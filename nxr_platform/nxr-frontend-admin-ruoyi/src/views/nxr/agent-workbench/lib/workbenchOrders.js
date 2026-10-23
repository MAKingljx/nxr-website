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
  if (['addressLine1', 'city', 'country'].some(field => !String(draft.customerAddress?.[field] || '').trim())) return 'Complete the customer address.'
  if (!draft.cards.length || draft.cards.length > maxCards) return 'Check the card quantity.'
  for (const card of draft.cards) {
    if (['cardName', 'brandName', 'setName', 'officialCardNumber', 'languageCode', 'productType', 'category'].some(field => !String(card[field] || '').trim())) return 'Complete the required card fields.'
    if (!card.front.file && !card.front.id || !card.back.file && !card.back.id) return 'Photograph and attach both sides of every card.'
  }
  return ''
}

export function submissionOrderIds(value) {
  if (value == null || value === '') return []
  const parts = Array.isArray(value) ? value : String(value).split(',')
  if (parts.length > 100 || parts.some(part => !/^[1-9]\d*$/.test(String(part)) || !Number.isSafeInteger(Number(part)))) throw new Error('Select valid orders for this Submit.')
  return [...new Set(parts.map(Number))].sort((a, b) => a - b)
}

export function addressLabel(address) {
  if (!address) return ''
  return [address.country, address.region, address.city, address.addressLine1, address.addressLine2, address.postalCode].filter(Boolean).join(' · ')
}

export function displayedCardStatus(orderStatus, cardStatus) {
  // An inbound parcel has shipped even while its cards await warehouse receipt.
  return orderStatus === 'inbound_shipped' && cardStatus === 'awaiting_inbound' ? 'inbound_shipped' : cardStatus
}

export function draftFingerprint(draft) {
  const photo = value => value.file ? { name: value.file.name, size: value.file.size, modified: value.file.lastModified } : { id: value.id }
  return { ...draft, customerAddress: { ...draft.customerAddress }, cards: draft.cards.map(({ localId, front, back, ...fields }) => ({ ...fields, front: photo(front), back: photo(back) })) }
}

export function canCompleteExistingIntake(order) {
  return Boolean(order?.legacy && !order.gradingOrderId && ['expected', 'received', 'exception'].includes(order.intakeStatusCode))
}
