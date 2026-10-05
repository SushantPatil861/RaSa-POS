import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post, put, qs } from '../../api'
import { dmy, inr, monthStartIso, todayIso } from '../../format'
import { useAuth } from '../../auth'
import { DateRange, ErrorNote, Field, Loading, Modal, PageTitle, ReasonDialog } from '../../components/ui'
import type { CostHistory, Ingredient, PackSpec, PurchaseLink, UnlinkedItem } from '../../types.phase2'
import { IngredientSelect, Tabs, Tag, Tags, UNIT_CHOICES, qty, unitCost, useIngredients } from './shared'

export default function Ingredients() {
  const [tab, setTab] = useState('list')
  const { can } = useAuth()
  return (
    <div>
      <PageTitle title="Ingredients" />
      <Tabs
        tabs={[
          { id: 'list', label: 'Ingredients' },
          ...(can('inventory.manage') ? [{ id: 'links', label: 'Link purchases' }] : []),
        ]}
        value={tab}
        onChange={setTab}
      />
      {tab === 'list' ? <IngredientList /> : <LinkPurchases />}
    </div>
  )
}

function IngredientList() {
  const { can } = useAuth()
  const list = useIngredients(true)
  const [search, setSearch] = useState('')
  const [edit, setEdit] = useState<Ingredient | 'new' | null>(null)
  const [open, setOpen] = useState<number | null>(null)
  const rows = (list.data ?? []).filter((i) => i.name.toLowerCase().includes(search.toLowerCase()))
  return (
    <div>
      <div className="mb-3 flex gap-2">
        <input className="input" placeholder="Search ingredients" value={search} onChange={(e) => setSearch(e.target.value)} />
        {can('inventory.manage') && (
          <button className="btn-primary whitespace-nowrap" onClick={() => setEdit('new')}>
            ➕ Add
          </button>
        )}
      </div>
      {list.isLoading && <Loading />}
      <ErrorNote error={list.error} />
      <div className="space-y-2">
        {rows.map((i) => (
          <div key={i.id} className={`card p-3 ${i.active ? '' : 'opacity-60'}`}>
            <div className="flex items-start justify-between gap-2">
              <div>
                <div className="font-medium">{i.name}</div>
                <div className="text-xs text-slate-500">
                  {i.kind === 'PREPARED' ? 'Prepared' : 'Raw'} · base unit {i.baseUnit}
                  {i.category ? ` · ${i.category}` : ''}
                  {i.minLevel ? ` · par ${qty(i.minLevel, i.baseUnit)}` : ''}
                </div>
                {i.kind === 'PREPARED' && !i.hasRecipe && <Tags labels={['NO_RECIPE']} />}
                {!i.active && <Tag label="VOIDED" />}
              </div>
              <div className="flex shrink-0 gap-3 text-xs">
                <button className="text-slate-600 underline" onClick={() => setOpen(open === i.id ? null : i.id)}>
                  {open === i.id ? 'Hide' : 'Packs & costs'}
                </button>
                {can('inventory.manage') && (
                  <button className="text-brand-700 underline" onClick={() => setEdit(i)}>
                    Edit
                  </button>
                )}
              </div>
            </div>
            {open === i.id && <IngredientDetail ing={i} />}
          </div>
        ))}
        {list.data && rows.length === 0 && <p className="text-sm text-slate-500">No ingredients yet.</p>}
      </div>
      {edit && <IngredientForm ing={edit === 'new' ? null : edit} onClose={() => setEdit(null)} />}
    </div>
  )
}

function IngredientForm({ ing, onClose }: { ing: Ingredient | null; onClose: () => void }) {
  const qc = useQueryClient()
  const [name, setName] = useState(ing?.name ?? '')
  const [kind, setKind] = useState<string>(ing?.kind ?? 'RAW')
  const [baseUnit, setBaseUnit] = useState<string>(ing?.baseUnit ?? 'g')
  const [category, setCategory] = useState(ing?.category ?? '')
  const [minLevel, setMinLevel] = useState(ing?.minLevel ?? '')
  const [active, setActive] = useState(ing?.active ?? true)
  const [reason, setReason] = useState('')
  const save = useMutation({
    mutationFn: () => {
      const body = { name, kind, baseUnit, category: category || null, minLevel: minLevel || null, active, reason: ing ? reason : undefined }
      return ing ? put(`/ingredients/${ing.id}`, body) : post('/ingredients', body)
    },
    onSuccess: () => {
      qc.invalidateQueries()
      onClose()
    },
  })
  return (
    <Modal title={ing ? 'Edit ingredient' : 'Add ingredient'} onClose={onClose}>
      <div className="space-y-3">
        <Field label="Name">
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Kind">
            <select className="input" value={kind} onChange={(e) => setKind(e.target.value)}>
              <option value="RAW">Raw ingredient</option>
              <option value="PREPARED">Prepared (sauce, batter…)</option>
            </select>
          </Field>
          <Field label="Base unit">
            <select className="input" value={baseUnit} onChange={(e) => setBaseUnit(e.target.value)}>
              <option value="g">grams (g)</option>
              <option value="ml">millilitres (ml)</option>
              <option value="pcs">pieces (pcs)</option>
            </select>
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Category">
            <input className="input" value={category} onChange={(e) => setCategory(e.target.value)} />
          </Field>
          <Field label={`Par level (${baseUnit})`}>
            <input className="input" inputMode="decimal" value={minLevel} onChange={(e) => setMinLevel(e.target.value)} />
          </Field>
        </div>
        {ing && (
          <>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Active
            </label>
            <Field label="Reason for change (required)">
              <input className="input" value={reason} onChange={(e) => setReason(e.target.value)} />
            </Field>
          </>
        )}
        <p className="text-xs text-slate-500">Weight and volume are separate: litres are never converted to kilograms automatically.</p>
        <ErrorNote error={save.error} />
        <button className="btn-primary w-full" disabled={!name.trim() || save.isPending || (!!ing && !reason.trim())} onClick={() => save.mutate()}>
          Save
        </button>
      </div>
    </Modal>
  )
}

function IngredientDetail({ ing }: { ing: Ingredient }) {
  const { can } = useAuth()
  const qc = useQueryClient()
  const canCosts = can('costing.view') && can('finance.view_profit')
  const packs = useQuery({ queryKey: ['packs', ing.id], queryFn: () => get<PackSpec[]>(`/ingredients/${ing.id}/pack-specs`) })
  const costs = useQuery({
    queryKey: ['cost-history', ing.id],
    queryFn: () => get<CostHistory[]>(`/ingredients/${ing.id}/cost-history`),
    enabled: canCosts,
  })
  const [packOpen, setPackOpen] = useState(false)
  const [costOpen, setCostOpen] = useState(false)
  const [voiding, setVoiding] = useState<CostHistory | null>(null)
  return (
    <div className="mt-3 space-y-4 border-t border-slate-100 pt-3">
      <div>
        <div className="mb-1 flex items-center justify-between">
          <div className="label mb-0">Pack sizes</div>
          {can('inventory.manage') && (
            <button className="text-xs text-brand-700 underline" onClick={() => setPackOpen(true)}>
              + Pack size
            </button>
          )}
        </div>
        {packs.isLoading && <Loading />}
        <ErrorNote error={packs.error} />
        {packs.data?.map((p) => (
          <div key={p.id} className={`flex justify-between text-sm ${p.active ? '' : 'text-slate-400 line-through'}`}>
            <span>{p.name}</span>
            <span>
              {qty(p.quantityBase, ing.baseUnit)} <span className="text-xs text-slate-500">({p.basis.replace(/_/g, ' ').toLowerCase()})</span>
            </span>
          </div>
        ))}
        {packs.data?.length === 0 && <p className="text-xs text-slate-500">No pack sizes yet (for example “1.4 kg tub” = 1400 g).</p>}
      </div>
      {canCosts && (
        <div>
          <div className="mb-1 flex items-center justify-between">
            <div className="label mb-0">Cost history (per {ing.baseUnit})</div>
            {can('inventory.manage') && (
              <button className="text-xs text-brand-700 underline" onClick={() => setCostOpen(true)}>
                + Cost
              </button>
            )}
          </div>
          {costs.isLoading && <Loading />}
          <ErrorNote error={costs.error} />
          {costs.data?.length === 0 && <Tag label="MISSING" />}
          {costs.data?.map((c) => (
            <div key={c.id} className="flex items-center justify-between gap-2 border-b border-slate-50 py-1 text-sm">
              <div className={c.voided ? 'line-through opacity-60' : ''}>
                <span className="font-medium">{unitCost(c.costPerBase, ing.baseUnit)}</span>{' '}
                <span className="text-xs text-slate-500">from {dmy(c.effectiveDate)}</span>
                <div className="text-xs text-slate-500">
                  {c.notes ?? ''} {c.createdByName ? `· ${c.createdByName}` : ''}
                </div>
              </div>
              <div className="flex shrink-0 items-center gap-2">
                <Tags labels={[c.source === 'ESTIMATE' ? 'ESTIMATED' : c.source, c.voided ? 'VOIDED' : null]} />
                {!c.voided && can('inventory.manage') && (
                  <button className="text-xs text-red-600 underline" onClick={() => setVoiding(c)}>
                    Void
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
      {packOpen && <PackForm ing={ing} onClose={() => setPackOpen(false)} />}
      {costOpen && <CostForm ing={ing} onClose={() => setCostOpen(false)} />}
      {voiding && (
        <ReasonDialog
          title="Void this cost entry?"
          confirmLabel="Void"
          danger
          onConfirm={async (reason) => {
            await post(`/ingredient-costs/${voiding.id}/void`, { reason })
            qc.invalidateQueries()
          }}
          onClose={() => setVoiding(null)}
        />
      )}
    </div>
  )
}

function PackForm({ ing, onClose }: { ing: Ingredient; onClose: () => void }) {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [quantity, setQuantity] = useState('')
  const [unit, setUnit] = useState<string>(ing.baseUnit)
  const [basis, setBasis] = useState('SUPPLIER_SPEC')
  const save = useMutation({
    mutationFn: () => post(`/ingredients/${ing.id}/pack-specs`, { name, quantity, unit, basis }),
    onSuccess: () => {
      qc.invalidateQueries()
      onClose()
    },
  })
  return (
    <Modal title={`Pack size for ${ing.name}`} onClose={onClose}>
      <div className="space-y-3">
        <Field label="Name (for example 1.4 kg tub)">
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Quantity">
            <input className="input" inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} />
          </Field>
          <Field label="Unit">
            <select className="input" value={unit} onChange={(e) => setUnit(e.target.value)}>
              {UNIT_CHOICES[ing.baseUnit].map((u) => (
                <option key={u}>{u}</option>
              ))}
            </select>
          </Field>
        </div>
        <Field label="How sure are you?">
          <select className="input" value={basis} onChange={(e) => setBasis(e.target.value)}>
            <option value="EXACT">Exact (weighed)</option>
            <option value="SUPPLIER_SPEC">Supplier specification</option>
            <option value="ESTIMATED">Estimated</option>
          </select>
        </Field>
        <ErrorNote error={save.error} />
        <button className="btn-primary w-full" disabled={!name.trim() || !quantity || save.isPending} onClick={() => save.mutate()}>
          Save
        </button>
      </div>
    </Modal>
  )
}

function CostForm({ ing, onClose }: { ing: Ingredient; onClose: () => void }) {
  const qc = useQueryClient()
  const [date, setDate] = useState(todayIso())
  const [mode, setMode] = useState<'per' | 'total'>('total')
  const [per, setPer] = useState('')
  const [total, setTotal] = useState('')
  const [quantity, setQuantity] = useState('')
  const [unit, setUnit] = useState<string>(ing.baseUnit)
  const [source, setSource] = useState('MANUAL')
  const [notes, setNotes] = useState('')
  const save = useMutation({
    mutationFn: () =>
      post(`/ingredients/${ing.id}/costs`, {
        effectiveDate: date,
        costPerBase: mode === 'per' ? per : undefined,
        totalCost: mode === 'total' ? total : undefined,
        quantity: mode === 'total' ? quantity : undefined,
        unit: mode === 'total' ? unit : undefined,
        source,
        notes: notes || null,
      }),
    onSuccess: () => {
      qc.invalidateQueries()
      onClose()
    },
  })
  const ok = mode === 'per' ? !!per : !!total && !!quantity
  return (
    <Modal title={`Add cost for ${ing.name}`} onClose={onClose}>
      <div className="space-y-3">
        <p className="text-xs text-slate-500">Costs are never edited: a new entry takes effect from its date and older sales keep their older cost.</p>
        <Field label="Effective from">
          <input type="date" className="input" value={date} max={todayIso()} onChange={(e) => setDate(e.target.value)} />
        </Field>
        <div className="flex gap-2 text-sm">
          <button className={mode === 'total' ? 'btn-primary' : 'btn-ghost'} onClick={() => setMode('total')}>
            Price for a quantity
          </button>
          <button className={mode === 'per' ? 'btn-primary' : 'btn-ghost'} onClick={() => setMode('per')}>
            Cost per {ing.baseUnit}
          </button>
        </div>
        {mode === 'per' ? (
          <Field label={`Cost per ${ing.baseUnit} ₹`}>
            <input className="input" inputMode="decimal" value={per} onChange={(e) => setPer(e.target.value)} />
          </Field>
        ) : (
          <div className="grid grid-cols-3 gap-2">
            <Field label="Price ₹">
              <input className="input" inputMode="decimal" value={total} onChange={(e) => setTotal(e.target.value)} />
            </Field>
            <Field label="For quantity">
              <input className="input" inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} />
            </Field>
            <Field label="Unit">
              <select className="input" value={unit} onChange={(e) => setUnit(e.target.value)}>
                {UNIT_CHOICES[ing.baseUnit].map((u) => (
                  <option key={u}>{u}</option>
                ))}
              </select>
            </Field>
          </div>
        )}
        <Field label="Type">
          <select className="input" value={source} onChange={(e) => setSource(e.target.value)}>
            <option value="MANUAL">Known price</option>
            <option value="ESTIMATE">Estimate</option>
          </select>
        </Field>
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

// ---------------------------------------------------------------- purchase links

function LinkPurchases() {
  const qc = useQueryClient()
  const [from, setFrom] = useState(monthStartIso())
  const [to, setTo] = useState(todayIso())
  const [linking, setLinking] = useState<UnlinkedItem | null>(null)
  const [unlinking, setUnlinking] = useState<PurchaseLink | null>(null)
  const items = useQuery({ queryKey: ['unlinked', from, to], queryFn: () => get<UnlinkedItem[]>(`/purchase-items/unlinked${qs({ from, to })}`) })
  const links = useQuery({ queryKey: ['links'], queryFn: () => get<PurchaseLink[]>('/purchase-links?status=ACTIVE') })
  const recon = useMutation({
    mutationFn: () => post<{ unlinked: number }>('/purchase-links/reconcile'),
    onSuccess: () => qc.invalidateQueries(),
  })
  const needs = links.data?.filter((l) => l.attention).length ?? 0
  return (
    <div className="space-y-4">
      <div className="card">
        <div className="mb-2 font-medium">Purchase items waiting to be linked</div>
        <p className="mb-2 text-xs text-slate-500">
          Linking adds stock and records the cost per gram/ml/piece from the bill. Only confirmed INVENTORY items appear.
        </p>
        <DateRange from={from} to={to} onChange={(f, t) => (setFrom(f), setTo(t))} />
        {items.isLoading && <Loading />}
        <ErrorNote error={items.error} />
        <div className="mt-2 space-y-2">
          {items.data?.map((i) => (
            <div key={i.purchaseItemId} className="flex items-center justify-between gap-2 rounded-lg bg-slate-50 p-2 text-sm">
              <div>
                <div className="font-medium">{i.itemName}</div>
                <div className="text-xs text-slate-500">
                  {i.purchaseNo} · {dmy(i.txnDate)} · {i.supplierName ?? 'no supplier'} · {qty(i.quantity)} {i.unit ?? ''} · {inr(i.total)}
                </div>
              </div>
              <button className="btn-primary" onClick={() => setLinking(i)}>
                Link
              </button>
            </div>
          ))}
          {items.data?.length === 0 && <p className="text-sm text-slate-500">Nothing to link in this range.</p>}
        </div>
      </div>
      <div className="card">
        <div className="mb-2 flex items-center justify-between">
          <div className="font-medium">Linked purchase items</div>
          <button className="btn-ghost" disabled={recon.isPending} onClick={() => recon.mutate()}>
            Check for voided / edited purchases
          </button>
        </div>
        {needs > 0 && <p className="mb-2 text-sm text-amber-800">{needs} link(s) need attention (purchase voided or edited).</p>}
        {recon.data && <p className="mb-2 text-sm text-emerald-700">{recon.data.unlinked} link(s) reversed with compensating movements.</p>}
        <ErrorNote error={recon.error} />
        {links.isLoading && <Loading />}
        <ErrorNote error={links.error} />
        <div className="space-y-2">
          {links.data?.map((l) => (
            <div key={l.id} className="rounded-lg bg-slate-50 p-2 text-sm">
              <div className="flex justify-between gap-2">
                <div>
                  <span className="font-medium">{l.itemName}</span> → {l.ingredientName}
                </div>
                <div>{qty(l.baseQuantity, l.baseUnit)}</div>
              </div>
              <div className="flex items-center justify-between text-xs text-slate-500">
                <span>
                  {l.purchaseNo} · {dmy(l.txnDate)} · {inr(l.itemTotal)}
                </span>
                <span className="flex items-center gap-2">
                  <Tags labels={[l.attention]} />
                  <button className="text-red-600 underline" onClick={() => setUnlinking(l)}>
                    Unlink
                  </button>
                </span>
              </div>
            </div>
          ))}
          {links.data?.length === 0 && <p className="text-sm text-slate-500">No links yet.</p>}
        </div>
      </div>
      {linking && <LinkForm item={linking} onClose={() => setLinking(null)} />}
      {unlinking && (
        <ReasonDialog
          title="Unlink this purchase item?"
          confirmLabel="Unlink"
          danger
          onConfirm={async (reason) => {
            await post(`/purchase-links/${unlinking.id}/unlink`, { reason })
            qc.invalidateQueries()
          }}
          onClose={() => setUnlinking(null)}
        />
      )}
    </div>
  )
}

function LinkForm({ item, onClose }: { item: UnlinkedItem; onClose: () => void }) {
  const qc = useQueryClient()
  const ings = useIngredients()
  const [ingId, setIngId] = useState('')
  const ing = ings.data?.find((i) => String(i.id) === ingId)
  const packs = useQuery({
    queryKey: ['packs', ingId],
    queryFn: () => get<PackSpec[]>(`/ingredients/${ingId}/pack-specs`),
    enabled: !!ingId,
  })
  const [mode, setMode] = useState<'qty' | 'pack'>('qty')
  const [quantity, setQuantity] = useState(item.quantity)
  const [unit, setUnit] = useState('')
  const [packId, setPackId] = useState('')
  const [packCount, setPackCount] = useState(item.quantity)
  const save = useMutation({
    mutationFn: () =>
      post(`/purchase-items/${item.purchaseItemId}/link`, {
        ingredientId: Number(ingId),
        quantity: mode === 'qty' ? quantity : undefined,
        unit: mode === 'qty' ? unit || ing?.baseUnit : undefined,
        packSpecId: mode === 'pack' ? Number(packId) : undefined,
        packCount: mode === 'pack' ? packCount : undefined,
      }),
    onSuccess: () => {
      qc.invalidateQueries()
      onClose()
    },
  })
  const ok = !!ingId && (mode === 'qty' ? !!quantity : !!packId && !!packCount)
  return (
    <Modal title="Link purchase item" onClose={onClose}>
      <div className="space-y-3">
        <div className="rounded-lg bg-slate-50 p-2 text-sm">
          <div className="font-medium">{item.itemName}</div>
          <div className="text-xs text-slate-500">
            {item.purchaseNo} · {dmy(item.txnDate)} · bought {qty(item.quantity)} {item.unit ?? ''} for {inr(item.total)}
          </div>
        </div>
        <Field label="This is ingredient">
          <IngredientSelect value={ingId} onChange={setIngId} />
        </Field>
        {ing && (
          <>
            <div className="flex gap-2 text-sm">
              <button className={mode === 'qty' ? 'btn-primary' : 'btn-ghost'} onClick={() => setMode('qty')}>
                Enter quantity
              </button>
              <button className={mode === 'pack' ? 'btn-primary' : 'btn-ghost'} onClick={() => setMode('pack')}>
                Number of packs
              </button>
            </div>
            {mode === 'qty' ? (
              <div className="grid grid-cols-2 gap-2">
                <Field label="Quantity bought">
                  <input className="input" inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} />
                </Field>
                <Field label="Unit">
                  <select className="input" value={unit || ing.baseUnit} onChange={(e) => setUnit(e.target.value)}>
                    {UNIT_CHOICES[ing.baseUnit].map((u) => (
                      <option key={u}>{u}</option>
                    ))}
                  </select>
                </Field>
              </div>
            ) : (
              <div className="grid grid-cols-2 gap-2">
                <Field label="Pack size">
                  <select className="input" value={packId} onChange={(e) => setPackId(e.target.value)}>
                    <option value="">Choose…</option>
                    {packs.data?.filter((p) => p.active).map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.name}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="How many packs">
                  <input className="input" inputMode="decimal" value={packCount} onChange={(e) => setPackCount(e.target.value)} />
                </Field>
              </div>
            )}
          </>
        )}
        <ErrorNote error={save.error} />
        <button className="btn-primary w-full" disabled={!ok || save.isPending} onClick={() => save.mutate()}>
          Link and add to stock
        </button>
      </div>
    </Modal>
  )
}
