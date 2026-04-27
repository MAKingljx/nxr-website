<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import AdminLayoutShell from '../components/AdminLayoutShell.vue'
import {
  createSubmission,
  fetchSubmission,
  fetchSubmissions,
  type CreateSubmissionPayload,
  type SubmissionDetail,
  type SubmissionListItem,
} from '../lib/api'

const rows = ref<SubmissionListItem[]>([])
const total = ref(0)
const selectedDetail = ref<SubmissionDetail | null>(null)
const selectedId = ref<number | null>(null)
const isLoading = ref(false)
const isCreateOpen = ref(false)
const isSubmitting = ref(false)
const filters = reactive({
  status: '',
  query: '',
})

const createForm = reactive<CreateSubmissionPayload>({
  certId: '',
  cardName: '',
  yearLabel: '2024',
  brandName: 'Pokemon',
  playerName: '',
  varietyName: '',
  setName: '',
  cardNumber: '',
  languageCode: 'EN',
  populationValue: 1,
  centeringScore: 9,
  edgesScore: 9,
  cornersScore: 9,
  surfaceScore: 9,
  entryNotes: '',
})

const statusOptions = ['pending', 'approved', 'published']

const summaryText = computed(() => `${total.value} submission${total.value === 1 ? '' : 's'}`)

async function loadRows() {
  isLoading.value = true

  try {
    const response = await fetchSubmissions({
      page: 1,
      pageSize: 12,
      status: filters.status || undefined,
      query: filters.query || undefined,
    })

    rows.value = response.items
    total.value = response.total

    if (!selectedId.value && response.items.length > 0) {
      await selectSubmission(response.items[0].id)
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Failed to load submissions')
  } finally {
    isLoading.value = false
  }
}

async function selectSubmission(submissionId: number) {
  selectedId.value = submissionId

  try {
    selectedDetail.value = await fetchSubmission(submissionId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Failed to load submission detail')
  }
}

async function submitCreate() {
  isSubmitting.value = true

  try {
    const created = await createSubmission(createForm)
    ElMessage.success(`Created ${created.certId}`)
    isCreateOpen.value = false
    Object.assign(createForm, {
      certId: '',
      cardName: '',
      yearLabel: '2024',
      brandName: 'Pokemon',
      playerName: '',
      varietyName: '',
      setName: '',
      cardNumber: '',
      languageCode: 'EN',
      populationValue: 1,
      centeringScore: 9,
      edgesScore: 9,
      cornersScore: 9,
      surfaceScore: 9,
      entryNotes: '',
    })
    await loadRows()
    await selectSubmission(created.id)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Failed to create submission')
  } finally {
    isSubmitting.value = false
  }
}

onMounted(() => {
  void loadRows()
})
</script>

<template>
  <AdminLayoutShell
    section-label="Submission Module"
    title="Entries list and intake"
    description="This first slice supports real list filtering, record inspection, and creation of new pending submissions."
  >
    <section class="toolbar">
      <div class="filter-row">
        <input v-model="filters.query" type="text" placeholder="Search cert ID, card name, set" @keyup.enter="loadRows" />
        <select v-model="filters.status" @change="loadRows">
          <option value="">All statuses</option>
          <option v-for="status in statusOptions" :key="status" :value="status">{{ status }}</option>
        </select>
        <button type="button" @click="loadRows">Apply</button>
      </div>

      <div class="toolbar-actions">
        <span>{{ summaryText }}</span>
        <button type="button" class="primary-button" @click="isCreateOpen = true">New Submission</button>
      </div>
    </section>

    <section class="entries-layout">
      <div class="list-panel">
        <article
          v-for="row in rows"
          :key="row.id"
          class="list-row"
          :class="{ active: selectedId === row.id }"
          @click="selectSubmission(row.id)"
        >
          <div>
            <strong>{{ row.certId }}</strong>
            <h3>{{ row.cardName }}</h3>
            <p>{{ row.brandName }} · {{ row.yearLabel }} · {{ row.languageCode }}</p>
          </div>
          <div class="row-meta">
            <span class="status-pill">{{ row.statusCode }}</span>
            <strong>{{ row.finalGradeValue }} · {{ row.finalGradeLabel }}</strong>
          </div>
        </article>

        <p v-if="!rows.length && !isLoading" class="empty-state">No submissions matched the current filter.</p>
      </div>

      <div class="detail-panel">
        <template v-if="selectedDetail">
          <p class="eyebrow">Submission Detail</p>
          <h2>{{ selectedDetail.certId }}</h2>
          <p class="detail-lead">{{ selectedDetail.cardName }}</p>

          <div class="detail-grid">
            <article>
              <span>Brand</span>
              <strong>{{ selectedDetail.brandName }}</strong>
            </article>
            <article>
              <span>Set</span>
              <strong>{{ selectedDetail.setName }}</strong>
            </article>
            <article>
              <span>Card Number</span>
              <strong>{{ selectedDetail.cardNumber }}</strong>
            </article>
            <article>
              <span>Status</span>
              <strong>{{ selectedDetail.statusCode }}</strong>
            </article>
          </div>

          <div class="score-grid">
            <article>
              <span>Centering</span>
              <strong>{{ selectedDetail.centeringScore }}</strong>
            </article>
            <article>
              <span>Edges</span>
              <strong>{{ selectedDetail.edgesScore }}</strong>
            </article>
            <article>
              <span>Corners</span>
              <strong>{{ selectedDetail.cornersScore }}</strong>
            </article>
            <article>
              <span>Surface</span>
              <strong>{{ selectedDetail.surfaceScore }}</strong>
            </article>
          </div>

          <div class="notes-panel">
            <h3>Notes</h3>
            <p>{{ selectedDetail.entryNotes || selectedDetail.decisionNotes }}</p>
          </div>

          <div v-if="selectedDetail.media.length" class="media-grid">
            <article v-for="mediaItem in selectedDetail.media" :key="`${mediaItem.mediaStageCode}-${mediaItem.mediaSideCode}`">
              <span>{{ mediaItem.mediaSideCode }} · {{ mediaItem.mediaStageCode }}</span>
              <img :src="mediaItem.publicUrl" :alt="mediaItem.mediaSideCode" />
            </article>
          </div>
        </template>

        <p v-else class="empty-state">Select a submission to inspect full grading data.</p>
      </div>
    </section>

    <div v-if="isCreateOpen" class="modal-backdrop" @click.self="isCreateOpen = false">
      <section class="modal-panel">
        <div class="modal-head">
          <div>
            <p class="eyebrow">New Submission</p>
            <h3>Create a pending intake record</h3>
          </div>
          <button type="button" class="ghost-button" @click="isCreateOpen = false">Close</button>
        </div>

        <div class="form-grid">
          <label><span>Cert ID</span><input v-model="createForm.certId" type="text" /></label>
          <label><span>Card Name</span><input v-model="createForm.cardName" type="text" /></label>
          <label><span>Year</span><input v-model="createForm.yearLabel" type="text" /></label>
          <label><span>Brand</span><input v-model="createForm.brandName" type="text" /></label>
          <label><span>Player</span><input v-model="createForm.playerName" type="text" /></label>
          <label><span>Variety</span><input v-model="createForm.varietyName" type="text" /></label>
          <label><span>Set Name</span><input v-model="createForm.setName" type="text" /></label>
          <label><span>Card Number</span><input v-model="createForm.cardNumber" type="text" /></label>
          <label><span>Language</span><input v-model="createForm.languageCode" type="text" /></label>
          <label><span>Population</span><input v-model.number="createForm.populationValue" type="number" min="1" /></label>
          <label><span>Centering</span><input v-model.number="createForm.centeringScore" type="number" min="1" max="10" step="0.1" /></label>
          <label><span>Edges</span><input v-model.number="createForm.edgesScore" type="number" min="1" max="10" step="0.1" /></label>
          <label><span>Corners</span><input v-model.number="createForm.cornersScore" type="number" min="1" max="10" step="0.1" /></label>
          <label><span>Surface</span><input v-model.number="createForm.surfaceScore" type="number" min="1" max="10" step="0.1" /></label>
          <label class="full-width"><span>Entry Notes</span><textarea v-model="createForm.entryNotes" rows="4" /></label>
        </div>

        <button type="button" class="primary-button" :disabled="isSubmitting" @click="submitCreate">
          {{ isSubmitting ? 'Creating...' : 'Create Submission' }}
        </button>
      </section>
    </div>
  </AdminLayoutShell>
</template>

<style scoped>
.toolbar,
.list-panel,
.detail-panel,
.modal-panel {
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 18px 42px rgba(20, 32, 51, 0.08);
}

.toolbar {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  padding: 18px 20px;
}

.filter-row,
.toolbar-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

input,
select,
textarea,
button {
  font: inherit;
}

.filter-row input,
.filter-row select,
.filter-row button,
.primary-button,
.ghost-button {
  height: 46px;
  border-radius: 14px;
  border: 1px solid rgba(20, 32, 51, 0.12);
}

.filter-row input,
.filter-row select,
textarea {
  padding: 0 14px;
}

.filter-row input {
  width: 320px;
}

.filter-row button,
.primary-button {
  padding: 0 16px;
  background: #143f9f;
  color: #fff;
  cursor: pointer;
}

.entries-layout {
  display: grid;
  grid-template-columns: 1.05fr 1fr;
  gap: 18px;
  margin-top: 18px;
}

.list-panel,
.detail-panel {
  padding: 18px;
}

.list-row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 14px;
  align-items: center;
  padding: 16px;
  border-radius: 18px;
  background: #f5f8fd;
  cursor: pointer;
}

.list-row + .list-row {
  margin-top: 12px;
}

.list-row.active {
  outline: 2px solid rgba(20, 63, 159, 0.22);
}

.list-row h3,
.detail-panel h2,
.modal-head h3,
.notes-panel h3 {
  margin: 0;
}

.list-row p,
.detail-lead,
.empty-state,
.notes-panel p {
  margin: 8px 0 0;
  color: #5a6a80;
}

.row-meta {
  text-align: right;
}

.status-pill {
  display: inline-flex;
  margin-bottom: 8px;
  padding: 8px 10px;
  border-radius: 999px;
  background: rgba(20, 63, 159, 0.1);
  font-size: 0.85rem;
  font-weight: 700;
}

.eyebrow {
  margin: 0 0 10px;
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  font-weight: 700;
  color: #2656c5;
}

.detail-grid,
.score-grid,
.media-grid,
.form-grid {
  display: grid;
  gap: 12px;
}

.detail-grid,
.score-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-top: 18px;
}

.detail-grid article,
.score-grid article,
.notes-panel,
.media-grid article {
  padding: 16px;
  border-radius: 18px;
  background: #f5f8fd;
}

.detail-grid span,
.score-grid span,
.media-grid span {
  display: block;
  color: #5a6a80;
}

.detail-grid strong,
.score-grid strong {
  display: block;
  margin-top: 8px;
}

.notes-panel,
.media-grid {
  margin-top: 18px;
}

.media-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.media-grid img {
  width: 100%;
  margin-top: 12px;
  border-radius: 14px;
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(17, 21, 31, 0.35);
}

.modal-panel {
  width: min(920px, 100%);
  padding: 22px;
}

.modal-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
}

.ghost-button {
  padding: 0 16px;
  background: #fff;
  cursor: pointer;
}

.form-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-top: 18px;
}

label span {
  display: block;
  margin-bottom: 8px;
  font-size: 0.92rem;
  font-weight: 600;
}

.form-grid input,
textarea {
  width: 100%;
  border-radius: 14px;
  border: 1px solid rgba(20, 32, 51, 0.12);
}

.form-grid input {
  height: 46px;
}

textarea {
  padding: 12px 14px;
}

.full-width {
  grid-column: 1 / -1;
}

.primary-button {
  margin-top: 18px;
}

@media (max-width: 1100px) {
  .entries-layout {
    grid-template-columns: 1fr;
  }

  .detail-grid,
  .score-grid,
  .media-grid,
  .form-grid {
    grid-template-columns: 1fr;
  }

  .toolbar,
  .filter-row,
  .toolbar-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .filter-row input {
    width: 100%;
  }
}
</style>
