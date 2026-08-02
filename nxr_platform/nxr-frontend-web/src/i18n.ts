import { createI18n } from 'vue-i18n'

export const supportedLocales = [
  { code: 'en', label: 'English' },
  { code: 'zh-CN', label: '中文' },
] as const

export type SupportedLocale = (typeof supportedLocales)[number]['code']

const storageKey = 'nxr-platform-web-locale'

const messages = {
  en: {
    common: {
      brand: 'NXR Grading',
      verify: 'Verify',
      admin: 'Admin',
      language: 'Language',
      loading: 'loading',
      status: 'Status',
      service: 'Service',
      version: 'Version',
      brandLabel: 'Brand',
      set: 'Set',
      cardNumber: 'Card Number',
      cardLanguage: 'Card Language',
      population: 'Population',
      published: 'Published',
      centering: 'Centering',
      edges: 'Edges',
      corners: 'Corners',
      surface: 'Surface',
    },
    home: {
      eyebrow: 'NXR Platform',
      title: 'AI precision, human review, public verification.',
      fallbackHeadline:
        'The new NXR stack keeps grading data, public certificates, and review workflow separate without losing the direct, transparent feel of the current client site.',
      verifyCta: 'Verify a Certificate',
      adminCta: 'Open Admin Workspace',
      publishedCertificates: 'Published Certificates',
      pendingReview: 'Pending Review',
      waitlistInterest: 'Waitlist Interest',
      platformPhase: 'Platform phase',
      legacyAdmin: 'Legacy hidden admin',
      totalSubmissions: 'Total submissions',
      howTitle: 'How NXR Grades',
      howCopy: 'AI inspects centering, edges, corners, and surface first. Human review confirms or challenges the result before a certificate goes public.',
      changedTitle: 'What Changed',
      changedCopy: 'Public web, admin workflow, and database design now live as separate modules, which reduces the risk of one change damaging the whole system.',
      modulesTitle: 'Current Modules',
      stepOneTitle: 'AI First Pass',
      stepOneCopy: 'Every submission starts with structured grading data so later publish and verify flows can stay exact.',
      stepTwoTitle: 'Human Review',
      stepTwoCopy: 'The admin workflow keeps pending, approved, and published states distinct instead of mixing them inside one table.',
      stepThreeTitle: 'Public Certificate',
      stepThreeCopy: 'Once a card is published, collectors can open a clean public certificate detail page with images, scores, and decision notes.',
      featuredEyebrow: 'Published Cards',
      featuredTitle: 'Featured certificates from the seeded platform dataset.',
      openVerify: 'Open Verify',
      loadFailure: 'Failed to load platform summary',
      modulePublicWeb: 'Public web',
      moduleAdminDashboard: 'Admin dashboard',
      moduleSubmissionWorkflow: 'Submission workflow',
      moduleCertificateVerify: 'Certificate verify',
      moduleWaitlist: 'Waitlist',
      moduleMysqlSchema: 'MySQL schema',
    },
    verify: {
      eyebrow: 'Public Verify',
      title: 'Verify a certificate by exact ID.',
      copy: 'Enter the slab certificate ID to open the published grading record. Lookup is case-insensitive for user convenience, but records remain exact on the backend.',
      placeholder: 'Certificate ID, e.g. VRA003',
      submit: 'Verify Card',
      hint: 'Try one of these seeded certificates:',
      loadFailure: 'Failed to load verify helper data',
    },
    card: {
      loadingEyebrow: 'Loading',
      loadingCopy: 'Loading certificate data.',
      backToVerify: 'Back to Verify',
      eyebrow: 'Published Certificate',
      notPublished: 'Not published',
      decisionNotes: 'Decision Notes',
      noReviewerNote: 'No reviewer note attached.',
      openQr: 'Open QR target',
      frontImageAlt: '{name} front image',
      backImageAlt: '{name} back image',
      missingEyebrow: 'Certificate Missing',
      missingCopy: 'This certificate was not found in the published dataset.',
      loadFailure: 'Failed to load certificate',
    },
  },
  'zh-CN': {
    common: {
      brand: 'NXR 评级',
      verify: '验证',
      admin: '后台',
      language: '语言',
      loading: '加载中',
      status: '状态',
      service: '服务',
      version: '版本',
      brandLabel: '品牌',
      set: '系列',
      cardNumber: '卡号',
      cardLanguage: '卡片语言',
      population: 'POP 数量',
      published: '发布时间',
      centering: '居中',
      edges: '边缘',
      corners: '边角',
      surface: '表面',
    },
    home: {
      eyebrow: 'NXR 平台',
      title: 'AI 精准识别，人工复核，公开验证。',
      fallbackHeadline: '新版 NXR 将评级数据、公开证书和审核流程分离，同时保留当前客户端站点直接透明的体验。',
      verifyCta: '验证证书',
      adminCta: '打开后台工作台',
      publishedCertificates: '已发布证书',
      pendingReview: '待审核',
      waitlistInterest: 'Waitlist 关注',
      platformPhase: '平台阶段',
      legacyAdmin: '旧隐藏后台入口',
      totalSubmissions: '总提交数',
      howTitle: 'NXR 如何评级',
      howCopy: 'AI 先检查居中、边缘、边角和表面，人工复核再确认或调整结果，最后才公开证书。',
      changedTitle: '这次改变了什么',
      changedCopy: '公开站点、后台流程和数据库设计已经拆成独立模块，降低单点改动影响全站的风险。',
      modulesTitle: '当前模块',
      stepOneTitle: 'AI 初评',
      stepOneCopy: '每个提交先生成结构化评级数据，后续发布和验证流程才能保持精确。',
      stepTwoTitle: '人工复核',
      stepTwoCopy: '后台流程明确区分 pending、approved 和 published，避免混在一张表里。',
      stepThreeTitle: '公开证书',
      stepThreeCopy: '发布后，收藏者可以打开清晰的公开证书页面，查看图片、分数和判定备注。',
      featuredEyebrow: '已发布卡片',
      featuredTitle: '来自平台种子数据集的精选证书。',
      openVerify: '打开验证',
      loadFailure: '加载平台概览失败',
      modulePublicWeb: '公开站点',
      moduleAdminDashboard: '后台仪表盘',
      moduleSubmissionWorkflow: '提交工作流',
      moduleCertificateVerify: '证书验证',
      moduleWaitlist: 'Waitlist 名单',
      moduleMysqlSchema: 'MySQL 表结构',
    },
    verify: {
      eyebrow: '公开验证',
      title: '通过精确 ID 验证证书。',
      copy: '输入封装证书 ID 即可打开已发布评级记录。为方便用户，查询不区分大小写，但后端记录仍保持精确。',
      placeholder: '证书 ID，例如 VRA003',
      submit: '验证卡片',
      hint: '可以试试这些种子证书：',
      loadFailure: '加载验证辅助数据失败',
    },
    card: {
      loadingEyebrow: '加载中',
      loadingCopy: '正在从 Java 后端加载证书数据。',
      backToVerify: '返回验证',
      eyebrow: '已发布证书',
      notPublished: '未发布',
      decisionNotes: '判定备注',
      noReviewerNote: '暂无审核备注。',
      openQr: '打开二维码目标',
      frontImageAlt: '{name} 正面图片',
      backImageAlt: '{name} 背面图片',
      missingEyebrow: '证书不存在',
      missingCopy: '在已发布数据集中没有找到该证书。',
      loadFailure: '加载证书失败',
    },
  },
}

function resolveInitialLocale(): SupportedLocale {
  if (typeof window === 'undefined') {
    return 'en'
  }

  const savedLocale = window.localStorage.getItem(storageKey)
  if (savedLocale === 'en' || savedLocale === 'zh-CN') {
    return savedLocale
  }

  return navigator.language.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en'
}

export const i18n = createI18n({
  legacy: false,
  locale: resolveInitialLocale(),
  fallbackLocale: 'en',
  messages,
})

export function persistLocale(locale: SupportedLocale) {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(storageKey, locale)
    document.documentElement.lang = locale
  }
}

persistLocale(i18n.global.locale.value as SupportedLocale)
