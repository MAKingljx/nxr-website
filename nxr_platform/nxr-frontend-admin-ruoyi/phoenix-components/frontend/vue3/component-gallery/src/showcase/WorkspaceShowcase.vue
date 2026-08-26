<script setup lang="ts">
import {
  PhoenixFileCenterPage,
  PhoenixMessageCenterPage,
  PhoenixProfileSettingsPage,
  PhoenixSystemSettingsPage,
  PhoenixWorkspaceHomePage,
} from '../patterns/workspace'

const metrics = [
  ['待办事项', '18'],
  ['今日消息', '36'],
  ['共享文件', '128'],
]
</script>

<template>
  <div class="cg-workspace-showcase">
    <article>
      <h3>登录后工作台</h3>
      <PhoenixWorkspaceHomePage>
        <template #topbar>上午好，林同学</template>
        <template #metrics><div v-for="metric in metrics" :key="metric[0]" class="cg-workspace-metric"><span>{{ metric[0] }}</span><strong>{{ metric[1] }}</strong></div></template>
        <template #tasks><div class="cg-workspace-list"><strong>待办事项</strong><span>审核新用户申请</span><span>确认本周预约</span></div></template>
        <template #activity><div class="cg-workspace-list"><strong>最近动态</strong><span>商品资料已更新</span><span>月度报表已生成</span></div></template>
        <template #aside><div class="cg-workspace-list"><strong>快捷入口</strong><span>创建项目</span><span>上传文件</span></div></template>
      </PhoenixWorkspaceHomePage>
    </article>
    <article>
      <h3>消息中心</h3>
      <PhoenixMessageCenterPage :unread="8">
        <template #list><div class="cg-workspace-list"><strong>系统通知</strong><span>权限申请已通过</span><span>订单状态已更新</span></div></template>
        <template #detail><div class="cg-workspace-list"><strong>权限申请已通过</strong><span>你现在可以访问项目资料库。</span></div></template>
      </PhoenixMessageCenterPage>
    </article>
    <article>
      <h3>文件中心</h3>
      <PhoenixFileCenterPage>
        <template #folders><div class="cg-workspace-list"><strong>项目文件夹</strong><span>产品资料</span><span>设计资源</span></div></template>
        <template #files><div class="cg-workspace-list"><strong>最近文件</strong><span>需求说明.pdf</span><span>组件清单.xlsx</span></div></template>
        <template #preview><div class="cg-workspace-list"><strong>文件预览</strong><span>选择文件后显示信息</span></div></template>
      </PhoenixFileCenterPage>
    </article>
    <article>
      <h3>个人设置</h3>
      <PhoenixProfileSettingsPage>
        <div class="cg-workspace-form"><label>姓名<input value="林同学"></label><label>部门<input value="产品中心"></label><label>语言<select><option>简体中文</option></select></label></div>
        <template #aside><div class="cg-workspace-list"><strong>账户状态</strong><span>已完成身份验证</span></div></template>
      </PhoenixProfileSettingsPage>
    </article>
    <article>
      <h3>系统设置</h3>
      <PhoenixSystemSettingsPage>
        <div class="cg-workspace-form"><label>系统名称<input value="Phoenix 工作台"></label><label>默认时区<select><option>中国标准时间</option></select></label></div>
        <template #audit><div class="cg-workspace-list"><strong>最近变更</strong><span>管理员更新了通知规则</span></div></template>
      </PhoenixSystemSettingsPage>
    </article>
  </div>
</template>
