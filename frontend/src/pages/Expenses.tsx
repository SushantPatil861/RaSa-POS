import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post, put, qs } from '../api'
import { dmy, inr, monthStartIso, todayIso } from '../format'
import type { Expense } from '../types'
import { Chips, DateRange, ErrorNote, Field, HistoryDrawer, Loading, Modal, PageTitle, ReasonDialog } from '../components/ui'
import { useAuth } from '../auth'

interface Cat { id: number; name: string }
const METHODS = ['CASH', 'UPI', 'CARD', 'BANK', 'OTHER']

export default function Expenses() {
  const { can } = useAuth()
  const qc = useQueryClient()
  const [from, setFrom] = useState(monthStartIso())
  const [to, setTo] = useState(todayIso())
  const [edit, setEdit] = useState<Expense | 'new' | null>(null)
  const [voiding, setVoiding] = useState<Expense | null>(null)
  const [hist, setHist] = useState<number | null>(null)
  const list = useQuery({ queryKey: ['expenses', from, to], queryFn: () => get<Expense[]>(`/expenses${qs({ from, to })}`) })
  const cats = useQuery({ queryKey: ['expense-cats'], queryFn: () => get<Cat[]>('/expense-categories') })
  const total = list.data?.reduce((s, e) => s + Number(e.amount), 0) ?? 0
  return (
    <div>
      <PageTitle title="Expenses">
        {can('expenses.create') && (
          <button className="btn-primary" onClick={() => setEdit('new')}>
            ➕ Add
          </button>
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
        {list.data?.map((e) => (
          <div key={e.id} className="card p-3">
            <div className="flex justify-between">
              <div className="font-medium">{e.categoryName ?? <span className="text-amber-700">Uncategorised</span>}</div>
              <div className="font-semibold">{inr(e.amount)}</div>
            </div>
            <div className="text-xs text-slate-500">
              {dmy(e.txnDate)} · {e.paymentMethod ?? 'method not recorded'} {e.paidTo ? `· ${e.paidTo}` : ''}
            </div>
            {e.notes && <div className="text-sm">{e.notes}</div>}
            <div className="mt-1 flex items-center justify-between">
              <Chips flags={e.flags.filter((f) => f !== 'MANUAL')} />
              <div className="flex gap-3 text-xs">
                <button className="text-slate-500 underline" onClick={() => setHist(e.id)}>
                  History
                </button>
                {can('expenses.edit') && (
                  <>
                    <button className="text-brand-700 underline" onClick={() => setEdit(e)}>
                      Correct
                    </button>
                    <button className="text-red-600 underline" onClick={() => setVoiding(e)}>
                      Void
                    </button>
                  </>
                )}
              </div>
            </div>
          </div>
        ))}
        {list.data?.length === 0 && <p className="text-sm text-slate-500">No expenses in this range.</p>}
      </div>
      {edit && <ExpenseForm cats={cats.data ?? []} expense={edit === 'new' ? null : edit} onClose={() => setEdit(null)} />}
      {voiding && (
        <ReasonDialog
          title="Void this expense?"
          confirmLabel="Void"
          danger
          onConfirm={async (reason) => {
            await post(`/expenses/${voiding.id}/void`, { reason })
            qc.invalidateQueries()
          }}
          onClose={() => setVoiding(null)}
        />
      )}
      {hist !== null && <HistoryDrawer entityType="EXPENSE" entityId={hist} onClose={() => setHist(null)} />}
    </div>
  )
}

function ExpenseForm({ cats, expense, onClose }: { cats: Cat[]; expense: Expense | null; onClose: () => void }) {
  const qc = useQueryClient()
  const [date, setDate] = useState(expense?.txnDate ?? todayIso())
  const [cat, setCat] = useState(expense?.categoryId?.toString() ?? '')
  const [amount, setAmount] = useState(expense?.amount ?? '')
  const [method, setMethod] = useState(expense?.paymentMethod ?? 'CASH')
  const [paidTo, setPaidTo] = useState(expense?.paidTo ?? '')
  const [notes, setNotes] = useState(expense?.notes ?? '')
  const [est, setEst] = useState(expense?.estimated ?? false)
  const [reason, setReason] = useState('')
  const save = useMutation({
    mutationFn: () => {
      const body = {
        txnDate: date,
        categoryId: cat ? Number(cat) : null,
        amount,
        paymentMethod: method || null,
        paidTo: paidTo || null,
        notes: notes || null,
        estimated: est,
        reason: expense ? reason : undefined,
        version: expense?.version,
      }
      return expense ? put(`/expenses/${expense.id}`, body) : post('/expenses', body)
    },
    onSuccess: () => {
      qc.invalidateQueries()
      onClose()
    },
  })
  return (
    <Modal title={expense ? 'Correct expense' : 'Add expense'} onClose={onClose}>
      <div className="space-y-3">
        <div className="grid grid-cols-2 gap-2">
          <Field label="Date">
            <input type="date" className="input" value={date} max={todayIso()} onChange={(e) => setDate(e.target.value)} />
          </Field>
          <Field label="Amount ₹">
            <input className="input" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} autoFocus />
          </Field>
        </div>
        <Field label="Category (can be set later)">
          <select className="input" value={cat} onChange={(e) => setCat(e.target.value)}>
            <option value="">Uncategorised</option>
            {cats.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Paid via">
            <select className="input" value={method} onChange={(e) => setMethod(e.target.value)}>
              {METHODS.map((m) => (
                <option key={m}>{m}</option>
              ))}
            </select>
          </Field>
          <Field label="Paid to">
            <input className="input" value={paidTo} onChange={(e) => setPaidTo(e.target.value)} />
          </Field>
        </div>
        <Field label="Notes">
          <input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={est} onChange={(e) => setEst(e.target.checked)} /> Amount is an estimate
        </label>
        {expense && (
          <Field label="Reason for correction (required)">
            <input className="input" value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
        )}
        <ErrorNote error={save.error} />
        <button
          className="btn-primary w-full"
          disabled={!amount || save.isPending || (!!expense && !reason.trim())}
          onClick={() => save.mutate()}
        >
          Save
        </button>
      </div>
    </Modal>
  )
}
