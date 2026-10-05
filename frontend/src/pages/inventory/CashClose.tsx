import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, get, post, put, qs } from '../../api'
import { addDays, dmy, inr, monthStartIso, todayIso } from '../../format'
import { useAuth } from '../../auth'
import { DateRange, ErrorNote, Field, Loading, Modal, PageTitle, ReasonDialog } from '../../components/ui'
import type { CashDay, CashDaySummary, Settlement, SettlementSummary } from '../../types.phase2'
import { Banner, Tabs, Tag, Tags } from './shared'

const CATEGORIES = ['CHANGE_SHORT', 'CASH_TAKEN_BY_OWNER', 'BANK_DEPOSIT', 'REFUND', 'ROUNDING', 'OTHER', 'UNKNOWN']
const METHODS = ['UPI', 'CARD', 'WHATSAPP', 'SWIGGY', 'ZOMATO', 'OTHER_ONLINE']
const label = (s: string) => s.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase())

export default function CashClose() {
  const [tab, setTab] = useState('day')
  return (
    <div>
      <PageTitle title="Cash close" />
      <Tabs
        tabs={[
          { id: 'day', label: 'Daily cash' },
          { id: 'payments', label: 'Payments received' },
        ]}
        value={tab}
        onChange={setTab}
      />
      {tab === 'day' ? <DayTab /> : <PaymentsTab />}
    </div>
  )
}

// ---------------------------------------------------------------- daily cash

function DayTab() {
  const [date, setDate] = useState(todayIso())
  const day = useQuery({ queryKey: ['cash-day', date], queryFn: () => get<CashDay>(`/cash-days/${date}`) })
  const recent = useQuery({
    queryKey: ['cash-days', 'recent'],
    queryFn: () => get<CashDaySummary[]>(`/cash-days${qs({ from: addDays(todayIso(), -45), to: todayIso() })}`),
  })
  return (
    <div className="space-y-4">
      <div className="card">
        <Field label="Day">
          <input type="date" className="input" value={date} max={todayIso()} onChange={(e) => setDate(e.target.value)} />
        </Field>
      </div>
      {day.isLoading && <Loading />}
      <ErrorNote error={day.error} />
      {day.data && <DaySheet key={`${date}-${day.data.status}-${day.data.reopenCount}-${day.data.explanations.length}-${day.data.countedCash}`} day={day.data} />}
      <div className="card">
        <div className="mb-2 font-medium">Recent days</div>
        {recent.isLoading && <Loading />}
        <ErrorNote error={recent.error} />
        {recent.data?.map((r) => (
          <button key={r.txnDate} className="flex w-full items-center justify-between border-t border-slate-100 py-2 text-left text-sm" onClick={() => setDate(r.txnDate)}>
            <span>{dmy(r.txnDate)}</span>
            <span className="flex items-center gap-2">
              <Tags labels={r.flags} />
              <span className={r.difference !== null && Number(r.difference) !== 0 ? 'text-red-700' : ''}>{r.difference === null ? '—' : inr(r.difference)}</span>
            </span>
          </button>
        ))}
        {recent.data?.length === 0 && <p className="text-sm text-slate-500">No cash days recorded yet.</p>}
      </div>
    </div>
  )
}

function DaySheet({ day }: { day: CashDay }) {
  const { can } = useAuth()
  const qc = useQueryClient()
  const closed = day.status === 'CLOSED'
  const canEdit = can('cash.close') && !closed
  const [opening, setOpening] = useState(day.status === 'NOT_STARTED' ? '' : day.openingFloat)
  const [counted, setCounted] = useState(day.countedCash ?? '')
  const [notes, setNotes] = useState(day.notes ?? '')
  const [expl, setExpl] = useState(false)
  const [reopening, setReopening] = useState(false)
  const save = useMutation({
    mutationFn: () => put<CashDay>(`/cash-days/${day.txnDate}`, { openingFloat: opening || '0', countedCash: counted || null, notes }),
    onSuccess: () => qc.invalidateQueries(),
  })
  const close = useMutation({
    mutationFn: async () => {
      await put<CashDay>(`/cash-days/${day.txnDate}`, { openingFloat: opening || '0', countedCash: counted || null, notes })
      return post<CashDay>(`/cash-days/${day.txnDate}/close`)
    },
    onSuccess: () => qc.invalidateQueries(),
  })
  const rm = useMutation({
    mutationFn: (id: number) => api<CashDay>('DELETE', `/cash-days/${day.txnDate}/explanations/${id}`),
    onSuccess: () => qc.invalidateQueries(),
  })
  const diff = day.difference
  return (
    <div className="card space-y-3">
      <div className="flex items-center justify-between">
        <div className="font-medium">{dmy(day.txnDate)}</div>
        <Tags labels={[day.status === 'NOT_STARTED' ? null : day.status, ...day.flags.filter((f) => f !== 'DRAFT')]} />
      </div>
      {day.flags.includes('CASH_SPLIT_NOT_ENTERED') && <Banner tone="warn">This day has only a quick total with no cash split, so cash sales are not known.</Banner>}
      {day.flags.includes('NO_SALES_RECORDED') && <Banner tone="warn">No sales are recorded for this day. Expected cash does not include any sales.</Banner>}
      {day.flags.includes('CHANGED_SINCE_CLOSE') && (
        <Banner tone="warn">Sales or expenses changed after this day was closed. Live expected cash is {inr(day.liveExpectedCash)}; the closed figure is kept as it was.</Banner>
      )}
      <Field label="Opening float ₹">
        <input className="input" inputMode="decimal" disabled={!canEdit} value={opening} onChange={(e) => setOpening(e.target.value)} placeholder="Cash in the till at start" />
      </Field>
      {canEdit && day.suggestedOpeningFloat !== null && opening === '' && (
        <button className="text-xs text-brand-700 underline" onClick={() => setOpening(day.suggestedOpeningFloat ?? '')}>
          Use {inr(day.suggestedOpeningFloat)} (counted at the last close)
        </button>
      )}
      <div className="space-y-1 text-sm">
        <Row k="Cash sales" v={day.cashSales === null ? 'not known' : inr(day.cashSales)} />
        <Row k="Cash expenses" v={`− ${inr(day.cashExpenses)}`} />
        <Row k="Expected cash" v={inr(day.expectedCash)} bold />
        {day.supplierCashPayments !== '0' && Number(day.supplierCashPayments) > 0 && (
          <p className="text-xs text-slate-500">Cash paid to suppliers ({inr(day.supplierCashPayments)}) is not deducted; explain it below if it came from the till.</p>
        )}
      </div>
      <Field label="Counted cash ₹">
        <input className="input" inputMode="decimal" disabled={!canEdit} value={counted} onChange={(e) => setCounted(e.target.value)} />
      </Field>
      {diff !== null && (
        <div className={`rounded-lg p-3 text-center ${Number(diff) === 0 ? 'bg-emerald-50 text-emerald-800' : 'bg-red-50 text-red-800'}`}>
          <div className="text-xs uppercase">Difference (counted − expected)</div>
          <div className="text-2xl font-semibold">{inr(diff)}</div>
          {Number(diff) !== 0 && day.unexplained !== null && <div className="text-sm">Unexplained: {inr(day.unexplained)}</div>}
        </div>
      )}
      <div>
        <div className="mb-1 flex items-center justify-between">
          <div className="label mb-0">Explanations</div>
          {canEdit && day.id !== null && (
            <button className="text-xs text-brand-700 underline" onClick={() => setExpl(true)}>
              + Explain
            </button>
          )}
        </div>
        {day.explanations.map((e) => (
          <div key={e.id} className="flex items-center justify-between border-t border-slate-100 py-1 text-sm">
            <span>
              {label(e.category)} {e.note ? <span className="text-xs text-slate-500">· {e.note}</span> : null}
              {e.category === 'UNKNOWN' && <span className="ml-1 text-xs text-red-700">(not counted as explained)</span>}
            </span>
            <span className="flex items-center gap-2">
              {inr(e.amount)}
              {canEdit && (
                <button className="text-xs text-red-600 underline" onClick={() => rm.mutate(e.id)}>
                  remove
                </button>
              )}
            </span>
          </div>
        ))}
        {day.id === null && canEdit && <p className="text-xs text-slate-500">Save the day first to add explanations.</p>}
        <ErrorNote error={rm.error} />
      </div>
      <Field label="Notes">
        <input className="input" disabled={!canEdit} value={notes} onChange={(e) => setNotes(e.target.value)} />
      </Field>
      <ErrorNote error={save.error ?? close.error} />
      {canEdit && (
        <div className="flex gap-2">
          <button className="btn-ghost flex-1" disabled={save.isPending} onClick={() => save.mutate()}>
            Save draft
          </button>
          <button className="btn-primary flex-1" disabled={close.isPending || !counted} onClick={() => close.mutate()}>
            Close day
          </button>
        </div>
      )}
      {canEdit && <p className="text-xs text-slate-500">A day can be closed with an unexplained difference; it stays flagged UNEXPLAINED. Nothing is forced to zero.</p>}
      {closed && (
        <div className="text-xs text-slate-500">
          Closed {day.closedByName ? `by ${day.closedByName}` : ''} {day.reopenCount > 0 ? `· reopened ${day.reopenCount} time(s) before` : ''}
          {can('cash.close') && (
            <button className="ml-3 text-red-600 underline" onClick={() => setReopening(true)}>
              Reopen with a reason
            </button>
          )}
        </div>
      )}
      {expl && <ExplainForm date={day.txnDate} diff={day.unexplained} onClose={() => setExpl(false)} />}
      {reopening && (
        <ReasonDialog
          title="Reopen this cash day?"
          confirmLabel="Reopen"
          danger
          onConfirm={async (reason) => {
            await post(`/cash-days/${day.txnDate}/reopen`, { reason })
            qc.invalidateQueries()
          }}
          onClose={() => setReopening(false)}
        />
      )}
    </div>
  )
}

function Row({ k, v, bold }: { k: string; v: string; bold?: boolean }) {
  return (
    <div className={`flex justify-between ${bold ? 'font-semibold' : ''}`}>
      <span>{k}</span>
      <span>{v}</span>
    </div>
  )
}

function ExplainForm({ date, diff, onClose }: { date: string; diff: string | null; onClose: () => void }) {
  const qc = useQueryClient()
  const [category, setCategory] = useState('CHANGE_SHORT')
  const [amount, setAmount] = useState(diff && Number(diff) !== 0 ? diff : '')
  const [note, setNote] = useState('')
  const save = useMutation({
    mutationFn: () => post(`/cash-days/${date}/explanations`, { category, amount, note: note || null }),
    onSuccess: () => {
      qc.invalidateQueries()
      onClose()
    },
  })
  return (
    <Modal title="Explain the difference" onClose={onClose}>
      <div className="space-y-3">
        <Field label="Reason">
          <select className="input" value={category} onChange={(e) => setCategory(e.target.value)}>
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {label(c)}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Amount ₹ (negative when cash is missing)">
          <input className="input" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
        </Field>
        <Field label="Note">
          <input className="input" value={note} onChange={(e) => setNote(e.target.value)} />
        </Field>
        <ErrorNote error={save.error} />
        <button className="btn-primary w-full" disabled={!amount || save.isPending} onClick={() => save.mutate()}>
          Add
        </button>
      </div>
    </Modal>
  )
}

// ---------------------------------------------------------------- payment settlements

function PaymentsTab() {
  const { can } = useAuth()
  const qc = useQueryClient()
  const [from, setFrom] = useState(monthStartIso())
  const [to, setTo] = useState(todayIso())
  const [adding, setAdding] = useState(false)
  const [voiding, setVoiding] = useState<Settlement | null>(null)
  const sum = useQuery({ queryKey: ['settle-sum', from, to], queryFn: () => get<SettlementSummary>(`/payment-settlements/summary${qs({ from, to })}`) })
  const list = useQuery({ queryKey: ['settle', from, to], queryFn: () => get<Settlement[]>(`/payment-settlements${qs({ from, to })}`) })
  return (
    <div className="space-y-4">
      <div className="card flex flex-wrap items-end justify-between gap-2">
        <DateRange from={from} to={to} onChange={(f, t) => (setFrom(f), setTo(t))} />
        {can('cash.close') && (
          <button className="btn-primary" onClick={() => setAdding(true)}>
            ➕ Payment received
          </button>
        )}
      </div>
      {sum.isLoading && <Loading />}
      <ErrorNote error={sum.error} />
      {sum.data && (
        <>
          <div className="card">
            <div className="mb-2 font-medium">By method</div>
            {sum.data.totals.map((t) => (
              <div key={t.method} className="flex items-center justify-between border-t border-slate-100 py-2 text-sm">
                <div>
                  <div className="font-medium">{label(t.method)}</div>
                  <div className="text-xs text-slate-500">
                    expected {inr(t.expected)} · received {inr(t.received)}
                    {t.pendingDays > 0 ? ` · ${t.pendingDays} day(s) pending` : ''}
                  </div>
                </div>
                <div className={Number(t.difference) < 0 ? 'font-semibold text-red-700' : 'font-semibold'}>{inr(t.difference)}</div>
              </div>
            ))}
            {sum.data.totals.length === 0 && <p className="text-sm text-slate-500">No non-cash sales in this range.</p>}
            {sum.data.notes.map((n) => (
              <p key={n} className="mt-1 text-xs text-slate-500">
                {n}
              </p>
            ))}
          </div>
          <div className="card">
            <div className="mb-2 font-medium">Day by day</div>
            {sum.data.rows.map((r) => (
              <div key={`${r.txnDate}-${r.method}`} className="flex items-center justify-between border-t border-slate-100 py-2 text-sm">
                <div>
                  {dmy(r.txnDate)} · {label(r.method)}
                  <div className="text-xs text-slate-500">
                    expected {r.expected === null ? '—' : inr(r.expected)} · received {r.received === null ? 'not recorded' : inr(r.received)}
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <Tag label={r.status} />
                  <span>{r.difference === null ? '' : inr(r.difference)}</span>
                </div>
              </div>
            ))}
          </div>
        </>
      )}
      <div className="card">
        <div className="mb-2 font-medium">Entries</div>
        {list.isLoading && <Loading />}
        <ErrorNote error={list.error} />
        {list.data?.map((s) => (
          <div key={s.id} className={`flex items-center justify-between border-t border-slate-100 py-2 text-sm ${s.voidState === 'VOIDED' ? 'opacity-60' : ''}`}>
            <div>
              {dmy(s.txnDate)} · {label(s.method)} {s.reference ? `· ${s.reference}` : ''}
              {s.notes && <div className="text-xs text-slate-500">{s.notes}</div>}
            </div>
            <div className="flex items-center gap-2">
              {inr(s.receivedAmount)}
              {s.voidState === 'VOIDED' && <Tag label="VOIDED" />}
              {can('cash.close') && s.voidState === 'ACTIVE' && (
                <button className="text-xs text-red-600 underline" onClick={() => setVoiding(s)}>
                  Void
                </button>
              )}
            </div>
          </div>
        ))}
        {list.data?.length === 0 && <p className="text-sm text-slate-500">No entries.</p>}
      </div>
      {adding && <SettlementForm onClose={() => setAdding(false)} />}
      {voiding && (
        <ReasonDialog
          title="Void this entry?"
          confirmLabel="Void"
          danger
          onConfirm={async (reason) => {
            await post(`/payment-settlements/${voiding.id}/void`, { reason })
            qc.invalidateQueries()
          }}
          onClose={() => setVoiding(null)}
        />
      )}
    </div>
  )
}

function SettlementForm({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const [date, setDate] = useState(addDays(todayIso(), -1))
  const [method, setMethod] = useState('UPI')
  const [amount, setAmount] = useState('')
  const [reference, setReference] = useState('')
  const [notes, setNotes] = useState('')
  const save = useMutation({
    mutationFn: () => post('/payment-settlements', { txnDate: date, method, receivedAmount: amount, reference: reference || null, notes: notes || null }),
    onSuccess: () => {
      qc.invalidateQueries()
      onClose()
    },
  })
  return (
    <Modal title="Payment received" onClose={onClose}>
      <div className="space-y-3">
        <div className="grid grid-cols-2 gap-2">
          <Field label="Sales day">
            <input type="date" className="input" value={date} max={todayIso()} onChange={(e) => setDate(e.target.value)} />
          </Field>
          <Field label="Method">
            <select className="input" value={method} onChange={(e) => setMethod(e.target.value)}>
              {METHODS.map((m) => (
                <option key={m} value={m}>
                  {label(m)}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <Field label="Amount received ₹">
          <input className="input" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} autoFocus />
        </Field>
        <Field label="Reference (settlement id)">
          <input className="input" value={reference} onChange={(e) => setReference(e.target.value)} />
        </Field>
        <Field label="Notes (for example commission deducted)">
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
