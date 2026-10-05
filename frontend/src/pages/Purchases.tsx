import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post, put, qs } from '../api'
import { dmy, inr, monthStartIso, todayIso, toNumber } from '../format'
import type { Purchase, Supplier } from '../types'
import { Chips, DateRange, ErrorNote, Field, HistoryDrawer, Loading, Modal, PageTitle, ReasonDialog } from '../components/ui'
import { useAuth } from '../auth'

interface Line { itemName: string; quantity: string; unit: string; total: string; treatment: string }
const blank = (): Line => ({ itemName: '', quantity: '1', unit: '', total: '', treatment: 'INVENTORY' })

export default function Purchases() {
  const { can } = useAuth()
  const qc = useQueryClient()
  const [from, setFrom] = useState(monthStartIso())
  const [to, setTo] = useState(todayIso())
  const [edit, setEdit] = useState<Purchase | 'new' | null>(null)
  const [voiding, setVoiding] = useState<Purchase | null>(null)
  const [hist, setHist] = useState<number | null>(null)
  const [supplierOpen, setSupplierOpen] = useState(false)
  const list = useQuery({ queryKey: ['purchases', from, to], queryFn: () => get<Purchase[]>(`/purchases${qs({ from, to })}`) })
  const suppliers = useQuery({ queryKey: ['suppliers'], queryFn: () => get<Supplier[]>('/suppliers') })
  const total = list.data?.reduce((s, p) => s + Number(p.totalAmount), 0) ?? 0
  return (
    <div>
      <PageTitle title="Purchases">
        {can('purchases.create') && (
          <>
            <button className="btn-ghost" onClick={() => setSupplierOpen(true)}>
              + Supplier
            </button>
            <button className="btn-primary" onClick={() => setEdit('new')}>
              ➕ Purchase
            </button>
          </>
        )}
      </PageTitle>
      <div className="card mb-3 flex flex-wrap items-end justify-between gap-2">
        <DateRange from={from} to={to} onChange={(f, t) => (setFrom(f), setTo(t))} />
        <div className="text-right">
          <div className="text-xs uppercase text-slate-500">Total</div>
          <div className="text-xl font-semibold">{inr(total)}</div>
        </div>
      </div>
      {list.isLoading && <Loading />}
      <ErrorNote error={list.error} />
      <div className="space-y-2">
        {list.data?.map((p) => (
          <div key={p.id} className="card p-3">
            <div className="flex justify-between">
              <div className="font-medium">
                {p.supplierName ?? <span className="text-amber-700">Supplier not recorded</span>} <span className="text-xs text-slate-500">{p.purchaseNo}</span>
              </div>
              <div className="font-semibold">{inr(p.totalAmount)}</div>
            </div>
            <div className="text-xs text-slate-500">
              {dmy(p.txnDate)} {p.invoiceNo ? `· Inv ${p.invoiceNo}` : ''} · {p.paymentStatus}
            </div>
            <div className="mt-1 flex items-center justify-between">
              <Chips flags={p.flags.filter((f) => f !== 'MANUAL')} />
              <div className="flex gap-3 text-xs">
                <button className="text-slate-500 underline" onClick={() => setHist(p.id)}>
                  History
                </button>
                {can('purchases.edit') && p.voidState === 'ACTIVE' && (
                  <>
                    <button
                      className="text-brand-700 underline"
                      onClick={async () => setEdit(await get<Purchase>(`/purchases/${p.id}`))}
                    >
                      Correct
                    </button>
                    <button className="text-red-600 underline" onClick={() => setVoiding(p)}>
                      Void
                    </button>
                  </>
                )}
              </div>
            </div>
          </div>
        ))}
        {list.data?.length === 0 && <p className="text-sm text-slate-500">No purchases in this range.</p>}
      </div>
      {edit && <PurchaseForm suppliers={suppliers.data ?? []} purchase={edit === 'new' ? null : edit} onClose={() => setEdit(null)} />}
      {supplierOpen && <SupplierForm onClose={() => setSupplierOpen(false)} />}
      {voiding && (
        <ReasonDialog
          title="Void this purchase?"
          confirmLabel="Void"
          danger
          onConfirm={async (reason) => {
            await post(`/purchases/${voiding.id}/void`, { reason })
            qc.invalidateQueries()
          }}
          onClose={() => setVoiding(null)}
        />
      )}
      {hist !== null && <HistoryDrawer entityType="PURCHASE" entityId={hist} onClose={() => setHist(null)} />}
    </div>
  )
}

function SupplierForm({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [contact, setContact] = useState('')
  const m = useMutation({
    mutationFn: () => post('/suppliers', { name, contact: contact || null }),
    onSuccess: () => (qc.invalidateQueries({ queryKey: ['suppliers'] }), onClose()),
  })
  return (
    <Modal title="New supplier" onClose={onClose}>
      <div className="space-y-3">
        <Field label="Name">
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </Field>
        <Field label="Contact">
          <input className="input" value={contact} onChange={(e) => setContact(e.target.value)} />
        </Field>
        <ErrorNote error={m.error} />
        <button className="btn-primary w-full" disabled={!name.trim()} onClick={() => m.mutate()}>
          Save
        </button>
      </div>
    </Modal>
  )
}

function PurchaseForm({ suppliers, purchase, onClose }: { suppliers: Supplier[]; purchase: Purchase | null; onClose: () => void }) {
  const qc = useQueryClient()
  const [date, setDate] = useState(purchase?.txnDate ?? todayIso())
  const [supplier, setSupplier] = useState(purchase?.supplierId?.toString() ?? '')
  const [invoice, setInvoice] = useState(purchase?.invoiceNo ?? '')
  const [status, setStatus] = useState(purchase?.paymentStatus ?? 'PAID')
  const [lines, setLines] = useState<Line[]>(
    purchase?.items.length
      ? purchase.items.map((i) => ({ itemName: i.itemName, quantity: i.quantity, unit: i.unit ?? '', total: i.total, treatment: i.treatment }))
      : [blank()],
  )
  const [total, setTotal] = useState(purchase?.totalAmount ?? '')
  const [reason, setReason] = useState('')
  const sum = lines.reduce((s, l) => s + toNumber(l.total), 0)
  const set = (i: number, patch: Partial<Line>) => setLines(lines.map((l, k) => (k === i ? { ...l, ...patch } : l)))
  const save = useMutation({
    mutationFn: () => {
      const items = lines
        .filter((l) => l.itemName.trim())
        .map((l) => ({ itemName: l.itemName, quantity: l.quantity || '0', unit: l.unit || null, unitPrice: null, total: l.total || '0', treatment: l.treatment }))
      const body = {
        txnDate: date,
        supplierId: supplier ? Number(supplier) : null,
        invoiceNo: invoice || null,
        paymentStatus: status,
        totalAmount: total || String(sum),
        items,
        reason: purchase ? reason : undefined,
        version: purchase?.version,
      }
      return purchase ? put(`/purchases/${purchase.id}`, body) : post('/purchases', body)
    },
    onSuccess: () => (qc.invalidateQueries(), onClose()),
  })
  return (
    <Modal title={purchase ? `Correct ${purchase.purchaseNo}` : 'Add purchase'} onClose={onClose}>
      <div className="space-y-3">
        <div className="grid grid-cols-2 gap-2">
          <Field label="Date">
            <input type="date" className="input" value={date} max={todayIso()} onChange={(e) => setDate(e.target.value)} />
          </Field>
          <Field label="Invoice no.">
            <input className="input" value={invoice} onChange={(e) => setInvoice(e.target.value)} />
          </Field>
          <Field label="Supplier">
            <select className="input" value={supplier} onChange={(e) => setSupplier(e.target.value)}>
              <option value="">Not recorded</option>
              {suppliers.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Payment">
            <select className="input" value={status} onChange={(e) => setStatus(e.target.value)}>
              {['PAID', 'PARTIAL', 'UNPAID'].map((s) => (
                <option key={s}>{s}</option>
              ))}
            </select>
          </Field>
        </div>
        <div>
          <div className="label">Items</div>
          {lines.map((l, i) => (
            <div key={i} className="mb-2 grid grid-cols-12 gap-1">
              <input className="input col-span-5" placeholder="Item" value={l.itemName} onChange={(e) => set(i, { itemName: e.target.value })} />
              <input className="input col-span-2" placeholder="Qty" inputMode="decimal" value={l.quantity} onChange={(e) => set(i, { quantity: e.target.value })} />
              <input className="input col-span-2" placeholder="Unit" value={l.unit} onChange={(e) => set(i, { unit: e.target.value })} />
              <input className="input col-span-3" placeholder="₹ total" inputMode="decimal" value={l.total} onChange={(e) => set(i, { total: e.target.value })} />
              <select className="input col-span-12 py-1 text-xs" value={l.treatment} onChange={(e) => set(i, { treatment: e.target.value })}>
                <option value="INVENTORY">Stock / ingredients</option>
                <option value="EXPENSE">Consumable expense</option>
                <option value="FIXED_ASSET">Equipment (fixed asset)</option>
              </select>
            </div>
          ))}
          <button className="text-sm text-brand-700 underline" onClick={() => setLines([...lines, blank()])}>
            + Add line
          </button>
        </div>
        <Field label={`Bill total ₹ (items add up to ${inr(sum)})`}>
          <input className="input" inputMode="decimal" placeholder={String(sum)} value={total} onChange={(e) => setTotal(e.target.value)} />
        </Field>
        {purchase && (
          <Field label="Reason for correction (required)">
            <input className="input" value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
        )}
        <ErrorNote error={save.error} />
        <button
          className="btn-primary w-full"
          disabled={save.isPending || (!total && sum <= 0) || (!!purchase && !reason.trim())}
          onClick={() => save.mutate()}
        >
          Save
        </button>
      </div>
    </Modal>
  )
}
