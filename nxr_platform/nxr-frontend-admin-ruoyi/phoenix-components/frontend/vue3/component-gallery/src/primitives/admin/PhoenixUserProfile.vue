<script setup lang="ts">
import { computed } from 'vue'

export interface PhoenixUserProfileValue {
  name: string
  email?: string
  phone?: string
  department?: string
  position?: string
  bio?: string
}
const props = withDefaults(defineProps<{
  modelValue: PhoenixUserProfileValue
  editing?: boolean
  saving?: boolean
  disabled?: boolean
  title?: string
}>(), { editing: false, saving: false, disabled: false, title: '个人资料' })
const emit = defineEmits<{
  'update:modelValue': [value: PhoenixUserProfileValue]
  'update:editing': [value: boolean]
  save: [value: PhoenixUserProfileValue]
  cancel: []
}>()
const initials = computed(() => props.modelValue.name.trim().slice(0, 2) || '用户')
function update(key: keyof PhoenixUserProfileValue, value: string) { emit('update:modelValue', { ...props.modelValue, [key]: value }) }
</script>

<template>
  <section class="px-user-profile" :aria-label="title" :aria-busy="saving">
    <header><span class="px-user-profile__avatar" aria-hidden="true">{{ initials }}</span><div><h3>{{ modelValue.name || '未设置姓名' }}</h3><p>{{ modelValue.position || '未设置职位' }}<template v-if="modelValue.department"> · {{ modelValue.department }}</template></p></div><button v-if="!editing" type="button" :disabled="disabled" @click="emit('update:editing', true)">编辑资料</button></header>
    <form v-if="editing" @submit.prevent="emit('save', modelValue)">
      <label><span>姓名</span><input :value="modelValue.name" required maxlength="80" autocomplete="name" :disabled="disabled || saving" @input="update('name', ($event.target as HTMLInputElement).value)"></label>
      <label><span>邮箱</span><input type="email" :value="modelValue.email || ''" maxlength="120" autocomplete="email" :disabled="disabled || saving" @input="update('email', ($event.target as HTMLInputElement).value)"></label>
      <label><span>手机</span><input type="tel" :value="modelValue.phone || ''" maxlength="30" autocomplete="tel" :disabled="disabled || saving" @input="update('phone', ($event.target as HTMLInputElement).value)"></label>
      <label><span>部门</span><input :value="modelValue.department || ''" maxlength="80" :disabled="disabled || saving" @input="update('department', ($event.target as HTMLInputElement).value)"></label>
      <label><span>职位</span><input :value="modelValue.position || ''" maxlength="80" :disabled="disabled || saving" @input="update('position', ($event.target as HTMLInputElement).value)"></label>
      <label class="is-wide"><span>个人简介</span><textarea rows="3" :value="modelValue.bio || ''" maxlength="500" :disabled="disabled || saving" @input="update('bio', ($event.target as HTMLTextAreaElement).value)"></textarea></label>
      <footer><button type="button" class="is-quiet" :disabled="saving" @click="emit('cancel'); emit('update:editing', false)">取消</button><button type="submit" :disabled="disabled || saving || !modelValue.name.trim()">{{ saving ? '保存中' : '保存资料' }}</button></footer>
    </form>
    <dl v-else><div><dt>邮箱</dt><dd>{{ modelValue.email || '未填写' }}</dd></div><div><dt>手机</dt><dd>{{ modelValue.phone || '未填写' }}</dd></div><div><dt>个人简介</dt><dd>{{ modelValue.bio || '未填写' }}</dd></div></dl>
  </section>
</template>
