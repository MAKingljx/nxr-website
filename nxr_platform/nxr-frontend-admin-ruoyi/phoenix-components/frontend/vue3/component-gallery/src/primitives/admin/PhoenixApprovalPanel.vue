<script setup lang="ts">
import { computed } from 'vue'

export type PhoenixApprovalStatus = 'pending' | 'approved' | 'rejected' | 'cancelled'
export interface PhoenixApprovalDetail { label: string; value: string }
export interface PhoenixApprovalStep { id: string | number; name: string; approver?: string; status: PhoenixApprovalStatus; time?: string; comment?: string }
const props = withDefaults(defineProps<{
  title?: string
  applicant?: string
  submittedAt?: string
  status?: PhoenixApprovalStatus
  details?: PhoenixApprovalDetail[]
  steps?: PhoenixApprovalStep[]
  comment?: string
  processing?: boolean
  disabled?: boolean
  requireRejectComment?: boolean
}>(), {
  title: '审批申请', applicant: '未填写', submittedAt: '', status: 'pending', details: () => [], steps: () => [], comment: '',
  processing: false, disabled: false, requireRejectComment: true,
})
const emit = defineEmits<{
  'update:comment': [value: string]
  approve: [comment: string]
  reject: [comment: string]
  cancel: []
}>()
const canAct = computed(() => props.status === 'pending' && !props.disabled && !props.processing)
const canReject = computed(() => canAct.value && (!props.requireRejectComment || props.comment.trim().length > 0))
const statusText: Record<PhoenixApprovalStatus, string> = { pending: '待审批', approved: '已通过', rejected: '已驳回', cancelled: '已取消' }
</script>

<template>
  <section class="px-approval-panel" :class="`is-${status}`" :aria-label="title" :aria-busy="processing">
    <header><div><h3>{{ title }}</h3><p>{{ applicant }}<template v-if="submittedAt"> · {{ submittedAt }}</template></p></div><strong>{{ statusText[status] }}</strong></header>
    <dl v-if="details.length"><div v-for="detail in details" :key="detail.label"><dt>{{ detail.label }}</dt><dd>{{ detail.value }}</dd></div></dl>
    <ol v-if="steps.length" aria-label="审批进度">
      <li v-for="step in steps" :key="step.id" :class="`is-${step.status}`">
        <span aria-hidden="true"></span><div><strong>{{ step.name }}</strong><small>{{ step.approver || '等待处理' }}<template v-if="step.time"> · {{ step.time }}</template></small><p v-if="step.comment">{{ step.comment }}</p></div>
      </li>
    </ol>
    <label v-if="status === 'pending'"><span>审批意见</span><textarea rows="3" :value="comment" :disabled="disabled || processing" placeholder="请输入审批意见" @input="emit('update:comment', ($event.target as HTMLTextAreaElement).value)"></textarea></label>
    <footer v-if="status === 'pending'">
      <button type="button" class="is-quiet" :disabled="!canAct" @click="emit('cancel')">取消</button>
      <button type="button" class="is-danger" :disabled="!canReject" @click="emit('reject', comment.trim())">驳回</button>
      <button type="button" :disabled="!canAct" @click="emit('approve', comment.trim())">{{ processing ? '处理中' : '通过' }}</button>
    </footer>
  </section>
</template>
