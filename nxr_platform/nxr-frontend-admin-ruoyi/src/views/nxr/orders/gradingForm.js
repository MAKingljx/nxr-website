export function partnerGradingBlock(orderStatus, itemStatus) {
  if (['return_shipped', 'delivered'].includes(itemStatus)) return 'This card has already been shipped.'
  if (orderStatus === 'intake_exception') return 'Resolve the receipt exception before grading.'
  if (['awaiting_inbound', 'inbound_shipped'].includes(orderStatus)) return 'Confirm receipt before grading.'
  return ['received', 'grading', 'review', 'quality_check', 'quality_hold'].includes(orderStatus) ? '' : 'Grading is unavailable at this stage.'
}

export const scoreFields = ['centering', 'edges', 'corners', 'surface'].map(name => ({
  key: `${name}Score`, label: `entries.${name}`
}))

export function normalizeProductType(value) {
  if (value === 'merch_product' || value === 'label_product') return 'merch_product'
  return value === 'vintage_product' ? value : 'graded_card'
}

export function emptySubmission() {
  return {
    certId: '', productType: 'graded_card', vintageClassification: '', merchDescription: '',
    cardCategory: 'trading_card', cardName: '', movieName: '', releaseYear: '', productionCompany: '',
    filmType: '', sportsType: '', groupName: '', yearLabel: '', brandName: '', setName: '',
    cardNumber: '', languageCode: 'EN', varietyName: '', entryNotes: '',
    ...Object.fromEntries(scoreFields.map(field => [field.key, 1]))
  }
}

export function requiredSubmissionFields(form) {
  const graded = form.productType === 'graded_card'
  const fields = ['certId', 'productType']
  if (graded) fields.push('cardCategory', ...scoreFields.map(field => field.key))
  if (form.productType === 'vintage_product') fields.push('vintageClassification')
  if (graded && form.cardCategory === 'movie_film') {
    fields.push('movieName', 'releaseYear', 'productionCompany', 'filmType')
  } else {
    fields.push('cardName', 'brandName', 'setName', 'cardNumber', 'languageCode')
    if (graded && form.cardCategory === 'sports_card') fields.push('sportsType')
    if (graded && form.cardCategory === 'celebrity_card') fields.push('groupName')
  }
  return fields
}

export function validScores(form) {
  return scoreFields.every(({ key }) => form[key] !== null && form[key] !== '' &&
    Number.isFinite(Number(form[key])) && Number(form[key]) >= 1 && Number(form[key]) <= 10)
}

export function scorePayload(form) {
  return Object.fromEntries(scoreFields.map(({ key }) => [key, Number(form[key])]))
}

export function submissionPayload(form) {
  const { finalGradeValue, finalGradeLabel, ...payload } = form
  const graded = form.productType === 'graded_card'
  payload.cardCategory = graded ? form.cardCategory : 'trading_card'
  payload.vintageClassification = form.productType === 'vintage_product' ? form.vintageClassification : null
  payload.merchDescription = form.productType === 'merch_product' ? form.merchDescription : null
  for (const { key } of scoreFields) payload[key] = graded ? form[key] : null
  if (payload.cardCategory !== 'sports_card') payload.sportsType = ''
  if (payload.cardCategory !== 'celebrity_card') payload.groupName = ''
  if (payload.cardCategory !== 'movie_film') {
    for (const key of ['movieName', 'releaseYear', 'productionCompany', 'filmType']) payload[key] = ''
  }
  return payload
}
