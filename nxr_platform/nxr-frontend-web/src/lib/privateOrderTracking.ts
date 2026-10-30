import type { PrivateOrderTracking } from './merchantBatches'
import type { privateTrackingMessages } from './privateOrderTrackingMessages'

export type PrivateReturnSummary = {
  hasParcels: boolean; total: number; delivered: number; inTransit: number;
  notReturned: number; complete: boolean;
}

/** Partner Orders retain their permanent intake reference after submission. */
export function privateOrderNumber(order: Pick<PrivateOrderTracking, 'orderNo' | 'clientReference'>) {
  const reference = order.clientReference?.trim() ?? ''
  return /^NXR-\d{6,19}$/.test(reference) ? reference : order.orderNo
}

/** A delivered parcel is not proof that all cards in the order have returned. */
export function privateReturnSummary(order: PrivateOrderTracking): PrivateReturnSummary {
  const parcels = order.returnParcels ?? []
  const rawTotal = order.totalCardCount ?? order.cardCount ?? 0
  const total = Number.isSafeInteger(rawTotal) && rawTotal > 0 ? rawTotal : 0
  const seen = new Set<string>()
  let delivered = 0, inTransit = 0, consistent = true
  for (const parcel of parcels) {
    for (const card of parcel.cards) {
      const identity = card.certId?.trim().toUpperCase()
      // New return cards always have a certificate. Missing or duplicate
      // identity cannot be used to assert complete delivery of the whole order.
      if (!identity || seen.has(identity)) { consistent = false; continue }
      seen.add(identity)
      if (parcel.statusCode === 'delivered' && card.statusCode === 'delivered') delivered++
      else if (parcel.statusCode === 'shipped' && card.statusCode === 'return_shipped') inTransit++
      else consistent = false
    }
  }
  return {
    hasParcels: parcels.length > 0, total, delivered, inTransit,
    notReturned: Math.max(0, total - delivered - inTransit),
    complete: consistent && total > 0 && seen.size === total && delivered === total,
  }
}

export function privateTrackingStatus(statusCode: string, summary: PrivateReturnSummary) {
  if (statusCode === 'cancelled' || statusCode === 'payment_exception') return statusCode
  if (summary.hasParcels && summary.complete) return 'agent_delivered'
  if (statusCode === 'delivered') return summary.hasParcels ? 'partial_return' : 'agent_delivered'
  if (statusCode === 'return_shipped') return 'agent_returning'
  return statusCode
}

/** Return parcel and card states share the same endpoint: the submitting agent. */
export function privateReturnStatus(statusCode: string) {
  if (statusCode === 'delivered') return 'agent_delivered'
  if (statusCode === 'shipped' || statusCode === 'return_shipped') return 'agent_returning'
  return statusCode
}

type TimelineEvent = Pick<PrivateOrderTracking['timeline'][number], 'eventCode' | 'title' | 'statusCode'>
type Translate = (key: string, parameters?: Record<string, string>) => string
type TimelineTextKey = keyof typeof privateTrackingMessages.en.timeline

// Match both the public event code and the exact server-generated title. A known
// event may carry a custom title; translating by status alone would erase it.
const systemTimelineTitles: Record<string, readonly [string, TimelineTextKey]> = {
  order_created: ['Order created', 'orderCreated'],
  intake_order_received: ['Intake received', 'intakeReceived'],
  payment_pending: ['Awaiting payment', 'awaitingPayment'],
  application_submitted: ['Application submitted', 'applicationSubmitted'],
  batch_inbound_shipped: ['Agent parcel shipped to NXR', 'agentParcelShipped'],
  batch_inbound_received: ['NXR received the agent parcel', 'agentParcelReceived'],
  batch_return_shipped: ['Agent return parcel shipped', 'agentReturnShipped'],
  batch_delivered: ['Agent return parcel delivered', 'agentReturnDelivered'],
  partner_return_shipped: ['Cards shipped to partner', 'cardsReturnShipped'],
  partner_return_delivered: ['Cards delivered to partner', 'cardsReturnDelivered'],
  inbound_delivery_confirmed: ['Delivery confirmed', 'deliveryConfirmed'],
  outbound_delivery_confirmed: ['Delivery confirmed', 'deliveryConfirmed'],
  grading_submission_linked: ['Grading work linked', 'gradingLinked'],
  late_payment_received: ['Payment received after admission window', 'latePaymentReceived'],
  packing_slip_ready: ['Packing slip ready', 'packingSlipReady'],
  intake_exception: ['Warehouse intake needs review', 'intakeReview'],
  order_exception: ['Order exception recorded', 'orderException'],
  exception_resolved: ['One order exception was resolved', 'exceptionResolved'],
  quality_passed: ['Final quality check passed', 'qualityPassed'],
  quality_failed: ['Quality check needs rework', 'qualityFailed'],
  warehouse_received: ['Cards received and counted', 'cardsCounted'],
  payment_exception: ['Payment needs review', 'paymentNeedsReview'],
  shipment_label_created: ['Shipping label created', 'shippingLabelCreated'],
  shipment_picked_up: ['Shipment picked up', 'shipmentPickedUp'],
  shipment_in_transit: ['Shipment in transit', 'shipmentInTransit'],
  shipment_customs: ['Shipment at customs', 'shipmentAtCustoms'],
  shipment_exception: ['Shipment exception', 'shipmentException'],
  shipment_out_for_delivery: ['Out for delivery', 'outForDelivery'],
  shipment_delivered: ['Shipment delivered', 'shipmentDelivered'],
  ticket_created: ['Support ticket created', 'ticketCreated'],
  ticket_open: ['Support ticket open', 'ticketOpen'],
  ticket_assigned: ['Support ticket assigned', 'ticketAssigned'],
  ticket_waiting_customer: ['Support ticket waiting customer', 'ticketWaitingCustomer'],
  ticket_resolved: ['Support ticket resolved', 'ticketResolved'],
  ticket_closed: ['Support ticket closed', 'ticketClosed'],
  shipping_change_requested: ['Return shipping change requested', 'shippingChangeRequested'],
  shipping_change_approved: ['Return shipping change approved', 'shippingChangeApproved'],
  shipping_change_rejected: ['Return shipping change rejected', 'shippingChangeRejected'],
  shipping_surcharge_paid: ['Shipping surcharge paid', 'shippingSurchargePaid'],
  shipping_refund_recorded: ['Shipping refund recorded', 'shippingRefundRecorded'],
}

const statusTimelineTitles: Record<string, readonly (readonly [string, TimelineTextKey])[]> = {
  admission_review: [['Application under review', 'applicationReview']],
  terms_confirmation: [['Confirm quote and terms', 'confirmQuote']],
  payment_expired: [['Payment deadline expired', 'paymentExpired'], ['Payment needs attention', 'paymentAttention']],
  awaiting_payment: [['Awaiting payment', 'awaitingPayment'], ['Payment needs attention', 'paymentAttention']],
  payment_review: [['Payment under review', 'paymentUnderReview'], ['Payment proof submitted', 'paymentProofSubmitted']],
  awaiting_inbound: [['Awaiting inbound shipment', 'awaitingInbound'], ['Payment confirmed', 'paymentConfirmed']],
  inbound_shipped: [['Cards shipped to NXR', 'cardsShippedToNxr']],
  received: [['Cards received', 'cardsReceived']],
  intake_exception: [['Warehouse intake needs review', 'intakeReview']],
  grading: [['Grading in progress', 'grading']],
  review: [['Final review', 'finalReview']],
  quality_check: [['Final quality check', 'qualityCheck']],
  quality_hold: [['Quality rework required', 'qualityRework']],
  completed: [['Grading completed', 'gradingCompleted']],
  return_shipped: [['Return shipment sent', 'returnShipmentSent']],
  delivered: [['Order delivered', 'orderDelivered'], ['Return shipment delivered', 'returnShipmentDelivered']],
  cancelled: [['Order cancelled', 'orderCancelled']],
}

const workTaskTitles: Record<string, string> = {
  preprocess: 'Card preprocessing', vision: 'Machine vision check', manual_review: 'Manual grading review',
  encapsulation: 'Encapsulation', quality_check: 'Final quality check',
}

/** Localize only known fixed system text. Unknown events and manual notes survive verbatim. */
export function privateTimelineTitle(event: TimelineEvent, translate: Translate) {
  const code = event.eventCode ?? ''
  const fixed = Object.hasOwn(systemTimelineTitles, code) ? systemTimelineTitles[code] : undefined
  if (fixed && event.title === fixed[0]) return translate(`tracking.timeline.${fixed[1]}`)
  if (code === 'status_changed' && event.statusCode && Object.hasOwn(statusTimelineTitles, event.statusCode)) {
    const match = statusTimelineTitles[event.statusCode]?.find(([title]) => title === event.title)
    if (match) return translate(`tracking.timeline.${match[1]}`)
  }
  for (const status of ['pending', 'in_progress', 'completed', 'failed']) {
    if (code !== `work_task_${status}`) continue
    for (const [type, title] of Object.entries(workTaskTitles)) {
      if (event.title === `${title} ${status.replaceAll('_', ' ')}`) {
        return translate('tracking.timeline.workTask', {
          task: translate(`tracking.workTasks.${type}`), status: translate(`tracking.workTaskStatuses.${status}`),
        })
      }
    }
  }
  return event.title
}

export function privateTrackingDate(value: string | Date | null | undefined, locale: string, options?: Intl.DateTimeFormatOptions) {
  if (!value) return '—'
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString(locale, options)
}
