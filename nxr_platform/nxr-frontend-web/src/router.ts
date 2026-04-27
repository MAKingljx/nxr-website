import { createRouter, createWebHistory } from 'vue-router'
import WebCardView from './views/WebCardView.vue'
import WebHomeView from './views/WebHomeView.vue'
import WebVerifyView from './views/WebVerifyView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: WebHomeView },
    { path: '/verify', component: WebVerifyView },
    { path: '/card/:certId', component: WebCardView, props: true },
  ],
})

export default router
