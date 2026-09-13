/** Stateless print view shared by the two independently built NXR frontends. */
export type CardIdentity = {
  orderId: number; orderItemId: number; itemNo: number; cardName: string;
  receiptCode: string | null; physicalBarcode: string | null;
  sourceType: 'direct' | 'partner'; ownerDisplayName: string; clientReference: string | null;
  partnerCompanyName: string | null; orderNo: string; batchNo: string | null;
  returnRoute: 'direct_to_customer' | 'via_partner'; certId: string | null;
}
export type CardIdentityOrder = {
  orderId: number; orderNo: string; sourceType: 'direct' | 'partner';
  ownerDisplayName: string; partnerCompanyName: string | null; batchNo: string | null;
  returnRoute: string; items: CardIdentity[];
}
type Encoder = (value: string) => Promise<string>
export function createCardLabelPrinter() {
  let preview: Window | null = null, generation = 0
  function close() { generation++; if (preview && !preview.closed) preview.close(); preview = null }
  async function open(load: () => Promise<CardIdentityOrder>, encode: Encoder, locale: 'zh-CN' | 'en' = 'zh-CN') {
    const zh = locale === 'zh-CN'
    const title = zh ? 'NXR 收卡标签' : 'NXR Intake Labels'
    if (!preview || preview.closed) preview = window.open('', '_blank', 'popup,width=980,height=850')
    if (!preview) throw new Error(zh ? '请允许弹出标签预览窗口后重试。' : 'Allow the label preview popup and try again.')
    const target = preview, current = ++generation, doc = target.document
    target.opener = null
    const node = <K extends keyof HTMLElementTagNameMap>(tag: K, text = '', className = '') => {
      const item = doc.createElement(tag); item.textContent = text; item.className = className; return item
    }
    const style = node('style')
    doc.documentElement.lang = locale
    style.textContent = '*{box-sizing:border-box}body{margin:0;background:#eee;color:#111;font:14px system-ui,sans-serif}.toolbar{max-width:210mm;margin:auto;padding:20px;display:flex;flex-wrap:wrap;gap:12px;align-items:center}.toolbar h1{font-size:20px;margin:0 auto 0 0}.toolbar p{width:100%;margin:0;line-height:1.6}button{padding:10px 16px;background:white;border:1px solid #444;border-radius:4px;cursor:pointer}.labels{max-width:210mm;margin:0 auto;padding:8mm;background:white;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:4mm}.label{display:grid;grid-template-columns:30mm minmax(0,1fr);gap:3mm;padding:3mm;border:1px dashed #777;min-height:48mm;break-inside:avoid}.label img{width:30mm;height:30mm}.label p{font-size:10px;margin:3px 0;line-height:1.5;overflow-wrap:anywhere}.label strong{font-size:12px}.label code{display:block;font-size:9px;margin-top:5px;overflow-wrap:anywhere}@page{size:A4;margin:10mm}@media print{.toolbar{display:none}body{background:white}.labels{padding:0;margin:0;max-width:none}}@media screen and (max-width:650px){.labels{grid-template-columns:1fr}}'
    const meta = node('meta'); meta.name = 'viewport'; meta.content = 'width=device-width,initial-scale=1'
    doc.head.replaceChildren(node('title', title), meta, style)
    const toolbar = node('div', '', 'toolbar'), print = node('button', zh ? '打印标签' : 'Print labels')
    print.type = 'button'; print.disabled = true; print.onclick = () => target.print()
    const dismiss = node('button', zh ? '关闭' : 'Close'); dismiss.type = 'button'; dismiss.onclick = close
    const status = node('p', zh ? '正在准备收卡标签…' : 'Preparing intake labels…')
    toolbar.append(node('h1', title), print, dismiss, status)
    const labels = node('section', '', 'labels'); labels.dataset.testid = 'card-labels'
    doc.body.replaceChildren(toolbar, labels)
    try {
      const order = await load()
      if (current !== generation || target.closed) return order
      if (!order.items.length || order.items.some(card => !card.receiptCode)) throw new Error(zh ? '收卡编号尚未完整生成，请重试。' : 'Some intake codes are missing. Try again.')
      for (const card of order.items) {
        const src = await encode(card.receiptCode!)
        if (current !== generation || target.closed) return order
        const label = node('article', '', 'label'), qr = node('img')
        qr.src = src; qr.alt = card.receiptCode!; qr.width = 240; qr.height = 240
        const copy = node('div')
        copy.append(node('strong', card.cardName), node('p', (zh ? '客户：' : 'Customer: ') + card.ownerDisplayName + (card.clientReference ? ' · ' + card.clientReference : '')))
        copy.append(node('p', card.sourceType === 'partner' ? (zh ? '子代理：' : 'Partner: ') + (card.partnerCompanyName || '') : (zh ? '客户直寄 NXR' : 'Customer direct to NXR')))
        copy.append(node('p', card.orderNo + ' · #' + card.itemNo), node('code', card.receiptCode!))
        label.append(qr, copy); labels.append(label)
      }
      await Promise.all(Array.from(doc.images).map(img => img.decode()))
      if (current === generation && !target.closed) {
        status.textContent = zh ? '共 ' + order.items.length + ' 张卡。每张卡使用自己的标签，随卡套或独立包装放置。' : order.items.length + ' cards. Keep each label with its matching card sleeve or individual package.'
        print.disabled = false
      }
      return order
    } catch (error) {
      if (current === generation && !target.closed) status.textContent = error instanceof Error ? error.message : (zh ? '标签生成失败。' : 'Label preparation failed.')
      throw error
    }
  }
  return { open, close }
}
