<script setup lang="ts">
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
</script>

<template>
  <LegacySiteNav active="home" />

  <main>
    <div class="page-hero" style="padding-top: 160px; padding-bottom: 100px">
      <div class="page-hero-inner">
        <div class="section-tag">Welcome to NXR Grading</div>
        <h1>Grading that doesn't<br />take itself too seriously.</h1>
        <p>
          We combine AI precision with human expertise — because sometimes you need a robot to spot the 0.1mm flaw,
          and a human to say "yeah, that's actually pretty cool."
        </p>
        <div class="hero-cta">
          <router-link to="/submit" class="btn-primary">Submit Your Cards</router-link>
          <router-link to="/verify" class="btn-secondary">Verify a Grade</router-link>
        </div>
      </div>
    </div>

    <section class="section">
      <div class="section-inner">
        <div class="section-tag">Our Approach</div>
        <h2 class="section-title">AI + Human = Better Grades</h2>
        <p class="section-copy">We don't trust robots alone. We don't trust humans alone. But together? That's the sweet spot.</p>
        <div class="steps">
          <div class="step">
            <div class="step-number">1</div>
            <h3>AI First Pass</h3>
            <p>
              Our AI scans your card with micron-level precision, analyzing centering, edges, corners, and surface.
              It's like having a microscope with a PhD.
            </p>
          </div>
          <div class="step">
            <div class="step-number">2</div>
            <h3>Human Review</h3>
            <p>
              A certified human grader independently reviews the AI's analysis. If they disagree, the card goes to a
              senior grader. No ego, just accuracy.
            </p>
          </div>
          <div class="step">
            <div class="step-number">3</div>
            <h3>Cross-Verification</h3>
            <p>
              Both must agree before a final grade is issued. If they don't, we document why. Transparency isn't a
              feature — it's the whole point.
            </p>
          </div>
        </div>
      </div>
    </section>

    <section class="section bg2">
      <div class="section-inner">
        <div class="section-tag">Why Choose NXR</div>
        <h2 class="section-title">We're not just another grading company</h2>
        <p class="section-copy">We're the anti-black-box grading service. Here's what that means for you.</p>
        <div class="features-grid">
          <div class="feature">
            <div class="feature-icon">🤖</div>
            <h3>No Black Box Grading</h3>
            <p>
              We publish all four sub-scores (centering, edges, corners, surface) with every grade. You'll know exactly
              how every point was earned or lost.
            </p>
          </div>
          <div class="feature">
            <div class="feature-icon">👥</div>
            <h3>No VIP Lanes</h3>
            <p>
              A $5 common and a $50,000 vintage card go through the exact same process. The tier only affects turnaround
              time — never the rigor of the grade.
            </p>
          </div>
          <div class="feature">
            <div class="feature-icon">📱</div>
            <h3>Verification That Works</h3>
            <p>
              NFC + QR dual verification built into every slab. Scan with any phone — no app required. Because
              verification shouldn't be a chore.
            </p>
          </div>
          <div class="feature">
            <div class="feature-icon">🔍</div>
            <h3>Full Audit Trail</h3>
            <p>
              Disagree with a grade? We'll show you the AI scan data, grader's notes, and reasoning behind every
              sub-score. We stand behind our work.
            </p>
          </div>
          <div class="feature">
            <div class="feature-icon">🛡️</div>
            <h3>Premium Protection</h3>
            <p>
              UV-resistant, tamper-evident slabs engineered for decades of protection. Your cards deserve better than
              flimsy plastic.
            </p>
          </div>
          <div class="feature">
            <div class="feature-icon">🌐</div>
            <h3>Permanent Digital Record</h3>
            <p>
              Every grade gets a permanent, publicly accessible digital certificate. No more lost paperwork or faded
              labels.
            </p>
          </div>
        </div>
      </div>
    </section>

    <section class="section">
      <div class="section-inner">
        <div class="section-tag">The NXR Difference</div>
        <h2 class="section-title">We're doing this differently</h2>
        <p class="section-copy" style="margin-bottom: 0">
          The grading industry has been stuck in the past. Here's how we're moving forward.
        </p>
        <div class="compare-wrap">
          <table class="compare-table">
            <thead>
              <tr>
                <th>Feature</th>
                <th>NXR Grading</th>
                <th>"The Other Guys"</th>
              </tr>
            </thead>
            <tbody>
              <tr class="highlight">
                <td>Sub-scores published</td>
                <td class="check">✓ Always, all 4</td>
                <td class="cross">"Proprietary information"</td>
              </tr>
              <tr>
                <td>AI + Human cross-verification</td>
                <td class="check">✓ Every single card</td>
                <td class="cross">Human-only (with human error)</td>
              </tr>
              <tr class="highlight">
                <td>Grading criteria</td>
                <td class="check">✓ Fully documented & public</td>
                <td class="cross">"Trust us, we're experts"</td>
              </tr>
              <tr>
                <td>Same standards for all</td>
                <td class="check">✓ No exceptions, no VIP lanes</td>
                <td class="cross">Pay more for "better" grades</td>
              </tr>
              <tr class="highlight">
                <td>Grade disputes</td>
                <td class="check">✓ Full transparency & audit trail</td>
                <td class="cross">"Our graders are always right"</td>
              </tr>
              <tr>
                <td>Verification</td>
                <td class="check">✓ NFC + QR, no app needed</td>
                <td class="cross">QR only (if you're lucky)</td>
              </tr>
              <tr class="highlight">
                <td>Digital certificates</td>
                <td class="check">✓ Free, permanent, public</td>
                <td class="cross">Paid add-on or not available</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="cta-strip">
          <h2>Ready for grading that makes sense?</h2>
          <p>No corporate speak. No runaround. Just honest grades backed by technology and transparency.</p>
          <router-link to="/submit" class="btn-primary">Submit Your Cards</router-link>
          <router-link to="/faq" class="btn-secondary">Read the FAQ</router-link>
        </div>
      </div>
    </section>
  </main>

  <LegacySiteFooter />
</template>
