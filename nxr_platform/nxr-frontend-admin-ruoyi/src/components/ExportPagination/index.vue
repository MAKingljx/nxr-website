<template>
  <pagination
    v-model:page="currentPage"
    v-model:limit="currentPageSize"
    :total="total"
    :page-sizes="pageSizes"
    @pagination="handlePagination"
  />
</template>

<script setup name="ExportPagination">
const props = defineProps({
  total: {
    type: Number,
    default: 0
  },
  page: {
    type: Number,
    default: 1
  },
  pageSize: {
    type: Number,
    default: 20
  },
  pageSizes: {
    type: Array,
    default: () => [10, 20, 50, 100, 200]
  }
})

const emit = defineEmits(['change'])

const currentPage = ref(props.page)
const currentPageSize = ref(props.pageSize)

watch(
  () => props.page,
  (value) => {
    currentPage.value = value
  }
)

watch(
  () => props.pageSize,
  (value) => {
    currentPageSize.value = value
  }
)

function handlePagination() {
  emit('change', { page: currentPage.value, pageSize: currentPageSize.value })
}
</script>
