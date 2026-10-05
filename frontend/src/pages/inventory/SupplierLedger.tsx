import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post } from '../../api'
import { dmy, inr, todayIso } from '../../format'
import { useAuth } from '../../auth'
import { ErrorNote, Field, Loading, Modal, PageTitle, ReasonDialog } from '../../components/ui'
import type { SupplierBalance, SupplierLedger as Ledger } from '../../types.phase2'
import { Banner, Tags } from './shared'

const METHODS = ['CASH', 'UPI', 'CARD', 'BANK', 'CHEQUE', 'OTHER']

export default function SupplierLedgerPage() {
  const [sel, setSel] = useState<SupplierBalance | null>(null)
  const q = useQuery({ queryKey: ['supplier-balances'], queryFn: () => get<SupplierBalance[]>('/supplier-ledger') })
  if (sel) return <SupplierDetail id={sel.supplierId} name={sel.supplierName} onBack={() => setSel(null)} />
  return (
    <div>
      <PageTitle title="Supplier ledger" />
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      <div className="space-y-2">
        {q.data?.map((s) => (
          <button key={s.supplierId} className="card block w-full p-3 text-left" onClick={() => setSel(s)}>
            <div className="flex justify-between">
              <span className="font-medium">{s.supplierName}</span>
              <span className={Number(s.balance) > 0 ? 'font-semibold text-red-700' : 'font-semibold'}>{inr(s.balance)}</span>
            </div>
            <div className="text-xs text-slate-500">
              bought on credit {inr(s.creditPurchases)} · paid {inr(s.paymentsTotal)}
            </div>
          </button>
        ))}
        {q.data?.length === 0 && <p className="text-sm text-slate-500">No supplier purchases or payments yet.</p>}
      </div>
    </div>
  )
}

function SupplierDetail({ id, name, onBack }: { id: number; name: string; onBack: () => void }) {
  const { can } = useAuth()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['supplier-ledger', id], queryFn: () => get<Ledger>(`/suppliers/${id}/ledger`) })
  const [adding, setAdding] = useState(false)
  const [voiding, setVoiding] = useState<number | null>(null)
  const l = q.data
  return (
    <div>
      <button className="mb-2 text-sm text-brand-700 underline" onClick={onBack}>
        ← All suppliers
      </button>
      <PageTitle title={name}>
        {can('supplier_ledger.manage') && (
          <button className="btn-primary" onClick={() => setAdding(true)}>
            ➕ Payment
          </button>
        )}
      </PageTitle>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      {l && (
        <div className="space-y-3">
          <div className="card">
            <div className="flex items-center justify-between">
              <div>
                <div className="text-xs uppercase text-slate-500">We owe</div>
                <div className="text-2xl font-semibold">{inr(l.balance)}</div>
                {Number(l.advance) > 0 && <div className="text-xs text-emerald-700">Advance paid: {inr(l.advance)}</div>}
              </div>
              <Tags labels={l.flags} />
            </div>
            <div className="mt-2 grid grid-cols-3 gap-2 text-center text-sm">
              <div className="rounded-lg bg-slate-50 p-2">
                <div className="text-xs text-slate-500">0-30 days</div>
                {inr(l.aging.bucket0to30)}
              </div>
              <div className="rounded-lg bg-slate-50 p-2">
                <div className="text-xs text-slate-500">31-60 days</div>
                {inr(l.aging.bucket31to60)}
              </div>
              <div className="rounded-lg bg-slate-50 p-2">
                <div className="text-xs text-slate-500">60+ days</div>
                <span className={Number(l.aging.bucket60plus) > 0 ? 'text-red-700' : ''}>{inr(l.aging.bucket60plus)}</span>
              </div>
            </div>
            <div className="mt-2 text-xs text-slate-500">
              Purchases {inr(l.purchasesTotal)} (paid at purchase {inr(l.paidAtPurchase)}, on credit {inr(l.creditPurchases)}) · payments {inr(l.paymentsTotal)}
            </div>
          </div>
          {l.notes.map((n) => (
            <Banner key={n} tone="info">
              {n}
            </Banner>
          ))}
          <div className="card">
            <div className="mb-1 font-medium">Unpaid bills (oldest first)</div>
            {l.aging.openBills.map((b) => (
              <div key={b.id} className="flex justify-between border-t border-slate-100 py-1 text-sm">
                <span>
                  {dmy(b.date)} <span className="text-xs text-slate-500">{b.ageDays} days · {b.bucket}</span>
                </span>
                <span>{inr(b.remaining)}</span>
              </div>
            ))}
            {l.aging.openBills.length === 0 && <p className="text-sm text-slate-500">Nothing outstanding.</p>}
          </div>
          <div className="card">
            <div className="mb-1 font-medium">Payments</div>
            {l.payments.map((p) => (
              <div key={p.id} className="flex items-center justify-between border-t border-slate-100 py-1 text-sm">
                <span>
                  {dmy(p.txnDate)} · {p.method.toLowerCase()} {p.reference ? `· ${p.reference}` : ''}
                </span>
                <span className="flex items-center gap-2">
                  {inr(p.amount)}
                  {can('supplier_ledger.manage') && (
                    <button className="text-xs text-red-600 underline" onClick={() => setVoiding(p.id)}>
                      Void
                    </button>
                  )}
                </span>
              </div>
            ))}
            {l.payments.length === 0 && <p className="text-sm text-slate-500">No payments recorded.</p>}
          </div>
        </div>
      )}
      {adding && <PaymentForm supplierId={id} onClose={() => setAdding(false)} />}
      {voiding !== null && (
        <ReasonDialog
          title="Void this payment?"
          confirmLabel="Void"
          danger
          onConfirm={async (reason) => {
            await post(`/supplier-payments/${voiding}/void`, { reason })
            qc.invalidateQueries()
          }}
          onClose={() => setVoiding(null)}
        />
      )}
    </div>
  )
}

function PaymentForm({ supplierId, onClose }: { supplierId: number; onClose: () => void }) {
  const qc = useQueryClient()
  const [date, setDate] = useState(todayIso())
  const [amount, setAmount] = useState('')
  const [method, setMethod] = useState('UPI')
  const [reference, setReference] = useState('')
  const [notes, setNotes] = useState('')
  const save = useMutation({
    mutationFn: () => post('/supplier-payments', { supplierId, txnDate: date, amount, method, reference: reference || null, notes: notes || null }),
    onSuccess: () => {
      qc.invalidateQueries()
      onClose()
    },
  })
  return (
    <Modal title="Payment to supplier" onClose={onClose}>
      <div className="space-y-3">
        <div className="grid grid-cols-2 gap-2">
          <Field label="Date">
            <input type="date" className="input" value={date} max={todayIso()} onChange={(e) => setDate(e.target.value)} />
          </Field>
          <Field label="Amount ₹">
            <input className="input" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} autoFocus />
          </Field>
        </div>
        <Field label="Paid via">
          <select className="input" value={method} onChange={(e) => setMethod(e.target.value)}>
            {METHODS.map((m) => (
              <option key={m}>{m}</option>
            ))}
          </select>
        </Field>
        <Field label="Reference">
          <input className="input" value={reference} onChange={(e) => setReference(e.target.value)} />
        </Field>
        <Field label="Notes">
          <input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <ErrorNote error={save.error} />
        <button className="btn-primary w-full" disabled={!amount || save.isPending} onClick={() => save.mutate()}>
          Save
        </button>
      </div>
    </Modal>
  )
}
