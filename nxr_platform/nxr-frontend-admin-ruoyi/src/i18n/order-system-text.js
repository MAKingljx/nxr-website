// Only recognized system copy is localized. Free-form notes and frozen custom terms stay verbatim.
export const orderDefaultTextZhCN = {
  'Please confirm the submitted item list and current NXR service terms before shipping.': '寄件前，请确认提交的卡片清单及当前 NXR 服务条款。',
  'Please confirm the submitted item list, approved quote, and current NXR service terms before payment.': '付款前，请确认提交的卡片清单、已批准报价及当前 NXR 服务条款。',
  'Subject to admission confirmation': '以受理确认为准'
}

// Codes are from the order/payment services and the existing product/category dictionaries.
const orderCodeSources = {
  serviceLevel: { basic_grading: 'Base Grading Service' },
  paymentType: { grading_fee: 'Grading fee', shipping_adjustment: 'Shipping adjustment', shipping_refund: 'Shipping refund' },
  paymentProvider: { manual_transfer: 'Manual transfer', bank_transfer: 'Bank transfer', wechat_transfer: 'WeChat transfer', wechat_pay_native: 'WeChat Pay', alipay_transfer: 'Alipay transfer', alipay: 'Alipay', stripe: 'Stripe', paypal: 'PayPal', wallet: 'Company wallet' },
  paymentStatus: { pending: 'Pending', proof_submitted: 'Proof submitted', confirmed: 'Confirmed', rejected: 'Rejected', failed: 'Failed', refund_pending: 'Refund pending', refunded: 'Refunded', reversed: 'Reversed' },
  productType: { graded_card: 'Graded Card', merch_product: 'Merch Product', label_product: 'Merch Product', vintage_product: 'Vintage Card' },
  cardCategory: { trading_card: 'Trading Card', sports_card: 'Sports Card', celebrity_card: 'Celebrity Card', movie_film: 'Movie Film' },
  shipmentDirection: { inbound: 'Inbound', outbound: 'Outbound' },
  actorType: { customer: 'Customer', admin: 'Staff', system: 'System' }
}

export function orderCodeSource(domain, value) {
  if (!Object.hasOwn(orderCodeSources, domain)) return value
  return Object.hasOwn(orderCodeSources[domain], value) ? orderCodeSources[domain][value] : value
}

export const orderSystemZhCN = {
  'Received card count must match the order': '实收卡片数量必须与订单一致',
  'This intake contains cards that cannot be received': '此来件包含无法收取的卡片',
  'Confirm resolution of the intake exceptions before receipt': '请先确认收件异常已处理',
  'Exception resolution is required': '请填写异常处理说明',
  'This order has not been submitted to NXR': '该订单尚未提交给 NXR',
  'This order has no cards': '该订单没有卡片',
  'Receipt details are required': '请填写收件信息',
  'No returned cards have been delivered to this partner': '该订单暂无已送达的回寄卡片',
  'Review the complete partner submission together': '请对整个 Submit 进行审核',
  'Partner submission not found': '未找到该 Submit',
  'Partner submission has invalid order membership': 'Submit 的订单清单不正确',
  'Submission changed; refresh before recording a decision': 'Submit 已发生变化，请刷新后重新审核',
  'Agent workbench': '送评工作台',
  'Order submitted for admission review': '订单已提交受理审核',
  'Order approved': '订单已批准',
  'Order application rejected': '订单申请已拒绝',
  'Additional information requested': '已要求补充资料',
  'Additional information submitted': '补充资料已提交',
  'Quote and terms confirmed': '报价与条款已确认',
  'Payment deadline expired': '付款期限已过',
  'Payment received after admission window': '受理期限后收到款项',
  'Confirm quote and terms': '确认报价与条款',
  'Payment under review': '款项审核中',
  'Awaiting inbound shipment': '等待寄入卡片',
  'Cards received': '卡片已签收',
  'Warehouse intake needs review': '收件需要核对',
  'Final review': '最终复核',
  'Final quality check': '最终质检',
  'Quality rework required': '需要质检返工',
  'Return shipment sent': '回寄包裹已寄出',
  'Return shipment delivered': '回寄包裹已送达',
  'Order delivered': '订单已送达',
  'Order updated': '订单已更新',
  'Order cancelled': '订单已取消',
  'Payment proof submitted': '付款凭证已提交',
  'Payment confirmed': '款项已确认',
  'Payment needs attention': '款项需要处理',
  'Cards shipped to NXR': '卡片已寄往 NXR',
  'Inbound shipment received': '寄入包裹已签收',
  'Shipment recorded': '物流已登记',
  'Delivery confirmed': '签收已确认',
  'Grading work linked': '评级记录已关联',
  'Agent return parcel shipped': '代理回寄包裹已寄出',
  'Agent return parcel delivered': '代理回寄包裹已送达',
  'NXR received the agent parcel': 'NXR 已签收送评包裹',
  'Order exception recorded': '订单异常已登记',
  'One order exception was resolved': '一项订单异常已解决',
  'Final quality check passed': '最终质检已通过',
  'Quality check needs rework': '质检需要返工',
  'Shipping label created': '物流面单已创建',
  'Shipment picked up': '包裹已揽收',
  'Shipment at customs': '包裹清关中',
  'Shipment exception': '物流异常',
  'Shipment delivered': '包裹已签收',
  'Shipment updated': '物流已更新',
  'Support ticket created': '客服工单已创建',
  'Return shipping change requested': '已申请变更回寄服务',
  'Return shipping change approved': '回寄服务变更已批准',
  'Return shipping change rejected': '回寄服务变更已拒绝',
  'Shipping surcharge paid': '运费补款已支付',
  'Shipping refund recorded': '运费退款已登记',
  'Financial review recorded': '财务复核已登记',
  'Financial review completed': '财务复核已完成',
  'Order resumed after financial review': '财务复核后订单已恢复',
  'Order cancelled after financial review': '财务复核后订单已取消',
  'NXR will review the item list and quote before payment opens.': 'NXR 将先审核卡片清单和报价，再开放付款。',
  'NXR will review the item list and quoted amount before payment opens.': 'NXR 将先审核卡片清单和报价金额，再开放付款。',
  'The customer confirmed the approved item list, quoted amount and terms version.': '客户已确认获批卡片清单、报价金额和条款版本。',
  'No new payment can be started. Contact NXR if the order should be reviewed again.': '暂时无法发起新付款，如需重新审核订单，请联系 NXR。',
  'Your grading order is ready for payment.': '评级订单已可付款。',
  'Submit a transfer reference after payment so our team can confirm it.': '付款后请提交转账凭证，以便工作人员核对。',
  'Our staff will verify the payment before accepting shipment.': '工作人员将先核对款项，再受理寄件。',
  'Your prepaid wallet payment was confirmed. Please send your cards to NXR.': '企业余额付款已确认，请将卡片寄往 NXR。',
  'Payment has been confirmed. Please send your cards to NXR and add inbound tracking.': '款项已确认，请将卡片寄往 NXR 并登记寄入运单。',
  'The verified funds were recorded, but fulfillment is paused for financial review.': '已核实款项已登记，订单处理暂停，等待财务复核。',
  'Print the packing slip and place it inside the shipment.': '请打印装箱单并放入包裹。',
  'Warehouse intake is complete.': '收件清点已完成。',
  'The receiving team recorded an intake difference.': '收件人员已登记来件差异。',
  'Our team is reviewing the order.': '工作人员正在核对订单。',
  'A grading work record has been linked to an order item.': '卡片已关联评级记录。',
  "Your order is included in the agent's inbound master parcel.": '本订单已加入代理寄往 NXR 的主包裹。',
  "NXR has received the agent's master parcel.": 'NXR 已签收代理送评主包裹。',
  'Your graded card is included in the return master parcel to the agent.': '评级后的卡片已加入回寄代理的主包裹。',
  "The agent's return master parcel has been delivered.": '回寄代理的主包裹已送达。',
  'Carrier delivery was confirmed.': '已确认承运商送达。',
  'Place each card in a sleeve and semi-rigid holder.': '请将每张卡片装入卡套和半硬质保护套。',
  'Protect cards with bubble wrap and use a rigid shipping box.': '请使用气泡膜保护卡片，并装入硬质快递箱。',
  'Put this packing slip inside the package and use tracked shipping.': '请将装箱单放入包裹，并使用可追踪物流。',
  'Grading order not found': '未找到评级订单',
  'Agent record not found': '未找到代理业务记录',
  'Return parcel or card not found': '未找到回寄包裹或卡片',
  'Order admission configuration is not installed': '订单受理配置尚未就绪',
  'Unsupported card field': '卡片包含不支持的字段',
  'Unsupported order field': '订单包含不支持的字段',
  'Order details are required': '请填写订单资料',
  'Card details are required': '请填写卡片资料',
  'Enter a customer phone number or email address': '请填写客户联系电话或邮箱',
  'Enter a valid email address': '请填写有效的邮箱地址',
  'Every card requires front and back photos': '每张卡片均需正反面照片',
  'Use a different uploaded photo for every card side': '每张卡片的每一面须使用单独上传的照片',
  'This request key was already used for different order details': '此次请求已用于其他订单资料，请刷新后重试',
  'This request key was already used for different details': '此次请求已用于其他资料，请刷新后重试',
  'Order admission was already initialized or the order changed': '订单已提交受理或发生变化，请刷新后查看',
  'Only an order awaiting more information can be resubmitted': '只有待补充资料的订单才能重新提交',
  'At most 40 supplemental photos can be submitted at once': '每次最多提交 40 张补充照片',
  'A note or supplemental photo is required': '请填写说明或上传补充照片',
  'Admission changed; refresh the order before resubmitting': '审核状态已变化，请刷新订单后重新提交',
  'This order has not been approved for payment': '此订单尚未获批付款',
  'The order quote changed after approval; NXR must review it again': '获批后的订单报价已变化，须由 NXR 重新审核',
  'The payment deadline has expired; NXR must review the order again': '付款期限已过，须由 NXR 重新审核订单',
  'The accepted terms or quote do not match the approved order snapshot': '确认的条款或报价与获批订单不一致，请刷新后核对',
  'Terms cannot be confirmed after payment processing has started or the order state has changed': '付款处理已开始或订单状态已变化，暂时无法确认条款',
  'Admission changed; refresh the order before confirming terms': '审核状态已变化，请刷新订单后确认条款',
  'Decision must be approve, reject or request_information': '请选择批准、拒绝或要求补充资料',
  'Admission changed; refresh the order before recording a decision': '审核状态已变化，请刷新订单后重新审核',
  'This admission decision cannot be changed in its current state': '当前状态暂时无法更改审核结论',
  'Admission cannot be changed after payment processing has started': '付款处理开始后无法更改受理审核',
  'Admission configuration is required': '请填写受理配置',
  'Payment deadline must be between 1 and 720 hours': '付款期限须为 1 至 720 小时',
  'Order card limit must be between 1 and 10000': '每单卡片上限须为 1 至 10000 张',
  'Accepted currency is invalid': '确认的币种无效',
  'Admission approval, quote acceptance and an active payment deadline are required before payment': '须先通过审核、确认报价并处于有效付款期限内，才能付款',
  'Admission and payment deadline state can only be changed through the admission workflow': '请通过订单受理流程更改审核状态和付款期限',
  'Complete every card\'s brand, set, category, official number, language and supported product type before submission': '送评前，请补齐每张卡片的品牌、卡集、类别、卡面编号、语言及支持的产品类型',
  'Every card requires its own front and back photos before submission': '送评前，每张卡片均须提供单独的正反面照片',
  'Every selected intake must be fully received, counted and free of exceptions': '所选来件须全部签收、清点完成且无未解决异常',
  'The batch quote has changed. Refresh the quote before submitting': '送评报价已变化，请刷新报价后提交',
  'Confirm final quality and packing checks': '请确认最终质检和包装核对均已完成',
  'Return parcel details are required': '请填写回寄包裹资料',
  'This request was already used for a different parcel': '此次请求已用于其他包裹，请刷新后重试',
  'Combine only cards for the same partner and return address': '只能合并同一代理、同一回寄地址的卡片',
  'A return parcel was created concurrently; refresh before trying again': '已有其他操作创建回寄包裹，请刷新后重试',
  'This parcel has not been shipped': '此包裹尚未寄出',
  'Too many orders requested': '所选订单过多',
  'This order uses card-based return parcels; manage delivery from its return parcels': '此订单按卡片分包回寄，请在回寄包裹中确认签收',
  'Select between 1 and 500 different cards': '请选择 1 至 500 张不同卡片',
  'Text is too long': '填写的内容过长'
}

Object.assign(orderSystemZhCN, {
  'Please agree to the service terms before submitting': '请同意服务条款后再提交',
  'Service terms changed; refresh and confirm the current terms before submitting': '服务条款已更新，请重新加载并确认后提交',
  'The saved service terms differ; refresh the order before confirming': '已保存的服务条款不一致，请刷新订单后确认',
  'Service terms can only be recorded with a new partner submission': '服务条款只能随新建B端送评一并确认',
  'A partner submission must contain at least one card': 'B端送评至少须包含一张卡片',
  'NXR will review the submitted item list.': 'NXR 将审核提交的卡片清单。',
  'The partner agreed to the service terms and submitted item list before admission review.': 'B端已在提交送审时确认服务条款和卡片清单。',
  'Your partner order was approved. It is ready to ship to NXR.': 'B端订单已通过审核，可以寄往 NXR。',
  'Submit must be approved before shipping.': '送评审核通过后即可寄件。',
  'Receive the parcel before checking in cards': '请先签收包裹，再核对卡片入库',
  'Submitted intake cards cannot be changed': '已送评的来件卡片无法更改',
  'Submitted intake evidence cannot be replaced': '已送评的来件照片无法替换',
  'Inventory code or certificate number does not match this card': '卡片编码或证书号与此卡不一致',
  'Select the agent return address': '请选择代理回寄地址',
  'This intake cannot be received': '此来件当前无法签收',
  'Photo side must be front or back': '请选择正面或背面照片',
  'Every card in a return parcel must belong to the same client': '同一回寄包裹中的卡片须属于同一客户',
  'Only checked returned cards that have not been shipped can be selected': '只能选择已核对且尚未寄出的返程卡片',
  'This card has already been shipped': '此卡片已寄出',
  'This card is not awaiting return from NXR': '此卡片当前不处于等待 NXR 回寄的状态',
  'The NXR return parcel has not arrived at the agent': 'NXR 回寄包裹尚未送达代理',
  'The NXR return parcel must be delivered to the agent before cards can be released': 'NXR 回寄包裹须先送达代理，才能放行卡片'
})

Object.assign(orderSystemZhCN, {
  'Order not found': '未找到订单',
  'This client reference already exists': '此客户编号已存在',
  'Select a client': '请选择客户',
  'This client is archived': '此客户已归档',
  'Register exactly the expected number of cards (1 to 1000)': '登记的卡片数量须与应收数量一致，范围为 1 至 1000 张',
  'Scan an inventory code, certificate number or NXR certificate QR code': '请扫描卡片编码、证书号或 NXR 证书二维码',
  'A merchant batch must contain between 1 and 200 customer orders': '每个代理批次须包含 1 至 200 个客户订单',
  'Client references must be unique within a merchant batch': '同一代理批次内的客户编号不能重复',
  'Every batch row requires an order': '批次中的每一行均须包含订单',
  'Unsupported batch status': '不支持此批次状态',
  'Private tracking link is invalid or revoked': '私密查询链接无效或已撤销',
  'This batch has no accepted orders': '此批次没有已接受的订单',
  'Every customer order must be paid and ready before the master parcel ships': '所有客户订单均须完成付款并具备寄件条件，才能寄出主包裹',
  'This batch already has an active inbound master parcel': '此批次已有有效的寄入主包裹',
  'Every customer order must be completed before batch return shipping': '所有客户订单均须完成，才能整批回寄',
  'This batch already has an active outbound master parcel': '此批次已有有效的回寄主包裹',
  'Batch shipment not found': '未找到批次物流',
  'This batch is not in return shipping': '此批次尚未处于回寄状态',
  'Unable to create private tracking link': '无法创建私密查询链接',
  'Merchant batch order not found': '未找到代理批次订单',
  'Merchant batch not found': '未找到代理批次',
  'Batch shipment details are required': '请填写批次物流资料',
  'Every batch order requires at least one card': '批次中的每个订单至少须包含一张卡片',
  'Batch order card quantities must be positive': '批次订单的卡片数量须大于零',
  'Batch order card count is too large': '批次订单的卡片数量过多',
  'This order cannot be opened in the workbench': '此订单当前无法在工作台中处理',
  'This order is locked by another workbench user': '此订单已由其他工作人员锁定',
  'Scan details are required': '请填写扫描资料',
  'Physical card barcode not found': '未找到实物卡片条码',
  'This physical card belongs to another order': '此实物卡片属于其他订单',
  'Packing check details are required': '请填写包装核对资料',
  'This order has no physical cards': '此订单没有实物卡片',
  'Invalid card selection': '所选卡片无效',
  'Selected card not found in this order': '未在此订单中找到所选卡片',
  'This order has no cards to export': '此订单没有可导出的卡片',
  'Every card must have one approved grading entry before label export': '每张卡片均须有一条已批准评级记录，才能导出标签',
  'A grading entry is linked across multiple order cards': '一条评级记录关联了多张订单卡片，请先核对',
  'Scan every physical card into this order before confirming intake': '请先扫描此订单的每张实物卡片，再确认收件',
  'Order-scoped packing verification must pass before return shipping': '此订单须通过包装核对后才能回寄',
  'Card identity is ambiguous': '卡片身份存在冲突，请核对卡片编码',
  'Card identity is already assigned': '卡片身份已分配，请刷新后核对',
  'Workbench session not found': '未找到此订单的工作台记录',
  'Workbench session is closed': '此订单的工作台处理已结束',
  'A reprint reason is required': '请填写重新打印的原因',
  'This card has no approved grading result for its label': '此卡片尚无可用于标签的已批准评级结果',
  'This card has no active label scan': '此卡片尚未扫描当前标签',
  'This order has no labeled cards': '此订单没有已贴标卡片',
  'Every packed card must keep an approved grading result': '每张装箱卡片均须保留已批准的评级结果',
  'Every packed card needs an active label scan': '每张装箱卡片均须扫描当前标签',
  'Export the current labels before shipping': '寄出前请先导出当前标签',
  'Export the current card label before scanning': '扫描前请先导出当前卡片标签',
  'Allocate and scan this physical card before packing': '装箱前请先分配并扫描此实物卡片',
  'Card grading is not approved': '卡片评级尚未批准',
  'The card label changed; export and scan the current label again': '卡片标签已变化，请重新导出并扫描当前标签',
  'Unsupported scan stage': '不支持此扫描阶段'
})

Object.assign(orderSystemZhCN, {
  'Every order in a master parcel must use the same return address, currency and return shipping option': '同一主包裹中的订单须使用相同的回寄地址、币种和回寄服务',
  'The grading result changed after label scanning; re-export and rescan this label': '标签扫描后评级结果已变化，请重新导出并扫描此标签',
  'A grading result changed after label scanning; re-export and rescan the affected label': '标签扫描后有评级结果变化，请重新导出并扫描受影响的标签',
  'This label barcode is not the current exported version; export and scan the current label': '此标签条码并非当前导出的版本，请导出并扫描当前标签',
  'The grading result changed after label export; export the current labels before scanning or shipping': '标签导出后评级结果已变化，请先导出当前标签，再扫描或寄出',
  'Scan the exported label barcode for label and packing stages': '贴标和装箱时，请扫描已导出的标签条码',
  'Return address not found': '未找到回寄地址',
  'Return shipping option not found': '未找到回寄服务',
  'Return shipping option is not available for this address': '此地址暂不支持所选回寄服务',
  'Grading price is not configured': '评级价格尚未配置',
  'Packing slip is available after payment confirmation': '款项确认后才能查看装箱单',
  'Intake order not found': '未找到收件订单',
  'Order reference is ambiguous; use the original intake code': '订单编号对应多个记录，请使用原始收卡编号',
  'This order is not ready for warehouse intake': '此订单尚未具备仓库收件条件',
  'Intake code does not match this order': '收卡编号与此订单不一致',
  'Received card count is invalid': '签收卡片数量无效',
  'Order exception is not open': '此订单异常已不处于待处理状态',
  'Completed work tasks cannot be reopened': '已完成的任务无法重新开启',
  'Task result is too long': '任务结果内容过长',
  'A shipped card cannot start a new work task': '已寄出的卡片无法新建处理任务',
  'Order item not found': '未找到订单卡片',
  'Confirm receipt and reconcile the card quantity before starting grading work': '开始评级前，请先确认收件并核对卡片数量',
  'Resolve the receipt quantity exception before starting grading work': '开始评级前，请先解决收件数量异常',
  'A matching receipt count is required before starting grading work': '开始评级前，收件数量须核对一致',
  'Quality check is available after grading review': '评级复核完成后才能质检',
  'Every card must be linked to a grading submission before final quality check': '最终质检前，每张卡片均须关联评级记录',
  'All grading and encapsulation tasks must be completed before final quality check': '最终质检前，所有评级和封装任务均须完成',
  'Unsupported tracking event': '不支持此物流事件',
  'Closed tickets cannot receive new messages': '已关闭的工单无法添加新消息',
  'Support ticket not found': '未找到客服工单',
  'Shipping option currency must match the order': '物流服务币种须与订单一致',
  'Choose a different return shipping option': '请选择其他回寄服务',
  'Shipping change request has already been reviewed': '此物流变更申请已审核',
  'Shipping change request was reviewed concurrently': '此物流变更申请已由其他操作审核，请刷新后查看',
  'Shipping change is not awaiting settlement': '此物流变更当前不处于待结算状态',
  'Shipping adjustment was settled concurrently': '此运费调整已由其他操作结算，请刷新后查看',
  'Shipping change request was settled concurrently': '此物流变更申请已由其他操作结算，请刷新后查看',
  'Return shipment can be created after final quality check': '最终质检通过后才能创建回寄物流',
  'Settle the return shipping change before creating the return label': '创建回寄面单前，请先完成回寄运费变更结算',
  'Customer account not found': '未找到客户账号',
  'Merchant batch ordering is not enabled for this account': '此账号尚未开通代理批次送评',
  'Work task not found': '未找到处理任务',
  'Shipping change request not found': '未找到物流变更申请',
  'Return shipping can no longer be changed after the label is created': '回寄面单创建后无法再更改回寄服务',
  'Return shipping can only be changed after the grading payment is confirmed and before return shipping': '只能在评级款项确认后、回寄前更改回寄服务',
  'A consolidated merchant batch must change return shipping through batch support': '合并送评的代理批次须通过批次客服流程更改回寄服务',
  'Another return shipping change is still open': '另有一项回寄服务变更尚未处理完成',
  'Order work is paused while payment needs attention': '款项需要处理，订单作业已暂停',
  'Shipment record not found': '未找到物流记录',
  'Return address is required': '请填写回寄地址',
  'Customer address is required': '请填写客户地址',
  'Unsupported intake exception type': '不支持此收件异常类型',
  'Unsupported work task type': '不支持此处理任务类型',
  'Unsupported work task status': '不支持此处理任务状态',
  'Unsupported support ticket category': '不支持此客服工单类别',
  'Unsupported support ticket status': '不支持此客服工单状态',
  'Currency must be a three-letter code': '币种须使用三个字母的代码',
  'Upload a JPEG or PNG image up to 15 MB': '请上传不超过 15 MB 的 JPEG 或 PNG 图片',
  'Too many unsubmitted images. Submit an application or remove unused images first.': '未提交图片过多，请先提交申请或移除未使用图片。',
  'Image exceeds 15 MB': '图片超过 15 MB',
  'The image could not be saved. Please try again.': '图片保存失败，请重试。',
  'This image is already attached to an application. Upload another copy for a different order.': '此图片已用于申请，其他订单请单独上传一份。',
  'Agent evidence can only be attached through its inventory card': '代理照片只能通过对应库存卡片关联',
  'This image belongs to another application': '此图片属于其他申请',
  'This image is already in use': '此图片已在使用中',
  'Submitted images must be preserved': '已提交的图片须保留',
  'Only valid JPEG and PNG images are supported': '只支持有效的 JPEG 和 PNG 图片',
  'Only JPEG and PNG images are supported': '只支持 JPEG 和 PNG 图片',
  'Image must contain fewer than 40 million pixels': '图片须小于 4000 万像素',
  'The image is damaged': '图片已损坏',
  'The image is damaged or unsupported': '图片已损坏或格式不受支持',
  'Image not found': '未找到图片',
  'Submission not found': '未找到评级记录',
  'Submission ids are required': '请选择评级记录',
  'Submission id is invalid': '评级记录编号无效',
  'Submission is required': '请提供评级记录',
  'An active owned-inventory business line is required': '须先配置有效的自有库存业务线',
  'Submission is missing or already belongs to a customer order': '评级记录不存在或已属于客户订单',
  'Staff user is required': '请选择工作人员',
  'Active staff user is required': '请选择已启用的工作人员',
  'Submission details are required': '请填写评级记录资料',
  'Order item already has a grading record': '此订单卡片已有关联的评级记录',
  'Order routing must be configured before grading entry': '评级录入前须先配置订单分工',
  'Start and lock this order in the workbench before grading entry': '评级录入前，请先在工作台开启并锁定此订单',
  'Scan this physical card into the order before grading entry': '评级录入前，请先将此实物卡片扫描至订单',
  'Scan a card receipt code': '请扫描卡片收卡编号',
  'NXR order access is required': '需要 NXR 订单访问权限',
  'Card or order not found': '未找到卡片或订单',
  'The master parcel has already shipped; review card custody before cancelling this order': '主包裹已寄出，取消订单前请先核对卡片保管情况',
  'The intake has changed; review its card custody before cancelling this order': '来件记录已变化，取消订单前请先核对卡片保管情况'
})

const templates = [
  ['Shipment #{id} was recorded.', '已登记物流 #{id}。'],
  ['NXR received the inbound shipment from {carrier}.', 'NXR 已签收 {carrier} 的寄入包裹。'],
  ['{carrier} tracking: {tracking}', '{carrier} 运单号：{tracking}'],
  ['An order must contain between 1 and {count} cards', '每单须包含 1 至 {count} 张卡片'],
  ['Each intake may contain at most {count} cards under the current NXR policy', '按当前 NXR 规则，每份来件最多包含 {count} 张卡片'],
  ['Text exceeds {count} characters', '内容超过 {count} 个字符'],
  ["Order belongs to merchant batch {batch}; use the batch's shared shipment workflow", '此订单属于代理批次 {batch}，请通过该批次的主包裹流程处理物流']
]
for (const [stage, zhStage] of Object.entries({ intake: '收件', label: '贴标', packing: '装箱' })) templates.push([`Packing check failed: {count} card(s) are missing the ${stage} scan`, `包装核对未通过：{count} 张卡片缺少${zhStage}扫描`])
for (const [kind, zhKind] of Object.entries({ intakes: '份不重复的来件', cards: '张不重复的卡片' })) templates.push([`Select between 1 and {count} unique ${kind}`, `请选择 1 至 {count} ${zhKind}`])
const taskTypes = { 'Card preprocessing': '卡片预处理', 'Machine vision check': '机器视觉核对', 'Manual grading review': '人工评级复核', Encapsulation: '封装', 'Final quality check': '最终质检', 'Work task': '任务' }
const taskStates = { pending: '待处理', queued: '已排队', 'in progress': '进行中', completed: '已完成', failed: '失败', cancelled: '已取消', skipped: '已跳过' }
for (const [task, zhTask] of Object.entries(taskTypes)) {
  for (const [state, zhState] of Object.entries(taskStates)) orderSystemZhCN[`${task} ${state}`] = `${zhTask}${zhState}`
}
for (const [state, zhState] of Object.entries({ open: '已开启', 'in progress': '处理中', resolved: '已解决', closed: '已关闭' })) orderSystemZhCN[`Support ticket ${state}`] = `客服工单${zhState}`
Object.assign(orderSystemZhCN, {
  'This order has not been approved for service': '此订单尚未通过服务审核',
  'The accepted terms do not match the approved order snapshot': '所确认的服务条款与获批订单不一致',
  "Every partner order must complete review and service terms before the master parcel ships": "主包裹寄出前，每个B端订单都须完成审核并确认服务条款",
  "Every partner order must have admission approval and accepted service terms before shipping": "寄件前，所有B端订单都须通过受理审核并确认服务条款",
  "Service terms cannot be confirmed in this order state": "当前订单状态无法确认服务条款",
  "A partner batch order is required": "此操作需要B端送评批次订单",
  "Order changed; refresh before continuing": "订单已变化，请刷新后继续",
  "Partner batch orders do not require payment": "B端送评批次无需在线付款",
  "Admission approval and service terms are required before fulfillment": "开始处理前须完成受理审核并确认服务条款",
  "Service terms confirmed": "服务条款已确认",
  "The partner confirmed the approved service terms. The order is ready for inbound shipping.": "B端已确认获批的服务条款，订单可以寄往 NXR。",
  "Ready to ship to NXR": "可寄往 NXR",
  "Service terms are confirmed. Add the inbound parcel tracking number to continue.": "服务条款已确认，请登记寄往 NXR 的包裹运单号。",
  "Confirm service terms": "确认服务条款",
  "Review and confirm the approved service terms before sending cards to NXR.": "寄往 NXR 前，请阅读并确认获批的服务条款。",
  "Your partner order was approved. Confirm the service terms before shipping.": "B端订单已通过审核，寄件前请确认服务条款。",
  "Legacy payment recorded": "已记录历史付款凭证",
  "Review the return shipping change before shipping": "寄出前请先审核回寄服务变更",
  "NXR will review the item list before service terms are confirmed.": "NXR 将先审核卡片清单，再确认服务条款。",
  "The partner order is ready for inbound shipping.": "B端订单已可寄往 NXR。"
})
const fields = {
  'Address contact name': '地址收件人', 'Address phone': '地址联系电话', 'Customer street address': '客户详细地址', 'Customer address line 2': '客户地址第二行', 'Customer city': '客户城市', 'Customer region': '客户省份', 'Customer postal code': '客户邮政编码', 'Customer country': '客户国家／地区', 'Customer name': '客户姓名', Phone: '联系电话', Email: '邮箱', Notes: '备注', Search: '搜索内容', Status: '状态', Barcode: '条码', 'Card name': '卡片名称', Brand: '品牌', 'Card set': '卡集', 'Official card number': '卡面编号', Language: '语言', Category: '类别', 'Product type': '产品类型', 'Terms version': '条款版本', 'Terms text': '条款正文', 'Turnaround text': '周转时间说明', 'Review note': '审核备注', 'Accepted quoted amount': '确认的报价金额', Carrier: '承运商', 'Tracking number': '运单号', 'Certificate ID': '证书号', 'Request key': '请求标识' }
Object.assign(fields, {
  'Client reference': '客户编号', 'Client name': '客户名称', 'Tracking token': '查询链接标识', 'Batch number': '批次编号', 'Order number': '订单编号', 'Return country': '回寄国家或地区', 'Inventory code': '卡片编码', 'Exception details': '异常说明', 'Return shipping option': '回寄服务', Currency: '币种', 'Inventory code or certificate number': '卡片编码或证书号', Recipient: '收件人', 'Recipient phone': '收件人联系电话', 'Street address': '街道地址', City: '城市', 'Postal code': '邮政编码', Country: '国家或地区', 'Service name': '服务名称', 'Service unit price': '服务单价', 'Option code': '物流选项编码', 'Option name': '物流选项名称', 'Shipping price': '运费', 'Intake code or Order number': '收卡编号或订单编号', 'Intake code': '收卡编号', 'Resolution note': '解决说明', 'Failure reason': '失败原因', 'Quality issue': '质检问题', 'Ticket subject': '工单主题', 'Ticket message': '工单内容', 'Change reason': '变更原因', 'Settlement note': '结算说明', 'Exception title': '异常标题', 'Contact name': '联系人姓名', 'Contact phone': '联系人电话', 'Address line 1': '地址第一行'
})
fields['Decision note'] = '审核意见'
for (const [field, zhField] of Object.entries(fields)) {
  orderSystemZhCN[`${field} is required`] = `请填写${zhField}`
  orderSystemZhCN[`${field} is too long`] = `${zhField}过长`
}
for (const field of ['Brand', 'Language', 'Product type', 'Category']) orderSystemZhCN[`${field} must be an active option`] = `请选择有效的${fields[field]}`
for (const field of ['Return shipping option', 'Option code']) orderSystemZhCN[`${field} contains unsupported characters`] = `${fields[field]}包含不支持的字符`
for (const field of ['Service unit price', 'Shipping price']) orderSystemZhCN[`${field} must be between 0 and 1000000`] = `${fields[field]}须在 0 至 1000000 之间`
for (const [field, label] of Object.entries({ 'Customer details': '客户资料', 'Intake details': '来件资料', 'Card details': '卡片资料', 'Card check-in': '卡片入库核对资料', 'Submission details': '评级记录资料', 'Return check': '返程核对资料', 'Shipment details': '物流资料' })) orderSystemZhCN[`${field} are required`] = `请填写${label}`
for (const [field, label] of Object.entries({ 'Business-line assignments': '业务线分配', 'Work-center assignments': '工作中心分配' })) {
  orderSystemZhCN[`${field} exceed the supported limit`] = `${label}数量超过上限`
  orderSystemZhCN[`${field} contain an invalid id`] = `${label}包含无效编号`
}
for (const [operation, label] of Object.entries({ 'Agent operator management': '代理操作员管理', 'Partner management': '代理管理' })) orderSystemZhCN[`${operation} requires unrestricted order access`] = `${label}需要不受限的订单访问权限`
for (const [stage, zhStage] of Object.entries({ intake: '收件', label: '贴标', packing: '装箱' })) {
  orderSystemZhCN[`This card was already scanned for ${stage}`] = `此卡片已完成${zhStage}扫描`
  orderSystemZhCN[`Scan this card for ${stage} before continuing`] = `继续前请先完成此卡片的${zhStage}扫描`
}
orderSystemZhCN['Accepted quoted amount must be positive'] = '确认的报价金额须大于零'
orderSystemZhCN['Accepted quoted amount has unsupported precision'] = '确认的报价金额精度无效'

function compile(template) {
  const keys = []
  const parts = template.split(/(\{\w+\})/g).map(part => {
    if (/^\{\w+\}$/.test(part)) {
      const key = part.slice(1, -1)
      keys.push(key)
      return ['id', 'count'].includes(key) ? '(\\d+)' : '(.+?)'
    }
    return part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  })
  return { regex: new RegExp(`^${parts.join('')}$`), keys }
}
const compiledTemplates = templates.map(([en, zh]) => ({ en, zh, fromEn: compile(en), fromZh: compile(zh) }))
const englishByChinese = new Map(Object.entries(orderSystemZhCN).map(([en, zh]) => [zh, en]))
const defaultEnglishByChinese = new Map(Object.entries(orderDefaultTextZhCN).map(([en, zh]) => [zh, en]))

export function localizeKnownOrderText(value, locale = 'en') {
  if (value == null) return value
  const source = String(value)
  const exact = locale === 'zh-CN' ? orderSystemZhCN[source] : englishByChinese.get(source)
  if (exact) return exact
  for (const template of compiledTemplates) {
    const { regex, keys } = locale === 'zh-CN' ? template.fromEn : template.fromZh
    const match = source.match(regex)
    if (!match) continue
    const values = Object.fromEntries(keys.map((key, index) => [key, match[index + 1]]))
    // Replace template tokens once so values containing braces or dollar signs stay literal.
    return (locale === 'zh-CN' ? template.zh : template.en).replace(/\{(\w+)\}/g, (_, key) => values[key])
  }
  return value
}

export function localizeDefaultOrderText(value, locale = 'en') {
  if (value == null) return value
  return (locale === 'zh-CN' ? orderDefaultTextZhCN[value] : defaultEnglishByChinese.get(value)) || value
}

// These event details are supplied by a customer/operator, even when they resemble system copy.
const freeTextEvents = new Set(['approved', 'rejected', 'information_requested', 'resubmitted', 'exception_resolved', 'quality_passed', 'quality_failed', 'shipping_change_approved', 'shipping_change_rejected', 'shipping_surcharge_paid', 'shipping_refund_recorded', 'ticket_created', 'finance_review', 'finance_review_completed'])
const freeTextTitles = new Set(['Order approved', 'Order application rejected', 'Additional information requested', 'Additional information submitted', 'One order exception was resolved', 'Final quality check passed', 'Quality check needs rework', 'Return shipping change approved', 'Return shipping change rejected', 'Shipping surcharge paid', 'Shipping refund recorded', 'Support ticket created', 'Financial review recorded', 'Financial review completed'])
const systemDetailCodes = new Set(['submitted', 'application_submitted', 'terms_accepted', 'payment_expired', 'order_created', 'payment_pending', 'payment_proof_submitted', 'packing_slip_ready', 'intake_exception', 'warehouse_received', 'grading_submission_linked', 'batch_inbound_shipped', 'batch_inbound_received', 'batch_return_shipped', 'batch_delivered', 'late_payment_received', 'shipment_created'])
const systemDetailTitles = new Set(['Application submitted', 'Order submitted for admission review', 'Quote and terms confirmed', 'Payment deadline expired', 'Order created', 'Awaiting payment', 'Payment proof submitted', 'Payment confirmed', 'Packing slip ready', 'Warehouse intake needs review', 'Cards received and counted', 'Grading work linked', 'Grading in progress', 'Agent parcel shipped to NXR', 'NXR received the agent parcel', 'Agent return parcel shipped', 'Agent return parcel delivered', 'Payment received after admission window', 'Cards shipped to NXR', 'Cards received', 'Return shipment sent', 'Return shipment delivered', 'Delivery confirmed', 'Inbound shipment received', 'Shipment recorded', 'Order exception recorded'])

export function localizeOrderEventDetail(event, locale = 'en') {
  if (!event?.detail) return event?.detail
  const code = event.eventCode || ''
  const title = englishByChinese.get(event.title) || event.title
  if (freeTextEvents.has(code) || /^(work_task_|ticket_)/.test(code) || freeTextTitles.has(title)) return event.detail
  if (code && !systemDetailCodes.has(code) && !['status_changed', 'status_note', 'order_exception', 'delivery_confirmed'].includes(code)) return event.detail
  if (!systemDetailCodes.has(code) && !systemDetailTitles.has(title)) return event.detail
  if (code.startsWith('shipment_') && code !== 'shipment_created') return event.detail
  return localizeKnownOrderText(event.detail, locale)
}
