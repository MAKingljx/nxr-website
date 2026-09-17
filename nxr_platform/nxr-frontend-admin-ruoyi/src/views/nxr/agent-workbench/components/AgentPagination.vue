<script setup lang="ts">
defineProps<{ page: number; pageSize: number; total: number; disabled?: boolean }>()
defineEmits<{ change: [page: number] }>()
</script>
<template><nav v-if="total > pageSize" class="agent-pagination" :aria-label="$tx('Pagination')"><button type="button" class="btn-secondary" :disabled="disabled || page <= 1" @click="$emit('change', page - 1)">{{ $tx('Previous') }}</button><span>{{ $tx('{p1} / {p2} · {p3} records', { p1: page, p2: Math.ceil(total / pageSize), p3: total }) }}</span><button type="button" class="btn-secondary" :disabled="disabled || page * pageSize >= total" @click="$emit('change', page + 1)">{{ $tx('Next') }}</button></nav></template>
