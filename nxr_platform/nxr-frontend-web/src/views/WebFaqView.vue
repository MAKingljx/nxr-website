<script setup lang="ts">
import { ref } from 'vue'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'

const openQuestion = ref('process-0')

const categories = [
  {
    title: 'The Grading Process',
    questions: [
      ['How does NXR\'s grading process work?', 'Every card goes through two independent stages. First, our AI system performs a high-resolution scan and assigns sub-scores for centering, edges, corners, and surface. Then a certified human grader independently reviews the AI\'s analysis. Both must agree before a final grade is issued.'],
      ['What sub-scores do you publish?', 'All four: Centering, Edges, Corners, and Surface. Every sub-score is published alongside the final grade on your digital certificate and on the slab itself.'],
      ['Do high-value cards get treated differently?', 'No. A $5 common and a $50,000 vintage card go through the exact same process, with the exact same criteria, reviewed by the same caliber of grader.'],
      ['What grading scale do you use?', 'We use the industry-standard 10-point scale, with half-point increments. Our full grading criteria are publicly documented.'],
      ['What if I disagree with my grade?', 'You can request a review within 30 days. We provide the full audit trail — AI scan data, grader notes, and the reasoning behind every sub-score.'],
    ],
  },
  {
    title: 'Submission & Turnaround',
    questions: [
      ['How do I submit my cards?', 'Fill out the submission form online, choose your service tier, and ship your cards to our facility. Use a tracked, insured shipping method.'],
      ['How long does grading take?', 'Turnaround times by tier: Economy 20–30 business days, Standard 10–15, Express 5–7, Premium 2–3.'],
      ['How should I package my cards for shipping?', 'Place each card in a penny sleeve, then a semi-rigid card saver or top loader. Wrap in bubble wrap and ship in a rigid box.'],
      ['Can I track my submission?', 'Yes. Once your cards are received, you get a tracking link showing received, scanning, grading, encapsulation, quality check, and shipped.'],
    ],
  },
  {
    title: 'Slabs & Verification',
    questions: [
      ['What are your slabs made of?', 'Our slabs are made from optically clear, UV-resistant polycarbonate. They are tamper-evident and include NFC plus QR verification.'],
      ['How does the NFC + QR verification work?', 'Tap the slab with any NFC-enabled phone, or scan the QR code with any camera app. You go directly to the card digital certificate.'],
      ['Can I verify a card before buying it?', 'Yes — that is exactly what the Verify page is for. Enter the certificate number or scan the QR code on the slab.'],
    ],
  },
  {
    title: 'Insurance & Security',
    questions: [
      ['Are my cards insured while at NXR?', 'Yes. All cards are fully insured while in our facility, from the moment we receive them to the moment they are shipped back.'],
      ['What happens if a card is damaged at your facility?', 'We take full responsibility. If a card is damaged while in our care, we compensate you for fair market value based on pre-submission condition.'],
    ],
  },
]

function questionId(categoryIndex: number, questionIndex: number) {
  return `${categoryIndex}-${questionIndex}`
}

function toggleQuestion(id: string) {
  openQuestion.value = openQuestion.value === id ? '' : id
}
</script>

<template>
  <LegacySiteNav active="faq" />

  <main>
    <div class="page-hero">
      <div class="page-hero-inner">
        <div class="section-tag">FAQ</div>
        <h1>Questions.<br />Straight answers.</h1>
        <p>No corporate speak. No runaround. If you have a question that isn't answered here, email us and we'll respond within 24 hours.</p>
      </div>
    </div>

    <section class="section">
      <div class="section-inner faq-width">
        <div v-for="(category, categoryIndex) in categories" :key="category.title" class="faq-category">
          <div class="faq-category-title">{{ category.title }}</div>
          <div
            v-for="([question, answer], questionIndex) in category.questions"
            :key="question"
            class="faq-item"
            :class="{ open: openQuestion === questionId(categoryIndex, questionIndex) }"
          >
            <button class="faq-q" type="button" @click="toggleQuestion(questionId(categoryIndex, questionIndex))">
              {{ question }} <span class="faq-icon"></span>
            </button>
            <div class="faq-a"><p>{{ answer }}</p></div>
          </div>
        </div>

        <div class="cta-strip">
          <h2>Still have questions?</h2>
          <p>Email us at <strong style="color: var(--text)">support@nxrgrading.com</strong> — we respond within 24 hours, every day.</p>
          <router-link to="/submit" class="btn-primary">Submit Your Cards</router-link>
          <router-link to="/services" class="btn-secondary">View Services</router-link>
        </div>
      </div>
    </section>
  </main>

  <LegacySiteFooter />
</template>
