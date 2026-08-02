<script setup lang="ts">
defineProps<{
  compact?: boolean
}>()

const logoUrl = `${import.meta.env.BASE_URL}static/images/nxr-logo-circle.png`
</script>

<template>
  <footer class="footer" :class="{ 'footer-compact': compact }">
    <div v-if="compact" class="footer-inner">
      <span class="footer-copy">&copy; 2026 NXR Card Grading. All rights reserved.</span>
      <span class="footer-tagline">Grading built on proof, not promises.</span>
    </div>

    <div v-else class="footer-inner">
      <div class="footer-top">
        <div class="footer-brand">
          <div class="footer-logo"><img :src="logoUrl" alt="NXR logo" /></div>
          <p>Professional card grading built on AI precision, human expertise, and radical transparency.</p>
        </div>
        <div class="footer-col">
          <h4>Company</h4>
          <ul>
            <li><router-link to="/about">About Us</router-link></li>
            <li><router-link to="/services">Services</router-link></li>
            <li><router-link to="/submit">Submit</router-link></li>
            <li><router-link to="/verify">Verify</router-link></li>
          </ul>
        </div>
        <div class="footer-col">
          <h4>Resources</h4>
          <ul>
            <li><router-link to="/faq">FAQ</router-link></li>
            <li><router-link to="/faq">Grading Standards</router-link></li>
            <li><router-link to="/submit">Shipping Info</router-link></li>
            <li><router-link to="/about">Contact</router-link></li>
          </ul>
        </div>
        <div class="footer-col">
          <h4>Connect</h4>
          <ul>
            <li><a href="#">Instagram</a></li>
            <li><a href="#">Twitter / X</a></li>
            <li><a href="#">YouTube</a></li>
            <li><a href="#">Discord</a></li>
          </ul>
        </div>
      </div>
      <div class="footer-bottom">
        <span class="footer-copy">&copy; 2026 NXR Card Grading. All rights reserved.</span>
        <span class="footer-tagline">Grading built on proof, not promises.</span>
      </div>
    </div>
  </footer>
</template>
