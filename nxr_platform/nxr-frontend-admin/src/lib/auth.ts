import type { AdminSession } from './api'

const storageKey = 'nxr-platform-admin-session'

export function readAdminSession(): AdminSession | null {
  const rawValue = window.localStorage.getItem(storageKey)
  if (!rawValue) {
    return null
  }

  try {
    return JSON.parse(rawValue) as AdminSession
  } catch {
    window.localStorage.removeItem(storageKey)
    return null
  }
}

export function writeAdminSession(session: AdminSession) {
  window.localStorage.setItem(storageKey, JSON.stringify(session))
}

export function clearAdminSession() {
  window.localStorage.removeItem(storageKey)
}
