import type { ReactNode } from 'react'

const BADGE: Record<string, string> = {
  UPLOADED: 'bg-slate-100 text-slate-700',
  MAPPED: 'bg-sky-100 text-sky-800',
  PREVIEWED: 'bg-amber-100 text-amber-800',
  PROCESSED: 'bg-sky-100 text-sky-800',
  DRAFTED: 'bg-amber-100 text-amber-800',
  PENDING_REVIEW: 'bg-amber-100 text-amber-800',
  COMMITTED: 'bg-emerald-100 text-emerald-800',
  CONFIRMED: 'bg-emerald-100 text-emerald-800',
  VALID: 'bg-emerald-100 text-emerald-800',
  REJECTED: 'bg-red-100 text-red-700',
  UNDONE: 'bg-red-100 text-red-700',
  ERROR: 'bg-red-100 text-red-700',
  DUPLICATE: 'bg-orange-100 text-orange-800',
  HIGH: 'bg-emerald-100 text-emerald-800',
  MEDIUM: 'bg-sky-100 text-sky-800',
  LOW: 'bg-amber-100 text-amber-800',
}

const LABEL: Record<string, string> = {
  DRAFTED: 'Needs review',
  PENDING_REVIEW: 'Needs review',
  PROCESSED: 'Read by OCR',
  UPLOADED: 'Uploaded',
}

/** Small status pill used by all Phase 3 pages. */
export function Badge({ value }: { value: string }) {
  return (
    <span className={`rounded-full px-2 py-0.5 text-[11px] font-semibold ${BADGE[value] ?? 'bg-slate-100 text-slate-700'}`}>
      {LABEL[value] ?? value.replace(/_/g, ' ')}
    </span>
  )
}

export function Tabs<T extends string>({
  value,
  options,
  onChange,
}: {
  value: T
  options: { value: T; label: string }[]
  onChange: (v: T) => void
}) {
  return (
    <div className="mb-3 flex flex-wrap gap-2">
      {options.map((o) => (
        <button
          key={o.value}
          className={`rounded-full px-3 py-1.5 text-sm ${value === o.value ? 'bg-brand-700 text-white' : 'bg-white text-slate-700 ring-1 ring-slate-300'}`}
          onClick={() => onChange(o.value)}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="card text-center text-sm text-slate-500">{children}</div>
}

export function errMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** Time of an ISO timestamp in Indian format (date + time). */
export function ago(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const mins = Math.round((Date.now() - d.getTime()) / 60000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins} min ago`
  const hrs = Math.round(mins / 60)
  if (hrs < 24) return `${hrs} h ago`
  return d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })
}
