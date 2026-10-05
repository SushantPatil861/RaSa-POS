import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post, qs } from '../../api'
import { dmy, inr, monthStartIso, todayIso } from '../../format'
import { useAuth } from '../../auth'
import { DateRange, ErrorNote, Field, Loading, Modal, PageTitle, ReasonDialog } from '../../components/ui'
import type { RecipeVariant, Wastage, WastageSummary } from '../../types.phase2'
import { IngredientSelect, Tabs, Tag, Tags, UNIT_CHOICES, qty, useIngredients } from './shared'

const REASONS = ['EXPIRED', 'SPOILED', 'SPILLAGE', 'OVER_PORTION', 'STAFF_MEAL', 'SAMPLE', 'OTHER']
const label = (r: string) => r.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase())

export default function WastagePage() {
  const { can } = useAuth()
  const qc = useQueryClient()
  const [tab, setTab] = useState('list')
  const [from, setFrom] = useState(monthStartIso())
  const [to, setTo] = useState(todayIso())
  const [reason, setReason] = useState('')
  const [adding, setAdding] = useState(false)
  const [voiding, setVoiding] = useState<Wastage | null>(null)
  const list = useQuery({ queryKey: ['wastage', from, to, reason], queryFn: () => get<Wastage[]>(`/wastage${qs({ from, to, reason })}`) })
  const summary = useQuery({ queryKey: ['wastage-summary', from, to], queryFn: () => get<WastageSummary>(`/wastage/summary${qs({ from, to })}`) })
  return (
    <div>
      <PageTitle title="Wastage">
        {can('wastage.create') && (
          <button className="btn-primary" onClick={() => setAdding(true)}>
            ➕ Add
          </button>
        )}
      </PageTitle>
      <div className="card mb-3 space-y-2">
        <DateRange from={from} to={to} onChange={(f, t) => (setFrom(f), setTo(t))} />
        <select className="input" value={reason} onChange={(e) => setReason(e.target.value)}>
          <option value="">All reasons</option>
          {REASONS.map((r) => (
            <option key={r} value={r}>
              {label(r)}
            </option>
          ))}
        </select>
      </div>
      <Tabs
        tabs={[
          { id: 'list', label: 'Entries' },
          { id: 'summary', label: 'Summary' },
        ]}
        value={tab}
        onChange={setTab}
      />
      {tab === 'list' ? (
        <div className="space-y-2">
          {list.isLoading && <Loading />}
          <ErrorNote error={list.error} />
          {list.data?.map((w) => (
            <div key={w.id} className={`card p-3 ${w.voidState === 'VOIDED' ? 'opacity-60' : ''}`}>
              <div className="flex justify-between gap-2">
                <div className="font-medium">{w.ingredientName ?? `${w.productName} - ${w.variantName}`}</div>
                <div className="text-right">
                  <div>{w.ingredientId ? qty(w.quantity, w.baseUnit) : `${qty(w.quantity)} portion(s)`}</div>
                  <div className="text-sm font-semibold">{w.costTotal === null ? <Tag label={w.costStatus === 'MISSING' ? 'MISSING' : w.costStatus} /> : inr(w.costTotal)}</div>
                </div>
              </div>
              <div className="text-xs text-slate-500">
                {dmy(w.txnDate)} · {label(w.reason)} {w.notes ? `· ${w.notes}` : ''}
              </div>
              <div className="mt-1 flex items-center justify-between">
                <Tags labels={w.flags.filter((f) => f !== 'MISSING' || w.costTotal !== null)} />
                {can('wastage.void') && w.voidState === 'ACTIVE' && (
                  <button className="text-xs text-red-600 underline" onClick={() => setVoiding(w)}>
                    Void
                  </button>
                )}
              </div>
              {w.voidReason && <div className="text-xs text-red-700">Voided: {w.voidReason}</div>}
            </div>
          ))}
          {list.data?.length === 0 && <p className="text-sm text-slate-500">No wastage recorded in this range.</p>}
        </div>
      ) : (
        <div className="card">
          {summary.isLoading && <Loading />}
          <ErrorNote error={summary.error} />
          {summary.data && (
            <>
              <div className="mb-2 flex items-center justify-between">
                <div>
                  <div className="text-xs uppercase text-slate-500">Cost of wastage</div>
                  <div className="text-2xl font-semibold">{summary.data.costsHidden ? 'Hidden' : inr(summary.data.totalCostKnown)}</div>
                </div>
                <Tags labels={summary.data.flags} />
              </div>
              {summary.data.entriesWithoutCost > 0 && !summary.data.costsHidden && (
                <p className="mb-2 text-sm text-orange-800">{summary.data.entriesWithoutCost} entr(ies) have no cost yet, so the total is understated.</p>
              )}
              <div className="label">By reason</div>
              {summary.data.byReason.map((r) => (
                <div key={r.reason} className="flex justify-between border-t border-slate-100 py-1 text-sm">
                  <span>
                    {label(r.reason)} <span className="text-xs text-slate-500">({r.entries})</span>
                  </span>
                  <span>{r.costKnown === null ? '—' : inr(r.costKnown)}</span>
                </div>
              ))}
              <div className="label mt-3">By month</div>
              {summary.data.byMonth.map((r) => (
                <div key={r.period} className="flex justify-between border-t border-slate-100 py-1 text-sm">
                  <span>
                    {r.period} <span className="text-xs text-slate-500">({r.entries})</span>
                  </span>
                  <span>{r.costKnown === null ? '—' : inr(r.costKnown)}</span>
                </div>
              ))}
              {summary.data.entries === 0 && <p className="text-sm text-slate-500">Nothing recorded in this range.</p>}
            </>
          )}
        </div>
      )}
      {adding && <WastageForm onClose={() => setAdding(false)} />}
      {voiding && (
        <ReasonDialog
          title="Void this wastage entry?"
          confirmLabel="Void"
          danger
          onConfirm={async (r) => {
            await post(`/wastage/${voiding.id}/void`, { reason: r })
            qc.invalidateQueries()
          }}
          onClose={() => setVoiding(null)}
        />
      )}
    </div>
  )
}

function WastageForm({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const ings = useIngredients()
  const variants = useQuery({ queryKey: ['recipe-variants'], queryFn: () => get<RecipeVariant[]>('/recipes/variants') })
  const [kind, setKind] = useState<'ingredient' | 'product'>('ingredient')
  const [ingId, setIngId] = useState('')
  const [variantId, setVariantId] = useState('')
  const [quantity, setQuantity] = useState('')
  const [unit, setUnit] = useState('')
  const [reason, setReason] = useState('SPILLAGE')
  const [date, setDate] = useState(todayIso())
  const [notes, setNotes] = useState('')
  const ing = ings.data?.find((i) => String(i.id) === ingId)
  const save = useMutation({
    mutationFn: () =>
      post('/wastage', {
        txnDate: date,
        ingredientId: kind === 'ingredient' ? Number(ingId) : undefined,
        variantId: kind === 'product' ? Number(variantId) : undefined,
        quantity,
        unit: kind === 'ingredient' ? unit || ing?.baseUnit : undefined,
        reason,
        notes: notes || null,
      }),
    onSuccess: () => {
      qc.invalidateQueries()
      onClose()
    },
  })
  const ok = !!quantity && (kind === 'ingredient' ? !!ingId : !!variantId)
  return (
    <Modal title="Record wastage" onClose={onClose}>
      <div className="space-y-3">
        <div className="flex gap-2">
          <button className={kind === 'ingredient' ? 'btn-primary' : 'btn-ghost'} onClick={() => setKind('ingredient')}>
            Ingredient
          </button>
          <button className={kind === 'product' ? 'btn-primary' : 'btn-ghost'} onClick={() => setKind('product')}>
            Finished product
          </button>
        </div>
        {kind === 'ingredient' ? (
          <Field label="Ingredient">
            <IngredientSelect value={ingId} onChange={setIngId} />
          </Field>
        ) : (
          <Field label="Product">
            <select className="input" value={variantId} onChange={(e) => setVariantId(e.target.value)}>
              <option value="">Choose product…</option>
              {variants.data?.map((v) => (
                <option key={v.variantId} value={v.variantId}>
                  {v.productName} - {v.variantName}
                </option>
              ))}
            </select>
          </Field>
        )}
        <div className="grid grid-cols-3 gap-2">
          <div className="col-span-2">
            <Field label={kind === 'ingredient' ? 'Quantity' : 'Portions'}>
              <input className="input" inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} autoFocus />
            </Field>
          </div>
          {kind === 'ingredient' && (
            <Field label="Unit">
              <select className="input" value={unit || ing?.baseUnit || ''} onChange={(e) => setUnit(e.target.value)}>
                {(ing ? UNIT_CHOICES[ing.baseUnit] : []).map((u) => (
                  <option key={u}>{u}</option>
                ))}
              </select>
            </Field>
          )}
        </div>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Reason">
            <select className="input" value={reason} onChange={(e) => setReason(e.target.value)}>
              {REASONS.map((r) => (
                <option key={r} value={r}>
                  {label(r)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Date">
            <input type="date" className="input" value={date} max={todayIso()} onChange={(e) => setDate(e.target.value)} />
          </Field>
        </div>
        <Field label="Notes">
          <input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <ErrorNote error={save.error} />
        <button className="btn-primary w-full" disabled={!ok || save.isPending} onClick={() => save.mutate()}>
          Save
        </button>
      </div>
    </Modal>
  )
}
