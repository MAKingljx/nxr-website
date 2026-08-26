import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import {
  PhoenixAdvancedTable,
  PhoenixApprovalPanel,
  PhoenixAttachmentPreviewer,
  PhoenixAuditLogViewer,
  PhoenixBatchActionBar,
  PhoenixImportExportPanel,
  PhoenixOrganizationTree,
  PhoenixPasswordChange,
  PhoenixQueryPanel,
  PhoenixSessionManager,
  PhoenixTreeSelect,
  PhoenixUserProfile,
} from '../src/primitives/admin'

describe('Phoenix 系统后台核心组件', () => {
  it('高级表格批量选择且不修改输入行', async () => {
    const rows = [{ id: 1, name: '甲' }, { id: 2, name: '乙' }]
    const snapshot = JSON.stringify(rows)
    const wrapper = mount(PhoenixAdvancedTable, { props: { rows, columns: [{ key: 'name', label: '名称' }] } })
    await wrapper.get('[aria-label="选择当前页全部数据"]').trigger('change')
    expect(wrapper.emitted('update:selectedKeys')?.[0]).toEqual([[1, 2]])
    expect(wrapper.emitted('selectionChange')?.[0]).toEqual([[1, 2]])
    expect(JSON.stringify(rows)).toBe(snapshot)
  })

  it('高级表格只发出服务端排序筛选和行内编辑事件', async () => {
    const row = { id: 'a', name: '旧名称' }
    const columns = [{ key: 'name', label: '名称', sortable: true, filterable: true, editable: true, fixed: 'left' as const, width: Number.POSITIVE_INFINITY }]
    const wrapper = mount(PhoenixAdvancedTable, { props: { rows: [row], columns, editValue: '新名称' } })
    await wrapper.get('[aria-label="按名称排序"]').trigger('click')
    await wrapper.get('[aria-label="筛选名称"]').setValue('关键字')
    await wrapper.get('[aria-label="编辑名称"]').trigger('click')
    expect(wrapper.emitted('sortChange')?.[0]).toEqual([{ key: 'name', direction: 'asc' }])
    expect(wrapper.emitted('filterChange')?.[0]).toEqual([{ name: '关键字' }])
    expect(wrapper.emitted('editRequest')?.[0]).toEqual([{ rowKey: 'a', columnKey: 'name' }, row])
    await wrapper.setProps({ editingCell: { rowKey: 'a', columnKey: 'name' } })
    await wrapper.get('[aria-label="编辑名称"]').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('editCommit')?.[0]).toEqual([{ rowKey: 'a', columnKey: 'name' }, '新名称', row])
  })

  it('查询面板更新受控条件并提交查询', async () => {
    const wrapper = mount(PhoenixQueryPanel, { props: { fields: [{ key: 'keyword', label: '关键字', type: 'search' }], modelValue: {} } })
    await wrapper.get('input').setValue('课程')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([{ keyword: '课程' }])
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('query')?.[0]).toEqual([{}])
  })

  it('查询面板收敛异常展示数并请求展开和重置', async () => {
    const fields = [{ key: 'a', label: '甲' }, { key: 'b', label: '乙' }]
    const wrapper = mount(PhoenixQueryPanel, { props: { fields, collapsed: true, visibleCount: -999 } })
    expect(wrapper.findAll('label')).toHaveLength(1)
    await wrapper.get('[aria-expanded="false"]').trigger('click')
    await wrapper.findAll('button.is-quiet')[1].trigger('click')
    expect(wrapper.emitted('update:collapsed')?.[0]).toEqual([false])
    expect(wrapper.emitted('reset')).toHaveLength(1)
  })

  it('批量操作栏收敛异常数量并阻止空选择操作', async () => {
    const wrapper = mount(PhoenixBatchActionBar, { props: { selectedCount: Number.POSITIVE_INFINITY, actions: [{ key: 'delete', label: '删除' }] } })
    expect(wrapper.text()).toContain('已选择 0 项')
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('action')).toBeUndefined()
  })

  it('批量操作栏返回动作对象并请求清空', async () => {
    const action = { key: 'archive', label: '归档', danger: true }
    const wrapper = mount(PhoenixBatchActionBar, { props: { selectedCount: 3.8, actions: [action] } })
    expect(wrapper.text()).toContain('已选择 3 项')
    await wrapper.findAll('button')[0].trigger('click')
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('action')?.[0]).toEqual([action])
    expect(wrapper.emitted('clear')).toHaveLength(1)
  })

  it('导入导出面板只把文件和格式交给外部处理', async () => {
    const wrapper = mount(PhoenixImportExportPanel)
    const file = new File(['仅测试对象'], '名单.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
    await input.trigger('change')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('importRequest')?.[0]).toEqual([file, 'xlsx'])
    expect(wrapper.emitted('exportRequest')?.[0]).toEqual(['xlsx'])
  })

  it('导入导出面板受控切换格式且忙碌时禁用操作', async () => {
    const wrapper = mount(PhoenixImportExportPanel, { props: { modelValue: 'xlsx', exporting: true } })
    await wrapper.get('select').setValue('csv')
    expect(wrapper.get('select').attributes('disabled')).toBeDefined()
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    expect(wrapper.emitted('exportRequest')).toBeUndefined()
  })

  it('树形选择通过键盘展开并选择节点', async () => {
    const child = { value: 'child', label: '子部门' }
    const root = { value: 'root', label: '总部', children: [child] }
    const wrapper = mount(PhoenixTreeSelect, { props: { nodes: [root], expandedKeys: [], modelValue: [] } })
    await wrapper.get('[role="treeitem"]').trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.emitted('update:expandedKeys')?.[0]).toEqual([['root']])
    await wrapper.get('[role="treeitem"]').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([['root']])
  })

  it('树形多选去重并忽略禁用节点', async () => {
    const nodes = [{ value: 1, label: '可选' }, { value: 2, label: '禁用', disabled: true }]
    const wrapper = mount(PhoenixTreeSelect, { props: { nodes, modelValue: [1], multiple: true } })
    await wrapper.findAll('[role="treeitem"]')[0].trigger('keydown', { key: 'Enter' })
    await wrapper.findAll('[role="treeitem"]')[1].trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([[]])
    expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
  })

  it('组织树展示收敛后人数并发出选择事件', async () => {
    const node = { id: 'dept', name: '研发部', memberCount: Number.NaN }
    const wrapper = mount(PhoenixOrganizationTree, { props: { nodes: [node] } })
    expect(wrapper.text()).toContain('0 人')
    await wrapper.get('.px-organization-tree__node').trigger('click')
    expect(wrapper.emitted('update:selectedId')?.[0]).toEqual(['dept'])
    expect(wrapper.emitted('select')?.[0]).toEqual([node])
  })

  it('组织树受控展开并提供新增编辑请求', async () => {
    const node = { id: 1, name: '总部', children: [{ id: 2, name: '分部' }] }
    const wrapper = mount(PhoenixOrganizationTree, { props: { nodes: [node], expandedIds: [] } })
    await wrapper.get('.px-organization-tree__toggle').trigger('click')
    await wrapper.get('[aria-label="在总部下新增"]').trigger('click')
    await wrapper.get('[aria-label="编辑总部"]').trigger('click')
    expect(wrapper.emitted('update:expandedIds')?.[0]).toEqual([[1]])
    expect(wrapper.emitted('add')?.[0]).toEqual([node])
    expect(wrapper.emitted('edit')?.[0]).toEqual([node])
  })

  it('审计日志去重展示并返回选中详情', async () => {
    const log = { id: 1, action: '修改角色', operator: '张三', time: '10:00', severity: 'warning' as const }
    const wrapper = mount(PhoenixAuditLogViewer, { props: { logs: [log, { ...log }] } })
    expect(wrapper.findAll('li')).toHaveLength(1)
    await wrapper.get('li button').trigger('click')
    expect(wrapper.emitted('update:selectedId')?.[0]).toEqual([1])
    expect(wrapper.emitted('select')?.[0]).toEqual([log])
  })

  it('审计日志空状态和加载更多保持受控', async () => {
    const empty = mount(PhoenixAuditLogViewer)
    expect(empty.text()).toContain('暂无审计记录')
    const wrapper = mount(PhoenixAuditLogViewer, { props: { hasMore: true } })
    await wrapper.get('footer button').trigger('click')
    expect(wrapper.emitted('loadMore')).toHaveLength(1)
  })

  it('审批面板要求驳回意见并发出裁决', async () => {
    const wrapper = mount(PhoenixApprovalPanel, { props: { comment: '' } })
    expect(wrapper.get('button.is-danger').attributes('disabled')).toBeDefined()
    await wrapper.setProps({ comment: '资料不完整' })
    await wrapper.get('button.is-danger').trigger('click')
    expect(wrapper.emitted('reject')?.[0]).toEqual(['资料不完整'])
  })

  it('审批面板更新受控意见并请求通过', async () => {
    const wrapper = mount(PhoenixApprovalPanel, { props: { comment: '', details: [{ label: '金额', value: '100 元' }] } })
    await wrapper.get('textarea').setValue('同意')
    expect(wrapper.emitted('update:comment')?.[0]).toEqual(['同意'])
    await wrapper.setProps({ comment: '同意' })
    await wrapper.findAll('footer button')[2].trigger('click')
    expect(wrapper.emitted('approve')?.[0]).toEqual(['同意'])
  })

  it('附件预览只渲染白名单内安全图片地址', () => {
    const attachments = [{ id: 1, name: '截图.png', url: 'https://assets.example.com/a.png', mimeType: 'image/png' }]
    const wrapper = mount(PhoenixAttachmentPreviewer, { props: { attachments, allowedHosts: ['assets.example.com'] } })
    expect(wrapper.get('img').attributes('src')).toBe('https://assets.example.com/a.png')
    expect(wrapper.find('iframe').exists()).toBe(false)
    expect(wrapper.find('script').exists()).toBe(false)
  })

  it('附件预览拦截脚本和 SVG data 地址且不发下载请求', async () => {
    const blocked = { id: 1, name: '危险.svg', url: 'data:image/svg+xml;base64,PHN2Zz4=', mimeType: 'image/svg+xml' }
    const script = { id: 2, name: '脚本', url: 'javascript:alert(1)', mimeType: 'text/html' }
    const wrapper = mount(PhoenixAttachmentPreviewer, { props: { attachments: [blocked, script], allowedProtocols: ['data:', 'https:', 'javascript:'] } })
    expect(wrapper.text()).toContain('安全策略拦截')
    expect(wrapper.find('img').exists()).toBe(false)
    await wrapper.findAll('li button')[1].trigger('click')
    expect(wrapper.emitted('blocked')?.[0]).toEqual([script, '附件地址不在安全白名单内'])
    expect(wrapper.emitted('download')).toBeUndefined()
  })

  it('个人资料只发出受控字段更新和保存请求', async () => {
    const profile = { name: '李雷', email: 'old@example.com' }
    const wrapper = mount(PhoenixUserProfile, { props: { modelValue: profile, editing: true } })
    await wrapper.get('input[type="email"]').setValue('new@example.com')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([{ ...profile, email: 'new@example.com' }])
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')?.[0]).toEqual([profile])
  })

  it('个人资料中文只读态可请求编辑和取消', async () => {
    const wrapper = mount(PhoenixUserProfile, { props: { modelValue: { name: '韩梅梅' } } })
    expect(wrapper.text()).toContain('未填写')
    await wrapper.get('header button').trigger('click')
    expect(wrapper.emitted('update:editing')?.[0]).toEqual([true])
    await wrapper.setProps({ editing: true })
    await wrapper.get('footer button').trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })

  it('密码修改收敛最小长度并提示不一致', () => {
    const wrapper = mount(PhoenixPasswordChange, { props: { minLength: Number.NaN, modelValue: { currentPassword: '旧口令', newPassword: 'NewPassword_123', confirmPassword: '不同' } } })
    expect(wrapper.text()).toContain('至少 12 个字符')
    expect(wrapper.get('[role="alert"]').text()).toContain('不一致')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  })

  it('密码修改发出受控更新、显示和提交事件', async () => {
    const value = { currentPassword: 'OldPassword_123', newPassword: 'NewPassword_456!', confirmPassword: 'NewPassword_456!' }
    const wrapper = mount(PhoenixPasswordChange, { props: { modelValue: value } })
    await wrapper.get('header button').trigger('click')
    await wrapper.get('input[autocomplete="current-password"]').setValue('ChangedOld_123')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('update:revealed')?.[0]).toEqual([true])
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([{ ...value, currentPassword: 'ChangedOld_123' }])
    expect(wrapper.emitted('submit')?.[0]).toEqual([value])
  })

  it('会话管理去重并保护当前设备', async () => {
    const current = { id: 1, device: '本机', lastActive: '刚刚', current: true }
    const other = { id: 2, device: '手机', lastActive: '昨天' }
    const wrapper = mount(PhoenixSessionManager, { props: { sessions: [current, other, { ...other }] } })
    expect(wrapper.findAll('li')).toHaveLength(2)
    expect(wrapper.text()).toContain('当前设备')
    await wrapper.get('[aria-label="退出手机"]').trigger('click')
    expect(wrapper.emitted('revoke')?.[0]).toEqual([other])
  })

  it('会话管理请求刷新和退出其他全部设备', async () => {
    const sessions = [{ id: 'a', device: '电脑', lastActive: '当前' }, { id: 'b', device: '平板', lastActive: '1 小时前' }]
    const wrapper = mount(PhoenixSessionManager, { props: { sessions, currentSessionId: 'a' } })
    await wrapper.get('header button').trigger('click')
    await wrapper.get('footer button').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    expect(wrapper.emitted('revokeOthers')?.[0]).toEqual([['b']])
  })

  it('后端未提供浏览器和 IP 时不显示虚构信息，并展示真实有效期', () => {
    const wrapper = mount(PhoenixSessionManager, {
      props: {
        sessions: [{
          id: 'current',
          device: '办公电脑',
          lastActive: '2026-08-11T02:00:00Z',
          expiresAt: '2026-08-12T02:00:00Z',
          current: true,
        }],
      },
    })
    expect(wrapper.text()).not.toContain('未知浏览器')
    expect(wrapper.text()).not.toContain('IP 未知')
    expect(wrapper.text()).toContain('有效期至：2026-08-12T02:00:00Z')
  })
})
