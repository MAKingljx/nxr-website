<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryRef" :inline="true">
      <el-form-item label="关键词" prop="query">
        <el-input
          v-model="queryParams.query"
          placeholder="证书编号 / 卡名 / 品牌"
          clearable
          style="width: 240px"
          @keyup.enter="handleQuery"
        />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="全部状态" clearable style="width: 160px">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="Plus" v-hasPermi="['nxr:entry:add']" @click="handleAdd">新增录入</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="success"
          plain
          icon="Check"
          :disabled="!selectedIds.length"
          v-hasPermi="['nxr:entry:approve']"
          @click="handleBatchApprove"
        >批量审批 ({{ selectedIds.length }})</el-button>
      </el-col>
    </el-row>

    <el-table v-loading="loading" :data="rows" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="50" align="center" :selectable="isRowSelectable" />
      <el-table-column label="证书编号" prop="certId" width="140" show-overflow-tooltip />
      <el-table-column label="卡名" prop="cardName" min-width="160" show-overflow-tooltip />
      <el-table-column label="类目" width="120">
        <template #default="scope">{{ scope.row.cardCategoryLabel || scope.row.cardCategory }}</template>
      </el-table-column>
      <el-table-column label="品牌" prop="brandName" width="120" show-overflow-tooltip />
      <el-table-column label="年份" prop="yearLabel" width="80" align="center" />
      <el-table-column label="语言" prop="languageCode" width="80" align="center" />
      <el-table-column label="状态" width="100" align="center">
        <template #default="scope">
          <el-tag :type="statusTagType(scope.row.statusCode)">{{ scope.row.statusCode }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最终评级" width="160" show-overflow-tooltip>
        <template #default="scope">{{ scope.row.finalGradeValue }} · {{ scope.row.finalGradeLabel }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220" align="center" class-name="small-padding fixed-width">
        <template #default="scope">
          <el-button link type="primary" icon="View" @click="handleDetail(scope.row)">详情</el-button>
          <el-button link type="primary" icon="Edit" v-hasPermi="['nxr:entry:edit']" @click="handleEdit(scope.row)">编辑</el-button>
          <el-button
            v-if="scope.row.statusCode === 'pending' || scope.row.statusCode === 'review'"
            link
            type="success"
            icon="Check"
            v-hasPermi="['nxr:entry:approve']"
            @click="handleApprove(scope.row)"
          >审批</el-button>
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

    <!-- 详情对话框 -->
    <el-dialog title="录入详情" v-model="detailOpen" width="760px" append-to-body>
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="证书编号">{{ detail.certId }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detail.statusCode)">{{ detail.statusCode }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="类目">{{ detail.cardCategoryLabel || detail.cardCategory }}</el-descriptions-item>
          <el-descriptions-item label="POP">{{ detail.populationValue }}</el-descriptions-item>
          <template v-if="detail.cardCategory === 'movie_film'">
            <el-descriptions-item label="电影名称">{{ detail.movieName || detail.cardName }}</el-descriptions-item>
            <el-descriptions-item label="上映年份">{{ detail.releaseYear || detail.yearLabel }}</el-descriptions-item>
            <el-descriptions-item label="制作公司">{{ detail.productionCompany || detail.brandName }}</el-descriptions-item>
            <el-descriptions-item label="影片类型">{{ detail.filmType || detail.varietyName }}</el-descriptions-item>
          </template>
          <template v-else>
            <el-descriptions-item label="卡名">{{ detail.cardName }}</el-descriptions-item>
            <el-descriptions-item label="品牌">{{ detail.brandName }}</el-descriptions-item>
            <el-descriptions-item label="系列">{{ detail.setName }}</el-descriptions-item>
            <el-descriptions-item label="卡号">{{ detail.cardNumber }}</el-descriptions-item>
            <el-descriptions-item label="语言">{{ detail.languageCode }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.cardCategory === 'sports_card'" label="运动类型">{{ detail.sportsType }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.cardCategory === 'celebrity_card'" label="团体名称">{{ detail.groupName }}</el-descriptions-item>
          </template>
          <el-descriptions-item label="居中">{{ detail.centeringScore }}</el-descriptions-item>
          <el-descriptions-item label="边缘">{{ detail.edgesScore }}</el-descriptions-item>
          <el-descriptions-item label="边角">{{ detail.cornersScore }}</el-descriptions-item>
          <el-descriptions-item label="表面">{{ detail.surfaceScore }}</el-descriptions-item>
          <el-descriptions-item label="最终评级" :span="2">
            {{ detail.finalGradeValue }} · {{ detail.finalGradeLabel }}
          </el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ detail.entryNotes || detail.decisionNotes || '-' }}</el-descriptions-item>
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
        >审 批</el-button>
        <el-button @click="detailOpen = false">关 闭</el-button>
      </template>
    </el-dialog>

    <!-- 新增/编辑对话框 -->
    <el-dialog :title="formTitle" v-model="formOpen" width="860px" append-to-body @close="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="类目" prop="cardCategory">
              <el-select v-model="form.cardCategory" style="width: 100%">
                <el-option
                  v-for="d in nxr_card_category"
                  :key="d.value"
                  :label="d.label"
                  :value="d.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="证书编号" prop="certId">
              <el-input v-model="form.certId" readonly inputmode="numeric" placeholder="自动生成 10 位证书编号（首位不为 0）">
                <template #append v-if="formMode === 'create'">
                  <el-button @click="fillGeneratedCertId">生成</el-button>
                </template>
              </el-input>
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16" v-if="isMovieForm">
          <el-col :span="12"><el-form-item label="电影名称"><el-input v-model="form.movieName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="上映年份"><el-input v-model="form.releaseYear" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="制作公司"><el-input v-model="form.productionCompany" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="影片类型"><el-input v-model="form.filmType" /></el-form-item></el-col>
        </el-row>

        <el-row :gutter="16" v-else>
          <el-col :span="12"><el-form-item label="卡名"><el-input v-model="form.cardName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="年份"><el-input v-model="form.yearLabel" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="品牌">
              <el-select v-model="form.brandName" filterable allow-create default-first-option style="width: 100%">
                <el-option v-for="b in brandOptions" :key="b.id" :label="b.name" :value="b.name" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12"><el-form-item label="角色/球员"><el-input v-model="form.playerName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="版本"><el-input v-model="form.varietyName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="系列"><el-input v-model="form.setName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="卡号"><el-input v-model="form.cardNumber" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="语言">
              <el-select v-model="form.languageCode" style="width: 100%">
                <el-option v-for="d in nxr_language" :key="d.value" :label="d.label" :value="d.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12" v-if="isSportsForm">
            <el-form-item label="运动类型">
              <el-select v-model="form.sportsType" filterable allow-create default-first-option style="width: 100%">
                <el-option v-for="d in nxr_sports_type" :key="d.value" :label="d.label" :value="d.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12" v-if="isCelebrityForm">
            <el-form-item label="团体名称"><el-input v-model="form.groupName" /></el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">四维度评分</el-divider>
        <el-row :gutter="16">
          <el-col :span="6"><el-form-item label="居中" label-width="60px"><el-input-number v-model="form.centeringScore" :min="1" :max="10" :step="0.5" controls-position="right" style="width: 100%" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="边缘" label-width="60px"><el-input-number v-model="form.edgesScore" :min="1" :max="10" :step="0.5" controls-position="right" style="width: 100%" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="边角" label-width="60px"><el-input-number v-model="form.cornersScore" :min="1" :max="10" :step="0.5" controls-position="right" style="width: 100%" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="表面" label-width="60px"><el-input-number v-model="form.surfaceScore" :min="1" :max="10" :step="0.5" controls-position="right" style="width: 100%" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="POP">
              <el-input v-model.number="form.populationValue" readonly>
                <template #append>自动计算</template>
              </el-input>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="录入备注">
          <el-input v-model="form.entryNotes" type="textarea" :rows="3" />
        </el-form-item>

        <el-alert
          v-if="gradePreview || populationLabel"
          type="info"
          :closable="false"
          class="mb8"
        >
          <div v-if="gradePreview">评级预览：{{ gradePreview.finalGradeValue }} · {{ gradePreview.finalGradeLabel }}</div>
          <div v-if="calculationLabel">{{ calculationLabel }}</div>
          <div v-if="populationLabel">{{ populationLabel }}</div>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button :loading="calculating" @click="calculateFormPreview">计算评级/POP</el-button>
        <el-button v-if="!isMovieForm" :disabled="!canMatchCard" :loading="matching" @click="applyCardMatch">匹配卡牌</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">确 定</el-button>
        <el-button @click="formOpen = false">取 消</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="NxrEntries">
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
import { fetchBrandSettings } from '@/api/nxr/brands'

const { proxy } = getCurrentInstance()
const { nxr_card_category, nxr_language, nxr_sports_type } = proxy.useDict(
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

function validateCertId(_rule, value, callback) {
  const certId = String(value || '').trim()
  if (!certId) {
    callback(new Error('证书编号不能为空'))
    return
  }
  if (formMode.value === 'create' && !/^[1-9]\d{9}$/.test(certId)) {
    callback(new Error('证书编号必须为 10 位数字，且不能以 0 开头'))
    return
  }
  callback()
}

const statusOptions = [
  { value: 'pending', label: 'pending' },
  { value: 'approved', label: 'approved' },
  { value: 'published', label: 'published' }
]

const data = reactive({
  form: createDefaultForm(),
  queryParams: {
    page: 1,
    pageSize: 10,
    status: undefined,
    query: undefined
  },
  rules: {
    certId: [{ validator: validateCertId, trigger: 'blur' }],
    cardCategory: [{ required: true, message: '请选择类目', trigger: 'change' }]
  }
})

const { form, queryParams, rules } = toRefs(data)

const formTitle = computed(() => (formMode.value === 'create' ? '新增录入' : '编辑录入'))
const isMovieForm = computed(() => form.value.cardCategory === 'movie_film')
const isSportsForm = computed(() => form.value.cardCategory === 'sports_card')
const isCelebrityForm = computed(() => form.value.cardCategory === 'celebrity_card')
const canMatchCard = computed(
  () => !isMovieForm.value && (form.value.setName || '').trim() && (form.value.cardNumber || '').trim()
)

function createDefaultForm() {
  return {
    certId: '',
    cardCategory: 'trading_card',
    cardName: '',
    movieName: '',
    releaseYear: '2026',
    productionCompany: '',
    filmType: '',
    sportsType: '',
    groupName: '',
    yearLabel: '2026',
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
    entryNotes: ''
  }
}

function statusTagType(status) {
  if (status === 'published') return 'success'
  if (status === 'approved') return 'warning'
  return 'info'
}

function isRowSelectable(row) {
  return row.statusCode === 'pending' || row.statusCode === 'review'
}

function getList() {
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
  handleQuery()
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
      entryNotes: d.entryNotes || ''
    })
    clearPreview()
    formOpen.value = true
  })
}

function clearPreview() {
  gradePreview.value = null
  calculationLabel.value = ''
  populationLabel.value = ''
}

function resetForm() {
  proxy.resetForm('formRef')
}

function fillGeneratedCertId() {
  generateCertId().then((res) => {
    form.value.certId = res.data.certId
    proxy.$modal.msgSuccess('已生成证书编号 ' + res.data.certId)
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
    const gradeRes = await calculateGrade(scorePayload())
    const grade = gradeRes.data
    gradePreview.value = grade
    calculationLabel.value = grade.calculation
    const popRes = await calculatePopulation({
      ...form.value,
      finalGradeLabel: grade.finalGradeLabel,
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
  refreshCalculatedFields()
    .then(() => proxy.$modal.msgSuccess('计算完成'))
    .catch(() => {})
}

function applyCardMatch() {
  matching.value = true
  matchCard({
    cardCategory: form.value.cardCategory,
    setName: form.value.setName,
    cardNumber: form.value.cardNumber
  })
    .then((res) => {
      const m = res.data
      if (!m.found) {
        proxy.$modal.msg(m.message || '未找到匹配卡牌')
        return
      }
      form.value.cardName = m.cardName || form.value.cardName
      form.value.brandName = m.brandName || form.value.brandName
      form.value.yearLabel = m.yearLabel || form.value.yearLabel
      form.value.varietyName = m.varietyName || form.value.varietyName
      form.value.languageCode = m.languageCode || form.value.languageCode
      form.value.sportsType = m.sportsType || form.value.sportsType
      form.value.groupName = m.groupName || form.value.groupName
      proxy.$modal.msgSuccess('已自动填充卡牌信息')
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
          ? createSubmission(form.value)
          : updateSubmission(editingId.value, form.value)
      )
      .then((res) => {
        proxy.$modal.msgSuccess(
          (formMode.value === 'create' ? '新增成功：' : '保存成功：') + res.data.certId
        )
        formOpen.value = false
        getList()
      })
      .finally(() => {
        submitting.value = false
      })
  })
}

function handleApprove(row) {
  proxy.$modal
    .confirm('确认审批通过证书 "' + row.certId + '" 吗？')
    .then(() => approveSubmission(row.id))
    .then(() => {
      proxy.$modal.msgSuccess('审批成功')
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
      proxy.$modal.msgSuccess('审批成功')
      getList()
    })
    .finally(() => {
      approving.value = false
    })
}

function handleBatchApprove() {
  const ids = [...selectedIds.value]
  proxy.$modal
    .confirm('确认批量审批选中的 ' + ids.length + ' 条录入吗？')
    .then(() => batchApproveSubmissions(ids))
    .then((res) => {
      proxy.$modal.msgSuccess('批量审批成功，共 ' + res.data.count + ' 条')
      getList()
    })
    .catch(() => {})
}

function loadBrands() {
  fetchBrandSettings().then((res) => {
    brandOptions.value = (res.data || []).filter((b) => b.isActive)
  })
}

getList()
loadBrands()
</script>

<style scoped>
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
