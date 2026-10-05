import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post, qs } from '../../api'
import { dmy, inr, todayIso } from '../../format'
import { useAuth } from '../../auth'
import { ErrorNote, Field, Loading, Modal, PageTitle } from '../../components/ui'
import type { RecipeCost, RecipeDetail, RecipeVariant } from '../../types.phase2'
import { Tabs, Tag, Tags, UNIT_CHOICES, pct, qty, unitCost, useIngredients } from './shared'

interface Selected { variantId?: number; ingredientId?: number; title: string; outputUnit: string }

export default function Recipes() {
  const [sel, setSel] = useState<Selected | null>(null)
  const [tab, setTab] = useState('products')
  return (
    <div>
      <PageTitle title="Recipes" />
      {sel ? (
        <RecipePanel sel={sel} onBack={() => setSel(null)} />
      ) : (
        <>
          <Tabs
            tabs={[
              { id: 'products', label: 'Products' },
              { id: 'prepared', label: 'Prepared items' },
            ]}
            value={tab}
            onChange={setTab}
          />
          {tab === 'products' ? <ProductList onPick={setSel} /> : <PreparedList onPick={setSel} />}
        </>
      )}
    </div>
  )
}

function ProductList({ onPick }: { onPick: (s: Selected) => void }) {
  const [search, setSearch] = useState('')
  const [onlyMissing, setOnlyMissing] = useState(false)
  const q = useQuery({ queryKey: ['recipe-variants'], queryFn: () => get<RecipeVariant[]>('/recipes/variants') })
  const rows = (q.data ?? []).filter(
    (v) =>
      `${v.productName} ${v.variantName}`.toLowerCase().includes(search.toLowerCase()) &&
      (!onlyMissing || v.status === 'NO_RECIPE' || v.status === 'MISSING' || v.status === 'PARTIAL'),
  )
  const showCost = q.data?.some((v) => v.costPerPortion !== null) ?? false
  return (
    <div>
      <input className="input mb-2" placeholder="Search products" value={search} onChange={(e) => setSearch(e.target.value)} />
      <label className="mb-3 flex items-center gap-2 text-sm">
        <input type="checkbox" checked={onlyMissing} onChange={(e) => setOnlyMissing(e.target.checked)} /> Only products needing attention
      </label>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      <div className="space-y-2">
        {rows.map((v) => (
          <button
            key={v.variantId}
            className="card block w-full p-3 text-left"
            onClick={() => onPick({ variantId: v.variantId, title: `${v.productName} - ${v.variantName}`, outputUnit: 'portion' })}
          >
            <div className="flex items-start justify-between gap-2">
              <div>
                <div className="font-medium">{v.productName}</div>
                <div className="text-xs text-slate-500">
                  {v.variantName} · price {inr(v.menuPriceInclusive, { blankIfNull: true })}
                </div>
              </div>
              <div className="text-right">
                <Tag label={v.status === 'RECIPE' ? 'COMPLETE' : v.status} />
                {showCost && v.costPerPortion !== null && (
                  <div className="mt-1 text-sm">
                    {inr(v.costPerPortion)} <span className="text-xs text-slate-500">margin {pct(v.marginPct)}</span>
                  </div>
                )}
              </div>
            </div>
          </button>
        ))}
        {q.data && rows.length === 0 && <p className="text-sm text-slate-500">Nothing to show.</p>}
      </div>
    </div>
  )
}

function PreparedList({ onPick }: { onPick: (s: Selected) => void }) {
  const q = useIngredients()
  const rows = (q.data ?? []).filter((i) => i.kind === 'PREPARED')
  return (
    <div className="space-y-2">
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      {rows.map((i) => (
        <button key={i.id} className="card block w-full p-3 text-left" onClick={() => onPick({ ingredientId: i.id, title: i.name, outputUnit: i.baseUnit })}>
          <div className="flex justify-between">
            <span className="font-medium">{i.name}</span>
            <Tags labels={[i.hasRecipe ? null : 'NO_RECIPE']} />
          </div>
          <div className="text-xs text-slate-500">Semi-finished · per {i.baseUnit}</div>
        </button>
      ))}
      {q.data && rows.length === 0 && <p className="text-sm text-slate-500">Mark an ingredient as “Prepared” (sauce, batter…) to give it a recipe.</p>}
    </div>
  )
}

function RecipePanel({ sel, onBack }: { sel: Selected; onBack: () => void }) {
  const { can } = useAuth()
  const canCosts = can('costing.view') && can('finance.view_profit')
  const params = qs({ variantId: sel.variantId, ingredientId: sel.ingredientId })
  const detail = useQuery({ queryKey: ['recipe-detail', sel.variantId, sel.ingredientId], queryFn: () => get<RecipeDetail>(`/recipes/detail${params}`) })
  const cost = useQuery({
    queryKey: ['recipe-cost', sel.variantId, sel.ingredientId],
    queryFn: () => get<RecipeCost>(`/recipes/cost${qs({ variantId: sel.variantId, ingredientId: sel.ingredientId, asOf: todayIso() })}`),
    enabled: canCosts,
  })
  const [adding, setAdding] = useState(false)
  const latest = detail.data?.versions[0]
  const c = cost.data
  return (
    <div>
      <button className="mb-2 text-sm text-brand-700 underline" onClick={onBack}>
        ← All recipes
      </button>
      <div className="mb-3 flex items-center justify-between gap-2">
        <h2 className="text-lg font-semibold">{sel.title}</h2>
        {can('recipes.edit') && (
          <button className="btn-primary" onClick={() => setAdding(true)}>
            {latest ? 'New version' : 'Create recipe'}
          </button>
        )}
      </div>
      {detail.isLoading && <Loading />}
      <ErrorNote error={detail.error} />
      {canCosts && (
        <div className="card mb-3">
          <div className="mb-1 flex items-center justify-between">
            <div className="label mb-0">Cost today</div>
            {c && <Tags labels={[c.status, c.estimated ? 'ESTIMATED' : null]} />}
          </div>
          {cost.isLoading && <Loading />}
          <ErrorNote error={cost.error} />
          {c && c.status === 'NO_RECIPE' && <p className="text-sm text-slate-500">No recipe is in force today.</p>}
          {c && c.status !== 'NO_RECIPE' && (
            <>
              <div className="grid grid-cols-2 gap-2 text-sm sm:grid-cols-4">
                <div>
                  <div className="text-xs text-slate-500">Cost per {sel.outputUnit}</div>
                  <div className="text-lg font-semibold">{c.costPerOutput === null ? '—' : sel.variantId ? inr(c.costPerOutput) : unitCost(c.costPerOutput)}</div>
                </div>
                {sel.variantId && (
                  <>
                    <div>
                      <div className="text-xs text-slate-500">Menu price (incl. GST)</div>
                      <div className="text-lg font-semibold">{inr(c.menuPriceInclusive, { blankIfNull: true })}</div>
                    </div>
                    <div>
                      <div className="text-xs text-slate-500">Margin</div>
                      <div className="text-lg font-semibold">{inr(c.marginAmount, { blankIfNull: true })}</div>
                    </div>
                    <div>
                      <div className="text-xs text-slate-500">Margin %</div>
                      <div className="text-lg font-semibold">{pct(c.marginPct)}</div>
                    </div>
                  </>
                )}
              </div>
              {c.missing.length > 0 && <p className="mt-2 text-sm text-red-700">No cost yet for: {c.missing.join(', ')}</p>}
              <table className="mt-3 w-full text-sm">
                <thead>
                  <tr>
                    <th className="th">Ingredient</th>
                    <th className="th text-right">Qty</th>
                    <th className="th text-right">Cost</th>
                  </tr>
                </thead>
                <tbody>
                  {c.lines.map((l) => (
                    <tr key={l.ingredientId} className="border-t border-slate-100">
                      <td className="td">
                        {l.name} {l.status !== 'COMPLETE' && <Tag label={l.status} />}
                        {l.estimated && <Tag label="ESTIMATED" />}
                      </td>
                      <td className="td text-right">{qty(l.quantityBase, l.baseUnit)}</td>
                      <td className="td text-right">{l.lineCost === null ? '—' : inr(l.lineCost)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {c.notes.map((n) => (
                <p key={n} className="mt-1 text-xs text-slate-500">
                  {n}
                </p>
              ))}
            </>
          )}
        </div>
      )}
      <div className="space-y-2">
        {detail.data?.versions.map((v, idx) => (
          <div key={v.id} className="card p-3">
            <div className="flex items-center justify-between">
              <div className="font-medium">
                Version {v.versionNo} {idx === 0 && <span className="text-xs text-slate-500">(latest)</span>}
              </div>
              <div className="text-xs text-slate-500">from {dmy(v.effectiveFrom)}</div>
            </div>
            <div className="text-xs text-slate-500">
              Makes {qty(v.outputQty, sel.outputUnit)}
              {v.reason ? ` · “${v.reason}”` : ''}
            </div>
            <ul className="mt-2 space-y-1 text-sm">
              {v.lines.map((l) => (
                <li key={l.ingredientId} className="flex justify-between">
                  <span>
                    {l.ingredientName} {l.kind === 'PREPARED' && <span className="text-xs text-slate-500">(prepared)</span>}
                  </span>
                  <span>
                    {qty(l.quantityBase, l.baseUnit)}
                    {Number(l.wastePct) > 0 ? ` + ${l.wastePct}% waste` : ''}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        ))}
        {detail.data && detail.data.versions.length === 0 && <p className="text-sm text-slate-500">No recipe yet.</p>}
        <p className="text-xs text-slate-500">Past versions are never edited: a new version applies from its date onwards, so old sales keep their old cost.</p>
      </div>
      {adding && detail.data && <VersionForm sel={sel} latestFrom={latest?.effectiveFrom ?? null} onClose={() => setAdding(false)} />}
    </div>
  )
}

interface FormLine { ingredientId: string; quantity: string; unit: string; wastePct: string }

function VersionForm({ sel, latestFrom, onClose }: { sel: Selected; latestFrom: string | null; onClose: () => void }) {
  const qc = useQueryClient()
  const ings = useIngredients()
  const [from, setFrom] = useState(todayIso())
  const [output, setOutput] = useState('1')
  const [reason, setReason] = useState('')
  const [lines, setLines] = useState<FormLine[]>([{ ingredientId: '', quantity: '', unit: '', wastePct: '' }])
  const byId = (id: string) => ings.data?.find((i) => String(i.id) === id)
  const upd = (i: number, patch: Partial<FormLine>) => setLines((ls) => ls.map((l, k) => (k === i ? { ...l, ...patch } : l)))
  const save = useMutation({
    mutationFn: () =>
      post('/recipes/versions', {
        variantId: sel.variantId,
        ingredientId: sel.ingredientId,
        effectiveFrom: from,
        outputQty: output,
        reason: reason || null,
        lines: lines.map((l) => ({
          ingredientId: Number(l.ingredientId),
          quantity: l.quantity,
          unit: l.unit || byId(l.ingredientId)?.baseUnit,
          wastePct: l.wastePct || null,
        })),
      }),
    onSuccess: () => {
      qc.invalidateQueries()
      onClose()
    },
  })
  const ok = lines.length > 0 && lines.every((l) => l.ingredientId && l.quantity) && !!output
  return (
    <Modal title="Recipe version" onClose={onClose}>
      <div className="space-y-3">
        <div className="grid grid-cols-2 gap-2">
          <Field label="Applies from">
            <input type="date" className="input" value={from} onChange={(e) => setFrom(e.target.value)} />
          </Field>
          <Field label={sel.variantId ? 'Makes (portions)' : `Makes (${sel.outputUnit})`}>
            <input className="input" inputMode="decimal" value={output} onChange={(e) => setOutput(e.target.value)} />
          </Field>
        </div>
        {latestFrom && <p className="text-xs text-slate-500">Must be after the latest version ({dmy(latestFrom)}).</p>}
        <div className="space-y-2">
          {lines.map((l, i) => {
            const ing = byId(l.ingredientId)
            return (
              <div key={i} className="rounded-lg bg-slate-50 p-2">
                <select className="input mb-2" value={l.ingredientId} onChange={(e) => upd(i, { ingredientId: e.target.value, unit: '' })}>
                  <option value="">Ingredient…</option>
                  {(ings.data ?? [])
                    .filter((x) => x.id !== sel.ingredientId)
                    .map((x) => (
                      <option key={x.id} value={x.id}>
                        {x.name} ({x.baseUnit}){x.kind === 'PREPARED' ? ' - prepared' : ''}
                      </option>
                    ))}
                </select>
                <div className="grid grid-cols-3 gap-2">
                  <input className="input" inputMode="decimal" placeholder="Qty" value={l.quantity} onChange={(e) => upd(i, { quantity: e.target.value })} />
                  <select className="input" value={l.unit || ing?.baseUnit || ''} onChange={(e) => upd(i, { unit: e.target.value })}>
                    {(ing ? UNIT_CHOICES[ing.baseUnit] : []).map((u) => (
                      <option key={u}>{u}</option>
                    ))}
                  </select>
                  <input className="input" inputMode="decimal" placeholder="Waste %" value={l.wastePct} onChange={(e) => upd(i, { wastePct: e.target.value })} />
                </div>
                {lines.length > 1 && (
                  <button className="mt-1 text-xs text-red-600 underline" onClick={() => setLines((ls) => ls.filter((_, k) => k !== i))}>
                    Remove line
                  </button>
                )}
              </div>
            )
          })}
          <button className="btn-ghost w-full" onClick={() => setLines((ls) => [...ls, { ingredientId: '', quantity: '', unit: '', wastePct: '' }])}>
            + Add ingredient
          </button>
        </div>
        <Field label="Why this version? (optional)">
          <input className="input" value={reason} onChange={(e) => setReason(e.target.value)} />
        </Field>
        <ErrorNote error={save.error} />
        <button className="btn-primary w-full" disabled={!ok || save.isPending} onClick={() => save.mutate()}>
          Save version
        </button>
      </div>
    </Modal>
  )
}
