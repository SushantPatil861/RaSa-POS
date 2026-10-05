import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post, qs } from '../api'
import { addDays, dateTime, dmy, inr, monthStartIso, todayIso } from '../format'
import type { DayRow, DayView, SaleDetail, SaleSummary, SummaryDto } from '../types'
import { Chip, Chips, DateRange, ErrorNote, Field, HistoryDrawer, Loading, Modal, PageTitle, ReasonDialog } from '../components/ui'
import { useAuth } from '../auth'

export function SalesList() {
  const { can } = useAuth()
  const [from, setFrom] = useState(monthStartIso())
  const [to, setTo] = useState(todayIso())
  const [voided, setVoided] = useState(false)
  const days = useQuery({
    queryKey: ['days', from, to],
    queryFn: () => get<DayRow[]>(`/reports/sales-by-day${qs({ from, to })}`),
    enabled: can('reports.view'),
  })
  const sales = useQuery({
    queryKey: ['sales', from, to, voided],
    queryFn: () => get<SaleSummary[]>(`/sales${qs({ from, to, includeVoided: voided })}`),
  })
  return (
    <div>
      <PageTitle title="Sales">
        {can('sales.create') && (
          <Link to="/sales/new" className="btn-primary">
            ➕ New
          </Link>
        )}
      </PageTitle>
      <div className="card mb-4 flex flex-wrap items-end justify-between gap-2">
        <DateRange from={from} to={to} onChange={(f, t) => (setFrom(f), setTo(t))} />
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={voided} onChange={(e) => setVoided(e.target.checked)} /> Show voided
        </label>
      </div>

      {can('reports.view') && (
        <div className="card mb-4 overflow-x-auto">
          <div className="mb-2 text-sm font-semibold">By day</div>
          {days.isLoading && <Loading />}
          <ErrorNote error={days.error} />
          <table className="w-full">
            <thead>
              <tr>
                <th className="th">Date</th>
                <th className="th">Basis</th>
                <th className="th text-right">Total</th>
                <th className="th">Status</th>
              </tr>
            </thead>
            <tbody>
              {days.data
                ?.slice()
                .reverse()
                .map((d) => (
                  <tr key={d.txnDate} className="border-t border-slate-100">
                    <td className="td">
                      <Link className="text-brand-700 underline" to={`/days/${d.txnDate}`}>
                        {dmy(d.txnDate)}
                      </Link>
                    </td>
                    <td className="td">
                      <Chip label={d.basis === 'BOTH' ? `BOTH → ${d.chosen}` : d.basis} />
                    </td>
                    <td className="td text-right font-medium">{inr(d.totalInclusive)}</td>
                    <td className="td">{d.reviewStatus !== 'NOT_APPLICABLE' && <Chip label={d.reviewStatus} />}</td>
                  </tr>
                ))}
              {days.data?.length === 0 && (
                <tr>
                  <td className="td text-slate-500" colSpan={4}>
                    No sales in this range.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      <div className="card overflow-x-auto">
        <div className="mb-2 text-sm font-semibold">Item-wise bills</div>
        {sales.isLoading && <Loading />}
        <ErrorNote error={sales.error} />
        <table className="w-full">
          <thead>
            <tr>
              <th className="th">No.</th>
              <th className="th">Date</th>
              <th className="th text-right">Total</th>
              <th className="th">Flags</th>
            </tr>
          </thead>
          <tbody>
            {sales.data?.map((s) => (
              <tr key={s.id} className="border-t border-slate-100">
                <td className="td">
                  <Link className="text-brand-700 underline" to={`/sales/${s.id}`}>
                    {s.saleNo}
                  </Link>
                </td>
                <td className="td">{dmy(s.txnDate)}</td>
                <td className="td text-right">{inr(s.totalInclusive)}</td>
                <td className="td">
                  <Chips flags={s.flags.filter((f) => f !== 'CONFIRMED' && f !== 'MANUAL')} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

export function SaleDetailPage() {
  const { id } = useParams()
  const { can } = useAuth()
  const nav = useNavigate()
  const qc = useQueryClient()
  const [voidOpen, setVoidOpen] = useState(false)
  const [hist, setHist] = useState(false)
  const q = useQuery({ queryKey: ['sale', id], queryFn: () => get<SaleDetail>(`/sales/${id}`) })
  const doVoid = useMutation({
    mutationFn: (reason: string) => post(`/sales/${id}/void`, { reason }),
    onSuccess: () => qc.invalidateQueries(),
  })
  if (q.isLoading) return <Loading />
  if (q.error) return <ErrorNote error={q.error} />
  const { sale, items, payments, flags } = q.data!
  const voided = sale.voidState === 'VOIDED'
  return (
    <div>
      <PageTitle title={`Sale ${sale.saleNo}`}>
        <button className="btn-ghost" onClick={() => setHist(true)}>
          History
        </button>
        {!voided && can('sales.edit') && (
          <Link className="btn-ghost" to={`/sales/${sale.id}/edit`}>
            Correct
          </Link>
        )}
        {!voided && can('sales.void') && (
          <button className="btn-danger" onClick={() => setVoidOpen(true)}>
            Void
          </button>
        )}
      </PageTitle>
      <div className="card space-y-2">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <Link className="text-brand-700 underline" to={`/days/${sale.txnDate}`}>
            {dmy(sale.txnDate)}
          </Link>
          <span className="text-slate-500">entered {dateTime(sale.entryAt)}</span>
          <Chips flags={flags} />
        </div>
        {voided && <div className="rounded bg-red-50 p-2 text-sm text-red-700">Voided: {sale.voidReason}</div>}
        <table className="w-full">
          <thead>
            <tr>
              <th className="th">Item</th>
              <th className="th text-right">Qty</th>
              <th className="th text-right">Price</th>
              <th className="th text-right">Total</th>
            </tr>
          </thead>
          <tbody>
            {items.map((i) => (
              <tr key={i.id} className="border-t border-slate-100">
                <td className="td">
                  {i.productName} {i.variantName !== 'Regular' && <span className="text-slate-500">({i.variantName})</span>}
                  {i.listPriceAtSale && i.listPriceAtSale !== i.unitPrice && (
                    <div className="text-[11px] text-amber-700">menu price was {inr(i.listPriceAtSale)}</div>
                  )}
                </td>
                <td className="td text-right">{i.quantity}</td>
                <td className="td text-right">{inr(i.unitPrice)}</td>
                <td className="td text-right">{inr(i.lineTotal)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="space-y-1 border-t pt-2 text-sm">
          <div className="flex justify-between">
            <span>Net (ex-GST)</span>
            <span>{inr(sale.netTaxable)}</span>
          </div>
          <div className="flex justify-between">
            <span>GST</span>
            <span>{inr(sale.taxAmount)}</span>
          </div>
          <div className="flex justify-between text-base font-semibold">
            <span>Total</span>
            <span>{inr(sale.totalInclusive)}</span>
          </div>
        </div>
        <div className="border-t pt-2 text-sm">
          Paid via: {payments.map((p) => `${p.method.replace('_', ' ')} ${inr(p.amount)}`).join(' · ') || '—'}
        </div>
      </div>
      {voidOpen && (
        <ReasonDialog
          title="Void this sale?"
          confirmLabel="Void sale"
          danger
          onConfirm={(r) => doVoid.mutateAsync(r)}
          onClose={() => setVoidOpen(false)}
        />
      )}
      {hist && <HistoryDrawer entityType="SALE" entityId={sale.id} onClose={() => setHist(false)} />}
      <button className="mt-4 text-sm text-slate-500 underline" onClick={() => nav(-1)}>
        ← Back
      </button>
    </div>
  )
}

/** One trading day: item-wise bills, the quick total, their difference, and the Owner's approval. */
export function DayPage() {
  const { date } = useParams()
  const { can } = useAuth()
  const qc = useQueryClient()
  const [approve, setApprove] = useState<string | null>(null)
  const day = useQuery({ queryKey: ['day', date], queryFn: () => get<DayView>(`/days/${date}`) })
  const sales = useQuery({ queryKey: ['sales', date, date, false], queryFn: () => get<SaleSummary[]>(`/sales${qs({ from: date, to: date })}`) })
  const summaries = useQuery({
    queryKey: ['summaries', date],
    queryFn: () => get<SummaryDto[]>(`/daily-summaries${qs({ from: date, to: date })}`),
  })
  const decide = useMutation({
    mutationFn: ({ decision, reason }: { decision: string; reason: string }) => post(`/days/${date}/approve`, { decision, reason }),
    onSuccess: () => qc.invalidateQueries(),
  })
  if (day.isLoading) return <Loading />
  if (day.error) return <ErrorNote error={day.error} />
  const d = day.data!
  const d1 = addDays(date!, -1)
  const d2 = addDays(date!, 1)
  return (
    <div>
      <PageTitle title={dmy(date)}>
        <Link className="btn-ghost" to={`/days/${d1}`}>
          ←
        </Link>
        {d2 <= todayIso() && (
          <Link className="btn-ghost" to={`/days/${d2}`}>
            →
          </Link>
        )}
      </PageTitle>
      <div className="grid gap-3 md:grid-cols-2">
        <div className="card">
          <div className="text-xs uppercase text-slate-500">Item-wise bills</div>
          <div className="text-2xl font-semibold">{d.itemisedCount ? inr(d.itemisedTotal) : '—'}</div>
          <div className="text-xs text-slate-500">{d.itemisedCount} bills</div>
        </div>
        <div className="card">
          <div className="text-xs uppercase text-slate-500">Quick daily total (net)</div>
          <div className="text-2xl font-semibold">{d.summaryNetTotal !== null ? inr(d.summaryNetTotal) : '—'}</div>
        </div>
      </div>

      {d.basis === 'BOTH' && (
        <div className="card mt-3 border-l-4 border-amber-400">
          <div className="flex flex-wrap items-center gap-2">
            <b>Both kinds of entry exist for this day.</b> <Chip label={d.reviewStatus} />
          </div>
          <p className="mt-1 text-sm">
            Difference (item-wise − quick): <b>{inr(d.difference)}</b>. The two totals are never added together. Reports currently use the{' '}
            <b>{d.chosen === 'SUMMARY' ? 'quick total' : 'item-wise bills'}</b>.
          </p>
          {d.review && (
            <p className="mt-1 text-xs text-slate-600">
              Decision: {d.review.decision.replace(/_/g, ' ')} by {d.review.approvedByName} on {dateTime(d.review.approvedAt)}
              {d.review.reason ? ` — “${d.review.reason}”` : ''}
            </p>
          )}
          {can('sales.approve_day_variance') && (
            <div className="mt-3 flex flex-wrap gap-2">
              <button className="btn-primary" onClick={() => setApprove('ACCEPT_ITEMISED')}>
                Use item-wise bills
              </button>
              <button className="btn-ghost" onClick={() => setApprove('ACCEPT_SUMMARY')}>
                Use quick total
              </button>
              <button className="btn-ghost" onClick={() => setApprove('ACCEPT_EXPLAINED')}>
                Keep item-wise, difference explained
              </button>
            </div>
          )}
          {!can('sales.approve_day_variance') && <p className="mt-2 text-xs text-slate-500">Only the Owner can approve this difference.</p>}
        </div>
      )}

      <div className="card mt-3 overflow-x-auto">
        <div className="mb-2 text-sm font-semibold">Bills</div>
        {sales.isLoading ? <Loading /> : null}
        {sales.data?.length === 0 && <p className="text-sm text-slate-500">No item-wise bills.</p>}
        {sales.data?.map((s) => (
          <Link key={s.id} to={`/sales/${s.id}`} className="flex justify-between border-t border-slate-100 py-2 text-sm first:border-0">
            <span>
              {s.saleNo} <Chips flags={s.flags.filter((f) => f !== 'CONFIRMED' && f !== 'MANUAL')} />
            </span>
            <span>{inr(s.totalInclusive)}</span>
          </Link>
        ))}
      </div>
      <div className="card mt-3">
        <div className="mb-2 text-sm font-semibold">Quick daily entries</div>
        {summaries.data?.length === 0 && <p className="text-sm text-slate-500">None.</p>}
        {summaries.data?.map((s) => (
          <div key={s.summary.id} className="border-t border-slate-100 py-2 text-sm first:border-0">
            <div className="flex justify-between">
              <span>
                Total {inr(s.summary.totalSales)} · net {inr(s.netCollected)} <Chips flags={s.flags.filter((f) => f !== 'CONFIRMED' && f !== 'MANUAL')} />
              </span>
            </div>
            <div className="text-xs text-slate-500">
              Cash {inr(s.summary.cash)} · UPI {inr(s.summary.upi)} · Card {inr(s.summary.card)} · WhatsApp {inr(s.summary.whatsapp)} · Swiggy{' '}
              {inr(s.summary.swiggy)} · Zomato {inr(s.summary.zomato)}
              {Number(s.tenderDifference) !== 0 && <span className="text-amber-700"> · split differs by {inr(s.tenderDifference)}</span>}
            </div>
          </div>
        ))}
      </div>
      <ErrorNote error={decide.error} />
      {approve && (
        <ReasonDialog
          title="Approve this day's figures"
          confirmLabel="Approve"
          required={approve !== 'ACCEPT_ITEMISED'}
          onConfirm={(reason) => decide.mutateAsync({ decision: approve, reason })}
          onClose={() => setApprove(null)}
        />
      )}
      {can('sales.edit') && <CalendarControl date={date!} current={d.calendarType} />}
    </div>
  )
}

function CalendarControl({ date, current }: { date: string; current: string | null }) {
  const qc = useQueryClient()
  const [open, setOpen] = useState(false)
  const [type, setType] = useState(current ?? 'CLOSED')
  const [note, setNote] = useState('')
  const m = useMutation({
    mutationFn: () => post(`/days/${date}/calendar`, { dayType: type, note }),
    onSuccess: () => {
      qc.invalidateQueries()
      setOpen(false)
    },
  })
  return (
    <div className="mt-4 text-sm">
      Calendar: <b>{current ?? 'OPEN (default)'}</b>{' '}
      <button className="text-brand-700 underline" onClick={() => setOpen(true)}>
        change
      </button>
      {open && (
        <Modal title="Mark this day" onClose={() => setOpen(false)}>
          <Field label="Type">
            <select className="input" value={type} onChange={(e) => setType(e.target.value)}>
              <option value="OPEN">Open</option>
              <option value="CLOSED">Closed (no sales expected)</option>
              <option value="HOLIDAY">Holiday</option>
            </select>
          </Field>
          <Field label="Note">
            <input className="input" value={note} onChange={(e) => setNote(e.target.value)} />
          </Field>
          <ErrorNote error={m.error} />
          <button className="btn-primary mt-3" onClick={() => m.mutate()}>
            Save
          </button>
        </Modal>
      )}
    </div>
  )
}
