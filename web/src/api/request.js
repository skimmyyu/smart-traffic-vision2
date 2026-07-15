import axios from 'axios'
import { SERVER_URL } from '../config.js'

const request = axios.create({
  baseURL: SERVER_URL,
  timeout: 10000
})

request.interceptors.response.use(
  (res) => {
    if (res.data?.code === 200) {
      return res.data.data
    }
    return Promise.reject(new Error(res.data?.message || '请求失败'))
  },
  (err) => {
    const msg = err.response?.data?.message || err.message || '请求失败'
    return Promise.reject(new Error(msg))
  }
)

export default request
