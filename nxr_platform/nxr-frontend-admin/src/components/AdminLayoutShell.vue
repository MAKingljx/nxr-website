<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { clearAdminSession, readAdminSession } from '../lib/auth'

defineProps<{
  sectionLabel: string
  title: string
  description: string
}>()

const route = useRoute()
const router = useRouter()
const session = computed(() => readAdminSession())

function logout() {
  clearAdminSession()
  router.push('/login')
}
</script>

<template>
  <main class="admin-shell">
    <aside class="sidebar">
      <div class="sidebar-head">
        <p>NXR Platform</p>
        <h1>Admin Workspace</h1>
        <span>Legacy public entry remains `/x7k9m2q4r8v6c3p1`.</span>
      </div>

      <nav class="nav-links">
        <router-link :class="{ active: route.path === '/dashboard' }" to="/dashboard">Dashboard</router-link>
        <router-link :class="{ active: route.path === '/entries' }" to="/entries">Entries</router-link>
        <router-link :class="{ active: route.path === '/upload' }" to="/upload">Upload Workspace</router-link>
      </nav>

      <div class="session-card">
        <strong>{{ session?.displayName ?? 'Signed out' }}</strong>
        <span>{{ session?.roleCode ?? 'guest' }}</span>
        <button type="button" @click="logout">Sign out</button>
      </div>
    </aside>

    <section class="workspace">
      <header class="workspace-header">
        <div>
          <p class="eyebrow">{{ sectionLabel }}</p>
          <h2>{{ title }}</h2>
          <p>{{ description }}</p>
        </div>
      </header>

      <slot />
    </section>
  </main>
</template>

<style scoped>
.admin-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 300px 1fr;
}

.sidebar {
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding: 28px 22px;
  background:
    radial-gradient(circle at top left, rgba(82, 136, 255, 0.22), transparent 35%),
    linear-gradient(180deg, #0d1a31 0%, #112649 100%);
  color: #edf2ff;
}

.sidebar-head p,
.eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.sidebar-head h1,
.workspace-header h2 {
  margin: 0;
}

.sidebar-head span,
.workspace-header p {
  display: block;
  margin-top: 10px;
  color: rgba(237, 242, 255, 0.72);
}

.nav-links {
  display: grid;
  gap: 10px;
}

.nav-links a,
.session-card {
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.07);
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.nav-links a {
  padding: 14px 16px;
}

.nav-links a.active {
  background: rgba(255, 255, 255, 0.16);
}

.session-card {
  margin-top: auto;
  padding: 16px;
}

.session-card strong,
.session-card span {
  display: block;
}

.session-card span {
  margin-top: 6px;
  color: rgba(237, 242, 255, 0.72);
}

.session-card button {
  width: 100%;
  margin-top: 14px;
  height: 42px;
  border: none;
  border-radius: 12px;
  background: #edf2ff;
  color: #112649;
  font: inherit;
  font-weight: 700;
  cursor: pointer;
}

.workspace {
  padding: 28px;
}

.workspace-header p {
  color: #55647f;
}

@media (max-width: 960px) {
  .admin-shell {
    grid-template-columns: 1fr;
  }
}
</style>
