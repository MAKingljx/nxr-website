import QRCode from 'qrcode'
import type { AgentCard, AgentIntake } from './agentWorkbench'

const labelStyles = `
  *{box-sizing:border-box}body{margin:0;background:#eee;color:#111;font:14px -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC",sans-serif}
  .toolbar{display:flex;align-items:center;flex-wrap:wrap;gap:12px;padding:20px;max-width:210mm;margin:auto}.toolbar h1{font-size:20px;margin:0 auto 0 0}.toolbar p{width:100%;margin:0;line-height:1.6}
  button{font:inherit;cursor:pointer;border:1px solid #444;border-radius:4px;background:white;padding:9px 15px;color:#111}button:disabled{opacity:.5;cursor:wait}
  .labels{max-width:210mm;margin:0 auto 24px;padding:10mm;background:#fff;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:5mm}
  .label{display:grid;grid-template-columns:32mm minmax(0,1fr);gap:3mm;align-items:center;padding:3mm;border:1px dashed #555;min-height:46mm;break-inside:avoid;page-break-inside:avoid}
  .label img{width:32mm;height:32mm;display:block}.copy{min-width:0;overflow-wrap:anywhere}.copy p{font-size:10px;line-height:1.5;margin:3px 0}.copy .name{font-size:12px;font-weight:700}.copy code{font-size:9px;line-height:1.5;display:block;margin-top:4px;overflow-wrap:anywhere;user-select:all}
  @page{size:A4;margin:10mm}@media print{body{background:#fff}.toolbar{display:none}.labels{max-width:none;margin:0;padding:0;gap:5mm}.label{break-inside:avoid;page-break-inside:avoid}}
  @media screen and (max-width:650px){.labels{grid-template-columns:1fr;padding:6mm}.label{grid-template-columns:32mm minmax(0,1fr)}}
`

export function createAgentIntakeLabelPrinter() {
  let preview: Window | null = null
  let generation = 0

  function close() {
    generation++
    if (preview && !preview.closed) preview.close()
    preview = null
  }

  // Open synchronously from the click; QR generation must not consume the user gesture first.
  function open(intake: AgentIntake, cards: AgentCard[]) {
    if (!cards.length) throw new Error('此来件暂无可打印的卡片标签。')
    if (!preview || preview.closed) preview = window.open('', '_blank', 'popup,width=940,height=820')
    if (!preview) throw new Error('标签预览被浏览器拦截，请允许此站点打开弹窗后重试。')
    const target = preview, current = ++generation, doc = target.document
    target.opener = null
    const node = <K extends keyof HTMLElementTagNameMap>(tag: K, text = '', className = '') => {
      const element = doc.createElement(tag)
      element.textContent = text
      if (className) element.className = className
      return element
    }
    const title = node('title', 'NXR 收卡标签')
    const meta = doc.createElement('meta'); meta.name = 'viewport'; meta.content = 'width=device-width,initial-scale=1'
    const style = node('style', labelStyles)
    doc.documentElement.lang = 'zh-CN'
    doc.head.replaceChildren(title, meta, style)
    const toolbar = node('header', '', 'toolbar')
    const print = node('button', '打印标签'); print.type = 'button'; print.disabled = true; print.dataset.testid = 'agent-labels-print'
    const exit = node('button', '关闭'); exit.type = 'button'; exit.addEventListener('click', close)
    const status = node('p', '正在生成二维码…'); status.setAttribute('role', 'status')
    toolbar.append(node('h1', '收卡标签'), print, exit, status)
    const sheet = node('main', '', 'labels'); sheet.dataset.testid = 'agent-labels-preview'
    doc.body.replaceChildren(toolbar, sheet)
    const inventory = cards.map(card => ({ inventoryCode: card.inventoryCode, cardName: card.cardName }))
    const clientName = intake.clientName, intakeNo = intake.intakeNo
    print.addEventListener('click', () => { if (!target.closed && current === generation) { target.focus(); target.print() } })
    target.onafterprint = () => { if (current === generation) close() }
    target.focus()

    void (async () => {
      try {
        const sources = await Promise.all(inventory.map(card => QRCode.toDataURL(card.inventoryCode, {
          width: 384, margin: 4, errorCorrectionLevel: 'M', type: 'image/png',
          color: { dark: '#000000ff', light: '#ffffffff' },
        })))
        if (target.closed || current !== generation) return
        const images: HTMLImageElement[] = []
        inventory.forEach((card, index) => {
          const label = node('article', '', 'label'); label.dataset.testid = 'agent-intake-label'
          const qr = node('img'); qr.alt = `库存码 ${card.inventoryCode}`; qr.src = sources[index]!; images.push(qr)
          const copy = node('div', '', 'copy')
          // Customer-provided text is always a text node, never parsed as markup.
          copy.append(node('p', card.cardName, 'name'), node('p', `客户：${clientName}`), node('p', `来件：${intakeNo}`), node('code', card.inventoryCode))
          label.append(qr, copy); sheet.append(label)
        })
        await Promise.all(images.map(qr => qr.decode()))
        if (target.closed || current !== generation) return
        status.textContent = `${inventory.length} 张标签 · 二维码内容为库存码。请按实际大小打印。`
        print.disabled = false
        sheet.dataset.ready = 'true'
      } catch {
        if (!target.closed && current === generation) {
          sheet.replaceChildren()
          status.textContent = '标签生成失败，请关闭预览后重新打开。'
          status.setAttribute('role', 'alert')
        }
      }
    })()
  }

  return { open, close }
}
