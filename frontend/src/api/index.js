import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: '/api',
  timeout: 120000
})

http.interceptors.response.use(
  (resp) => resp.data,
  (error) => {
    const msg =
      error.response?.data?.message ||
      error.message ||
      '请求失败，请检查后端服务是否可用'
    ElMessage.error(msg)
    return Promise.reject(new Error(msg))
  }
)

export const api = {
  // 录音
  upload(file) {
    const fd = new FormData()
    fd.append('file', file)
    return http.post('/recordings/upload', fd)
  },
  listRecordings({ statuses, keyword, page, size }) {
    return http.get('/recordings', {
      params: { statuses: statuses?.join(',') || undefined, keyword, page, size }
    })
  },
  recordingDetail(id) {
    return http.get(`/recordings/${id}`)
  },
  recordingLogs(id) {
    return http.get(`/recordings/${id}/logs`)
  },
  retryRecording(id) {
    return http.post(`/recordings/${id}/retry`)
  },
  deleteRecording(id) {
    return http.delete(`/recordings/${id}`)
  },

  // 人工复检
  review(id, payload) {
    return http.post(`/reviews/${id}`, payload)
  },

  // 敏感词库
  listDictionary({ keyword, source, enabled, page, size }) {
    return http.get('/dictionary', {
      params: { keyword, source, enabled, page, size }
    })
  },
  createWord(payload) {
    return http.post('/dictionary', payload)
  },
  updateWord(id, payload) {
    return http.put(`/dictionary/${id}`, payload)
  },
  deleteWord(id) {
    return http.delete(`/dictionary/${id}`)
  },
  testText(text) {
    return http.post('/dictionary/test', { text })
  },

  // 语料库
  listCorpus({ label, keyword, page, size }) {
    return http.get('/corpus', { params: { label, keyword, page, size } })
  },
  createCorpus(payload) {
    return http.post('/corpus', payload)
  },
  deleteCorpus(id) {
    return http.delete(`/corpus/${id}`)
  },
  exportCorpus() {
    return http.get('/corpus/export', { responseType: 'blob' })
  },

  // 统计
  overview() {
    return http.get('/stats/overview')
  }
}

export default api