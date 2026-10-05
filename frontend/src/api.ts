// Thin fetch wrapper. The access token lives in memory only; the refresh token is an httpOnly cookie.
export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

let accessToken: string | null = null
let onUnauthorized: (() => void) | null = null
let refreshing: Promise<boolean> | null = null

export function setAccessToken(t: string | null) {
  accessToken = t
}
export function setOnUnauthorized(fn: () => void) {
  onUnauthorized = fn
}

export interface MeResponse {
  id: number
  name: string
  email: string
  role: string
  permissions: string[]
}
interface TokenResponse {
  accessToken: string
  expiresInSeconds: number
  user: MeResponse
}

async function parseError(res: Response): Promise<ApiError> {
  let msg = `${res.status} ${res.statusText}`
  try {
    const body = await res.json()
    msg = body.detail || body.title || msg
  } catch {
    /* not JSON */
  }
  return new ApiError(res.status, msg)
}

export async function refreshSession(): Promise<TokenResponse | null> {
  const res = await fetch('/api/v1/auth/refresh', { method: 'POST', credentials: 'include' })
  if (!res.ok) return null
  const body = (await res.json()) as TokenResponse
  accessToken = body.accessToken
  return body
}

async function tryRefresh(): Promise<boolean> {
  if (!refreshing) {
    refreshing = refreshSession()
      .then((b) => !!b)
      .finally(() => {
        refreshing = null
      })
  }
  return refreshing
}

export async function api<T = unknown>(method: string, path: string, body?: unknown): Promise<T> {
  const doFetch = () =>
    fetch(`/api/v1${path}`, {
      method,
      credentials: 'include',
      headers: {
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })
  let res = await doFetch()
  if (res.status === 401 && !path.startsWith('/auth/')) {
    if (await tryRefresh()) res = await doFetch()
    else {
      onUnauthorized?.()
      throw new ApiError(401, 'Session expired. Please sign in again.')
    }
  }
  if (!res.ok) throw await parseError(res)
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export const get = <T,>(path: string) => api<T>('GET', path)
export const post = <T,>(path: string, body?: unknown) => api<T>('POST', path, body ?? {})
export const put = <T,>(path: string, body?: unknown) => api<T>('PUT', path, body ?? {})
export const patch = <T,>(path: string, body?: unknown) => api<T>('PATCH', path, body ?? {})

export async function login(email: string, password: string): Promise<MeResponse> {
  const res = await fetch('/api/v1/auth/login', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  if (!res.ok) throw await parseError(res)
  const body = (await res.json()) as TokenResponse
  accessToken = body.accessToken
  return body.user
}

export async function logout() {
  try {
    await fetch('/api/v1/auth/logout', { method: 'POST', credentials: 'include' })
  } finally {
    accessToken = null
  }
}

export function qs(params: Record<string, string | number | boolean | null | undefined>): string {
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v !== null && v !== undefined && v !== '') p.set(k, String(v))
  const s = p.toString()
  return s ? `?${s}` : ''
}

/** Authenticated binary download (images, exports). Returns a Blob; use URL.createObjectURL for <img>/<a download>. */
export async function download(path: string): Promise<Blob> {
  const doFetch = () =>
    fetch(`/api/v1${path}`, {
      credentials: 'include',
      headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
    })
  let res = await doFetch()
  if (res.status === 401 && (await tryRefresh())) res = await doFetch()
  if (!res.ok) throw await parseError(res)
  return res.blob()
}

/** Authenticated multipart upload (do NOT set Content-Type; the browser adds the boundary). */
export async function upload<T = unknown>(path: string, form: FormData): Promise<T> {
  const doFetch = () =>
    fetch(`/api/v1${path}`, {
      method: 'POST',
      credentials: 'include',
      headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
      body: form,
    })
  let res = await doFetch()
  if (res.status === 401 && (await tryRefresh())) res = await doFetch()
  if (!res.ok) throw await parseError(res)
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}
