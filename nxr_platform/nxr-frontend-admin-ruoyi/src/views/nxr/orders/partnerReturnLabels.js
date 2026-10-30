export function partnerReturnReadinessLabel(row) {
  if (row.itemStatus === 'delivered') return 'Delivered to B'
  if (row.itemStatus === 'return_shipped') return 'Returning to B'
  return row.eligible ? 'Ready to return' : 'Not available for return'
}
