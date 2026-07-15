const TOKEN_KEY = 'traffic_auth_token'
const USER_KEY = 'traffic_auth_user'

/** 固定账号（无数据库，仅前端校验） */
const CREDENTIALS = {
  username: 'admin',
  password: '123456'
}

export function login(username, password) {
  const user = String(username || '').trim()
  const pass = String(password || '')
  if (user === CREDENTIALS.username && pass === CREDENTIALS.password) {
    const token = btoa(`${user}:${Date.now()}`)
    sessionStorage.setItem(TOKEN_KEY, token)
    sessionStorage.setItem(USER_KEY, user)
    return { username: user, token }
  }
  return null
}

export function logout() {
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(USER_KEY)
}

export function isLoggedIn() {
  return Boolean(sessionStorage.getItem(TOKEN_KEY))
}

export function currentUser() {
  return sessionStorage.getItem(USER_KEY) || ''
}

export function getToken() {
  return sessionStorage.getItem(TOKEN_KEY) || ''
}
