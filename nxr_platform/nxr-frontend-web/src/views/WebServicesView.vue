<script setup lang="ts">
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'

const tiers = [
  {
    name: 'Economy',
    turnaround: '20–30 business days',
    price: '$15',
    desc: 'Best for bulk submissions and lower-value cards where turnaround time is flexible.',
    features: ['AI + human dual grading', 'Full sub-score breakdown', 'Premium slab encapsulation', 'NFC + QR verification', 'Digital certificate'],
  },
  {
    name: 'Standard',
    turnaround: '10–15 business days',
    price: '$25',
    desc: 'The most popular tier. Balanced turnaround for mid-value cards and regular collectors.',
    features: ['AI + human dual grading', 'Full sub-score breakdown', 'Premium slab encapsulation', 'NFC + QR verification', 'Digital certificate', 'Priority customer support'],
  },
  {
    name: 'Express',
    turnaround: '5–7 business days',
    price: '$45',
    desc: 'Fast-track processing for time-sensitive submissions. Ideal for cards you want graded quickly.',
    featured: true,
    features: ['AI + human dual grading', 'Full sub-score breakdown', 'Premium slab encapsulation', 'NFC + QR verification', 'Digital certificate', 'Priority customer support', 'Insured return shipping'],
  },
  {
    name: 'Premium',
    turnaround: '2–3 business days',
    price: '$85',
    desc: 'Maximum priority for high-value cards. Fastest possible turnaround with full coverage.',
    features: ['AI + human dual grading', 'Full sub-score breakdown', 'Premium slab encapsulation', 'NFC + QR verification', 'Digital certificate', 'Dedicated account manager', 'Insured return shipping', 'Full insurance coverage'],
  },
]

const included = [
  ['🤖 AI + Human Dual Grading', "Our AI system scans and grades first. A certified human grader then independently reviews. Both must agree before a grade is issued."],
  ['📊 Full Sub-Score Breakdown', "Centering, edges, corners, and surface — all published with your grade. You'll know exactly how every point was earned or lost."],
  ['🔒 Premium Slab Encapsulation', 'UV-resistant, tamper-evident, optically clear slabs. Engineered for decades of protection, not just display.'],
  ['📱 NFC + QR Verification', 'Dual verification built into every slab. Scan with any phone — no app required — to instantly verify authenticity and grade.'],
  ['🌐 Digital Certificate', "A permanent, publicly accessible digital record of your card's grade, sub-scores, and authentication status."],
  ['📦 Secure Handling', 'Your cards are tracked and handled with care at every step. Fully insured while in our facility.'],
]
</script>

<template>
  <LegacySiteNav active="services" />

  <main>
    <div class="page-hero">
      <div class="page-hero-inner">
        <div class="section-tag">Grading Services</div>
        <h1>Honest grades.<br />Every tier.</h1>
        <p>
          Whether you're grading a bulk lot or a vintage chase card, every submission gets the same AI + human dual
          verification process. The tier only affects turnaround time — never the quality of the grade.
        </p>
      </div>
    </div>

    <section class="section">
      <div class="section-inner">
        <div class="section-tag">Service Tiers</div>
        <h2 class="section-title">Choose your turnaround</h2>
        <p class="section-copy">Same grading standards across all tiers. No shortcuts, no compromises.</p>
        <div class="tiers-grid">
          <div v-for="tier in tiers" :key="tier.name" class="tier" :class="{ featured: tier.featured }">
            <div v-if="tier.featured" class="tier-badge">Popular</div>
            <div class="tier-name">{{ tier.name }}</div>
            <div class="tier-turnaround">{{ tier.turnaround }}</div>
            <div class="tier-price">{{ tier.price }}<span>/card</span></div>
            <div class="tier-desc">{{ tier.desc }}</div>
            <ul class="tier-features">
              <li v-for="feature in tier.features" :key="feature">{{ feature }}</li>
            </ul>
            <router-link to="/submit" class="tier-btn">Submit {{ tier.name }}</router-link>
          </div>
        </div>
      </div>
    </section>

    <section class="section bg2">
      <div class="section-inner">
        <div class="section-tag">What's Included</div>
        <h2 class="section-title">Every submission includes</h2>
        <p class="section-copy">No hidden fees. No upsells. Everything you need is included in every tier.</p>
        <div class="included-grid">
          <div v-for="[title, copy] in included" :key="title" class="included-item">
            <h4>{{ title }}</h4>
            <p>{{ copy }}</p>
          </div>
        </div>
      </div>
    </section>

    <section class="section">
      <div class="section-inner">
        <div class="section-tag">Comparison</div>
        <h2 class="section-title">How we compare</h2>
        <p class="section-copy" style="margin-bottom: 0">The grading industry has a transparency problem. Here's where we stand.</p>
        <div class="compare-wrap">
          <table class="compare-table">
            <thead>
              <tr><th>Feature</th><th>NXR Grading</th><th>Traditional Graders</th></tr>
            </thead>
            <tbody>
              <tr class="highlight"><td>Sub-scores published</td><td class="check">✓ Always, all 4</td><td class="cross">Rarely or never</td></tr>
              <tr><td>AI-assisted grading</td><td class="check">✓ Every card</td><td class="cross">Limited or none</td></tr>
              <tr class="highlight"><td>Grading criteria documented</td><td class="check">✓ Fully public</td><td class="cross">Vague or proprietary</td></tr>
              <tr><td>Same standards for all submissions</td><td class="check">✓ No exceptions</td><td class="cross">VIP tiers exist</td></tr>
              <tr class="highlight"><td>Grade dispute process</td><td class="check">✓ Full audit trail</td><td class="cross">Opaque or unavailable</td></tr>
              <tr><td>NFC + QR dual verification</td><td class="check">✓ Standard</td><td class="cross">QR only or none</td></tr>
              <tr class="highlight"><td>Digital certificate included</td><td class="check">✓ Free, always</td><td class="cross">Paid add-on</td></tr>
            </tbody>
          </table>
        </div>
        <div class="cta-strip">
          <h2>The grade you see is the grade you get.</h2>
          <p>No surprises. No black box. Submit your cards and experience grading done right.</p>
          <router-link to="/submit" class="btn-primary">Submit Your Cards</router-link>
          <router-link to="/faq" class="btn-secondary">Read the FAQ</router-link>
        </div>
      </div>
    </section>
  </main>

  <LegacySiteFooter />
</template>
