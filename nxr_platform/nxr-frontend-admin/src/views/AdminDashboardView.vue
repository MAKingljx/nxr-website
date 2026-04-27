<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AdminLayoutShell from '../components/AdminLayoutShell.vue'
import { fetchDashboard, type AdminDashboard } from '../lib/api'

const dashboard = ref<AdminDashboard | null>(null)
const errorMessage = ref('')

onMounted(async () => {
  try {
    dashboard.value = await fetchDashboard()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Failed to load dashboard'
  }
})
</script>

<template>
  <AdminLayoutShell
    section-label="Phase 1"
    title="Real dashboard metrics"
    description="These counts come from the Java backend and seeded MySQL-style schema, not from placeholder constants."
  >
    <div class="metric-grid">
      <article class="metric-card">
        <span>Total submissions</span>
        <strong>{{ dashboard?.totalSubmissions ?? 0 }}</strong>
      </article>
      <article class="metric-card">
        <span>Pending review</span>
        <strong>{{ dashboard?.pendingReview ?? 0 }}</strong>
      </article>
      <article class="metric-card">
        <span>Approved ready</span>
        <strong>{{ dashboard?.approvedReady ?? 0 }}</strong>
      </article>
      <article class="metric-card">
        <span>Published certificates</span>
        <strong>{{ dashboard?.publishedCertificates ?? 0 }}</strong>
      </article>
      <article class="metric-card">
        <span>Waitlist emails</span>
        <strong>{{ dashboard?.waitlistCount ?? 0 }}</strong>
      </article>
    </div>

    <section class="recent-panel">
      <div class="recent-head">
        <div>
          <p class="eyebrow">Recent Publish</p>
          <h3>Recently published certificates</h3>
        </div>
        <router-link class="outline-link" to="/entries">Open Entries</router-link>
      </div>

      <div class="recent-table">
        <article v-for="row in dashboard?.recentPublished ?? []" :key="row.certId" class="recent-row">
          <div>
            <strong>{{ row.certId }}</strong>
            <span>{{ row.cardName }}</span>
          </div>
          <div>
            <span>{{ row.brandName }}</span>
          </div>
          <div class="grade-pill">{{ row.finalGradeValue }} · {{ row.finalGradeLabel }}</div>
        </article>
      </div>

      <p v-if="errorMessage" class="error-banner">{{ errorMessage }}</p>
    </section>
  </AdminLayoutShell>
</template>

<style scoped>
.metric-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 16px;
}

.metric-card,
.recent-panel {
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 18px 42px rgba(20, 32, 51, 0.08);
}

.metric-card {
  padding: 22px;
}

.metric-card span {
  display: block;
  color: #5a6a80;
}

.metric-card strong {
  display: block;
  margin-top: 12px;
  font-size: 1.4rem;
}

.recent-panel {
  margin-top: 20px;
  padding: 24px;
}

.recent-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
}

.eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  font-weight: 700;
  color: #2656c5;
}

h3 {
  margin: 0;
}

.outline-link {
  padding: 12px 14px;
  border-radius: 999px;
  border: 1px solid rgba(20, 32, 51, 0.12);
}

.recent-table {
  display: grid;
  gap: 12px;
  margin-top: 18px;
}

.recent-row {
  display: grid;
  grid-template-columns: 1.4fr 1fr auto;
  gap: 14px;
  align-items: center;
  padding: 16px 18px;
  border-radius: 18px;
  background: #f5f8fd;
}

.recent-row strong,
.recent-row span {
  display: block;
}

.recent-row span {
  color: #5a6a80;
}

.grade-pill {
  padding: 10px 12px;
  border-radius: 999px;
  background: rgba(20, 63, 159, 0.1);
  font-weight: 700;
}

.error-banner {
  margin-top: 16px;
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(201, 82, 50, 0.12);
  color: #a3391a;
}

@media (max-width: 1100px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .recent-row {
    grid-template-columns: 1fr;
  }
}
</style>
