import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post, put, qs } from '../../api'
import { dmy, inr, monthStartIso, todayIso } from '../../format'
import { useAuth } from '../../auth'
import { DateRange, ErrorNote, Field, Loading, Modal, PageTitle, ReasonDialog } from '../../components/ui'
import type {
  CountDetail, CountHeader, Movement, PrepBatch, StockReport, UsageReport, VarianceReport,
} from '../../types.phase2'
import { Banner, IngredientSelect, Tabs, Tag, Tags, UNIT_CHOICES, qty, unitCost, useIngredients } from './shared'

export default function Stock() {
  const [tab, setTab] = useState('hand')
  const { can } = useAuth()
  return (
    <div>
      <PageTitle title="Stock" />
      <Tabs
        tabs={[
          { id: 'hand', label: 'On hand' },
          { id: 'counts', label: 'Stock count' },
          { id: 'moves', label: 'Movements' },
          { id: 'prep', label: 'Prep batches' },
          { id: 'reports', label: 'Reports' },
        ]}
        value={tab}
        onChange={setTab}
      />
      {tab === 'hand' && <OnHand />}
      {tab === 'counts' && <Counts canManage={can('inventory.manage')} />}
      {tab === 'moves' && <Movements canManage={can('inventory.manage')} />}
      {tab === 'prep' && <PrepBatches canManage={can('inventory.manage')} />}
      {tab === 'reports' && <Reports />}
    </div>
  )
}

// ---------------------------------------------------------------- on hand

function OnHand() {
  const [lowOnly, setLowOnly] = useState(false)
  const q = useQuery({ queryKey: ['stock'], queryFn: () => get<StockReport>('/inventory/stock') })
  const rows = (q.data?.rows ?? []).filter((r) => !lowOnly || r.lowStock)
  return (
    <div>
      <label className="mb-3 flex items-center gap-2 text-sm">
        <input type="checkbox" checked={lowOnly} onChange={(e) => setLowOnly(e.target.checked)} /> Low stock only
      </label>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      {q.data?.notes.map((n) => (
        <Banner key={n} tone="info">
          {n}
        </Banner>
      ))}
      <div className="space-y-2">
        {rows.map((r) => (
          <div key={r.ingredientId} className="card p-3">
            <div className="flex items-start justify-between gap-2">
              <div>
                <div className="font-medium">{r.name}</div>
                <div className="text-xs text-slate-500">
                  {r.category ?? ''} {r.minLevel ? `· par ${qty(r.minLevel, r.baseUnit)}` : ''}
                </div>
              </div>
              <div className="text-right">
                <div className={`text-lg font-semibold ${r.lowStock ? 'text-red-700' : ''}`}>
                  {r.expectedStock === null ? 'Not known' : qty(r.expectedStock, r.baseUnit)}
                </div>
                <Tags labels={[r.lowStock ? 'LOW' : null, ...r.flags]} />
              </div>
            </div>
            {r.baseline !== 'NONE' && (
              <div className="mt-1 text-xs text-slate-500">
                {r.lastCountDate ? `Counted ${qty(r.lastCountQty, r.baseUnit)} on ${dmy(r.lastCountDate)}` : 'No count yet'} · moves since{' '}
                {qty(r.movementsSinceBaseline ?? '0', r.baseUnit)} · sales usage −{qty(r.theoreticalUsage ?? '0', r.baseUnit)} · recorded balance{' '}
                {qty(r.ledgerBalance, r.baseUnit)}
              </div>
            )}
          </div>
        ))}
        {q.data && rows.length === 0 && <p className="text-sm text-slate-500">{lowOnly ? 'Nothing is below its par level.' : 'No ingredients yet.'}</p>}
      </div>
    </div>
  )
}

// ---------------------------------------------------------------- movements

function Movements({ canManage }: { canManage: boolean }) {
  const qc = useQueryClient()
  const [from, setFrom] = useState(monthStartIso())
  const [to, setTo] = useState(todayIso())
  const [ing, setIng] = useState('')
  const [adding, setAdding] = useState(false)
  const [reversing, setReversing] = useState<Movement | null>(null)
  const q = useQuery({
    queryKey: ['movements', from, to, ing],
    queryFn: () => get<Movement[]>(`/inventory/movements${qs({ from, to, ingredientId: ing })}`),
  })
  return (
    <div>
      <div className="card mb-3 space-y-2">
        <DateRange from={from} to={to} onChange={(f, t) => (setFrom(f), setTo(t))} />
        <IngredientSelect value={ing} onChange={setIng} />
        {canManage && (
          <button className="btn-primary" onClick={() => setAdding(true)}>
            ➕ Opening stock / adjustment
          </button>
        )}
      </div>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      <div className="space-y-2">
        {q.data?.map((m) => (
          <div key={m.id} className={`card p-3 ${m.reversed ? 'opacity-60' : ''}`}>
            <div className="flex justify-between">
              <div className="font-medium">{m.ingredientName}</div>
              <div className={`font-semibold ${Number(m.quantityBase) < 0 ? 'text-red-700' : 'text-emerald-700'}`}>
                {Number(m.quantityBase) > 0 ? '+' : ''}
                {qty(m.quantityBase, m.baseUnit)}
              </div>
            </div>
            <div className="text-xs text-slate-500">
              {dmy(m.txnDate)} · {m.movementType.replace(/_/g, ' ').toLowerCase()} {m.reason ? `· ${m.reason}` : ''}
            </div>
            <div className="mt-1 flex items-center justify-between">
              <Tags labels={[m.reversed ? 'VOIDED' : null, m.movementType === 'REVERSAL' ? 'CORRECTED' : null]} />
              {canManage && !m.reversed && m.refType === null && m.movementType !== 'REVERSAL' && (
                <button className="text-xs text-red-600 underline" onClick={() => setReversing(m)}>
                  Reverse
                </button>
              )}
            </div>
          </div>
        ))}
        {q.data?.length === 0 && <p className="text-sm text-slate-500">No movements in this range.</p>}
      </div>
      {adding && <MovementForm onClose={() => setAdding(false)} />}
      {reversing && (
        <ReasonDialog
          title="Reverse this movement?"
          confirmLabel="Reverse"
          danger
          onConfirm={async (reason) => {
            await post(`/inventory/movements/${reversing.id}/reverse`, { reason })
            qc.invalidateQueries()
          }}
          onClose={() => setReversing(null)}
        />
      )}
    </div>
  )
}

function MovementForm({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const ings = useIngredients()
  const [type, setType] = useState('OPENING')
  const [ingId, setIngId] = useState('')
  const [quantity, setQuantity] = useState('')
  const [unit, setUnit] = useState('')
  const [date, setDate] = useState(todayIso())
  const [reason, setReason] = useState('')
  const ing = ings.data?.find((i) => String(i.id) === ingId)
  const save = useMutation({
    mutationFn: () =>
      post('/inventory/movements', {
        ingredientId: Number(ingId),
        movementType: type,
        quantity,
        unit: unit || ing?.baseUnit,
        txnDate: date,
        reason: reason || null,
      }),
    onSuccess: () => {
      qc.invalidateQueries()
      onClose()
    },
  })
  return (
    <Modal title="Opening stock / adjustment" onClose={onClose}>
      <div className="space-y-3">
        <div className="flex gap-2">
          <button className={type === 'OPENING' ? 'btn-primary' : 'btn-ghost'} onClick={() => setType('OPENING')}>
            Opening stock
          </button>
          <button className={type === 'ADJUSTMENT' ? 'btn-primary' : 'btn-ghost'} onClick={() => setType('ADJUSTMENT')}>
            Adjustment (+/−)
          </button>
        </div>
        <Field label="Ingredient">
          <IngredientSelect value={ingId} onChange={setIngId} />
        </Field>
        <div className="grid grid-cols-3 gap-2">
          <div className="col-span-2">
            <Field label={type === 'ADJUSTMENT' ? 'Quantity (negative to remove)' : 'Quantity'}>
              <input className="input" inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} />
            </Field>
          </div>
          <Field label="Unit">
            <select className="input" value={unit || ing?.baseUnit || ''} onChange={(e) => setUnit(e.target.value)}>
              {(ing ? UNIT_CHOICES[ing.baseUnit] : []).map((u) => (
                <option key={u}>{u}</option>
              ))}
            </select>
          </Field>
        </div>
        <Field label="Date">
          <input type="date" className="input" value={date} max={todayIso()} onChange={(e) => setDate(e.target.value)} />
        </Field>
        <Field label={type === 'ADJUSTMENT' ? 'Reason (required)' : 'Note'}>
          <input className="input" value={reason} onChange={(e) => setReason(e.target.value)} />
        </Field>
        <ErrorNote error={save.error} />
        <button className="btn-primary w-full" disabled={!ingId || !quantity || save.isPending || (type === 'ADJUSTMENT' && !reason.trim())} onClick={() => save.mutate()}>
          Save
        </button>
      </div>
    </Modal>
  )
}

// ---------------------------------------------------------------- counts

function Counts({ canManage }: { canManage: boolean }) {
  const qc = useQueryClient()
  const [open, setOpen] = useState<number | null>(null)
  const list = useQuery({ queryKey: ['counts'], queryFn: () => get<CountHeader[]>('/stock-counts') })
  const create = useMutation({
    mutationFn: () => post<CountDetail>('/stock-counts', { countDate: todayIso(), lines: [] }),
    onSuccess: (d) => {
      qc.invalidateQueries()
      setOpen(d.header.id)
    },
  })
  if (open !== null) return <CountSheet id={open} canManage={canManage} onBack={() => setOpen(null)} />
  return (
    <div>
      {canManage && (
        <button className="btn-primary mb-3" disabled={create.isPending} onClick={() => create.mutate()}>
          ➕ New count sheet
        </button>
      )}
      <ErrorNote error={create.error} />
      {list.isLoading && <Loading />}
      <ErrorNote error={list.error} />
      <div className="space-y-2">
        {list.data?.map((c) => (
          <button key={c.id} className="card block w-full p-3 text-left" onClick={() => setOpen(c.id)}>
            <div className="flex justify-between">
              <span className="font-medium">{dmy(c.countDate)}</span>
              <Tag label={c.status} />
            </div>
            <div className="text-xs text-slate-500">
              {c.lineCount} item(s) {c.notes ? `· ${c.notes}` : ''} {c.voidReason ? `· voided: ${c.voidReason}` : ''}
            </div>
          </button>
        ))}
        {list.data?.length === 0 && <p className="text-sm text-slate-500">No counts yet. A count anchors the stock figure; sales usage is deducted from the count onwards.</p>}
      </div>
    </div>
  )
}

function CountSheet({ id, canManage, onBack }: { id: number; canManage: boolean; onBack: () => void }) {
  const qc = useQueryClient()
  const { can } = useAuth()
  const detail = useQuery({ queryKey: ['count', id], queryFn: () => get<CountDetail>(`/stock-counts/${id}`) })
  const h = detail.data?.header
  const ings = useIngredients()
  const stock = useQuery({
    queryKey: ['stock-asof', h?.countDate],
    queryFn: () => get<StockReport>(`/inventory/stock${qs({ asOf: h?.countDate })}`),
    enabled: !!h && h.status === 'DRAFT',
  })
  const [date, setDate] = useState<string | null>(null)
  const [vals, setVals] = useState<Record<number, string>>({})
  const [voiding, setVoiding] = useState(false)
  const draft = h?.status === 'DRAFT'
  const current = (iid: number): string => {
    if (vals[iid] !== undefined) return vals[iid]
    const l = detail.data?.lines.find((x) => x.ingredientId === iid)
    return l ? l.countedQty : ''
  }
  const lines = () =>
    (ings.data ?? [])
      .filter((i) => i.active && current(i.id) !== '')
      .map((i) => ({ ingredientId: i.id, countedQty: current(i.id) }))
  const saveDraft = useMutation({
    mutationFn: () => put<CountDetail>(`/stock-counts/${id}`, { countDate: date ?? h?.countDate, lines: lines() }),
    onSuccess: () => {
      setVals({})
      qc.invalidateQueries()
    },
  })
  const doPost = useMutation({
    mutationFn: async () => {
      await put<CountDetail>(`/stock-counts/${id}`, { countDate: date ?? h?.countDate, lines: lines() })
      return post<CountDetail>(`/stock-counts/${id}/post`)
    },
    onSuccess: () => {
      setVals({})
      qc.invalidateQueries()
    },
  })
  return (
    <div>
      <button className="mb-2 text-sm text-brand-700 underline" onClick={onBack}>
        ← All counts
      </button>
      {detail.isLoading && <Loading />}
      <ErrorNote error={detail.error} />
      {h && (
        <>
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-lg font-semibold">
              Count {dmy(h.countDate)} <Tag label={h.status} />
            </h2>
            {canManage && h.status !== 'VOID' && (
              <button className="text-xs text-red-600 underline" onClick={() => setVoiding(true)}>
                {h.status === 'POSTED' ? 'Void with reason' : 'Discard'}
              </button>
            )}
          </div>
          {detail.data?.flags.includes('FIRST_COUNT_FOR_SOME_ITEMS') && (
            <Banner tone="info">Items without an earlier count have no expected quantity: their first count becomes the starting point.</Banner>
          )}
          {draft ? (
            <div>
              <Field label="Count date">
                <input type="date" className="input mb-3" value={date ?? h.countDate} max={todayIso()} onChange={(e) => setDate(e.target.value)} />
              </Field>
              <p className="mb-2 text-xs text-slate-500">Type what you counted. Leave blank if not counted today.</p>
              <div className="space-y-2">
                {(ings.data ?? [])
                  .filter((i) => i.active)
                  .map((i) => {
                    const exp = stock.data?.rows.find((r) => r.ingredientId === i.id)?.expectedStock
                    return (
                      <div key={i.id} className="card flex items-center justify-between gap-2 p-3">
                        <div>
                          <div className="font-medium">{i.name}</div>
                          <div className="text-xs text-slate-500">expected {exp === undefined || exp === null ? 'not known' : qty(exp, i.baseUnit)}</div>
                        </div>
                        <div className="flex w-36 items-center gap-1">
                          <input
                            className="input"
                            inputMode="decimal"
                            value={current(i.id)}
                            onChange={(e) => setVals((v) => ({ ...v, [i.id]: e.target.value }))}
                          />
                          <span className="text-xs text-slate-500">{i.baseUnit}</span>
                        </div>
                      </div>
                    )
                  })}
              </div>
              <ErrorNote error={saveDraft.error ?? doPost.error} />
              {canManage && (
                <div className="mt-3 flex gap-2">
                  <button className="btn-ghost flex-1" disabled={saveDraft.isPending} onClick={() => saveDraft.mutate()}>
                    Save draft
                  </button>
                  <button className="btn-primary flex-1" disabled={doPost.isPending || lines().length === 0} onClick={() => doPost.mutate()}>
                    Post count
                  </button>
                </div>
              )}
            </div>
          ) : (
            <div className="space-y-2">
              {detail.data?.lines.map((l) => (
                <div key={l.id} className="card p-3">
                  <div className="flex justify-between">
                    <span className="font-medium">{l.ingredientName}</span>
                    <span>{qty(l.countedQty, l.baseUnit)}</span>
                  </div>
                  <div className="flex items-center justify-between text-xs text-slate-500">
                    <span>expected {l.expectedQty === null ? 'not known (first count)' : qty(l.expectedQty, l.baseUnit)}</span>
                    <span className={l.variance !== null && Number(l.variance) < 0 ? 'text-red-700' : ''}>
                      variance {l.variance === null ? '—' : qty(l.variance, l.baseUnit)}
                      {can('costing.view') && l.varianceValue !== null ? ` · ${inr(l.varianceValue)}` : ''}
                    </span>
                  </div>
                  <Tags labels={[l.costStatus && l.costStatus !== 'COMPLETE' ? l.costStatus : null, l.costEstimated ? 'ESTIMATED' : null]} />
                </div>
              ))}
            </div>
          )}
        </>
      )}
      {voiding && h && (
        <ReasonDialog
          title={h.status === 'POSTED' ? 'Void this posted count?' : 'Discard this draft?'}
          confirmLabel={h.status === 'POSTED' ? 'Void count' : 'Discard'}
          danger
          onConfirm={async (reason) => {
            await post(`/stock-counts/${id}/void`, { reason })
            qc.invalidateQueries()
          }}
          onClose={() => setVoiding(false)}
        />
      )}
    </div>
  )
}

// ---------------------------------------------------------------- prep batches

function PrepBatches({ canManage }: { canManage: boolean }) {
  const qc = useQueryClient()
  const [adding, setAdding] = useState(false)
  const [voiding, setVoiding] = useState<PrepBatch | null>(null)
  const q = useQuery({ queryKey: ['prep'], queryFn: () => get<PrepBatch[]>('/prep-batches') })
  return (
    <div>
      {canManage && (
        <button className="btn-primary mb-3" onClick={() => setAdding(true)}>
          ➕ Record a batch
        </button>
      )}
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      <div className="space-y-2">
        {q.data?.map((b) => (
          <div key={b.id} className="card p-3">
            <div className="flex justify-between">
              <span className="font-medium">{b.ingredientName}</span>
              <span>{qty(b.actualQty, b.baseUnit)}</span>
            </div>
            <div className="text-xs text-slate-500">
              {dmy(b.txnDate)} · planned {qty(b.plannedQty, b.baseUnit)} · yield {b.yieldPct ?? '—'}%
              {b.costPerUnit !== null ? ` · ${unitCost(b.costPerUnit, b.baseUnit)}` : ''}
            </div>
            <div className="mt-1 flex items-center justify-between">
              <Tags labels={[b.costStatus && b.costStatus !== 'COMPLETE' ? b.costStatus : null, b.costEstimated ? 'ESTIMATED' : null]} />
              {canManage && (
                <button className="text-xs text-red-600 underline" onClick={() => setVoiding(b)}>
                  Void
                </button>
              )}
            </div>
          </div>
        ))}
        {q.data?.length === 0 && <p className="text-sm text-slate-500">No batches yet. A batch uses the raw ingredients of the recipe and adds the prepared item to stock.</p>}
      </div>
      {adding && <PrepForm onClose={() => setAdding(false)} />}
      {voiding && (
        <ReasonDialog
          title="Void this batch?"
          confirmLabel="Void"
          danger
          onConfirm={async (reason) => {
            await post(`/prep-batches/${voiding.id}/void`, { reason })
            qc.invalidateQueries()
          }}
          onClose={() => setVoiding(null)}
        />
      )}
    </div>
  )
}

function PrepForm({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const ings = useIngredients()
  const [ingId, setIngId] = useState('')
  const [date, setDate] = useState(todayIso())
  const [planned, setPlanned] = useState('')
  const [actual, setActual] = useState('')
  const [notes, setNotes] = useState('')
  const ing = ings.data?.find((i) => String(i.id) === ingId)
  const save = useMutation({
    mutationFn: () => post('/prep-batches', { ingredientId: Number(ingId), txnDate: date, plannedQty: planned || actual, actualQty: actual, notes: notes || null }),
    onSuccess: () => {
      qc.invalidateQueries()
      onClose()
    },
  })
  return (
    <Modal title="Record a prep batch" onClose={onClose}>
      <div className="space-y-3">
        <Field label="Prepared item">
          <IngredientSelect value={ingId} onChange={setIngId} only="PREPARED" />
        </Field>
        <Field label="Date">
          <input type="date" className="input" value={date} max={todayIso()} onChange={(e) => setDate(e.target.value)} />
        </Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label={`Planned (${ing?.baseUnit ?? 'g'})`}>
            <input className="input" inputMode="decimal" value={planned} onChange={(e) => setPlanned(e.target.value)} placeholder="same as actual" />
          </Field>
          <Field label={`Actually made (${ing?.baseUnit ?? 'g'})`}>
            <input className="input" inputMode="decimal" value={actual} onChange={(e) => setActual(e.target.value)} />
          </Field>
        </div>
        <Field label="Notes">
          <input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <ErrorNote error={save.error} />
        <button className="btn-primary w-full" disabled={!ingId || !actual || save.isPending} onClick={() => save.mutate()}>
          Save batch
        </button>
      </div>
    </Modal>
  )
}

// ---------------------------------------------------------------- reports

function Reports() {
  const [from, setFrom] = useState(monthStartIso())
  const [to, setTo] = useState(todayIso())
  const usage = useQuery({ queryKey: ['usage', from, to], queryFn: () => get<UsageReport>(`/inventory/usage${qs({ from, to })}`) })
  const variance = useQuery({ queryKey: ['variance', from, to], queryFn: () => get<VarianceReport>(`/inventory/variance${qs({ from, to })}`) })
  return (
    <div className="space-y-4">
      <div className="card">
        <DateRange from={from} to={to} onChange={(f, t) => (setFrom(f), setTo(t))} />
      </div>
      <div className="card">
        <div className="mb-2 font-medium">Theoretical usage (from recipes x itemised sales)</div>
        {usage.isLoading && <Loading />}
        <ErrorNote error={usage.error} />
        {usage.data?.notes.map((n) => (
          <p key={n} className="mb-1 text-xs text-slate-500">
            {n}
          </p>
        ))}
        <table className="mt-2 w-full text-sm">
          <thead>
            <tr>
              <th className="th">Ingredient</th>
              <th className="th text-right">Used (recipe)</th>
              <th className="th text-right">Wasted</th>
            </tr>
          </thead>
          <tbody>
            {usage.data?.rows.map((r) => (
              <tr key={r.ingredientId} className="border-t border-slate-100">
                <td className="td">{r.name}</td>
                <td className="td text-right">{qty(r.theoreticalUsage, r.baseUnit)}</td>
                <td className="td text-right">{qty(r.wastageRecorded, r.baseUnit)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {usage.data?.rows.length === 0 && <p className="text-sm text-slate-500">No usage in this range.</p>}
      </div>
      <div className="card">
        <div className="mb-2 flex items-center justify-between">
          <div className="font-medium">Stock count variance</div>
          {variance.data && <Tags labels={variance.data.flags} />}
        </div>
        {variance.isLoading && <Loading />}
        <ErrorNote error={variance.error} />
        {variance.data && !variance.data.valuesHidden && (
          <p className="mb-2 text-sm">
            Value of known variances: <b>{inr(variance.data.totalValueKnown)}</b>
            {variance.data.linesWithoutCost > 0 ? ` (${variance.data.linesWithoutCost} line(s) have no cost and are not valued)` : ''}
          </p>
        )}
        <div className="space-y-1">
          {variance.data?.rows.map((r) => (
            <div key={`${r.countId}-${r.ingredientId}`} className="flex justify-between border-t border-slate-100 py-1 text-sm">
              <span>
                {r.ingredientName} <span className="text-xs text-slate-500">{dmy(r.countDate)}</span>
              </span>
              <span className={r.variance !== null && Number(r.variance) < 0 ? 'text-red-700' : ''}>
                {r.variance === null ? 'first count' : qty(r.variance, r.baseUnit)}
                {r.varianceValue !== null ? ` · ${inr(r.varianceValue)}` : ''}
              </span>
            </div>
          ))}
          {variance.data?.rows.length === 0 && <p className="text-sm text-slate-500">No posted counts in this range.</p>}
        </div>
      </div>
    </div>
  )
}
