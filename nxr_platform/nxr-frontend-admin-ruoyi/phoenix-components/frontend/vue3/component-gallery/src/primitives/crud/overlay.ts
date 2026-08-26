export type PhoenixOverlayCloseReason = 'escape' | 'overlay' | 'close-button'

const focusableSelector = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

export function getFocusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(focusableSelector)).filter(
    (element) => !element.hasAttribute('hidden') && element.getAttribute('aria-hidden') !== 'true',
  )
}

export function focusOverlay(container: HTMLElement, initialFocus?: string) {
  const requested = initialFocus ? container.querySelector<HTMLElement>(initialFocus) : undefined
  const target = requested ?? getFocusableElements(container)[0] ?? container
  target.focus()
}

export function trapOverlayFocus(event: KeyboardEvent, container: HTMLElement) {
  if (event.key !== 'Tab') return
  const focusable = getFocusableElements(container)
  if (focusable.length === 0) {
    event.preventDefault()
    container.focus()
    return
  }

  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (!first || !last) return
  if (event.shiftKey && (document.activeElement === first || document.activeElement === container)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
