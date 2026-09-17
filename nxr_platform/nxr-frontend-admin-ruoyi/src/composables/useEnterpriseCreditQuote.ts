import { computed, onScopeDispose, ref, watch, type Ref } from 'vue'
import { tx } from '@/i18n'
import type { CreditQuote } from '@/api/nxr/enterpriseCredit'

export function sameCreditAmount(left: string | number, right: string | number) {
  const canonical = (value: string | number) => {
    const match = String(value).trim().match(/^\+?(\d*)(?:\.(\d*))?$/)
    if (!match || (!match[1] && !match[2])) return null
    const whole = (match[1] || '0').replace(/^0+(?=\d)/, ''), fraction = (match[2] || '').replace(/0+$/, '')
    return `${whole}${fraction ? `.${fraction}` : ''}`
  }
  const amount = canonical(left)
  return amount !== null && amount === canonical(right)
}

/** A quote is valid only for the exact visible amount and currency. */
export function useEnterpriseCreditQuote(input: () => { currencyCode: string; amount: string | number; enabled?: boolean }, fetcher: (value: { currencyCode: string; amount: string | number }) => Promise<CreditQuote>) {
  const quote = ref<CreditQuote | null>(null) as Ref<CreditQuote | null>, loading = ref(false), error = ref('')
  let generation = 0, timer: ReturnType<typeof setTimeout> | undefined, active = true
  const valid = computed(() => Boolean(quote.value && !loading.value && input().enabled !== false && quote.value.sourceCurrency === input().currencyCode && sameCreditAmount(quote.value.sourceAmount, input().amount)))
  function invalidate() { generation++; clearTimeout(timer); quote.value = null; loading.value = false }
  async function refresh() {
    invalidate(); error.value = ''
    const value = input(), current = generation
    if (value.enabled === false || !Number.isFinite(Number(value.amount)) || Number(value.amount) <= 0) return
    loading.value = true
    try { const result = await fetcher({ currencyCode: value.currencyCode, amount: value.amount }); if (active && current === generation) quote.value = result }
    catch (e) { if (active && current === generation) error.value = e instanceof Error ? e.message : tx('Unable to calculate credits. Refresh the quote and try again.') }
    finally { if (active && current === generation) loading.value = false }
  }
  watch(input, () => { invalidate(); error.value = ''; if (input().enabled !== false && Number(input().amount) > 0) { loading.value = true; timer = setTimeout(refresh, 250) } }, { deep: true, immediate: true, flush: 'sync' })
  onScopeDispose(() => { active = false; invalidate() })
  return { quote, loading, error, valid, refresh, invalidate }
}
