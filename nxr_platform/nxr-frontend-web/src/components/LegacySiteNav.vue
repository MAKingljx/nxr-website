<script setup lang="ts">
import { useRouter } from 'vue-router'
import { customerSession, isCustomerSignedIn, logoutCustomer } from '../lib/customer'

defineProps<{
  active?: 'home' | 'services' | 'submit' | 'verify' | 'about' | 'faq' | 'account'
  ctaHref?: string
  ctaLabel?: string
}>()

const router = useRouter()
const logoUrl = `${import.meta.env.BASE_URL}static/images/nxr-logo-circle.png`

async function signOut() {
  await logoutCustomer()
  await router.push('/verify')
}
</script>

<template>
  <nav class="nav">
    <router-link class="nav-logo" to="/" aria-label="NXR Home">
      <img :src="logoUrl" alt="NXR logo" />
    </router-link>
    <ul class="nav-links">
      <li><router-link to="/" :class="{ active: active === 'home' }">Home</router-link></li>
      <li><router-link to="/services" :class="{ active: active === 'services' }">Services</router-link></li>
      <li><router-link to="/submit" :class="{ active: active === 'submit' }">Submit</router-link></li>
      <li><router-link to="/verify" :class="{ active: active === 'verify' }">Verify</router-link></li>
      <li><router-link to="/about" :class="{ active: active === 'about' }">About</router-link></li>
      <li><router-link to="/faq" :class="{ active: active === 'faq' }">FAQ</router-link></li>
      <li v-if="isCustomerSignedIn"><router-link to="/account/cards" :class="{ active: active === 'account' }">My Cards</router-link></li>
      <li v-if="isCustomerSignedIn"><router-link to="/account/orders" :class="{ active: active === 'account' }">My Orders</router-link></li>
    </ul>
    <div class="nav-account">
      <router-link v-if="!isCustomerSignedIn" class="nav-signin" to="/account/login">Sign In</router-link>
      <button v-else class="nav-signin" type="button" :title="customerSession?.customer.email" @click="signOut">Sign Out</button>
      <router-link class="nav-cta" :to="ctaHref ?? '/submit'">{{ ctaLabel ?? 'Submit Now' }}</router-link>
    </div>
  </nav>
</template>
