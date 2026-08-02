import request from '@/utils/request'

// 录入列表
export function listSubmissions(query) {
  return request({
    url: '/api/admin/submissions',
    method: 'get',
    params: query
  })
}

// 录入详情
export function getSubmission(submissionId) {
  return request({
    url: '/api/admin/submissions/' + submissionId,
    method: 'get'
  })
}

// 新建录入
export function createSubmission(data) {
  return request({
    url: '/api/admin/submissions',
    method: 'post',
    data
  })
}

// 编辑录入
export function updateSubmission(submissionId, data) {
  return request({
    url: '/api/admin/submissions/' + submissionId,
    method: 'put',
    data
  })
}

// 单条审批
export function approveSubmission(submissionId) {
  return request({
    url: '/api/admin/submissions/' + submissionId + '/approve',
    method: 'post'
  })
}

// 批量审批
export function batchApproveSubmissions(submissionIds) {
  return request({
    url: '/api/admin/submissions/batch-approve',
    method: 'post',
    data: { submissionIds }
  })
}

// 生成证书编号
export function generateCertId() {
  return request({
    url: '/api/admin/submissions/generate-cert-id',
    method: 'get'
  })
}

// 实时计算最终评级
export function calculateGrade(data) {
  return request({
    url: '/api/admin/submissions/calculate-grade',
    method: 'post',
    data,
    headers: { repeatSubmit: false }
  })
}

// 实时计算 POP
export function calculatePopulation(data) {
  return request({
    url: '/api/admin/submissions/calculate-pop',
    method: 'post',
    data,
    headers: { repeatSubmit: false }
  })
}

// 按 set/card_number 匹配卡牌元数据
export function matchCard(data) {
  return request({
    url: '/api/admin/submissions/match-card',
    method: 'post',
    data,
    headers: { repeatSubmit: false }
  })
}

// 仪表盘统计
export function fetchDashboard() {
  return request({
    url: '/api/admin/dashboard',
    method: 'get'
  })
}
