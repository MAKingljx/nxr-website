<template>
  <div class="app-container home">
    <el-row :gutter="16" class="mb8">
      <el-col :span="6" :xs="12">
        <el-card shadow="never"><el-statistic title="总录入" :value="dashboard.totalSubmissions" /></el-card>
      </el-col>
      <el-col :span="6" :xs="12">
        <el-card shadow="never"><el-statistic title="待审批" :value="dashboard.pendingReview" /></el-card>
      </el-col>
      <el-col :span="6" :xs="12">
        <el-card shadow="never"><el-statistic title="已审批待发布" :value="dashboard.approvedReady" /></el-card>
      </el-col>
      <el-col :span="6" :xs="12">
        <el-card shadow="never"><el-statistic title="已发布证书" :value="dashboard.publishedCertificates" /></el-card>
      </el-col>
    </el-row>
    <el-row :gutter="16">
      <el-col :span="16" :xs="24">
        <el-card shadow="never">
          <template #header><span>最近发布</span></template>
          <el-table :data="dashboard.recentPublished" size="small">
            <el-table-column label="证书编号" prop="certId" width="140" />
            <el-table-column label="卡名" prop="cardName" min-width="180" show-overflow-tooltip />
            <el-table-column label="品牌" prop="brandName" width="140" />
            <el-table-column label="评级" width="160">
              <template #default="scope">{{ scope.row.finalGradeValue }} · {{ scope.row.finalGradeLabel }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="8" :xs="24">
        <el-card shadow="never">
          <template #header><span>Waitlist</span></template>
          <el-statistic title="候补报名总数" :value="dashboard.waitlistCount" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup name="Index">
import { fetchDashboard } from '@/api/nxr/entries'

const dashboard = ref({
  totalSubmissions: 0,
  pendingReview: 0,
  approvedReady: 0,
  publishedCertificates: 0,
  waitlistCount: 0,
  recentPublished: []
})

fetchDashboard().then((res) => {
  dashboard.value = res.data
})
</script>

<style scoped lang="scss">
.home {
  .mb8 {
    margin-bottom: 8px;
  }
}
</style>
