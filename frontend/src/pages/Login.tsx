import { useState, type FormEvent } from 'react'
import { useAuth } from '../auth'
import { ErrorNote, Field } from '../components/ui'

export default function Login() {
  const { signIn } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [err, setErr] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setErr(null)
    try {
      await signIn(email.trim(), password)
    } catch (x) {
      setErr(x)
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="flex min-h-screen items-center justify-center p-4">
      <form onSubmit={submit} className="card w-full max-w-sm space-y-4">
        <div className="text-center">
          <div className="text-4xl">🍨</div>
          <h1 className="text-xl font-semibold text-brand-700">Valentina Ice Cream &amp; Café</h1>
          <p className="text-sm text-slate-500">Sign in to continue</p>
        </div>
        <Field label="Email">
          <input className="input" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </Field>
        <Field label="Password">
          <input
            className="input"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </Field>
        <ErrorNote error={err} />
        <button className="btn-primary w-full" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  )
}
