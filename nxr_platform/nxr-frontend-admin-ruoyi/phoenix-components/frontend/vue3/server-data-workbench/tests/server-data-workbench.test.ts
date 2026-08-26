import { h } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PhoenixServerDataWorkbench from '../src/PhoenixServerDataWorkbench.vue'

function mountWorkbench(props: Record<string, unknown> = {}, slots: Record<string, unknown> = {}) {
  return mount(PhoenixServerDataWorkbench, {
    props: {
      total: 42,
      page: 1,
      pageSize: 10,
      pageSizeOptions: [10, 20, 50],
      ...props,
    },
    slots: {
      filters: '<label>Keyword <input name="keyword" /></label>',
      default: '<table data-testid="host-table"><tbody><tr><td>Host row</td></tr></tbody></table>',
      ...slots,
    },
  })
}

describe('PhoenixServerDataWorkbench', () => {
  it('renders a native query form, host content, toolbar, footer, and replaceable labels', async () => {
    const wrapper = mountWorkbench(
      {
        ariaLabel: 'Remote records',
        labels: { query: 'Search now', reset: 'Clear all', pageSize: 'Rows' },
      },
      {
        toolbar: '<button type="button" data-testid="host-toolbar">Export</button>',
        footer: '<span data-testid="host-footer">Selection summary</span>',
      },
    )

    expect(wrapper.attributes('aria-label')).toBe('Remote records')
    expect(wrapper.get('[data-testid="filter-form"]').element.tagName).toBe('FORM')
    expect(wrapper.get('button[type="submit"]').text()).toBe('Search now')
    expect(wrapper.get('[data-testid="host-table"]').text()).toContain('Host row')
    expect(wrapper.get('[data-testid="host-toolbar"]').text()).toBe('Export')
    expect(wrapper.get('[data-testid="host-footer"]').text()).toBe('Selection summary')
    expect(wrapper.get('.psdw-page-size label').text()).toBe('Rows')

    await wrapper.get('[data-testid="filter-form"]').trigger('submit')
    expect(wrapper.emitted('query')).toEqual([[{ page: 1, pageSize: 10 }]])

    await wrapper.get('.psdw-button-secondary').trigger('click')
    expect(wrapper.emitted('reset')).toHaveLength(1)
  })

  it('allows filter-actions to replace the default buttons while preserving scoped intents', async () => {
    const wrapper = mountWorkbench({}, {
      'filter-actions': ({ query, reset }: { query: () => void; reset: () => void }) =>
        h('div', [
          h('button', { type: 'button', 'data-testid': 'custom-query', onClick: query }, 'Run'),
          h('button', { type: 'button', 'data-testid': 'custom-reset', onClick: reset }, 'Clear'),
        ]),
    })

    expect(wrapper.find('button[type="submit"]').exists()).toBe(false)
    await wrapper.get('[data-testid="custom-query"]').trigger('click')
    await wrapper.get('[data-testid="custom-reset"]').trigger('click')
    expect(wrapper.emitted('query')).toEqual([[{ page: 1, pageSize: 10 }]])
    expect(wrapper.emitted('reset')).toHaveLength(1)
  })

  it('provides accessible default loading, error, and empty states with explicit precedence', async () => {
    const loading = mountWorkbench({ loading: true, error: 'Unavailable', empty: true })
    expect(loading.attributes('aria-busy')).toBe('true')
    expect(loading.get('[data-testid="loading-state"]').attributes('role')).toBe('status')
    expect(loading.find('[data-testid="error-state"]').exists()).toBe(false)
    expect(loading.find('.psdw-pagination-shell').exists()).toBe(false)

    const error = mountWorkbench({ error: 'Unavailable', empty: true })
    expect(error.get('[data-testid="error-state"]').attributes('role')).toBe('alert')
    expect(error.text()).toContain('Unavailable')
    await error.get('[data-testid="error-state"] button').trigger('click')
    expect(error.emitted('retry')).toHaveLength(1)

    const empty = mountWorkbench({ empty: true, emptyTitle: 'Nothing here', emptyDescription: 'Change the filters.' })
    expect(empty.get('[data-testid="empty-state"]').attributes('role')).toBe('status')
    expect(empty.text()).toContain('Nothing here')
    expect(empty.text()).toContain('Change the filters.')
    expect(empty.find('[data-testid="host-table"]').exists()).toBe(false)

    const emptyWithoutReset = mountWorkbench({ empty: true, showReset: false })
    expect(emptyWithoutReset.find('[data-testid="empty-state"] button').exists()).toBe(false)
    expect(emptyWithoutReset.find('.psdw-filter-actions .psdw-button-secondary').exists()).toBe(false)
  })

  it('lets loading, error, and empty slots replace their complete default state', () => {
    const loading = mountWorkbench({ loading: true }, {
      loading: '<div data-testid="custom-loading">Custom loading</div>',
    })
    expect(loading.get('[data-testid="custom-loading"]').text()).toBe('Custom loading')
    expect(loading.find('[data-testid="loading-state"]').exists()).toBe(false)

    const error = mountWorkbench({ error: 'No connection' }, {
      error: ({ error: message }: { error: string }) => h('p', { 'data-testid': 'custom-error' }, message),
    })
    expect(error.get('[data-testid="custom-error"]').text()).toBe('No connection')

    const empty = mountWorkbench({ empty: true }, {
      empty: '<p data-testid="custom-empty">No matching rows</p>',
    })
    expect(empty.get('[data-testid="custom-empty"]').text()).toBe('No matching rows')
  })

  it('emits controlled page updates without emitting a query', async () => {
    const wrapper = mountWorkbench({ total: 25 })

    await wrapper.get('[data-testid="next-page"]').trigger('click')
    expect(wrapper.emitted('update:page')).toEqual([[2]])
    expect(wrapper.emitted('pageChange')).toEqual([[2, 10]])
    expect(wrapper.emitted('query')).toBeUndefined()

    await wrapper.get('.psdw-page-size select').setValue('20')
    expect(wrapper.emitted('update:pageSize')).toEqual([[20]])
    expect(wrapper.emitted('update:page')?.at(-1)).toEqual([1])
    expect(wrapper.emitted('pageChange')?.at(-1)).toEqual([1, 20])
    expect(wrapper.emitted('query')).toBeUndefined()
  })

  it('normalizes invalid totals, pages, page sizes, and options without corrective side effects', async () => {
    const wrapper = mountWorkbench({
      total: 21.9,
      page: 999,
      pageSize: Number.NaN,
      pageSizeOptions: [5.8, 5, Number.NaN, -3, 0],
    })

    expect(wrapper.get('.psdw-current-page').text()).toContain('5 / 5')
    expect(wrapper.get('[data-testid="next-page"]').attributes()).toHaveProperty('disabled')
    expect(wrapper.findAll('.psdw-page-size option').map((option) => option.text())).toEqual(['5'])
    expect(wrapper.text()).toContain('共 21 条')
    expect(wrapper.emitted()).toEqual({})

    await wrapper.setProps({ total: -10, page: Number.NaN, pageSize: -1, pageSizeOptions: [] })
    expect(wrapper.get('.psdw-current-page').text()).toContain('1 / 1')
    expect(wrapper.text()).toContain('共 0 条')
    expect(wrapper.findAll('.psdw-page-size option').map((option) => option.text())).toEqual(['10'])
    expect(wrapper.emitted()).toEqual({})
  })

  it('does not emit requests or pagination events when controlled props change', async () => {
    const wrapper = mountWorkbench()

    await wrapper.setProps({ page: 3, pageSize: 20, total: 100, loading: true })
    await wrapper.setProps({ loading: false, error: 'Temporary error' })
    await wrapper.setProps({ error: '', empty: true })

    expect(wrapper.emitted('query')).toBeUndefined()
    expect(wrapper.emitted('pageChange')).toBeUndefined()
    expect(wrapper.emitted('update:page')).toBeUndefined()
    expect(wrapper.emitted('update:pageSize')).toBeUndefined()
  })

  it('allows the pagination slot to fully replace default controls and exposes normalized helpers', async () => {
    const wrapper = mountWorkbench(
      { total: 31, page: 2, pageSize: 10 },
      {
        pagination: ({ page, totalPages, goToPage, setPageSize }: {
          page: number
          totalPages: number
          goToPage: (page: number) => void
          setPageSize: (pageSize: number) => void
        }) => h('div', { 'data-testid': 'custom-pagination' }, [
          h('span', `${page}/${totalPages}`),
          h('button', { type: 'button', 'data-testid': 'custom-next', onClick: () => goToPage(3) }, 'Next'),
          h('button', { type: 'button', 'data-testid': 'custom-size', onClick: () => setPageSize(25) }, '25'),
        ]),
      },
    )

    expect(wrapper.find('.psdw-page-size').exists()).toBe(false)
    expect(wrapper.get('[data-testid="custom-pagination"]').text()).toContain('2/4')
    await wrapper.get('[data-testid="custom-next"]').trigger('click')
    await wrapper.get('[data-testid="custom-size"]').trigger('click')
    expect(wrapper.emitted('pageChange')).toEqual([[3, 10], [1, 25]])
  })
})
