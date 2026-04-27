import { createRouter, createWebHistory } from 'vue-router'
import AdminDashboardView from './views/AdminDashboardView.vue'
import AdminEntriesView from './views/AdminEntriesView.vue'
import AdminLoginView from './views/AdminLoginView.vue'
import AdminUploadView from './views/AdminUploadView.vue'
import { readAdminSession } from './lib/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/login', component: AdminLoginView },
    { path: '/dashboard', component: AdminDashboardView },
    { path: '/entries', component: AdminEntriesView },
    { path: '/upload', component: AdminUploadView },
  ],
})

router.beforeEach((to) => {
  const session = readAdminSession()
  const requiresAuth = to.path !== '/login'

  if (requiresAuth && !session) {
    return '/login'
  }

  if (to.path === '/login' && session) {
    return '/dashboard'
  }

  return true
})

export default router
