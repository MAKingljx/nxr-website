import request from '@/utils/request'

// 品牌列表
export function fetchBrandSettings() {
  return request({
    url: '/api/admin/brand-settings',
    method: 'get'
  })
}

// 新增品牌
export function createBrandSetting(data) {
  return request({
    url: '/api/admin/brand-settings',
    method: 'post',
    data
  })
}

// 编辑品牌
export function updateBrandSetting(brandId, data) {
  return request({
    url: '/api/admin/brand-settings/' + brandId,
    method: 'put',
    data
  })
}
