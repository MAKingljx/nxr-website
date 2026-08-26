import { mount } from '@vue/test-utils'
import { h } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import {
  PhoenixLoginPanel,
  PhoenixPermissionGuard,
  PhoenixRolePermissionMatrix,
  PhoenixUserMenu,
} from '../src/primitives/auth'

const roles = [
  { key: 'admin', label: '管理员' },
  { key: 'viewer', label: '查看者' },
]
const permissions = [
  { key: 'read', label: '查看' },
  { key: 'write', label: '编辑' },
]

describe('Phoenix 认证与权限组件', () => {
  it('登录面板受控更新用户名和口令', async () => {
    const wrapper = mount(PhoenixLoginPanel, { props: { username: '', password: '' } })
    await wrapper.get('input[name="username"]').setValue('张三')
    await wrapper.get('input[name="password"]').setValue('private-value')
    expect(wrapper.emitted('update:username')?.[0]).toEqual(['张三'])
    expect(wrapper.emitted('update:password')?.[0]).toEqual(['private-value'])
  })

  it('登录面板受控更新记住状态', async () => {
    const wrapper = mount(PhoenixLoginPanel, { props: { username: '张三', password: 'secret', remember: false } })
    await wrapper.get('input[name="remember"]').setValue(true)
    expect(wrapper.emitted('update:remember')?.[0]).toEqual([true])
  })

  it('登录提交只发出受控数据且不记录或存储口令', async () => {
    const localStorageSpy = vi.spyOn(Storage.prototype, 'setItem')
    const logSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const wrapper = mount(PhoenixLoginPanel, { props: { username: '  张三  ', password: 'secret', remember: true } })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')?.[0]).toEqual([{ username: '张三', password: 'secret', remember: true }])
    expect(localStorageSpy).not.toHaveBeenCalled()
    expect(logSpy).not.toHaveBeenCalled()
    localStorageSpy.mockRestore()
    logSpy.mockRestore()
  })

  it('登录忙碌状态禁用表单且阻止重复提交', async () => {
    const wrapper = mount(PhoenixLoginPanel, { props: { username: '张三', password: 'secret', busy: true } })
    expect(wrapper.get('form').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('登录字段使用正确的补全和可访问错误语义', () => {
    const wrapper = mount(PhoenixLoginPanel, { props: { error: '用户名或口令错误' } })
    expect(wrapper.get('input[name="username"]').attributes('autocomplete')).toBe('username')
    expect(wrapper.get('input[name="password"]').attributes()).toMatchObject({ type: 'password', autocomplete: 'current-password' })
    expect(wrapper.get('[role="alert"]').text()).toBe('用户名或口令错误')
    expect(wrapper.get('input[name="password"]').attributes('aria-describedby')).toBe(wrapper.get('[role="alert"]').attributes('id'))
  })

  it('登录面板在必填内容不完整时不可提交', async () => {
    const wrapper = mount(PhoenixLoginPanel, { props: { username: '张三', password: '' } })
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('用户菜单展示用户和多个角色', () => {
    const wrapper = mount(PhoenixUserMenu, { props: { name: '张三', roles: ['管理员', '审核员'], open: true } })
    expect(wrapper.text()).toContain('张三')
    expect(wrapper.text()).toContain('管理员、审核员')
    expect(wrapper.get('[role="menu"]').attributes('aria-label')).toContain('张三')
  })

  it('用户菜单触发受控展开与收起', async () => {
    const wrapper = mount(PhoenixUserMenu, { props: { name: '张三', open: false } })
    expect(wrapper.get('.px-user-menu__trigger').attributes('aria-expanded')).toBe('false')
    await wrapper.get('.px-user-menu__trigger').trigger('click')
    expect(wrapper.emitted('update:open')?.[0]).toEqual([true])
    await wrapper.setProps({ open: true })
    await wrapper.get('.px-user-menu').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])
  })

  it('用户菜单退出时同时请求收起', async () => {
    const wrapper = mount(PhoenixUserMenu, { props: { name: '张三', open: true } })
    await wrapper.get('[role="menuitem"]').trigger('click')
    expect(wrapper.emitted('logout')).toHaveLength(1)
    expect(wrapper.emitted('update:open')?.[0]).toEqual([false])
  })

  it('用户菜单禁用时不触发操作', async () => {
    const wrapper = mount(PhoenixUserMenu, { props: { name: '张三', disabled: true } })
    expect(wrapper.get('.px-user-menu__trigger').attributes('disabled')).toBeDefined()
    await wrapper.get('.px-user-menu__trigger').trigger('click')
    expect(wrapper.emitted('update:open')).toBeUndefined()
  })

  it('权限守卫在全部权限满足时显示默认内容', () => {
    const wrapper = mount(PhoenixPermissionGuard, {
      props: { permissions: ['read', 'write'], grantedPermissions: ['write', 'read'] },
      slots: { default: '<button>允许操作</button>', denied: '<span>无权操作</span>' },
    })
    expect(wrapper.text()).toContain('允许操作')
    expect(wrapper.text()).not.toContain('无权操作')
  })

  it('权限守卫按任一权限匹配并在拒绝时传出缺失集合', () => {
    const allowed = mount(PhoenixPermissionGuard, {
      props: { permissions: ['read', 'write'], grantedPermissions: ['read'], match: 'any' },
      slots: { default: '<span>已授权</span>' },
    })
    expect(allowed.text()).toBe('已授权')

    const denied = mount(PhoenixPermissionGuard, {
      props: { permissions: ['read', 'write'], grantedPermissions: ['read'] },
      slots: { denied: ({ missingPermissions }: { missingPermissions: string[] }) => h('span', missingPermissions.join(',')) },
    })
    expect(denied.text()).toBe('write')
  })

  it('权限守卫在没有声明必需权限时默认通过', () => {
    const wrapper = mount(PhoenixPermissionGuard, { slots: { default: '<span>公开内容</span>' } })
    expect(wrapper.text()).toBe('公开内容')
  })

  it('角色权限矩阵展示表头和受控选中状态', () => {
    const wrapper = mount(PhoenixRolePermissionMatrix, { props: { modelValue: { admin: ['read'] }, roles, permissions } })
    expect(wrapper.get('caption').text()).toBe('角色权限配置')
    expect(wrapper.findAll('th')).toHaveLength(5)
    expect((wrapper.get('[aria-label="管理员：查看"]').element as HTMLInputElement).checked).toBe(true)
    expect((wrapper.get('[aria-label="管理员：编辑"]').element as HTMLInputElement).checked).toBe(false)
  })

  it('角色权限矩阵勾选时只发出新值而不修改原数据', async () => {
    const modelValue = { admin: ['read'], viewer: ['read'] }
    const wrapper = mount(PhoenixRolePermissionMatrix, { props: { modelValue, roles, permissions } })
    await wrapper.get('[aria-label="管理员：编辑"]').setValue(true)
    expect(modelValue).toEqual({ admin: ['read'], viewer: ['read'] })
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([{ admin: ['read', 'write'], viewer: ['read'] }])
    expect(wrapper.emitted('change')?.[0]?.[0]).toMatchObject({ roleKey: 'admin', permissionKey: 'write', checked: true })
  })

  it('角色权限矩阵取消勾选时保留其他权限', async () => {
    const wrapper = mount(PhoenixRolePermissionMatrix, { props: { modelValue: { admin: ['read', 'write'] }, roles, permissions } })
    await wrapper.get('[aria-label="管理员：查看"]').setValue(false)
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([{ admin: ['write'] }])
  })

  it('角色或权限禁用时不可修改', () => {
    const wrapper = mount(PhoenixRolePermissionMatrix, {
      props: {
        roles: [{ key: 'admin', label: '管理员', disabled: true }, { key: 'viewer', label: '查看者' }],
        permissions: [{ key: 'read', label: '查看' }, { key: 'write', label: '编辑', disabled: true }],
      },
    })
    expect(wrapper.get('[aria-label="管理员：查看"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[aria-label="查看者：编辑"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[aria-label="查看者：查看"]').attributes('disabled')).toBeUndefined()
  })

  it('角色权限矩阵去重并在无数据时给出状态', () => {
    const deduplicated = mount(PhoenixRolePermissionMatrix, {
      props: { roles: [roles[0], roles[0]], permissions: [permissions[0], permissions[0]] },
    })
    expect(deduplicated.findAll('tbody tr')).toHaveLength(1)
    expect(deduplicated.findAll('tbody input')).toHaveLength(1)

    const empty = mount(PhoenixRolePermissionMatrix)
    expect(empty.get('[role="status"]').text()).toContain('暂无可配置')
  })
})
