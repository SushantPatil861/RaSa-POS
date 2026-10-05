import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { get } from '../../api'
import { toNumber } from '../../format'
import type { Ingredient } from '../../types.phase2'

const TAG: Record<string, string> = {
  COMPLETE: 'bg-emerald-100 text-emerald-800',
  PARTIAL: 'bg-orange-100 text-orange-800',
  MISSING: 'bg-red-100 text-red-800',
  UNKNOWN: 'bg-red-100 text-red-800',
  NO_RECIPE: 'bg-red-100 text-red-800',
  ESTIMATED: 'bg-amber-100 text-amber-800',
  LOW: 'bg-red-100 text-red-800',
  OK: 'bg-emerald-100 text-emerald-800',
  OPEN: 'bg-sky-100 text-sky-800',
  FINISHED: 'bg-slate-100 text-slate-700',
  DRAFT: 'bg-amber-100 text-amber-800',
  POSTED: 'bg-emerald-100 text-emerald-800',
  CLOSED: 'bg-emerald-100 text-emerald-800',
  VOID: 'bg-red-100 text-red-700',
  VOIDED: 'bg-red-100 text-red-700 line-through',
  UNEXPLAINED: 'bg-red-100 text-red-800',
  OVER_SERVING: 'bg-red-100 text-red-800',
  MATCHED: 'bg-emerald-100 text-emerald-800',
  SHORT: 'bg-red-100 text-red-800',
  EXCESS: 'bg-amber-100 text-amber-800',
  PENDING: 'bg-slate-100 text-slate-700',
  NO_BASELINE: 'bg-slate-200 text-slate-700',
  NO_COUNT: 'bg-amber-100 text-amber-800',
  NEGATIVE_STOCK: 'bg-red-100 text-red-800',
  SUMMARY_DAYS_EXCLUDED: 'bg-violet-100 text-violet-800',
  OVERLAPPING_CONTAINERS: 'bg-violet-100 text-violet-800',
  CORRECTED: 'bg-fuchsia-100 text-fuchsia-800',
  CHANGED_SINCE_CLOSE: 'bg-orange-100 text-orange-800',
  PURCHASE_VOIDED: 'bg-red-100 text-red-800',
  ITEM_REMOVED: 'bg-red-100 text-red-800',
  AMOUNT_CHANGED: 'bg-orange-100 text-orange-800',
}

/** Status chip used across Phase 2 (MISSING / PARTIAL / ESTIMATED / NO_RECIPE ...). */
export function Tag({ label }: { label: string }) {
  return (
    <span className={`whitespace-nowrap rounded-full px-2 py-0.5 text-[11px] font-semibold ${TAG[label] ?? 'bg-slate-100 text-slate-700'}`}>
      {label.replace(/_/g, ' ')}
    </span>
  )
}

export function Tags({ labels }: { labels: (string | null | undefined)[] }) {
  const l = labels.filter((x): x is string => !!x)
  if (!l.length) return null
  return (
    <span className="inline-flex flex-wrap gap-1">
      {l.map((x) => (
        <Tag key={x} label={x} />
      ))}
    </span>
  )
}

/** Quantity with Indian digit grouping and its unit; null means "not available" (never shown as 0). */
export function qty(v: string | number | null | undefined, unit?: string | null, decimals = 2): string {
  if (v === null || v === undefined || v === '') return '—'
  const n = toNumber(v)
  const s = new Intl.NumberFormat('en-IN', { maximumFractionDigits: decimals }).format(n)
  return unit ? `${s} ${unit}` : s
}

/** Per-unit cost needs more decimals than rupees (e.g. 0.0357 per g). */
export function unitCost(v: string | number | null | undefined, unit?: string): string {
  if (v === null || v === undefined || v === '') return '—'
  const n = toNumber(v)
  const s = `₹${new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(n)}`
  return unit ? `${s}/${unit}` : s
}

export function pct(v: string | number | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—'
  return `${new Intl.NumberFormat('en-IN', { maximumFractionDigits: 1 }).format(toNumber(v))}%`
}

export function useIngredients(includeInactive = false) {
  return useQuery({
    queryKey: ['ingredients', includeInactive],
    queryFn: () => get<Ingredient[]>(`/ingredients?includeInactive=${includeInactive}`),
  })
}

export function Tabs({ tabs, value, onChange }: { tabs: { id: string; label: string }[]; value: string; onChange: (id: string) => void }) {
  return (
    <div className="mb-3 flex gap-1 overflow-x-auto rounded-xl bg-white p-1 ring-1 ring-slate-200">
      {tabs.map((t) => (
        <button
          key={t.id}
          onClick={() => onChange(t.id)}
          className={`whitespace-nowrap rounded-lg px-3 py-2 text-sm font-medium ${
            value === t.id ? 'bg-brand-700 text-white' : 'text-slate-600 hover:bg-slate-50'
          }`}
        >
          {t.label}
        </button>
      ))}
    </div>
  )
}

export function Banner({ tone, children }: { tone: 'warn' | 'info' | 'bad'; children: ReactNode }) {
  const cls =
    tone === 'bad' ? 'bg-red-50 text-red-800 ring-red-200' : tone === 'warn' ? 'bg-amber-50 text-amber-900 ring-amber-200' : 'bg-sky-50 text-sky-900 ring-sky-200'
  return <div className={`mb-3 rounded-xl p-3 text-sm ring-1 ${cls}`}>{children}</div>
}

/** A small hook for "pick an ingredient" selects so each page does not repeat the markup. */
export function IngredientSelect({
  value,
  onChange,
  only,
  includeEmpty = true,
}: {
  value: string
  onChange: (v: string) => void
  only?: 'RAW' | 'PREPARED'
  includeEmpty?: boolean
}) {
  const q = useIngredients()
  const list = (q.data ?? []).filter((i) => !only || i.kind === only)
  return (
    <select className="input" value={value} onChange={(e) => onChange(e.target.value)}>
      {includeEmpty && <option value="">Choose ingredient…</option>}
      {list.map((i) => (
        <option key={i.id} value={i.id}>
          {i.name} ({i.baseUnit})
        </option>
      ))}
    </select>
  )
}

export function useToggle(initial = false): [boolean, () => void, (v: boolean) => void] {
  const [v, set] = useState(initial)
  return [v, () => set((x) => !x), set]
}

export const UNIT_CHOICES: Record<string, string[]> = { g: ['g', 'kg'], ml: ['ml', 'l'], pcs: ['pcs'] }
