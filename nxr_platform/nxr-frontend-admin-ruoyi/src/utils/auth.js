import Cookies from 'js-cookie'

const TokenKey = 'Admin-Token'
const RememberKey = 'Admin-Remember-Me'

export function getToken() {
  return Cookies.get(TokenKey)
}

export function setToken(token, options = {}) {
  const attributes = { sameSite: 'lax' }
  if (options.rememberMe === true) {
    attributes.expires = 30
    Cookies.set(RememberKey, 'true', attributes)
  } else {
    Cookies.remove(RememberKey)
  }
  return Cookies.set(TokenKey, token, attributes)
}

export function touchTokenCookie() {
  const token = getToken()
  if (token && Cookies.get(RememberKey) === 'true') {
    Cookies.set(TokenKey, token, { expires: 30, sameSite: 'lax' })
  }
}

export function removeToken() {
  Cookies.remove(RememberKey)
  return Cookies.remove(TokenKey)
}
