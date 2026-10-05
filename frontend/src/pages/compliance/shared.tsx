import type { ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import { isoDate } from '../../format'

export type Tone = 'green' | 'amber' | 'red' | 'slate' | 'sky' | 'violet'

const TONES: Record<Tone, string> = {
  green: 'bg-emerald-100 text-emerald-800',
  amber: 'bg-amber-100 text-amber-800',
  red: 'bg-red-100 text-red-700',
  slate: 'bg-slate-100 text-slate-700',
  sky: 'bg-sky-100 text-sky-800',
  violet: 'bg-violet-100 text-violet-800',
}

export function Pill({ tone = 'slate', children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`inline-block rounded-full px-2 py-0.5 text-[11px] font-semibold ${TONES[tone]}`}>{children}</span>
}

export function Banner({ tone = 'amber', children }: { tone?: 'amber' | 'red' | 'sky' | 'green'; children: ReactNode }) {
  const cls = {
    amber: 'bg-amber-50 text-amber-900 ring-amber-200',
    red: 'bg-red-50 text-red-800 ring-red-200',
    sky: 'bg-sky-50 text-sky-900 ring-sky-200',
    green: 'bg-emerald-50 text-emerald-900 ring-emerald-200',
  }[tone]
  return <div className={`my-3 rounded-lg p-3 text-sm ring-1 ${cls}`}>{children}</div>
}

/** Pill tabs kept in the URL (?tab=...) so a page can be linked to. */
export function useTab(ids: string[], initial?: string): [string, (id: string) => void] {
  const [sp, setSp] = useSearchParams()
  const q = sp.get('tab')
  const tab = q && ids.includes(q) ? q : initial && ids.includes(initial) ? initial : ids[0] ?? ''
  return [tab, (id: string) => setSp({ tab: id }, { replace: true })]
}

export function TabBar({ tabs, value, onChange }: { tabs: { id: string; label: string }[]; value: string; onChange: (id: string) => void }) {
  return (
    <div className="mb-3 flex gap-1 overflow-x-auto pb-1">
      {tabs.map((t) => (
        <button
          key={t.id}
          onClick={() => onChange(t.id)}
          className={`shrink-0 rounded-full px-3 py-1.5 text-sm ${value === t.id ? 'bg-brand-700 text-white' : 'bg-white ring-1 ring-slate-300'}`}
        >
          {t.label}
        </button>
      ))}
    </div>
  )
}

export function Notice({ children }: { children: ReactNode }) {
  return <p className="my-2 rounded-lg bg-slate-50 p-3 text-xs text-slate-600">{children}</p>
}

/** Triggers a browser download of a Blob (used with api.download for CSV exports). */
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/** "2026-09-01" -> "Sep 2026" */
export function monthLabel(iso: string): string {
  const m = /^(\d{4})-(\d{2})/.exec(iso)
  return m ? `${MONTHS[Number(m[2]) - 1]} ${m[1]}` : iso
}

export function monthStart(iso: string): string {
  return iso.slice(0, 8) + '01'
}

export function addMonths(iso: string, n: number): string {
  const [y, m] = iso.split('-').map(Number)
  return isoDate(new Date(y, m - 1 + n, 1))
}

export function daysInMonth(iso: string): number {
  const [y, m] = iso.split('-').map(Number)
  return new Date(y, m, 0).getDate()
}

/** Indian financial year starting calendar year for an ISO date. */
export function fyStartYear(iso: string): number {
  const [y, m] = iso.split('-').map(Number)
  return m >= 4 ? y : y - 1
}

export function Row({ label, value, strong, muted }: { label: ReactNode; value: ReactNode; strong?: boolean; muted?: boolean }) {
  return (
    <div className={`flex justify-between gap-3 border-b border-slate-100 py-1.5 text-sm ${strong ? 'font-semibold' : ''} ${muted ? 'text-slate-500' : ''}`}>
      <span>{label}</span>
      <span className="text-right tabular-nums">{value}</span>
    </div>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="py-4 text-center text-sm text-slate-500">{children}</p>
}
