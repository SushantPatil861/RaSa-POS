import { useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, patch, post, qs } from '../api'
import { dmy, inr, todayIso } from '../format'
import type { Comparison, MenuDetail, MenuVersion } from '../types'
import { Chip, ErrorNote, Field, HistoryDrawer, Loading, Modal, PageTitle } from '../components/ui'
import { useAuth } from '../auth'

export function MenuList() {
  const { can } = useAuth()
  const nav = useNavigate()
  const [creating, setCreating] = useState(false)
  const q = useQuery({ queryKey: ['menus'], queryFn: () => get<MenuVersion[]>('/menus') })
  return (
    <div>
      <PageTitle title="Menu versions">
        {can('menu.edit') && (
          <button className="btn-primary" onClick={() => setCreating(true)}>
            ➕ New version
          </button>
        )}
      </PageTitle>
      <p className="mb-3 text-sm text-slate-600">
        A menu is never overwritten. A new version takes over on the date you choose; older bills keep the prices that applied then.
      </p>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      <div className="space-y-2">
        {q.data?.map((m) => (
          <Link key={m.id} to={`/menus/${m.id}`} className="card flex items-center justify-between">
            <div>
              <div className="font-semibold">{m.name}</div>
              <div className="text-xs text-slate-500">
                {m.effectiveFrom ? `Effective from ${dmy(m.effectiveFrom)}` : 'No date yet'} · {m.itemCount} items
              </div>
            </div>
            <Chip label={m.status} />
          </Link>
        ))}
      </div>
      {(q.data?.length ?? 0) > 1 && (
        <Link className="mt-4 inline-block text-sm text-brand-700 underline" to="/menus/compare">
          Compare two versions →
        </Link>
      )}
      {creating && <CreateMenu versions={q.data ?? []} onClose={() => setCreating(false)} onCreated={(id) => nav(`/menus/${id}`)} />}
    </div>
  )
}

function CreateMenu({ versions, onClose, onCreated }: { versions: MenuVersion[]; onClose: () => void; onCreated: (id: number) => void }) {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [copy, setCopy] = useState<string>(versions.find((v) => v.status === 'ACTIVE')?.id.toString() ?? '')
  const [reason, setReason] = useState('')
  const m = useMutation({
    mutationFn: () => post<MenuDetail>('/menus', { name, reason, copyFromId: copy ? Number(copy) : null, blank: !copy }),
    onSuccess: (d) => {
      qc.invalidateQueries({ queryKey: ['menus'] })
      onCreated(d.version.id)
    },
  })
  return (
    <Modal title="New menu version" onClose={onClose}>
      <div className="space-y-3">
        <Field label="Name">
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Winter 2026 Menu" />
        </Field>
        <Field label="Start from">
          <select className="input" value={copy} onChange={(e) => setCopy(e.target.value)}>
            <option value="">Blank menu</option>
            {versions.map((v) => (
              <option key={v.id} value={v.id}>
                Copy of {v.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Why is this change being made?">
          <input className="input" value={reason} onChange={(e) => setReason(e.target.value)} />
        </Field>
        <ErrorNote error={m.error} />
        <button className="btn-primary w-full" disabled={!name.trim() || m.isPending} onClick={() => m.mutate()}>
          Create draft
        </button>
      </div>
    </Modal>
  )
}

export function MenuDetailPage() {
  const { id } = useParams()
  const { can } = useAuth()
  const qc = useQueryClient()
  const [sched, setSched] = useState(false)
  const [hist, setHist] = useState(false)
  const [date, setDate] = useState('')
  const [reason, setReason] = useState('')
  const [filter, setFilter] = useState('')
  const q = useQuery({ queryKey: ['menu', id], queryFn: () => get<MenuDetail>(`/menus/${id}`) })
  const refresh = () => qc.invalidateQueries()
  const setPrice = useMutation({
    mutationFn: ({ itemId, price }: { itemId: number; price: string }) => patch(`/menus/${id}/items/${itemId}`, { price }),
    onSuccess: refresh,
  })
  const schedule = useMutation({
    mutationFn: () => post(`/menus/${id}/schedule`, { effectiveFrom: date, reason }),
    onSuccess: () => (refresh(), setSched(false)),
  })
  const unschedule = useMutation({ mutationFn: () => post(`/menus/${id}/unschedule`), onSuccess: refresh })
  if (q.isLoading) return <Loading />
  if (q.error) return <ErrorNote error={q.error} />
  const { version, items } = q.data!
  const editable = version.editable && can('menu.edit')
  const cats = Array.from(new Set(items.map((i) => i.categoryName)))
  return (
    <div>
      <PageTitle title={version.name}>
        <button className="btn-ghost" onClick={() => setHist(true)}>
          History
        </button>
        {version.status === 'DRAFT' && can('menu.publish') && (
          <button className="btn-primary" onClick={() => setSched(true)}>
            Schedule…
          </button>
        )}
        {version.status === 'SCHEDULED' && can('menu.publish') && (
          <button className="btn-ghost" onClick={() => unschedule.mutate()}>
            Back to draft
          </button>
        )}
      </PageTitle>
      <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
        <Chip label={version.status} />
        {version.effectiveFrom && <span>Effective from {dmy(version.effectiveFrom)}</span>}
        {!version.editable && <span className="text-slate-500">· Frozen history — create a new version to change prices.</span>}
      </div>
      <ErrorNote error={setPrice.error || unschedule.error} />
      <input className="input mb-3" placeholder="Filter items…" value={filter} onChange={(e) => setFilter(e.target.value)} />
      {cats.map((c) => {
        const rows = items.filter((i) => i.categoryName === c && `${i.displayName} ${i.variantName}`.toLowerCase().includes(filter.toLowerCase()))
        if (!rows.length) return null
        return (
          <div key={c} className="card mb-3">
            <div className="mb-1 font-semibold text-brand-700">{c}</div>
            <table className="w-full">
              <tbody>
                {rows.map((i) => (
                  <tr key={i.id} className="border-t border-slate-100">
                    <td className="td">
                      {i.displayName} {i.variantName !== 'Regular' && <span className="text-slate-500">({i.variantName})</span>}
                      {i.availability !== 'AVAILABLE' && <span className="ml-1 text-xs text-red-600">{i.availability}</span>}
                    </td>
                    <td className="td w-28 text-right">
                      {editable ? (
                        <PriceInput value={i.price} onSave={(price) => setPrice.mutate({ itemId: i.id, price })} />
                      ) : (
                        inr(i.price, { decimals: 0 })
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      })}
      {sched && (
        <Modal title="From which date should this menu be effective?" onClose={() => setSched(false)}>
          <p className="mb-3 text-sm text-slate-600">
            The current menu stays in force until this date. Bills dated earlier keep the old prices. Choose a future date, or today to switch right away.
          </p>
          <Field label="Effective from">
            <input type="date" className="input" value={date} min={todayIso()} onChange={(e) => setDate(e.target.value)} />
          </Field>
          <Field label="Reason for the change">
            <input className="input" value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
          <ErrorNote error={schedule.error} />
          <button className="btn-primary mt-3 w-full" disabled={!date || schedule.isPending} onClick={() => schedule.mutate()}>
            Schedule menu
          </button>
        </Modal>
      )}
      {hist && <HistoryDrawer entityType="MENU_VERSION" entityId={version.id} onClose={() => setHist(false)} />}
    </div>
  )
}

function PriceInput({ value, onSave }: { value: string; onSave: (v: string) => void }) {
  const [v, setV] = useState(value)
  return (
    <input
      className="input w-24 py-1 text-right"
      inputMode="decimal"
      value={v}
      onChange={(e) => setV(e.target.value)}
      onBlur={() => v !== value && v !== '' && onSave(v)}
    />
  )
}

export function MenuCompare() {
  const [sp, setSp] = useSearchParams()
  const versions = useQuery({ queryKey: ['menus'], queryFn: () => get<MenuVersion[]>('/menus') })
  const a = sp.get('a') ?? ''
  const b = sp.get('b') ?? ''
  const cmp = useQuery({
    queryKey: ['cmp', a, b],
    enabled: !!a && !!b && a !== b,
    queryFn: () => get<Comparison>(`/menus/compare${qs({ a, b })}`),
  })
  const sel = (key: 'a' | 'b', val: string) => {
    const n = new URLSearchParams(sp)
    n.set(key, val)
    setSp(n)
  }
  const changed = cmp.data?.rows.filter((r) => r.changes.length) ?? []
  return (
    <div>
      <PageTitle title="Compare menus" />
      <div className="card mb-4 grid grid-cols-2 gap-2">
        {(['a', 'b'] as const).map((k) => (
          <Field key={k} label={k === 'a' ? 'Older' : 'Newer'}>
            <select className="input" value={k === 'a' ? a : b} onChange={(e) => sel(k, e.target.value)}>
              <option value="">Select…</option>
              {versions.data?.map((v) => (
                <option key={v.id} value={v.id}>
                  {v.name}
                </option>
              ))}
            </select>
          </Field>
        ))}
      </div>
      {cmp.isLoading && <Loading />}
      <ErrorNote error={cmp.error} />
      {cmp.data && (
        <>
          <div className="mb-3 grid grid-cols-3 gap-2 text-center text-xs md:grid-cols-6">
            {[
              ['Added', cmp.data.added],
              ['Removed', cmp.data.removed],
              ['Renamed', cmp.data.renamed],
              ['Price ↑', cmp.data.priceUp],
              ['Price ↓', cmp.data.priceDown],
              ['Unchanged', cmp.data.unchanged],
            ].map(([l, n]) => (
              <div key={l as string} className="card p-2">
                <div className="text-lg font-semibold">{n}</div>
                {l}
              </div>
            ))}
          </div>
          <div className="card overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr>
                  <th className="th">Item</th>
                  <th className="th text-right">Old</th>
                  <th className="th text-right">New</th>
                  <th className="th">Change</th>
                </tr>
              </thead>
              <tbody>
                {changed.map((r) => (
                  <tr key={`${r.productId}-${r.variantId}`} className="border-t border-slate-100">
                    <td className="td">
                      {r.name} {r.variantName !== 'Regular' && <span className="text-slate-500">({r.variantName})</span>}
                    </td>
                    <td className="td text-right">{r.oldPrice ? inr(r.oldPrice) : '—'}</td>
                    <td className="td text-right">{r.newPrice ? inr(r.newPrice) : '—'}</td>
                    <td className="td text-xs">{r.changes.join(', ')}{r.pct ? ` (${r.pct}%)` : ''}</td>
                  </tr>
                ))}
                {changed.length === 0 && (
                  <tr>
                    <td className="td text-slate-500" colSpan={4}>
                      No differences.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  )
}
