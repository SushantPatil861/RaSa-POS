import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post } from '../../api'
import { dmy, todayIso } from '../../format'
import { useAuth } from '../../auth'
import { ErrorNote, Field, Loading, Modal, PageTitle } from '../../components/ui'
import type { Container, PackSpec } from '../../types.phase2'
import { Banner, IngredientSelect, Tag, Tags, UNIT_CHOICES, pct, qty, useIngredients } from './shared'

export default function Containers() {
  const { can } = useAuth()
  const [opening, setOpening] = useState(false)
  const [finishing, setFinishing] = useState<Container | null>(null)
  const [result, setResult] = useState<Container | null>(null)
  const open = useQuery({ queryKey: ['containers', 'OPEN'], queryFn: () => get<Container[]>('/containers?status=OPEN') })
  const done = useQuery({ queryKey: ['containers', 'FINISHED'], queryFn: () => get<Container[]>('/containers?status=FINISHED&limit=30') })
  return (
    <div>
      <PageTitle title="Opened containers">
        {can('containers.manage') && (
          <button className="btn-primary" onClick={() => setOpening(true)}>
            ➕ Open new
          </button>
        )}
      </PageTitle>
      {result && <Result c={result} onClose={() => setResult(null)} />}
      <div className="label">Open now</div>
      {open.isLoading && <Loading />}
      <ErrorNote error={open.error} />
      <div className="mb-4 space-y-2">
        {open.data?.map((c) => (
          <div key={c.id} className="card p-3">
            <div className="flex items-start justify-between gap-2">
              <div>
                <div className="font-medium">{c.ingredientName}</div>
                <div className="text-xs text-slate-500">
                  {c.containerCode} · opened {dmy(c.openedDate)} · {qty(c.openingQty, c.baseUnit)} ({c.quantityBasis.toLowerCase()})
                </div>
                {c.summary && <div className="mt-1 text-sm">{c.summary}</div>}
              </div>
              <Tags labels={c.flags} />
            </div>
            {can('containers.manage') && (
              <button className="btn-ghost mt-2" onClick={() => setFinishing(c)}>
                Finished / empty
              </button>
            )}
          </div>
        ))}
        {open.data?.length === 0 && <p className="text-sm text-slate-500">No container is open.</p>}
      </div>
      <div className="label">Finished</div>
      {done.isLoading && <Loading />}
      <ErrorNote error={done.error} />
      <div className="space-y-2">
        {done.data?.map((c) => (
          <button key={c.id} className="card block w-full p-3 text-left" onClick={() => setResult(c)}>
            <div className="flex justify-between gap-2">
              <div>
                <div className="font-medium">{c.ingredientName}</div>
                <div className="text-xs text-slate-500">
                  {c.containerCode} · {dmy(c.openedDate)} → {dmy(c.finishedDate)}
                </div>
              </div>
              <div className="text-right">
                <div className={c.overServingFlag ? 'font-semibold text-red-700' : ''}>{c.overServingPct === null ? '—' : `${c.overServingPct}%`}</div>
                <Tags labels={c.flags.filter((f) => f !== 'ESTIMATED')} />
              </div>
            </div>
          </button>
        ))}
      </div>
      {opening && <OpenForm onClose={() => setOpening(false)} />}
      {finishing && <FinishForm c={finishing} onClose={() => setFinishing(null)} onDone={(r) => setResult(r)} />}
    </div>
  )
}

function Result({ c, onClose }: { c: Container; onClose: () => void }) {
  return (
    <div className="card mb-4">
      <div className="mb-1 flex items-center justify-between">
        <div className="font-medium">
          {c.ingredientName} · {c.containerCode}
        </div>
        <button className="text-slate-400" onClick={onClose} aria-label="Close">
          ✕
        </button>
      </div>
      <div className="grid grid-cols-2 gap-2 text-sm sm:grid-cols-4">
        <div>
          <div className="text-xs text-slate-500">Actually used</div>
          <div className="font-semibold">{qty(c.actualUsage, c.baseUnit)}</div>
        </div>
        <div>
          <div className="text-xs text-slate-500">Recipes say</div>
          <div className="font-semibold">{qty(c.theoreticalUsage, c.baseUnit)}</div>
        </div>
        <div>
          <div className="text-xs text-slate-500">Wastage recorded</div>
          <div className="font-semibold">{qty(c.wastageRecorded, c.baseUnit)}</div>
        </div>
        <div>
          <div className="text-xs text-slate-500">Over-serving</div>
          <div className={`font-semibold ${c.overServingFlag ? 'text-red-700' : ''}`}>{pct(c.overServingPct)}</div>
        </div>
      </div>
      {c.summary && <p className="mt-2 text-sm">{c.summary}</p>}
      <p className="mt-1 text-xs text-slate-500">
        Flagged above {c.thresholdPct}%. The system only reports the gap; you decide the reason (portioning, spillage, sampling…).
      </p>
      <div className="mt-1">
        <Tags labels={c.flags} />
      </div>
      {c.flags.includes('SUMMARY_DAYS_EXCLUDED') && <Banner tone="warn">Some days only have a quick daily total, so recipe-based use is understated and the gap looks bigger.</Banner>}
    </div>
  )
}

function OpenForm({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const ings = useIngredients()
  const [ingId, setIngId] = useState('')
  const ing = ings.data?.find((i) => String(i.id) === ingId)
  const packs = useQuery({ queryKey: ['packs', ingId], queryFn: () => get<PackSpec[]>(`/ingredients/${ingId}/pack-specs`), enabled: !!ingId })
  const [packId, setPackId] = useState('')
  const [measured, setMeasured] = useState('')
  const [unit, setUnit] = useState('')
  const [date, setDate] = useState(todayIso())
  const save = useMutation({
    mutationFn: () =>
      post<Container>('/containers', {
        ingredientId: Number(ingId),
        openedDate: date,
        openingQty: measured || undefined,
        unit: measured ? unit || ing?.baseUnit : undefined,
        packSpecId: packId ? Number(packId) : undefined,
        quantityBasis: measured ? 'MEASURED' : 'ESTIMATED',
      }),
    onSuccess: () => {
      qc.invalidateQueries()
      onClose()
    },
  })
  return (
    <Modal title="Open a new container" onClose={onClose}>
      <div className="space-y-3">
        <Field label="Ingredient">
          <IngredientSelect value={ingId} onChange={setIngId} />
        </Field>
        {ingId && (
          <>
            <Field label="Pack size (quick: no weighing)">
              <select className="input" value={packId} onChange={(e) => setPackId(e.target.value)}>
                <option value="">Choose…</option>
                {packs.data?.filter((p) => p.active).map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} ({qty(p.quantityBase, ing?.baseUnit)})
                  </option>
                ))}
              </select>
            </Field>
            <div className="grid grid-cols-3 gap-2">
              <div className="col-span-2">
                <Field label="Or weighed quantity">
                  <input className="input" inputMode="decimal" value={measured} onChange={(e) => setMeasured(e.target.value)} />
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
          </>
        )}
        <Field label="Opened on">
          <input type="date" className="input" value={date} max={todayIso()} onChange={(e) => setDate(e.target.value)} />
        </Field>
        <p className="text-xs text-slate-500">A pack size gives an ESTIMATED opening quantity; a weighed quantity is MEASURED.</p>
        <ErrorNote error={save.error} />
        <button className="btn-primary w-full" disabled={!ingId || (!packId && !measured) || save.isPending} onClick={() => save.mutate()}>
          Save
        </button>
      </div>
    </Modal>
  )
}

function FinishForm({ c, onClose, onDone }: { c: Container; onClose: () => void; onDone: (c: Container) => void }) {
  const qc = useQueryClient()
  const [closing, setClosing] = useState('0')
  const [unit, setUnit] = useState<string>(c.baseUnit)
  const [date, setDate] = useState(todayIso())
  const save = useMutation({
    mutationFn: () => post<Container>(`/containers/${c.id}/finish`, { finishedDate: date, closingQty: closing === '' ? '0' : closing, unit }),
    onSuccess: (r) => {
      qc.invalidateQueries()
      onDone(r)
      onClose()
    },
  })
  return (
    <Modal title={`Finish ${c.ingredientName}`} onClose={onClose}>
      <div className="space-y-3">
        <p className="text-sm text-slate-600">
          Opened {dmy(c.openedDate)} with {qty(c.openingQty, c.baseUnit)} <Tag label={c.quantityBasis} />. How much is left?
        </p>
        <div className="grid grid-cols-3 gap-2">
          <div className="col-span-2">
            <Field label="Left in the container (0 if empty)">
              <input className="input" inputMode="decimal" value={closing} onChange={(e) => setClosing(e.target.value)} autoFocus />
            </Field>
          </div>
          <Field label="Unit">
            <select className="input" value={unit} onChange={(e) => setUnit(e.target.value)}>
              {UNIT_CHOICES[c.baseUnit].map((u) => (
                <option key={u}>{u}</option>
              ))}
            </select>
          </Field>
        </div>
        <Field label="Finished on">
          <input type="date" className="input" value={date} max={todayIso()} min={c.openedDate} onChange={(e) => setDate(e.target.value)} />
        </Field>
        <ErrorNote error={save.error} />
        <button className="btn-primary w-full" disabled={save.isPending} onClick={() => save.mutate()}>
          Finish and compare with recipes
        </button>
      </div>
    </Modal>
  )
}
