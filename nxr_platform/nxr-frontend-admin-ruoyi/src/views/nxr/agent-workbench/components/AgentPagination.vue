<script setup lang="ts">
defineProps<{ page: number; pageSize: number; total: number; disabled?: boolean }>()
defineEmits<{ change: [page: number] }>()
</script>
<template><nav v-if="total > pageSize" class="agent-pagination" aria-label="分页"><button type="button" class="btn-secondary" :disabled="disabled || page <= 1" @click="$emit('change', page - 1)">上一页</button><span>{{ page }} / {{ Math.ceil(total / pageSize) }} · 共 {{ total }} 条</span><button type="button" class="btn-secondary" :disabled="disabled || page * pageSize >= total" @click="$emit('change', page + 1)">下一页</button></nav></template>
