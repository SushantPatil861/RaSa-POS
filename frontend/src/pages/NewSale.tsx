import { useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post, put } from '../api'
import { inr, todayIso, toNumber } from '../format'
import type { SaleDetail } from '../types'
import { addToCart, CartTable, cartTotal, ItemPicker, PAY_METHODS, type CartLine } from '../components/Cart'
import { ErrorNote, Field, Loading, PageTitle } from '../components/ui'

interface Pay { method: string; amount: string }

/** Itemised sale. Used for new sales and (with :id) for corrections, which need a reason. */
export default function NewSale() {
  const { id } = useParams()
  const editing = !!id
  const nav = useNavigate()
  const qc = useQueryClient()
  const [date, setDate] = useState(todayIso())
  const [lines, setLines] = useState<CartLine[]>([])
  const [pays, setPays] = useState<Pay[]>([{ method: 'CASH', amount: '' }])
  const [notes, setNotes] = useState('')
  const [reason, setReason] = useState('')
  const [loaded, setLoaded] = useState(!editing)
  const [version, setVersion] = useState<number | undefined>()

  const existing = useQuery({
    queryKey: ['sale', id],
    enabled: editing,
    queryFn: () => get<SaleDetail>(`/sales/${id}`),
  })
  if (editing && existing.data && !loaded) {
    const s = existing.data
    setDate(s.sale.txnDate)
    setVersion(s.sale.version)
    setNotes(s.sale.notes ?? '')
    setLines(
      s.items.map((i) => ({
        key: `${i.productId}-${i.variantId}`,
        productId: i.productId,
        variantId: i.variantId,
        name: i.variantName && i.variantName !== 'Regular' ? `${i.productName} (${i.variantName})` : i.productName,
        listPrice: toNumber(i.listPriceAtSale ?? i.unitPrice),
        quantity: toNumber(i.quantity),
        unitPrice: toNumber(i.unitPrice),
        discount: toNumber(i.discount),
      })),
    )
    setPays(s.payments.map((p) => ({ method: p.method, amount: p.amount })))
    setLoaded(true)
  }

  const total = useMemo(() => cartTotal(lines), [lines])
  const paid = pays.reduce((s, p) => s + toNumber(p.amount), 0)
  // one payment with no amount typed = full total
  const effectivePays = pays.length === 1 && pays[0].amount === '' ? [{ method: pays[0].method, amount: String(total) }] : pays
  const effectivePaid = effectivePays.reduce((s, p) => s + toNumber(p.amount), 0)

  const save = useMutation({
    mutationFn: () => {
      const body = {
        txnDate: date,
        channel: 'COUNTER',
        notes: notes || null,
        items: lines
          .filter((l) => l.quantity > 0)
          .map((l) => ({
            productId: l.productId,
            variantId: l.variantId,
            quantity: l.quantity,
            unitPrice: l.unitPrice, // null -> server uses the menu price for that date
            discount: l.discount || null,
          })),
        payments: effectivePays.filter((p) => toNumber(p.amount) > 0).map((p) => ({ method: p.method, amount: p.amount })),
        reason: editing ? reason : undefined,
        version,
      }
      return editing ? put<SaleDetail>(`/sales/${id}`, body) : post<SaleDetail>('/sales', body)
    },
    onSuccess: (r) => {
      qc.invalidateQueries()
      nav(`/sales/${r.sale.id}`)
    },
  })

  if (editing && existing.isLoading) return <Loading />
  const diff = Math.round((total - effectivePaid) * 100) / 100
  return (
    <div>
      <PageTitle title={editing ? `Correct sale ${existing.data?.sale.saleNo ?? ''}` : 'New sale'} />
      <div className="grid gap-4 md:grid-cols-2">
        <div className="card">
          <Field label="Sale date">
            <input type="date" className="input mb-3" value={date} max={todayIso()} onChange={(e) => setDate(e.target.value)} />
          </Field>
          {date !== todayIso() && (
            <div className="mb-2 rounded bg-violet-50 p-2 text-xs text-violet-800">
              Past date: this will be marked BACKDATED and priced from the menu in force on that day.
            </div>
          )}
          <ItemPicker date={date} onAdd={(i) => setLines((l) => addToCart(l, i))} />
        </div>
        <div className="card">
          <div className="mb-2 text-sm font-semibold">Bill</div>
          <CartTable lines={lines} onChange={setLines} />
          <div className="mt-3 flex justify-between border-t pt-3 text-lg font-semibold">
            <span>Total (incl. GST)</span>
            <span>{inr(total)}</span>
          </div>

          <div className="mt-4 space-y-2">
            <div className="text-sm font-semibold">Paid via</div>
            {pays.map((p, idx) => (
              <div key={idx} className="flex gap-2">
                <select
                  className="input"
                  value={p.method}
                  onChange={(e) => setPays(pays.map((x, i) => (i === idx ? { ...x, method: e.target.value } : x)))}
                >
                  {PAY_METHODS.map((m) => (
                    <option key={m} value={m}>
                      {m.replace('_', ' ')}
                    </option>
                  ))}
                </select>
                <input
                  className="input"
                  inputMode="decimal"
                  placeholder={pays.length === 1 ? String(total) : 'Amount'}
                  value={p.amount}
                  onChange={(e) => setPays(pays.map((x, i) => (i === idx ? { ...x, amount: e.target.value } : x)))}
                />
                {pays.length > 1 && (
                  <button className="text-red-600" onClick={() => setPays(pays.filter((_, i) => i !== idx))}>
                    ✕
                  </button>
                )}
              </div>
            ))}
            <button className="text-sm text-brand-700 underline" onClick={() => setPays([...pays, { method: 'UPI', amount: '' }])}>
              + Split payment
            </button>
            {paid > 0 && diff !== 0 && (
              <div className="text-sm text-red-600">Payments are {inr(Math.abs(diff))} {diff > 0 ? 'short' : 'over'}.</div>
            )}
          </div>

          <div className="mt-4 space-y-2">
            <Field label="Notes">
              <input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} />
            </Field>
            {editing && (
              <Field label="Reason for correction (required)">
                <input className="input" value={reason} onChange={(e) => setReason(e.target.value)} />
              </Field>
            )}
          </div>
          <ErrorNote error={save.error} />
          <button
            className="btn-primary mt-4 w-full py-3"
            disabled={save.isPending || total <= 0 || diff !== 0 || (editing && !reason.trim())}
            onClick={() => save.mutate()}
          >
            {save.isPending ? 'Saving…' : editing ? 'Save correction' : `Save sale · ${inr(total)}`}
          </button>
        </div>
      </div>
    </div>
  )
}
