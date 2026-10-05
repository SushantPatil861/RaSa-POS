import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post, qs } from '../api'
import { addToCart, CartTable, cartTotal, ItemPicker, PAY_METHODS, type CartLine } from '../components/Cart'
import { ErrorNote, Field, Loading, Modal, PageTitle, ReasonDialog } from '../components/ui'
import { addDays, inr, todayIso } from '../format'
import type { Order, OrderDetail } from '../types'
import { useAuth } from '../auth'

const COLS = ['NEW', 'CONFIRMED', 'PREPARING', 'READY', 'OUT_FOR_DELIVERY']
const NEXT: Record<string, string> = { NEW: 'PREPARING', CONFIRMED: 'PREPARING', PREPARING: 'READY', READY: 'OUT_FOR_DELIVERY' }

export default function Orders() {
  const { can } = useAuth()
  const qc = useQueryClient()
  const [creating, setCreating] = useState(false)
  const [open, setOpen] = useState<number | null>(null)
  const q = useQuery({
    queryKey: ['orders'],
    queryFn: () => get<Order[]>(`/orders${qs({ from: addDays(todayIso(), -2), to: todayIso() })}`),
    refetchInterval: 15000,
  })
  const move = useMutation({
    mutationFn: ({ id, status }: { id: number; status: string }) => post(`/orders/${id}/status`, { status }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['orders'] }),
  })
  if (q.isLoading) return <Loading />
  const orders = q.data ?? []
  return (
    <div>
      <PageTitle title="Orders">
        {can('orders.manage') && (
          <button className="btn-primary" onClick={() => setCreating(true)}>
            ➕ New order
          </button>
        )}
      </PageTitle>
      <ErrorNote error={q.error || move.error} />
      <div className="grid gap-3 md:grid-cols-5">
        {COLS.map((c) => (
          <div key={c} className="rounded-xl bg-slate-100 p-2">
            <div className="mb-2 px-1 text-xs font-semibold uppercase text-slate-500">
              {c.replace(/_/g, ' ')} ({orders.filter((o) => o.status === c).length})
            </div>
            <div className="space-y-2">
              {orders
                .filter((o) => o.status === c)
                .map((o) => (
                  <div key={o.id} className="card cursor-pointer p-3" onClick={() => setOpen(o.id)}>
                    <div className="flex justify-between text-sm font-semibold">
                      <span>{o.orderNo}</span>
                      <span>{inr(o.totalAmount, { decimals: 0 })}</span>
                    </div>
                    <div className="text-xs text-slate-500">
                      {o.customerName ?? 'Walk-in'} · {o.source} · {o.orderType.replace('_', ' ')}
                    </div>
                    {NEXT[o.status] && (can('orders.prepare') || can('orders.manage')) && (
                      <button
                        className="btn-ghost mt-2 w-full py-1 text-xs"
                        onClick={(e) => {
                          e.stopPropagation()
                          move.mutate({ id: o.id, status: NEXT[o.status] })
                        }}
                      >
                        → {NEXT[o.status].replace(/_/g, ' ')}
                      </button>
                    )}
                  </div>
                ))}
            </div>
          </div>
        ))}
      </div>
      <div className="mt-4 text-xs text-slate-500">Completed and cancelled orders from the last 3 days are in Sales (completed orders become sales automatically).</div>
      {creating && <NewOrder onClose={() => setCreating(false)} />}
      {open !== null && <OrderModal id={open} onClose={() => setOpen(null)} />}
    </div>
  )
}

function NewOrder({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const [lines, setLines] = useState<CartLine[]>([])
  const [source, setSource] = useState('COUNTER')
  const [type, setType] = useState('TAKEAWAY')
  const [name, setName] = useState('')
  const [mobile, setMobile] = useState('')
  const [note, setNote] = useState('')
  const save = useMutation({
    mutationFn: () =>
      post('/orders', {
        source,
        orderType: type,
        customerName: name || null,
        customerMobile: mobile || null,
        specialInstructions: note || null,
        items: lines.filter((l) => l.quantity > 0).map((l) => ({ productId: l.productId, variantId: l.variantId, quantity: l.quantity, discount: l.discount || null })),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['orders'] })
      onClose()
    },
  })
  return (
    <Modal title="New order" onClose={onClose}>
      <ItemPicker date={todayIso()} onAdd={(i) => setLines((l) => addToCart(l, i))} />
      <div className="mt-3">
        <CartTable lines={lines} onChange={setLines} />
        <div className="flex justify-between border-t pt-2 font-semibold">
          <span>Total</span>
          <span>{inr(cartTotal(lines))}</span>
        </div>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-2">
        <Field label="Source">
          <select className="input" value={source} onChange={(e) => setSource(e.target.value)}>
            {['COUNTER', 'WHATSAPP', 'PHONE', 'OTHER'].map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Type">
          <select className="input" value={type} onChange={(e) => setType(e.target.value)}>
            {['TAKEAWAY', 'PICKUP', 'DELIVERY', 'DINE_IN'].map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Customer name">
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Mobile">
          <input className="input" inputMode="tel" value={mobile} onChange={(e) => setMobile(e.target.value)} />
        </Field>
      </div>
      <Field label="Special instructions">
        <input className="input" value={note} onChange={(e) => setNote(e.target.value)} />
      </Field>
      <ErrorNote error={save.error} />
      <button className="btn-primary mt-3 w-full" disabled={!lines.length || save.isPending} onClick={() => save.mutate()}>
        Create order
      </button>
    </Modal>
  )
}

function OrderModal({ id, onClose }: { id: number; onClose: () => void }) {
  const { can } = useAuth()
  const qc = useQueryClient()
  const [method, setMethod] = useState('CASH')
  const [cancel, setCancel] = useState(false)
  const q = useQuery({ queryKey: ['order', id], queryFn: () => get<OrderDetail>(`/orders/${id}`) })
  const done = () => qc.invalidateQueries()
  const complete = useMutation({ mutationFn: () => post(`/orders/${id}/complete`, { paymentMethod: method }), onSuccess: () => (done(), onClose()) })
  if (!q.data) return <Modal title="Order" onClose={onClose}><Loading /></Modal>
  const o = q.data.order
  const closed = o.status === 'COMPLETED' || o.status === 'CANCELLED'
  return (
    <Modal title={`${o.orderNo} · ${o.status.replace(/_/g, ' ')}`} onClose={onClose}>
      <div className="text-sm text-slate-600">
        {o.customerName ?? 'Walk-in'} {o.customerMobile && `· ${o.customerMobile}`} · {o.source}
      </div>
      {o.specialInstructions && <div className="mt-1 rounded bg-amber-50 p-2 text-sm">📝 {o.specialInstructions}</div>}
      <ul className="mt-2 divide-y text-sm">
        {q.data.items.map((i) => (
          <li key={i.id} className="flex justify-between py-1">
            <span>
              {i.quantity} × {i.itemName}
            </span>
            <span>{inr(i.lineTotal)}</span>
          </li>
        ))}
      </ul>
      <div className="mt-2 flex justify-between border-t pt-2 font-semibold">
        <span>Total</span>
        <span>{inr(o.totalAmount)}</span>
      </div>
      <ErrorNote error={complete.error} />
      {!closed && can('orders.manage') && (
        <div className="mt-4 space-y-2">
          <Field label="Paid via">
            <select className="input" value={method} onChange={(e) => setMethod(e.target.value)}>
              {PAY_METHODS.map((m) => (
                <option key={m} value={m}>
                  {m.replace('_', ' ')}
                </option>
              ))}
            </select>
          </Field>
          <div className="flex gap-2">
            <button className="btn-primary flex-1" disabled={complete.isPending} onClick={() => complete.mutate()}>
              Take payment &amp; complete
            </button>
            <button className="btn-danger" onClick={() => setCancel(true)}>
              Cancel order
            </button>
          </div>
        </div>
      )}
      {cancel && (
        <ReasonDialog
          title="Cancel this order?"
          confirmLabel="Cancel order"
          danger
          onConfirm={async (reason) => {
            await post(`/orders/${id}/cancel`, { reason })
            done()
            onClose()
          }}
          onClose={() => setCancel(false)}
        />
      )}
    </Modal>
  )
}
