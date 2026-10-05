import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import * as client from './api'
import type { MeResponse } from './api'

interface AuthState {
  user: MeResponse | null
  loading: boolean
  can: (perm: string) => boolean
  signIn: (email: string, password: string) => Promise<void>
  signOut: () => Promise<void>
}

const Ctx = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<MeResponse | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    client.setOnUnauthorized(() => setUser(null))
    client
      .refreshSession()
      .then((b) => setUser(b ? b.user : null))
      .catch(() => setUser(null))
      .finally(() => setLoading(false))
  }, [])

  const signIn = useCallback(async (email: string, password: string) => {
    setUser(await client.login(email, password))
  }, [])
  const signOut = useCallback(async () => {
    await client.logout()
    setUser(null)
  }, [])
  const can = useCallback((p: string) => !!user && user.permissions.includes(p), [user])

  const value = useMemo(() => ({ user, loading, can, signIn, signOut }), [user, loading, can, signIn, signOut])
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useAuth(): AuthState {
  const v = useContext(Ctx)
  if (!v) throw new Error('useAuth outside AuthProvider')
  return v
}
