import request from '@/utils/request'

// Waitlist 列表
export function fetchWaitlist(query) {
  return request({
    url: '/api/admin/waitlist',
    method: 'get',
    params: query
  })
}
