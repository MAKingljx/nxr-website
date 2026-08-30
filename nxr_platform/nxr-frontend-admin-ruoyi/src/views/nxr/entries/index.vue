<template>
  <main class="nxr-workspace entry-workspace">
    <nxr-page-header
      :kicker="t('entries.kicker')"
      :title="t('entries.title')"
      :summary="entrySummary"
    >
      <template #actions>
        <el-button type="primary" plain icon="Plus" v-hasPermi="['nxr:entry:add']" @click="handleAdd">{{ t('entries.newEntry') }}</el-button>
        <el-button
          type="success"
          plain
          icon="Check"
          :disabled="!selectedIds.length"
          v-hasPermi="['nxr:entry:approve']"
          @click="handleBatchApprove"
        >{{ t('entries.batchApprove', { count: selectedIds.length }) }}</el-button>
      </template>
    </nxr-page-header>

    <el-card shadow="never" class="filter-card mb8">
      <template #header><span>{{ t('entries.filtersTitle') }}</span></template>
      <el-form :model="queryParams" ref="queryRef" :inline="true" label-position="top">
        <el-form-item :label="t('entries.certId')" prop="certId">
          <el-input v-model="queryParams.certId" :placeholder="t('entries.certIdPlaceholder')" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item :label="t('entries.cardName')" prop="cardName">
          <el-input v-model="queryParams.cardName" :placeholder="t('entries.cardNamePlaceholder')" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item :label="t('entries.category')" prop="cardCategory">
          <el-select v-model="queryParams.cardCategory" :placeholder="t('entries.allCategories')" clearable>
            <el-option v-for="d in nxr_card_category" :key="d.value" :label="d.label" :value="d.value" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('entries.productType')" prop="productType">
          <el-select v-model="queryParams.productType" :placeholder="t('entries.allProductTypes')" clearable>
            <el-option v-for="d in productTypeOptions" :key="d.value" :label="d.label" :value="d.value" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('entries.brand')" prop="brand">
          <el-select v-model="queryParams.brand" :placeholder="t('entries.allBrands')" clearable filterable>
            <el-option v-for="b in brandOptions" :key="b.id" :label="b.name" :value="b.name" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('entries.finalGrade')" prop="finalGrade">
          <el-select v-model="queryParams.finalGrade" :placeholder="t('entries.allGrades')" clearable>
            <el-option v-for="grade in gradeFilterOptions" :key="grade" :label="grade" :value="grade" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('entries.setName')" prop="setName">
          <el-input v-model="queryParams.setName" :placeholder="t('entries.searchSetName')" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item :label="t('entries.language')" prop="language">
          <el-select v-model="queryParams.language" :placeholder="t('entries.allLanguages')" clearable>
            <el-option v-for="d in nxr_language" :key="d.value" :label="d.label" :value="d.value" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('entries.enteredBy')" prop="enteredBy">
          <el-input v-model="queryParams.enteredBy" :placeholder="t('entries.searchUser')" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item :label="t('entries.status')" prop="status">
          <el-select v-model="queryParams.status" :placeholder="t('entries.allStatuses')" clearable>
            <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('entries.sortBy')" prop="sortBy">
          <el-select v-model="queryParams.sortBy">
            <el-option v-for="option in sortOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('entries.order')" prop="sortOrder">
          <el-select v-model="queryParams.sortOrder">
            <el-option :label="t('entries.descending')" value="desc" />
            <el-option :label="t('entries.ascending')" value="asc" />
          </el-select>
        </el-form-item>
        <el-form-item class="filter-actions">
          <el-button type="primary" icon="Search" @click="handleQuery">{{ t('entries.applyFilters') }}</el-button>
          <el-button icon="Refresh" @click="resetQuery">{{ t('entries.clearFilters') }}</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-table v-loading="loading" :data="rows" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="50" align="center" :selectable="isRowSelectable" />
      <el-table-column :label="t('entries.id')" prop="id" width="72" align="center" />
      <el-table-column :label="t('entries.certId')" prop="certId" width="140" show-overflow-tooltip />
      <el-table-column :label="t('entries.cardName')" prop="cardName" min-width="160" show-overflow-tooltip />
      <el-table-column :label="t('entries.productType')" width="130">
        <template #default="scope">{{ productTypeText(scope.row.productType) }}</template>
      </el-table-column>
      <el-table-column :label="t('entries.category')" width="120">
        <template #default="scope">{{ cardCategoryText(scope.row.cardCategory) }}</template>
      </el-table-column>
      <el-table-column :label="t('entries.brand')" prop="brandName" width="120" show-overflow-tooltip />
      <el-table-column :label="t('entries.finalGrade')" width="160" show-overflow-tooltip>
        <template #default="scope">{{ gradeText(scope.row) }}</template>
      </el-table-column>
      <el-table-column :label="t('entries.status')" width="100" align="center">
        <template #default="scope">
          <nxr-status-tag :code="scope.row.statusCode" domain="entries" />
        </template>
      </el-table-column>
      <el-table-column :label="t('entries.entryDate')" width="170">
        <template #default="scope">{{ formatDateTime(scope.row.createdAt) }}</template>
      </el-table-column>
      <el-table-column :label="t('entries.enteredBy')" prop="enteredBy" width="120" show-overflow-tooltip />
      <el-table-column :label="t('common.actions')" width="220" align="center" class-name="small-padding fixed-width">
        <template #default="scope">
          <el-button link type="primary" icon="View" @click="handleDetail(scope.row)">{{ t('common.view') }}</el-button>
          <el-button link type="primary" icon="Edit" v-hasPermi="['nxr:entry:edit']" @click="handleEdit(scope.row)">{{ t('common.edit') }}</el-button>
          <el-button
            v-if="scope.row.statusCode === 'pending' || scope.row.statusCode === 'review'"
            link
            type="success"
            icon="Check"
            v-hasPermi="['nxr:entry:approve']"
            @click="handleApprove(scope.row)"
          >{{ t('common.approve') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination
      v-show="total > 0"
      :total="total"
      v-model:page="queryParams.page"
      v-model:limit="queryParams.pageSize"
      @pagination="getList"
    />

    <el-dialog :title="t('entries.details')" v-model="detailOpen" width="820px" append-to-body>
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item :label="t('entries.certId')">{{ detail.certId }}</el-descriptions-item>
          <el-descriptions-item :label="t('entries.status')">
            <nxr-status-tag :code="detail.statusCode" domain="entries" />
          </el-descriptions-item>
          <el-descriptions-item :label="t('entries.productType')">{{ productTypeText(detail.productType) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.productType === 'vintage_product'" :label="t('entries.vintageClassification')">
            {{ vintageClassificationText(detail.vintageClassification) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.productType === 'merch_product'" :label="t('entries.merchDescription')" :span="2">
            {{ detail.merchDescription || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('entries.cardCategory')">{{ cardCategoryText(detail.cardCategory) }}</el-descriptions-item>
          <el-descriptions-item :label="t('entries.population')">{{ detail.populationValue }}</el-descriptions-item>
          <template v-if="detail.cardCategory === 'movie_film'">
            <el-descriptions-item :label="t('entries.movieName')">{{ detail.movieName || detail.cardName }}</el-descriptions-item>
            <el-descriptions-item :label="t('entries.releaseYear')">{{ detail.releaseYear || detail.yearLabel }}</el-descriptions-item>
            <el-descriptions-item :label="t('entries.productionCompany')">{{ detail.productionCompany || detail.brandName }}</el-descriptions-item>
            <el-descriptions-item :label="t('entries.filmType')">{{ detail.filmType || detail.varietyName }}</el-descriptions-item>
          </template>
          <template v-else>
            <el-descriptions-item :label="t('entries.cardName')">{{ detail.cardName }}</el-descriptions-item>
            <el-descriptions-item :label="t('entries.brand')">{{ detail.brandName }}</el-descriptions-item>
            <el-descriptions-item :label="t('entries.year')">{{ detail.yearLabel }}</el-descriptions-item>
            <el-descriptions-item :label="t('entries.varietyType')">{{ detail.varietyName || '-' }}</el-descriptions-item>
            <el-descriptions-item :label="t('entries.setName')">{{ detail.setName }}</el-descriptions-item>
            <el-descriptions-item :label="t('entries.cardNumber')">{{ detail.cardNumber }}</el-descriptions-item>
            <el-descriptions-item :label="t('entries.language')">{{ languageText(detail.languageCode) }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.cardCategory === 'sports_card'" :label="t('entries.sportsType')">{{ sportsTypeText(detail.sportsType) }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.cardCategory === 'celebrity_card'" :label="t('entries.groupName')">{{ detail.groupName }}</el-descriptions-item>
          </template>
          <template v-if="detail.productType === 'graded_card'">
            <el-descriptions-item :label="t('entries.centering')">{{ detail.centeringScore }}</el-descriptions-item>
            <el-descriptions-item :label="t('entries.edges')">{{ detail.edgesScore }}</el-descriptions-item>
            <el-descriptions-item :label="t('entries.corners')">{{ detail.cornersScore }}</el-descriptions-item>
            <el-descriptions-item :label="t('entries.surface')">{{ detail.surfaceScore }}</el-descriptions-item>
            <el-descriptions-item :label="t('entries.finalGradeCalculation')" :span="2">{{ gradeText(detail) }}</el-descriptions-item>
          </template>
          <el-descriptions-item :label="t('entries.entryNotes')" :span="2">{{ detail.entryNotes || detail.decisionNotes || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="t('entries.enteredBy')">{{ detail.enteredBy || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="t('entries.entryDate')">{{ formatDateTime(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item :label="t('entries.updatedAt')">{{ formatDateTime(detail.updatedAt) }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="detail.media && detail.media.length" class="media-grid">
          <figure v-for="m in detail.media" :key="m.mediaStageCode + '-' + m.mediaSideCode">
            <img :src="m.publicUrl" :alt="m.mediaSideCode" loading="lazy" />
            <figcaption>{{ m.mediaSideCode }} · {{ m.mediaStageCode }}</figcaption>
          </figure>
        </div>
      </template>
      <template #footer>
        <el-button
          v-if="detail && (detail.statusCode === 'pending' || detail.statusCode === 'review')"
          type="success"
          :loading="approving"
          v-hasPermi="['nxr:entry:approve']"
          @click="handleApproveDetail"
        >{{ t('common.approve') }}</el-button>
        <el-button @click="detailOpen = false">{{ t('common.close') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog :title="formTitle" v-model="formOpen" width="860px" append-to-body @close="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="150px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item :label="t('entries.productType')" prop="productType">
              <el-select v-model="form.productType" style="width: 100%">
                <el-option v-for="d in productTypeOptions" :key="d.value" :label="d.label" :value="d.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="t('entries.certificateId')" prop="certId">
              <el-input v-model="form.certId" readonly inputmode="numeric" :placeholder="t('entries.certificateIdPlaceholder')">
                <template #append v-if="formMode === 'create'">
                  <el-button @click="fillGeneratedCertId">{{ t('common.generate') }}</el-button>
                </template>
              </el-input>
            </el-form-item>
          </el-col>
        </el-row>

        <el-row v-if="isMerchForm" :gutter="16">
          <el-col :span="24">
            <el-form-item :label="t('entries.merchDescription')">
              <el-input
                v-model="form.merchDescription"
                type="textarea"
                :rows="4"
                maxlength="4000"
                show-word-limit
                :placeholder="t('entries.merchPlaceholder')"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row v-if="isGradedForm || isVintageForm" :gutter="16">
          <el-col v-if="isGradedForm" :span="12">
            <el-form-item :label="t('entries.cardCategory')" prop="cardCategory">
              <el-select v-model="form.cardCategory" style="width: 100%">
                <el-option v-for="d in nxr_card_category" :key="d.value" :label="d.label" :value="d.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col v-if="isVintageForm" :span="12">
            <el-form-item :label="t('entries.vintageClassification')" prop="vintageClassification">
              <el-select v-model="form.vintageClassification" :placeholder="t('entries.selectClassification')" style="width: 100%">
                <el-option v-for="d in nxr_vintage_classification" :key="d.value" :label="d.label" :value="d.value" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16" v-if="isMovieForm">
          <el-col :span="12"><el-form-item :label="t('entries.movieName')" prop="movieName"><el-input v-model="form.movieName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item :label="t('entries.releaseYear')" prop="releaseYear"><el-input v-model="form.releaseYear" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item :label="t('entries.productionCompany')" prop="productionCompany"><el-input v-model="form.productionCompany" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item :label="t('entries.filmType')" prop="filmType"><el-input v-model="form.filmType" /></el-form-item></el-col>
        </el-row>

        <el-row :gutter="16" v-else>
          <el-col :span="12"><el-form-item :label="t('entries.cardName')" prop="cardName"><el-input v-model="form.cardName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item :label="t('entries.year')"><el-input v-model="form.yearLabel" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item :label="t('entries.brand')" prop="brandName">
              <el-select v-model="form.brandName" filterable allow-create default-first-option style="width: 100%">
                <el-option v-for="b in brandOptions" :key="b.id" :label="b.name" :value="b.name" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12"><el-form-item :label="t('entries.varietyType')"><el-input v-model="form.varietyName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item :label="t('entries.setName')" prop="setName"><el-input v-model="form.setName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item :label="t('entries.cardNumber')" prop="cardNumber"><el-input v-model="form.cardNumber" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item :label="t('entries.language')" prop="languageCode">
              <el-select v-model="form.languageCode" style="width: 100%">
                <el-option v-for="d in nxr_language" :key="d.value" :label="d.label" :value="d.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12" v-if="isSportsForm">
            <el-form-item :label="t('entries.sportsType')" prop="sportsType">
              <el-select v-model="form.sportsType" filterable allow-create default-first-option style="width: 100%">
                <el-option v-for="d in nxr_sports_type" :key="d.value" :label="d.label" :value="d.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12" v-if="isCelebrityForm">
            <el-form-item :label="t('entries.groupName')" prop="groupName"><el-input v-model="form.groupName" /></el-form-item>
          </el-col>
        </el-row>

        <template v-if="isGradedForm">
          <el-divider content-position="left">{{ t('entries.gradingDetails') }}</el-divider>
          <el-row :gutter="16">
            <el-col :span="6"><el-form-item :label="t('entries.centering')" prop="centeringScore" label-width="72px"><el-input-number v-model="form.centeringScore" :min="1" :max="10" :step="0.1" :precision="1" controls-position="right" style="width: 100%" /></el-form-item></el-col>
            <el-col :span="6"><el-form-item :label="t('entries.edges')" prop="edgesScore" label-width="54px"><el-input-number v-model="form.edgesScore" :min="1" :max="10" :step="0.1" :precision="1" controls-position="right" style="width: 100%" /></el-form-item></el-col>
            <el-col :span="6"><el-form-item :label="t('entries.corners')" prop="cornersScore" label-width="64px"><el-input-number v-model="form.cornersScore" :min="1" :max="10" :step="0.1" :precision="1" controls-position="right" style="width: 100%" /></el-form-item></el-col>
            <el-col :span="6"><el-form-item :label="t('entries.surface')" prop="surfaceScore" label-width="60px"><el-input-number v-model="form.surfaceScore" :min="1" :max="10" :step="0.1" :precision="1" controls-position="right" style="width: 100%" /></el-form-item></el-col>
          </el-row>
          <el-row :gutter="16">
            <el-col :span="6">
              <el-form-item :label="t('entries.finalGrade')" label-width="88px">
                <el-input v-model="form.finalGradeValue" readonly />
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <el-form-item :label="t('entries.gradeText')" label-width="84px">
                <el-input v-model="form.finalGradeLabel" readonly />
              </el-form-item>
            </el-col>
          </el-row>
        </template>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item :label="t('entries.population')">
              <el-input v-model.number="form.populationValue" readonly>
                <template #append>{{ t('entries.autoCalculated') }}</template>
              </el-input>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item :label="t('entries.entryNotes')">
          <el-input v-model="form.entryNotes" type="textarea" :rows="3" />
        </el-form-item>

        <el-alert
          v-if="form.finalGradeValue || calculationLabel || populationLabel"
          type="info"
          :closable="false"
          class="mb8"
        >
          <div v-if="form.finalGradeValue"><strong>{{ t('entries.finalGradeCalculation') }}</strong> {{ form.finalGradeValue }} · {{ form.finalGradeLabel }}</div>
          <div v-if="calculationLabel">{{ calculationLabel }}</div>
          <div v-if="populationLabel">{{ populationLabel }}</div>
        </el-alert>

        <el-divider content-position="left">{{ t('entries.imageUploads') }}</el-divider>
        <el-row v-if="canImportMedia" :gutter="16">
          <el-col :span="12">
            <el-form-item :label="t('entries.frontImage')">
              <input ref="frontImageInput" class="image-file-input" type="file" accept="image/webp,image/jpeg,image/png" @change="handleImageSelection('front', $event)" />
              <small v-if="frontImageFile" class="selected-image-name">{{ frontImageFile.name }}</small>
              <figure v-else-if="existingFrontMedia" class="existing-media-preview">
                <img :src="existingFrontMedia.publicUrl" :alt="t('entries.frontImage')" />
                <figcaption>{{ t('entries.currentImage') }} · {{ mediaStageText(existingFrontMedia.mediaStageCode) }}</figcaption>
              </figure>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="t('entries.backImage')">
              <input ref="backImageInput" class="image-file-input" type="file" accept="image/webp,image/jpeg,image/png" @change="handleImageSelection('back', $event)" />
              <small v-if="backImageFile" class="selected-image-name">{{ backImageFile.name }}</small>
              <figure v-else-if="existingBackMedia" class="existing-media-preview">
                <img :src="existingBackMedia.publicUrl" :alt="t('entries.backImage')" />
                <figcaption>{{ t('entries.currentImage') }} · {{ mediaStageText(existingBackMedia.mediaStageCode) }}</figcaption>
              </figure>
            </el-form-item>
          </el-col>
        </el-row>
        <el-alert v-else type="info" :closable="false" :title="t('entries.mediaPermissionMissing')" />
      </el-form>
      <template #footer>
        <el-button :loading="calculating" @click="calculateFormPreview">
          {{ isGradedForm ? t('entries.calculateGradePop') : t('entries.calculatePop') }}
        </el-button>
        <el-button v-if="!isMovieForm" :disabled="!canMatchCard" :loading="matching" @click="applyCardMatch">{{ t('entries.matchCard') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">{{ t('common.save') }}</el-button>
        <el-button @click="formOpen = false">{{ t('common.cancel') }}</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<script setup name="NxrEntries">
import { useI18n } from 'vue-i18n'
import {
  listSubmissions,
  getSubmission,
  createSubmission,
  updateSubmission,
  approveSubmission,
  batchApproveSubmissions,
  generateCertId,
  calculateGrade,
  calculatePopulation,
  matchCard
} from '@/api/nxr/entries'
import { fetchBrandOptions } from '@/api/nxr/brands'
import { importSubmissionMedia } from '@/api/nxr/media'
import auth from '@/plugins/auth'
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import NxrStatusTag from '@/components/NxrWorkspace/StatusTag.vue'

const { proxy } = getCurrentInstance()
const route = useRoute()
const { t } = useI18n()
const { nxr_product_type, nxr_vintage_classification, nxr_card_category, nxr_language, nxr_sports_type } = proxy.useDict(
  'nxr_product_type',
  'nxr_vintage_classification',
  'nxr_card_category',
  'nxr_language',
  'nxr_sports_type'
)

const rows = ref([])
const total = ref(0)
const loading = ref(true)
const selectedIds = ref([])
const detailOpen = ref(false)
const detail = ref(null)
const formOpen = ref(false)
const formMode = ref('create')
const editingId = ref(null)
const submitting = ref(false)
const approving = ref(false)
const calculating = ref(false)
const matching = ref(false)
const gradePreview = ref(null)
const calculationLabel = ref('')
const populationLabel = ref('')
const brandOptions = ref([])
const canListEntries = auth.hasPermi('nxr:entry:list')
const canImportMedia = auth.hasPermi('nxr:media:import')
const frontImageFile = ref(null)
const backImageFile = ref(null)
const frontImageInput = ref(null)
const backImageInput = ref(null)
const existingMedia = ref([])
let calculationTimer = null

function validateCertId(_rule, value, callback) {
  const certId = String(value || '').trim()
  if (!certId) {
    callback(new Error(t('entries.certRequired')))
    return
  }
  if (formMode.value === 'create' && !/^[1-9]\d{9}$/.test(certId)) {
    callback(new Error(t('entries.certFormat')))
    return
  }
  callback()
}

function validateVintageClassification(_rule, value, callback) {
  if (form.value.productType === 'vintage_product' && !String(value || '').trim()) {
    callback(new Error(t('entries.vintageRequired')))
    return
  }
  callback()
}

function conditionalRequired(predicate, message) {
  return (_rule, value, callback) => {
    if (predicate() && !String(value ?? '').trim()) {
      callback(new Error(message))
      return
    }
    callback()
  }
}

function validateScore(label) {
  return (_rule, value, callback) => {
    if (!isGradedForm.value) {
      callback()
      return
    }
    const score = Number(value)
    if (!Number.isFinite(score) || score < 1 || score > 10) {
      callback(new Error(t('entries.scoreRange', { label })))
      return
    }
    callback()
  }
}

const statusOptions = [
  { value: 'pending', label: t('entries.pending') },
  { value: 'approved', label: t('entries.approved') },
  { value: 'published', label: t('entries.published') }
]
const gradeFilterOptions = ['8', '8.5', '9', '9.5', '10', 'Pristine 10']
const sortOptions = [
  { value: 'entry_date', label: t('entries.entryDate') },
  { value: 'cert_id', label: t('entries.certId') },
  { value: 'card_name', label: t('entries.cardName') },
  { value: 'product_type', label: t('entries.productType') },
  { value: 'card_category', label: t('entries.category') },
  { value: 'brand', label: t('entries.brand') },
  { value: 'final_grade', label: t('entries.finalGrade') },
  { value: 'set_name', label: t('entries.setName') },
  { value: 'language', label: t('entries.language') }
]

const data = reactive({
  form: createDefaultForm(),
  queryParams: {
    page: 1,
    pageSize: 10,
    status: undefined,
    query: undefined,
    certId: undefined,
    cardName: undefined,
    cardCategory: undefined,
    productType: undefined,
    brand: undefined,
    finalGrade: undefined,
    setName: undefined,
    language: undefined,
    enteredBy: undefined,
    sortBy: 'entry_date',
    sortOrder: 'desc'
  },
  rules: {
    certId: [{ validator: validateCertId, trigger: 'blur' }],
    productType: [{ required: true, message: t('entries.productTypeRequired'), trigger: 'change' }],
    cardCategory: [{ required: true, message: t('entries.categoryRequired'), trigger: 'change' }],
    vintageClassification: [{ validator: validateVintageClassification, trigger: 'change' }],
    cardName: [{ validator: conditionalRequired(() => !isMovieForm.value, t('entries.cardNameRequired')), trigger: 'blur' }],
    brandName: [{ validator: conditionalRequired(() => !isMovieForm.value, t('entries.brandRequired')), trigger: 'change' }],
    setName: [{ validator: conditionalRequired(() => !isMovieForm.value, t('entries.setNameRequired')), trigger: 'blur' }],
    cardNumber: [{ validator: conditionalRequired(() => !isMovieForm.value, t('entries.cardNumberRequired')), trigger: 'blur' }],
    languageCode: [{ validator: conditionalRequired(() => !isMovieForm.value, t('entries.languageRequired')), trigger: 'change' }],
    movieName: [{ validator: conditionalRequired(() => isMovieForm.value, t('entries.movieNameRequired')), trigger: 'blur' }],
    releaseYear: [{ validator: conditionalRequired(() => isMovieForm.value, t('entries.releaseYearRequired')), trigger: 'blur' }],
    productionCompany: [{ validator: conditionalRequired(() => isMovieForm.value, t('entries.productionCompanyRequired')), trigger: 'blur' }],
    filmType: [{ validator: conditionalRequired(() => isMovieForm.value, t('entries.filmTypeRequired')), trigger: 'blur' }],
    sportsType: [{ validator: conditionalRequired(() => isSportsForm.value, t('entries.sportsTypeRequired')), trigger: 'change' }],
    groupName: [{ validator: conditionalRequired(() => isCelebrityForm.value, t('entries.groupNameRequired')), trigger: 'blur' }],
    centeringScore: [{ validator: validateScore(t('entries.centering')), trigger: 'change' }],
    edgesScore: [{ validator: validateScore(t('entries.edges')), trigger: 'change' }],
    cornersScore: [{ validator: validateScore(t('entries.corners')), trigger: 'change' }],
    surfaceScore: [{ validator: validateScore(t('entries.surface')), trigger: 'change' }]
  }
})

const { form, queryParams, rules } = toRefs(data)

const formTitle = computed(() => (formMode.value === 'create' ? t('entries.newEntry') : t('entries.editEntry')))
const entrySummary = computed(() =>
  selectedIds.value.length
    ? t('entries.selectedSummary', { count: total.value, selected: selectedIds.value.length })
    : t('entries.summary', { count: total.value })
)
const isGradedForm = computed(() => form.value.productType === 'graded_card')
const isMerchForm = computed(() => form.value.productType === 'merch_product')
const isVintageForm = computed(() => form.value.productType === 'vintage_product')
const isMovieForm = computed(() => form.value.cardCategory === 'movie_film')
const isSportsForm = computed(() => form.value.cardCategory === 'sports_card')
const isCelebrityForm = computed(() => form.value.cardCategory === 'celebrity_card')
const canMatchCard = computed(
  () => !isMovieForm.value && (form.value.setName || '').trim() && (form.value.cardNumber || '').trim()
)

function createDefaultForm() {
  return {
    certId: '',
    productType: 'graded_card',
    vintageClassification: '',
    merchDescription: '',
    cardCategory: 'trading_card',
    cardName: '',
    movieName: '',
    releaseYear: '',
    productionCompany: '',
    filmType: '',
    sportsType: '',
    groupName: '',
    yearLabel: '',
    brandName: '',
    playerName: '',
    varietyName: '',
    setName: '',
    cardNumber: '',
    languageCode: '',
    populationValue: 1,
    centeringScore: 1,
    edgesScore: 1,
    cornersScore: 1,
    surfaceScore: 1,
    finalGradeValue: '',
    finalGradeLabel: '',
    entryNotes: ''
  }
}

function normalizeProductType(productType) {
  if (productType === 'label_product') return 'merch_product'
  return productType === 'merch_product' || productType === 'vintage_product' ? productType : 'graded_card'
}

const productTypeOptions = computed(() => {
  const labels = new Map(
    (nxr_product_type.value || []).map((item) => [normalizeProductType(item.value), item.label])
  )
  return [
    { value: 'graded_card', label: labels.get('graded_card') || t('entries.gradedCard') },
    { value: 'merch_product', label: t('entries.merchProduct') },
    { value: 'vintage_product', label: labels.get('vintage_product') || t('entries.vintageCard') }
  ]
})

function productTypeText(productType) {
  const option = productTypeOptions.value.find((item) => item.value === normalizeProductType(productType))
  return option ? option.label : t('entries.gradedCard')
}

function dictionaryText(options, value) {
  const normalizedValue = String(value || '')
  return (options.value || []).find((item) => String(item.value) === normalizedValue)?.label || normalizedValue || '-'
}

function cardCategoryText(value) {
  return dictionaryText(nxr_card_category, value)
}

function languageText(value) {
  return dictionaryText(nxr_language, value)
}

function sportsTypeText(value) {
  return dictionaryText(nxr_sports_type, value)
}

function vintageClassificationText(value) {
  return dictionaryText(nxr_vintage_classification, value)
}

function currentMediaForSide(side) {
  const candidates = existingMedia.value.filter((item) => item.mediaSideCode === side && item.publicUrl)
  return candidates.find((item) => item.mediaStageCode === 'staged')
    || candidates.find((item) => item.mediaStageCode === 'published')
    || candidates[0]
    || null
}

const existingFrontMedia = computed(() => currentMediaForSide('front'))
const existingBackMedia = computed(() => currentMediaForSide('back'))

function mediaStageText(stage) {
  return stage === 'published' ? t('entries.publishedImage') : t('entries.stagedImage')
}

function gradeText(item) {
  const parts = [item.finalGradeValue, item.finalGradeLabel].filter(
    (value) => value !== null && value !== undefined && String(value).trim() !== ''
  )
  return parts.length ? parts.join(' · ') : '-'
}

function formatDateTime(value) {
  return value ? proxy.parseTime(value, '{y}-{m}-{d} {h}:{i}') : '-'
}

function isRowSelectable(row) {
  return row.statusCode === 'pending' || row.statusCode === 'review'
}

function getList() {
  if (!canListEntries) {
    rows.value = []
    total.value = 0
    loading.value = false
    return
  }
  loading.value = true
  listSubmissions(queryParams.value).then((res) => {
    rows.value = res.data.items
    total.value = res.data.total
    loading.value = false
  })
}

function handleQuery() {
  queryParams.value.page = 1
  getList()
}

function resetQuery() {
  proxy.resetForm('queryRef')
  queryParams.value.sortBy = 'entry_date'
  queryParams.value.sortOrder = 'desc'
  queryParams.value.status = menuStatus()
  handleQuery()
}

function routeQueryValue(key) {
  const value = route.query[key]
  return Array.isArray(value) ? value[0] : value
}

function menuStatus() {
  const status = routeQueryValue('status')
  return statusOptions.some((option) => option.value === status) ? status : undefined
}

function applyMenuRoute() {
  queryParams.value.page = 1
  queryParams.value.query = undefined
  queryParams.value.status = menuStatus()
  getList()

  if (routeQueryValue('mode') === 'create') {
    nextTick(() => {
      handleAdd()
    })
  } else if (formMode.value === 'create') {
    formOpen.value = false
  }
}

function handleSelectionChange(selection) {
  selectedIds.value = selection.map((item) => item.id)
}

function handleDetail(row) {
  getSubmission(row.id).then((res) => {
    detail.value = res.data
    detailOpen.value = true
  })
}

function handleAdd() {
  formMode.value = 'create'
  editingId.value = null
  Object.assign(form.value, createDefaultForm())
  clearPreview()
  clearSelectedImages()
  existingMedia.value = []
  formOpen.value = true
  generateCertId().then((res) => {
    form.value.certId = res.data.certId
  })
}

function handleEdit(row) {
  getSubmission(row.id).then((res) => {
    const d = res.data
    formMode.value = 'edit'
    editingId.value = d.id
    Object.assign(form.value, {
      certId: d.certId,
      productType: normalizeProductType(d.productType),
      vintageClassification: d.vintageClassification || '',
      merchDescription: d.merchDescription || '',
      cardCategory: d.cardCategory || 'trading_card',
      cardName: d.cardName || '',
      movieName: d.movieName || d.cardName || '',
      releaseYear: d.releaseYear || d.yearLabel || '',
      productionCompany: d.productionCompany || d.brandName || '',
      filmType: d.filmType || d.varietyName || '',
      sportsType: d.sportsType || '',
      groupName: d.groupName || '',
      yearLabel: d.yearLabel || '',
      brandName: d.brandName || '',
      playerName: d.playerName || '',
      varietyName: d.varietyName || '',
      setName: d.setName || '',
      cardNumber: d.cardNumber || '',
      languageCode: d.languageCode || 'EN',
      populationValue: d.populationValue || 1,
      centeringScore: d.centeringScore,
      edgesScore: d.edgesScore,
      cornersScore: d.cornersScore,
      surfaceScore: d.surfaceScore,
      finalGradeValue: d.finalGradeValue ?? '',
      finalGradeLabel: d.finalGradeLabel || '',
      entryNotes: d.entryNotes || ''
    })
    clearPreview()
    clearSelectedImages()
    existingMedia.value = d.media || []
    formOpen.value = true
  })
}

function clearPreview() {
  gradePreview.value = null
  calculationLabel.value = ''
  populationLabel.value = ''
}

function handleImageSelection(side, event) {
  const file = event.target.files?.[0] || null
  if (side === 'front') {
    frontImageFile.value = file
  } else {
    backImageFile.value = file
  }
}

function renamedImageFile(file, certId, sideCode) {
  if (!file) return null
  const originalExtension = file.name.split('.').pop()?.toLowerCase()
  const supportedExtensions = new Set(['webp', 'jpg', 'jpeg', 'png'])
  const contentTypeExtension = {
    'image/webp': 'webp',
    'image/jpeg': 'jpg',
    'image/png': 'png'
  }[file.type]
  const extension = supportedExtensions.has(originalExtension) ? originalExtension : contentTypeExtension
  if (!extension) return null
  return new File([file], `${certId}_${sideCode}.${extension}`, {
    type: file.type,
    lastModified: file.lastModified
  })
}

function prepareSelectedImages(certId) {
  return [
    renamedImageFile(frontImageFile.value, certId, 'A'),
    renamedImageFile(backImageFile.value, certId, 'B')
  ].filter(Boolean)
}

function clearSelectedImages() {
  frontImageFile.value = null
  backImageFile.value = null
  if (frontImageInput.value) frontImageInput.value.value = ''
  if (backImageInput.value) backImageInput.value.value = ''
}

function resetForm() {
  window.clearTimeout(calculationTimer)
  calculationTimer = null
  proxy.resetForm('formRef')
  clearSelectedImages()
  existingMedia.value = []
}

function fillGeneratedCertId() {
  generateCertId().then((res) => {
    form.value.certId = res.data.certId
    proxy.$modal.msgSuccess(t('entries.generatedCertId', { certId: res.data.certId }))
  })
}

function scorePayload() {
  return {
    centeringScore: Number(form.value.centeringScore),
    edgesScore: Number(form.value.edgesScore),
    cornersScore: Number(form.value.cornersScore),
    surfaceScore: Number(form.value.surfaceScore)
  }
}

async function refreshCalculatedFields() {
  calculating.value = true
  try {
    let grade = null
    if (isGradedForm.value) {
      const gradeRes = await calculateGrade(scorePayload())
      grade = gradeRes.data
      gradePreview.value = grade
      form.value.finalGradeValue = grade.finalGradeValue
      form.value.finalGradeLabel = grade.finalGradeLabel
      calculationLabel.value = grade.calculation
    } else {
      gradePreview.value = null
      calculationLabel.value = ''
      form.value.finalGradeValue = ''
      form.value.finalGradeLabel = ''
    }
    const popRes = await calculatePopulation({
      ...form.value,
      finalGradeLabel: grade?.finalGradeLabel || null,
      currentSubmissionId: formMode.value === 'edit' ? editingId.value : null
    })
    form.value.populationValue = popRes.data.populationValue
    populationLabel.value = popRes.data.calculation
    return grade
  } finally {
    calculating.value = false
  }
}

function calculateFormPreview() {
  window.clearTimeout(calculationTimer)
  calculationTimer = null
  refreshCalculatedFields()
    .then(() => proxy.$modal.msgSuccess(t(isGradedForm.value ? 'entries.gradeCalculated' : 'entries.popCalculated')))
    .catch(() => {})
}

function applyCardMatch() {
  matching.value = true
  matchCard({
    productType: form.value.productType,
    cardCategory: form.value.cardCategory,
    setName: form.value.setName,
    cardNumber: form.value.cardNumber
  })
    .then((res) => {
      const m = res.data
      if (!m.found) {
        proxy.$modal.msg(m.message || t('entries.noMatch'))
        return
      }
      form.value.cardName = m.cardName || form.value.cardName
      form.value.brandName = m.brandName || form.value.brandName
      form.value.yearLabel = m.yearLabel || form.value.yearLabel
      form.value.varietyName = m.varietyName || form.value.varietyName
      form.value.languageCode = m.languageCode || form.value.languageCode
      form.value.sportsType = m.sportsType || form.value.sportsType
      form.value.groupName = m.groupName || form.value.groupName
      if (isMerchForm.value) {
        form.value.merchDescription = m.merchDescription || ''
      }
      proxy.$modal.msgSuccess(t('entries.matchApplied'))
    })
    .finally(() => {
      matching.value = false
    })
}

function submitForm() {
  proxy.$refs.formRef.validate((valid) => {
    if (!valid) return
    submitting.value = true
    refreshCalculatedFields()
      .then(() =>
        formMode.value === 'create'
          ? createSubmission(submissionPayload())
          : updateSubmission(editingId.value, submissionPayload())
      )
      .then(async (res) => {
        const selectedImages = prepareSelectedImages(res.data.certId)
        let mediaUploadFailed = false
        if (selectedImages.length) {
          try {
            const mediaRes = await importSubmissionMedia(res.data.id, selectedImages)
            if (mediaRes.data.savedFiles !== selectedImages.length) {
              mediaUploadFailed = true
            }
          } catch {
            mediaUploadFailed = true
          }
        }
        proxy.$modal.msgSuccess(t(
          formMode.value === 'create' ? 'entries.entryCreated' : 'entries.entrySaved',
          { certId: res.data.certId }
        ))
        if (mediaUploadFailed) {
          proxy.$modal.msgWarning(t('entries.mediaUploadFailed'))
        }
        formOpen.value = false
        getList()
      })
      .finally(() => {
        submitting.value = false
      })
  })
}

function submissionPayload() {
  const graded = isGradedForm.value
  const { finalGradeValue, finalGradeLabel, ...editableFields } = form.value
  return {
    ...editableFields,
    cardCategory: graded ? form.value.cardCategory : 'trading_card',
    vintageClassification: isVintageForm.value ? form.value.vintageClassification : null,
    merchDescription: isMerchForm.value ? form.value.merchDescription : null,
    centeringScore: graded ? form.value.centeringScore : null,
    edgesScore: graded ? form.value.edgesScore : null,
    cornersScore: graded ? form.value.cornersScore : null,
    surfaceScore: graded ? form.value.surfaceScore : null,
    populationValue: form.value.populationValue
  }
}

function handleApprove(row) {
  proxy.$modal
    .confirm(t('entries.approveConfirm', { certId: row.certId }))
    .then(() => approveSubmission(row.id))
    .then(() => {
      proxy.$modal.msgSuccess(t('entries.entryApproved'))
      getList()
    })
    .catch(() => {})
}

function handleApproveDetail() {
  if (!detail.value) return
  approving.value = true
  approveSubmission(detail.value.id)
    .then((res) => {
      detail.value = res.data
      proxy.$modal.msgSuccess(t('entries.entryApproved'))
      getList()
    })
    .finally(() => {
      approving.value = false
    })
}

function handleBatchApprove() {
  const ids = [...selectedIds.value]
  proxy.$modal
    .confirm(t('entries.batchApproveConfirm', { count: ids.length }))
    .then(() => batchApproveSubmissions(ids))
    .then((res) => {
      proxy.$modal.msgSuccess(t('entries.entriesApproved', { count: res.data.count }))
      getList()
    })
    .catch(() => {})
}

function loadBrands() {
  fetchBrandOptions().then((res) => {
    brandOptions.value = res.data || []
  })
}

watch(
  () => form.value.productType,
  (productType) => {
    if (productType !== 'graded_card') {
      form.value.cardCategory = 'trading_card'
      form.value.sportsType = ''
      form.value.groupName = ''
      form.value.centeringScore = null
      form.value.edgesScore = null
      form.value.cornersScore = null
      form.value.surfaceScore = null
      form.value.populationValue = 1
      form.value.finalGradeValue = ''
      form.value.finalGradeLabel = ''
      clearPreview()
    } else if (form.value.centeringScore === null) {
      form.value.centeringScore = 1
      form.value.edgesScore = 1
      form.value.cornersScore = 1
      form.value.surfaceScore = 1
    }
    if (productType !== 'vintage_product') {
      form.value.vintageClassification = ''
    }
    if (productType !== 'merch_product') {
      form.value.merchDescription = ''
    }
  }
)

watch(
  () => [
    form.value.productType,
    form.value.cardCategory,
    form.value.cardName,
    form.value.setName,
    form.value.cardNumber,
    form.value.languageCode,
    form.value.movieName,
    form.value.releaseYear,
    form.value.productionCompany,
    form.value.filmType,
    form.value.sportsType,
    form.value.groupName,
    form.value.vintageClassification,
    form.value.centeringScore,
    form.value.edgesScore,
    form.value.cornersScore,
    form.value.surfaceScore
  ],
  () => {
    if (!formOpen.value) return
    window.clearTimeout(calculationTimer)
    calculationTimer = window.setTimeout(() => {
      calculationTimer = null
      refreshCalculatedFields().catch(() => {})
    }, 450)
  }
)

watch(() => route.fullPath, applyMenuRoute)

applyMenuRoute()
loadBrands()
</script>

<style scoped>
.filter-card :deep(.el-card__body) {
  padding-bottom: 2px;
}

.filter-card :deep(.el-form-item) {
  width: 180px;
  margin-right: 12px;
}

.filter-card :deep(.el-form-item__content),
.filter-card :deep(.el-select),
.filter-card :deep(.el-input) {
  width: 100%;
}

.filter-card :deep(.filter-actions) {
  width: auto;
  align-self: flex-end;
}

.image-file-input {
  width: 100%;
  color: var(--nxr-text-muted);
}

.image-file-input::file-selector-button {
  margin-right: 10px;
  padding: 7px 12px;
  border: 1px solid var(--nxr-border);
  border-radius: 5px;
  background: var(--nxr-surface);
  color: var(--nxr-text-strong);
  cursor: pointer;
}

.selected-image-name {
  display: block;
  width: 100%;
  margin-top: 6px;
  color: var(--nxr-text-faint);
}

.existing-media-preview {
  width: 100%;
  margin: 10px 0 0;
  text-align: center;
}

.existing-media-preview img {
  display: block;
  width: 100%;
  max-height: 160px;
  object-fit: contain;
  border: 1px solid var(--nxr-border);
  border-radius: 8px;
  background: var(--nxr-surface-soft);
}

.existing-media-preview figcaption {
  margin-top: 6px;
  color: var(--nxr-text-faint);
  font-size: 12px;
}

.media-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 16px;
}

.media-grid figure {
  margin: 0;
  text-align: center;
}

.media-grid img {
  width: 100%;
  border-radius: 8px;
}

.media-grid figcaption {
  margin-top: 6px;
  color: var(--nxr-text-faint);
  font-size: 12px;
}
</style>
