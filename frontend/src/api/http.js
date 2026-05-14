import axios from 'axios'
import { ElMessage } from 'element-plus'

export const TOKEN_KEY = 'mall_token'
export const USERNAME_KEY = 'mall_username'
export const USER_ID_KEY = 'mall_user_id'
export const LATEST_ORDER_SN_KEY = 'mall_latest_order_sn'
export const LATEST_REQUEST_ID_KEY = 'mall_latest_request_id'

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 15000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (!body || typeof body.code !== 'number') {
      return body
    }

    if (body.code === 0) {
      return body.data
    }

    if (body.code === 401) {
      localStorage.removeItem(TOKEN_KEY)
    }
    ElMessage.error(body.message || '请求处理失败')
    return Promise.reject(new Error(body.message || '请求处理失败'))
  },
  (error) => {
    const message = error.response?.data?.message || '请求失败，请确认 Nginx 与后端服务已启动'
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

export default http
