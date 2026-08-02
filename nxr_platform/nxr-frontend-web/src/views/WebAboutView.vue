<script setup lang="ts">
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'

const principles = [
  ['1', 'Transparency over tradition', 'Every sub-score published. Every criterion documented. No black box, no mystery grades.'],
  ['2', 'Consistency over convenience', 'The same standards applied to every card, every time. No exceptions for high-value submissions.'],
  ['3', 'Technology in service of accuracy', "AI doesn't replace human judgment — it removes human bias. Both work together."],
  ['4', 'Collectors first, always', 'We exist to serve the people who love these cards — not the institutions that profit from them.'],
]

const team = [
  ['🧠', 'AI Grading System', 'Core Technology', 'Proprietary computer vision trained on millions of card images. Measures to sub-millimeter precision.'],
  ['🔬', 'Certified Graders', 'Human Verification', "Every AI grade is independently reviewed by a certified expert. Both must agree before a grade is issued."],
  ['🛡️', 'Authentication Team', 'Fraud Prevention', 'Specialists in detecting alterations, trimming, and counterfeits across all major card categories.'],
  ['📦', 'Encapsulation Lab', 'Protection & Finishing', 'UV-resistant, tamper-evident slabs with NFC + QR dual verification, assembled in a controlled environment.'],
]
</script>

<template>
  <LegacySiteNav active="about" />

  <main>
    <div class="page-hero">
      <div class="page-hero-inner">
        <div class="section-tag">About NXR</div>
        <h1>Built by collectors.<br />For collectors.</h1>
        <p>
          We started NXR because we were tired of the same thing you were tired of — grades you couldn't trust,
          institutions that didn't care, and a system that rewarded loyalty over accuracy.
        </p>
      </div>
    </div>

    <section class="section">
      <div class="section-inner">
        <div class="mission-grid">
          <div class="mission-text">
            <div class="section-tag">Our Mission</div>
            <h2>Transparency isn't a feature.<br /><em>It's the foundation.</em></h2>
            <p>
              NXR was founded on a simple belief: <strong>collectors deserve to know exactly how their cards are graded.</strong>
              Not just the final number — the full breakdown, the criteria, the reasoning.
            </p>
            <p>
              We combine AI-powered precision scanning with certified human expertise to deliver grades that are
              consistent, documented, and defensible. Every time. For every card. Regardless of who submitted it.
            </p>
            <p>
              We don't have a VIP tier. We don't have a fast lane for high-profile submissions.
              <strong>Your card gets the same rigorous process as everyone else's.</strong> That's not a policy — it's a principle.
            </p>
          </div>
          <div class="mission-visual">
            <div v-for="[number, title, copy] in principles" :key="number" class="principle">
              <div class="principle-num">{{ number }}</div>
              <div class="principle-body">
                <h4>{{ title }}</h4>
                <p>{{ copy }}</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section class="section bg2">
      <div class="section-inner">
        <div class="section-tag" style="display: block; text-align: center; margin-bottom: 16px">Our Values</div>
        <h2 class="section-title" style="text-align: center">What we stand for</h2>
        <p class="section-copy" style="text-align: center; margin-bottom: 0">The principles that guide every decision we make.</p>
        <div class="values-grid">
          <div class="value-card">
            <div class="value-icon">🔍</div>
            <h3>Radical Transparency</h3>
            <p>Every grade comes with a full breakdown. Centering, edges, corners, surface — all published, all verifiable.</p>
          </div>
          <div class="value-card">
            <div class="value-icon">⚖️</div>
            <h3>Absolute Consistency</h3>
            <p>The same criteria. The same process. The same standards — for a $5 common and a $50,000 vintage.</p>
          </div>
          <div class="value-card">
            <div class="value-icon">🤝</div>
            <h3>Collector-First</h3>
            <p>We answer to collectors, not shareholders. Accurate grades build trust, and trust builds everything else.</p>
          </div>
        </div>
      </div>
    </section>

    <section class="section">
      <div class="section-inner">
        <div class="team-intro">
          <div class="section-tag">The Team</div>
          <h2>Experts who've been on your side of the slab</h2>
          <p>
            Our team combines decades of collecting experience with expertise in AI, materials science, and authentication.
            We've submitted cards. We know what it feels like to get a grade that doesn't make sense.
          </p>
        </div>
        <div class="team-grid">
          <div v-for="[avatar, name, role, copy] in team" :key="name" class="team-card">
            <div class="team-avatar">{{ avatar }}</div>
            <h4>{{ name }}</h4>
            <div class="role">{{ role }}</div>
            <p>{{ copy }}</p>
          </div>
        </div>
        <div class="cta-strip">
          <h2>Ready to experience grading done right?</h2>
          <p>Submit your cards and see exactly what they're worth — with full transparency, every step of the way.</p>
          <router-link to="/submit" class="btn-primary">Submit Your Cards</router-link>
          <router-link to="/services" class="btn-secondary">View Services</router-link>
        </div>
      </div>
    </section>
  </main>

  <LegacySiteFooter />
</template>
