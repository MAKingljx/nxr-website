<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { workspacePath } from '@/utils/submissionWorkspace'

const props = defineProps<{ orderId: number; orderNo: string; companyId: number; disabled?: boolean }>()
const emit = defineEmits<{ preview: [] }>()
const router = useRouter()
const href = computed(() => router.resolve({ path: workspacePath('orders'), query: { company: String(props.companyId), record: String(props.orderId) } }).href)
function open(event: MouseEvent) {
  if (props.disabled) { event.preventDefault(); return }
  // A normal click previews in place; native new-tab actions keep the real URL.
  if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  emit('preview')
}
</script>

<template>
  <a class="order-number-link" :href="disabled ? undefined : href" :aria-disabled="disabled"
    :tabindex="disabled ? -1 : 0" aria-haspopup="dialog" :data-testid="`agent-order-link-${orderId}`" @click="open">{{ orderNo }}</a>
</template>

<style scoped>
.order-number-link{display:inline-flex;align-items:center;min-height:32px;color:var(--el-color-primary);font-weight:600;text-decoration:underline;text-underline-offset:3px;border-radius:3px;cursor:pointer}
.order-number-link:hover{text-decoration-thickness:2px}
.order-number-link:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:3px}
.order-number-link[aria-disabled="true"]{cursor:not-allowed;opacity:.5}
</style>
