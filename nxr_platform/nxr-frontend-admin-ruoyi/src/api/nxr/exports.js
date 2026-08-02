import request from '@/utils/request'

// 导出预览
export function previewExport(data) {
  return request({
    url: '/api/admin/exports/preview',
    method: 'post',
    data
  })
}

// 生成导出文件
export function generateExport(data) {
  return request({
    url: '/api/admin/exports/generate',
    method: 'post',
    data,
    timeout: 1000 * 60 * 5
  })
}

// 导出历史列表
export function fetchExports(query) {
  return request({
    url: '/api/admin/exports',
    method: 'get',
    params: query
  })
}

// 下载导出文件（blob）
export function downloadExportBlob(filename) {
  return request({
    url: '/api/admin/exports/' + encodeURIComponent(filename) + '/download',
    method: 'get',
    responseType: 'blob',
    timeout: 1000 * 60 * 5
  })
}

// 删除导出文件
export function deleteExport(filename) {
  return request({
    url: '/api/admin/exports/' + encodeURIComponent(filename),
    method: 'delete'
  })
}
