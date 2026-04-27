<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { loginAdmin } from '../lib/api'
import { writeAdminSession } from '../lib/auth'

const router = useRouter()
const isSubmitting = ref(false)
const form = reactive({
  username: 'nxr_admin',
  password: 'NxrAdmin2026!',
})

async function submitLogin() {
  isSubmitting.value = true

  try {
    const session = await loginAdmin(form.username, form.password)
    writeAdminSession(session)
    ElMessage.success('Admin session initialized')
    router.push('/dashboard')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Login failed')
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <main class="admin-auth-shell">
    <section class="admin-auth-card">
      <p class="eyebrow">NXR Admin</p>
      <h1>Phase-1 admin login</h1>
      <p>This login now verifies against the Java backend seed account so dashboard and entries pages are backed by real API data.</p>

      <form class="login-form" @submit.prevent="submitLogin">
        <label>
          <span>Username</span>
          <input v-model="form.username" type="text" autocomplete="username" />
        </label>

        <label>
          <span>Password</span>
          <input v-model="form.password" type="password" autocomplete="current-password" />
        </label>

        <button class="primary-link" type="submit" :disabled="isSubmitting">
          {{ isSubmitting ? 'Signing in...' : 'Sign in to Admin' }}
        </button>
      </form>

      <p class="helper-text">Seed local credentials are prefilled. Legacy public admin path remains `https://nxrgrading.com/x7k9m2q4r8v6c3p1`.</p>
    </section>
  </main>
</template>

<style scoped>
.admin-auth-shell {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
}

.admin-auth-card {
  width: min(640px, 100%);
  padding: 36px;
  border-radius: 28px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(20, 32, 51, 0.08);
  box-shadow: 0 24px 70px rgba(20, 32, 51, 0.08);
}

.eyebrow {
  margin: 0 0 12px;
  color: #2656c5;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  font-size: 12px;
  font-weight: 700;
}

h1 {
  margin: 0 0 16px;
  font-size: clamp(2rem, 4vw, 3rem);
}

p {
  margin: 0;
  color: #5a6a80;
}

.login-form {
  display: grid;
  gap: 16px;
  margin-top: 28px;
}

label span {
  display: block;
  margin-bottom: 8px;
  font-size: 0.9rem;
  font-weight: 600;
}

input,
.primary-link {
  width: 100%;
  height: 52px;
  border-radius: 16px;
  border: 1px solid rgba(20, 32, 51, 0.12);
  font: inherit;
}

input {
  padding: 0 14px;
}

.primary-link {
  padding: 14px 18px;
  background: #143f9f;
  color: #fff;
  font-weight: 700;
  cursor: pointer;
}

.helper-text {
  margin-top: 18px;
  font-size: 0.92rem;
}
</style>
