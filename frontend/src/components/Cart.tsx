import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { get, qs } from '../api'
import { inr, toNumber } from '../format'
import type { MenuDetail, MenuItem } from '../types'
import { ErrorNote, Loading } from './ui'

export interface CartLine {
  key: string
  productId: number
  variantId: number
  name: string
  listPrice: number
  quantity: number
  unitPrice: number | null // null = use menu price
  discount: number
}

export function lineTotal(l: CartLine): number {
  return Math.max(l.quantity * (l.unitPrice ?? l.listPrice) - l.discount, 0)
}
export function cartTotal(lines: CartLine[]): number {
  return Math.round(lines.reduce((s, l) => s + lineTotal(l), 0) * 100) / 100
}

export function useMenuFor(date: string) {
  return useQuery({
    queryKey: ['menu-active', date],
    queryFn: () => get<MenuDetail>(`/menus/active${qs({ date })}`),
    retry: false,
  })
}

/** Menu browser: category chips + search; tap an item to add it. Prices shown are those in force on `date`. */
export function ItemPicker({ date, onAdd }: { date: string; onAdd: (i: MenuItem) => void }) {
  const q = useMenuFor(date)
  const [cat, setCat] = useState<string>('')
  const [search, setSearch] = useState('')
  const items = q.data?.items ?? []
  const cats = useMemo(() => Array.from(new Set(items.map((i) => i.categoryName))), [items])
  const shown = items.filter(
    (i) =>
      (!cat || i.categoryName === cat) &&
      (!search || `${i.displayName} ${i.variantName}`.toLowerCase().includes(search.toLowerCase())),
  )
  if (q.isLoading) return <Loading />
  if (q.error) return <ErrorNote error={q.error} />
  return (
    <div>
      <div className="mb-1 text-xs text-slate-500">
        Menu: <b>{q.data?.version.name}</b>
      </div>
      <input className="input mb-2" placeholder="Search item…" value={search} onChange={(e) => setSearch(e.target.value)} />
      <div className="-mx-1 mb-2 flex gap-1 overflow-x-auto pb-1">
        {['', ...cats].map((c) => (
          <button
            key={c || 'all'}
            onClick={() => setCat(c)}
            className={`shrink-0 rounded-full px-3 py-1 text-xs ${cat === c ? 'bg-brand-700 text-white' : 'bg-white ring-1 ring-slate-300'}`}
          >
            {c || 'All'}
          </button>
        ))}
      </div>
      <div className="grid max-h-72 grid-cols-2 gap-2 overflow-y-auto sm:grid-cols-3">
        {shown.map((i) => (
          <button
            key={i.id}
            disabled={i.availability !== 'AVAILABLE'}
            onClick={() => onAdd(i)}
            className="rounded-lg bg-white p-2 text-left text-sm ring-1 ring-slate-200 hover:ring-brand-500 disabled:opacity-40"
          >
            <div className="font-medium leading-tight">{i.displayName}</div>
            <div className="text-xs text-slate-500">{i.variantName !== 'Regular' ? i.variantName : ''}</div>
            <div className="mt-1 font-semibold text-brand-700">{inr(i.price, { decimals: 0 })}</div>
          </button>
        ))}
      </div>
    </div>
  )
}

export function addToCart(lines: CartLine[], i: MenuItem): CartLine[] {
  const key = `${i.productId}-${i.variantId}`
  const found = lines.find((l) => l.key === key)
  if (found) return lines.map((l) => (l.key === key ? { ...l, quantity: l.quantity + 1 } : l))
  return [
    ...lines,
    {
      key,
      productId: i.productId,
      variantId: i.variantId,
      name: i.variantName && i.variantName !== 'Regular' ? `${i.displayName} (${i.variantName})` : i.displayName,
      listPrice: toNumber(i.price),
      quantity: 1,
      unitPrice: null,
      discount: 0,
    },
  ]
}

export function CartTable({ lines, onChange }: { lines: CartLine[]; onChange: (l: CartLine[]) => void }) {
  const set = (key: string, patch: Partial<CartLine>) => onChange(lines.map((l) => (l.key === key ? { ...l, ...patch } : l)))
  if (!lines.length) return <p className="py-4 text-center text-sm text-slate-500">Tap items above to add them.</p>
  return (
    <div className="divide-y divide-slate-100">
      {lines.map((l) => (
        <div key={l.key} className="py-2">
          <div className="flex items-start justify-between gap-2">
            <div className="text-sm font-medium">{l.name}</div>
            <div className="text-sm font-semibold">{inr(lineTotal(l))}</div>
          </div>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-xs">
            <div className="flex items-center">
              <button className="btn-ghost px-2 py-0.5" onClick={() => set(l.key, { quantity: Math.max(l.quantity - 1, 0) })}>
                −
              </button>
              <span className="w-8 text-center text-sm">{l.quantity}</span>
              <button className="btn-ghost px-2 py-0.5" onClick={() => set(l.key, { quantity: l.quantity + 1 })}>
                +
              </button>
            </div>
            <label className="flex items-center gap-1">
              Price
              <input
                className="input w-20 py-1 text-sm"
                inputMode="decimal"
                value={l.unitPrice ?? ''}
                placeholder={String(l.listPrice)}
                onChange={(e) => set(l.key, { unitPrice: e.target.value === '' ? null : toNumber(e.target.value) })}
              />
            </label>
            <label className="flex items-center gap-1">
              Disc ₹
              <input
                className="input w-16 py-1 text-sm"
                inputMode="decimal"
                value={l.discount || ''}
                onChange={(e) => set(l.key, { discount: toNumber(e.target.value) })}
              />
            </label>
            <button className="text-red-600" onClick={() => onChange(lines.filter((x) => x.key !== l.key))}>
              Remove
            </button>
          </div>
          {l.unitPrice !== null && l.unitPrice !== l.listPrice && (
            <div className="mt-1 text-[11px] text-amber-700">Price differs from menu ({inr(l.listPrice)}). It is recorded as an override.</div>
          )}
        </div>
      ))}
    </div>
  )
}

export const PAY_METHODS = ['CASH', 'UPI', 'CARD', 'WHATSAPP', 'SWIGGY', 'ZOMATO', 'OTHER_ONLINE']
