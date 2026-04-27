<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  fetchHealth,
  fetchOverview,
  fetchSummary,
  type PlatformHealth,
  type PlatformSummary,
  type PublicOverview,
} from '../lib/api'

const health = ref<PlatformHealth | null>(null)
const summary = ref<PlatformSummary | null>(null)
const overview = ref<PublicOverview | null>(null)
const errorMessage = ref('')

onMounted(async () => {
  try {
    const [healthResult, summaryResult, overviewResult] = await Promise.all([
      fetchHealth(),
      fetchSummary(),
      fetchOverview(),
    ])
    health.value = healthResult
    summary.value = summaryResult
    overview.value = overviewResult
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Failed to load platform summary'
  }
})
</script>

<template>
  <main class="web-shell">
    <header class="topbar">
      <router-link class="brand-mark" to="/">NXR Grading</router-link>
      <nav class="topbar-nav">
        <router-link to="/verify">Verify</router-link>
        <a href="http://127.0.0.1:3001" target="_blank" rel="noreferrer">Admin</a>
      </nav>
    </header>

    <section class="hero-panel">
      <div class="hero-copy">
        <p class="eyebrow">NXR Platform</p>
        <h1>AI precision, human review, public verification.</h1>
        <p class="hero-text">
          {{ overview?.headline ?? 'The new NXR stack keeps grading data, public certificates, and review workflow separate without losing the direct, transparent feel of the current client site.' }}
        </p>
        <div class="hero-actions">
          <router-link class="primary-link" to="/verify">Verify a Certificate</router-link>
          <a class="secondary-link" href="http://127.0.0.1:3001" target="_blank" rel="noreferrer">Open Admin Workspace</a>
        </div>

        <div class="metric-strip">
          <article class="metric-card">
            <span>Published Certificates</span>
            <strong>{{ overview?.publishedCertificates ?? 0 }}</strong>
          </article>
          <article class="metric-card">
            <span>Pending Review</span>
            <strong>{{ overview?.pendingReview ?? 0 }}</strong>
          </article>
          <article class="metric-card">
            <span>Waitlist Interest</span>
            <strong>{{ overview?.waitlistCount ?? 0 }}</strong>
          </article>
        </div>
      </div>

      <div class="status-card">
        <div class="status-row">
          <span>Status</span>
          <strong>{{ health?.status ?? 'loading' }}</strong>
        </div>
        <div class="status-row">
          <span>Service</span>
          <strong>{{ health?.service ?? 'nxr-platform-backend' }}</strong>
        </div>
        <div class="status-row">
          <span>Version</span>
          <strong>{{ health?.version ?? 'phase-1' }}</strong>
        </div>
        <div class="status-row">
          <span>Platform phase</span>
          <strong>{{ summary?.phase ?? 'phase-1' }}</strong>
        </div>
        <div class="status-row">
          <span>Legacy hidden admin</span>
          <strong>{{ summary?.publicAdminEntry ?? '/x7k9m2q4r8v6c3p1' }}</strong>
        </div>
        <div class="status-row">
          <span>Total submissions</span>
          <strong>{{ summary?.submissionCount ?? 0 }}</strong>
        </div>
      </div>
    </section>

    <section class="info-grid">
      <article class="info-card">
        <h2>How NXR Grades</h2>
        <p>AI inspects centering, edges, corners, and surface first. Human review confirms or challenges the result before a certificate goes public.</p>
      </article>

      <article class="info-card">
        <h2>What Changed</h2>
        <p>Public web, admin workflow, and database design now live as separate modules, which reduces the risk of one change damaging the whole system.</p>
      </article>

      <article class="info-card">
        <h2>Current Modules</h2>
        <ul>
          <li v-for="moduleName in summary?.modules ?? []" :key="moduleName">{{ moduleName }}</li>
        </ul>
      </article>
    </section>

    <section class="workflow-grid">
      <article class="workflow-card">
        <span>1</span>
        <h2>AI First Pass</h2>
        <p>Every submission starts with structured grading data so later publish and verify flows can stay exact.</p>
      </article>
      <article class="workflow-card">
        <span>2</span>
        <h2>Human Review</h2>
        <p>The admin workflow keeps pending, approved, and published states distinct instead of mixing them inside one table.</p>
      </article>
      <article class="workflow-card">
        <span>3</span>
        <h2>Public Certificate</h2>
        <p>Once a card is published, collectors can open a clean public certificate detail page with images, scores, and decision notes.</p>
      </article>
    </section>

    <section class="featured-panel">
      <div class="section-heading">
        <div>
          <p class="eyebrow">Published Cards</p>
          <h2>Featured certificates from the seeded platform dataset.</h2>
        </div>
        <router-link class="text-link" to="/verify">Open Verify</router-link>
      </div>

      <div class="featured-grid">
        <router-link
          v-for="card in overview?.featuredCards ?? []"
          :key="card.certId"
          class="featured-card"
          :to="`/card/${card.certId}`"
        >
          <img :src="card.frontImageUrl" :alt="card.cardName" />
          <div class="featured-copy">
            <p>{{ card.certId }}</p>
            <h3>{{ card.cardName }}</h3>
            <span>{{ card.brandName }} · {{ card.setName }}</span>
            <strong>{{ card.finalGradeValue }} · {{ card.finalGradeLabel }}</strong>
          </div>
        </router-link>
      </div>
    </section>

    <p v-if="errorMessage" class="error-banner">{{ errorMessage }}</p>
  </main>
</template>

<style scoped>
.web-shell {
  max-width: 1240px;
  margin: 0 auto;
  padding: 28px 24px 72px;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
}

.brand-mark {
  font-size: 1.1rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.topbar-nav {
  display: flex;
  gap: 16px;
  color: var(--muted);
}

.hero-panel {
  display: grid;
  grid-template-columns: 1.7fr 1fr;
  gap: 24px;
}

.hero-copy,
.status-card,
.info-card,
.workflow-card,
.featured-panel {
  border: 1px solid var(--line);
  border-radius: 28px;
  background: var(--panel);
  backdrop-filter: blur(10px);
  box-shadow: 0 20px 60px rgba(31, 48, 79, 0.08);
}

.hero-copy {
  padding: 34px;
}

.eyebrow {
  margin: 0 0 12px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--accent);
}

h1 {
  margin: 0;
  max-width: 11ch;
  font-size: clamp(2.8rem, 5vw, 5.2rem);
  line-height: 0.96;
}

.hero-text {
  max-width: 58ch;
  margin: 22px 0 0;
  font-size: 1.05rem;
  color: var(--muted);
}

.hero-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 28px;
}

.metric-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 28px;
}

.metric-card {
  padding: 18px;
  border-radius: 18px;
  background: rgba(17, 21, 31, 0.04);
}

.metric-card span {
  display: block;
  color: var(--muted);
  font-size: 0.88rem;
}

.metric-card strong {
  display: block;
  margin-top: 8px;
  font-size: 1.6rem;
}

.primary-link,
.secondary-link {
  padding: 14px 18px;
  border-radius: 999px;
  font-weight: 700;
}

.primary-link {
  background: var(--accent);
  color: #fff;
}

.secondary-link {
  border: 1px solid var(--line);
}

.status-card {
  padding: 28px;
}

.status-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
}

.status-row span {
  color: var(--muted);
}

.info-grid,
.workflow-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
  margin-top: 24px;
}

.info-card,
.workflow-card {
  padding: 24px;
}

.info-card h2,
.workflow-card h2 {
  margin: 0 0 12px;
  font-size: 1.1rem;
}

.info-card ul {
  margin: 0;
  padding-left: 18px;
  color: var(--muted);
}

.info-card p,
.workflow-card p {
  margin: 0;
  color: var(--muted);
}

.workflow-card span {
  display: inline-grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 999px;
  background: var(--accent);
  color: #fff;
  font-weight: 700;
}

.workflow-card h2 {
  margin-top: 18px;
}

.featured-panel {
  margin-top: 24px;
  padding: 28px;
}

.section-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 16px;
}

.section-heading h2 {
  margin: 6px 0 0;
  font-size: clamp(1.7rem, 3vw, 2.6rem);
}

.text-link {
  padding: 12px 16px;
  border-radius: 999px;
  border: 1px solid var(--line);
}

.featured-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  margin-top: 22px;
}

.featured-card {
  overflow: hidden;
  border-radius: 24px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.94);
}

.featured-card img {
  display: block;
  width: 100%;
  aspect-ratio: 1.15;
  object-fit: cover;
}

.featured-copy {
  padding: 18px;
}

.featured-copy p,
.featured-copy span {
  margin: 0;
  color: var(--muted);
}

.featured-copy h3 {
  margin: 8px 0;
  font-size: 1.2rem;
}

.featured-copy strong {
  display: block;
  margin-top: 12px;
}

.error-banner {
  margin-top: 20px;
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(204, 75, 31, 0.12);
  color: var(--accent-dark);
}

@media (max-width: 960px) {
  .hero-panel,
  .info-grid,
  .workflow-grid,
  .featured-grid,
  .metric-strip {
    grid-template-columns: 1fr;
  }

  .section-heading,
  .topbar {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
