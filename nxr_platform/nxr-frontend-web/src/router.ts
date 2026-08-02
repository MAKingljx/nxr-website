import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', component: () => import('./views/WebHomeView.vue') },
    { path: '/services', component: () => import('./views/WebServicesView.vue') },
    { path: '/submit', component: () => import('./views/WebSubmitView.vue') },
    { path: '/submit/order', component: () => import('./views/WebNewOrderView.vue') },
    { path: '/verify', component: () => import('./views/WebVerifyView.vue') },
    { path: '/about', component: () => import('./views/WebAboutView.vue') },
    { path: '/faq', component: () => import('./views/WebFaqView.vue') },
    { path: '/card/:certId', component: () => import('./views/WebCardView.vue'), props: true },
    { path: '/account/login', component: () => import('./views/WebAccountAuthView.vue'), props: { mode: 'login' } },
    { path: '/account/register', component: () => import('./views/WebAccountAuthView.vue'), props: { mode: 'register' } },
    { path: '/account/cards', component: () => import('./views/WebAccountCardsView.vue') },
    { path: '/account/orders', component: () => import('./views/WebOrdersView.vue') },
    { path: '/account/orders/:orderNo', component: () => import('./views/WebOrderDetailView.vue'), props: true },
  ],
})

export default router
