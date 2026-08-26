import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PhoenixAccountSecurityPage from '../src/patterns/solutions/account/PhoenixAccountSecurityPage.vue'

const sessions = [
  { id: 'one', clientName: '手机', createdAt: '2026-08-10T00:00:00Z', lastSeenAt: '2026-08-11T01:00:00Z', expiresAt: '2026-08-12T00:00:00Z', current: false },
  { id: 'two', clientName: '办公电脑', createdAt: '2026-08-11T00:00:00Z', lastSeenAt: '2026-08-11T02:00:00Z', expiresAt: '2026-08-12T00:00:00Z', current: true },
]

describe('PhoenixAccountSecurityPage', () => {
  it('直接呈现修改密码和登录设备两个成品区域', () => {
    const wrapper = mount(PhoenixAccountSecurityPage, { props: { sessions } })
    expect(wrapper.text()).toContain('账户安全')
    expect(wrapper.find('.px-password-change').exists()).toBe(true)
    expect(wrapper.find('.px-session-manager').exists()).toBe(true)
    expect(wrapper.text()).toContain('办公电脑')
  })

  it('保持密码表单受控并只提交后端需要的两个字段', async () => {
    const passwordValue = {
      currentPassword: 'Current-Password-42!',
      newPassword: 'New-Password-84!',
      confirmPassword: 'New-Password-84!',
    }
    const wrapper = mount(PhoenixAccountSecurityPage, { props: { passwordValue } })
    await wrapper.get('.px-password-change').trigger('submit')
    expect(wrapper.emitted('change-password')?.[0]?.[0]).toEqual({
      currentPassword: passwordValue.currentPassword,
      newPassword: passwordValue.newPassword,
    })
    expect(JSON.stringify(wrapper.emitted('change-password'))).not.toContain('confirmPassword')
  })

  it('把单个会话撤销映射回原始会话对象', async () => {
    const wrapper = mount(PhoenixAccountSecurityPage, { props: { sessions } })
    const revoke = wrapper.findAll('.px-session-manager button').find((button) => button.text() === '退出登录')
    await revoke?.trigger('click')
    expect(wrapper.emitted('revoke-session')?.[0]?.[0]).toEqual(sessions[0])
  })

  it('撤销其他设备时不信任组件计算出的 ID 列表', async () => {
    const wrapper = mount(PhoenixAccountSecurityPage, { props: { sessions } })
    const revokeOthers = wrapper.findAll('.px-session-manager button').find((button) => button.text() === '退出其他全部设备')
    await revokeOthers?.trigger('click')
    expect(wrapper.emitted('revoke-other-sessions')?.[0]).toEqual([])
  })

  it('转发刷新和密码可见状态请求', async () => {
    const wrapper = mount(PhoenixAccountSecurityPage, { props: { sessions } })
    const refresh = wrapper.findAll('.px-session-manager button').find((button) => button.text() === '刷新')
    await refresh?.trigger('click')
    await wrapper.findAll('.px-password-change button')[0].trigger('click')
    expect(wrapper.emitted('refresh-sessions')).toHaveLength(1)
    expect(wrapper.emitted('update:passwordRevealed')?.[0]?.[0]).toBe(true)
  })

  it('以无障碍状态展示后端错误并禁用操作', () => {
    const wrapper = mount(PhoenixAccountSecurityPage, {
      props: { sessions, disabled: true, passwordError: '当前密码不正确', sessionsError: '会话加载失败' },
    })
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(2)
    expect(wrapper.findAll('button').every((button) => button.attributes('disabled') !== undefined)).toBe(true)
  })
})
