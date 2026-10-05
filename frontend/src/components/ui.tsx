import { useEffect, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { get, qs } from '../api'
import { dateTime } from '../format'
import type { AuditRow } from '../types'

const CHIP: Record<string, string> = {
  CONFIRMED: 'bg-emerald-100 text-emerald-800',
  IMPORTED: 'bg-sky-100 text-sky-800',
  MANUAL: 'bg-slate-100 text-slate-700',
  ESTIMATED: 'bg-amber-100 text-amber-800',
  PARTIAL: 'bg-orange-100 text-orange-800',
  MISSING: 'bg-red-100 text-red-800',
  BACKDATED: 'bg-violet-100 text-violet-800',
  CORRECTED: 'bg-fuchsia-100 text-fuchsia-800',
  VOIDED: 'bg-red-100 text-red-700 line-through',
  PENDING_APPROVAL: 'bg-amber-100 text-amber-800',
  STALE: 'bg-orange-100 text-orange-800',
  APPROVED: 'bg-emerald-100 text-emerald-800',
}

export function Chip({ label }: { label: string }) {
  return (
    <span className={`rounded-full px-2 py-0.5 text-[11px] font-semibold ${CHIP[label] ?? 'bg-slate-100 text-slate-700'}`}>
      {label.replace(/_/g, ' ')}
    </span>
  )
}

export function Chips({ flags }: { flags: string[] }) {
  if (!flags.length) return null
  return (
    <span className="inline-flex flex-wrap gap-1">
      {flags.map((f) => (
        <Chip key={f} label={f} />
      ))}
    </span>
  )
}

export function PageTitle({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="mb-4 flex items-center justify-between gap-2">
      <h1 className="text-xl font-semibold text-brand-700">{title}</h1>
      <div className="flex gap-2">{children}</div>
    </div>
  )
}

export function ErrorNote({ error }: { error: unknown }) {
  if (!error) return null
  const msg = error instanceof Error ? error.message : String(error)
  return <div className="my-2 rounded-lg bg-red-50 p-3 text-sm text-red-700">{msg}</div>
}

export function Loading() {
  return <div className="p-6 text-center text-sm text-slate-500">Loading…</div>
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="label">{label}</span>
      {children}
    </label>
  )
}

export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', h)
    return () => window.removeEventListener('keydown', h)
  }, [onClose])
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 sm:items-center" onClick={onClose}>
      <div
        className="max-h-[90vh] w-full overflow-y-auto rounded-t-2xl bg-white p-5 shadow-xl sm:max-w-lg sm:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-lg font-semibold">{title}</h2>
          <button className="text-slate-400 hover:text-slate-700" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}

/** Every edit/void/approval asks "why?". The reason is stored in the audit trail. */
export function ReasonDialog({
  title,
  confirmLabel,
  danger,
  required = true,
  onConfirm,
  onClose,
}: {
  title: string
  confirmLabel: string
  danger?: boolean
  required?: boolean
  onConfirm: (reason: string) => Promise<unknown>
  onClose: () => void
}) {
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<unknown>(null)
  return (
    <Modal title={title} onClose={onClose}>
      <Field label={required ? 'Reason (required)' : 'Reason'}>
        <textarea className="input" rows={3} value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
      </Field>
      <ErrorNote error={err} />
      <div className="mt-4 flex justify-end gap-2">
        <button className="btn-ghost" onClick={onClose}>
          Cancel
        </button>
        <button
          className={danger ? 'btn-danger' : 'btn-primary'}
          disabled={busy || (required && !reason.trim())}
          onClick={async () => {
            setBusy(true)
            setErr(null)
            try {
              await onConfirm(reason.trim())
              onClose()
            } catch (e) {
              setErr(e)
            } finally {
              setBusy(false)
            }
          }}
        >
          {confirmLabel}
        </button>
      </div>
    </Modal>
  )
}

/** Shows who changed what, when and why for one record. Nothing here can be edited. */
export function HistoryDrawer({ entityType, entityId, onClose }: { entityType: string; entityId: number; onClose: () => void }) {
  const q = useQuery({
    queryKey: ['audit', entityType, entityId],
    queryFn: () => get<AuditRow[]>(`/audit${qs({ entityType, entityId, limit: 100 })}`),
  })
  return (
    <Modal title="History" onClose={onClose}>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      <ol className="space-y-3">
        {q.data?.map((r) => (
          <li key={r.id} className="rounded-lg bg-slate-50 p-3 text-sm">
            <div className="flex justify-between">
              <span className="font-semibold">{r.action}</span>
              <span className="text-xs text-slate-500">{dateTime(r.at)}</span>
            </div>
            <div className="text-xs text-slate-600">
              by {r.userName ?? 'system'} {r.source ? `· ${r.source}` : ''}
            </div>
            {r.reason && <div className="mt-1">“{r.reason}”</div>}
          </li>
        ))}
        {q.data && q.data.length === 0 && <p className="text-sm text-slate-500">No history yet.</p>}
      </ol>
    </Modal>
  )
}

export function DateRange({
  from,
  to,
  onChange,
}: {
  from: string
  to: string
  onChange: (from: string, to: string) => void
}) {
  return (
    <div className="flex flex-wrap items-end gap-2">
      <Field label="From">
        <input type="date" className="input" value={from} onChange={(e) => onChange(e.target.value, to)} />
      </Field>
      <Field label="To">
        <input type="date" className="input" value={to} onChange={(e) => onChange(from, e.target.value)} />
      </Field>
    </div>
  )
}

export function Stat({ label, value, sub }: { label: string; value: ReactNode; sub?: ReactNode }) {
  return (
    <div className="card">
      <div className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</div>
      <div className="mt-1 text-2xl font-semibold">{value}</div>
      {sub && <div className="mt-1 text-xs text-slate-500">{sub}</div>}
    </div>
  )
}
