import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { get, qs } from '../api'
import { dmy, inr, monthStartIso, todayIso } from '../format'
import type { DataHealth, MonthRow, Pnl, ProductRow } from '../types'
import { Chip, DateRange, ErrorNote, Loading, PageTitle, Stat } from '../components/ui'
import { useAuth } from '../auth'

const TABS = [
  { id: 'pnl', label: 'Profit & loss', perm: 'finance.view_profit' },
  { id: 'months', label: 'Monthly review', perm: 'finance.view_profit' },
  { id: 'products', label: 'Products', perm: 'reports.view' },
  { id: 'health', label: 'Data health', perm: 'reports.view' },
]

export default function Reports() {
  const { can } = useAuth()
  const [sp, setSp] = useSearchParams()
  const tabs = TABS.filter((t) => can(t.perm))
  const tab = sp.get('tab') ?? tabs[0]?.id ?? 'health'
  const [from, setFrom] = useState(monthStartIso())
  const [to, setTo] = useState(todayIso())
  return (
    <div>
      <PageTitle title="Reports" />
      <div className="mb-3 flex gap-1 overflow-x-auto">
        {tabs.map((t) => (
          <button
            key={t.id}
            onClick={() => setSp({ tab: t.id })}
            className={`shrink-0 rounded-full px-3 py-1 text-sm ${tab === t.id ? 'bg-brand-700 text-white' : 'bg-white ring-1 ring-slate-300'}`}
          >
            {t.label}
          </button>
        ))}
      </div>
      {tab !== 'months' && (
        <div className="card mb-3">
          <DateRange from={from} to={to} onChange={(f, t) => (setFrom(f), setTo(t))} />
        </div>
      )}
      {tab === 'pnl' && <PnlTab from={from} to={to} />}
      {tab === 'months' && <MonthsTab />}
      {tab === 'products' && <ProductsTab from={from} to={to} />}
      {tab === 'health' && <HealthTab from={from} to={to} />}
    </div>
  )
}

function Row({ label, value, strong, muted }: { label: string; value: string; strong?: boolean; muted?: boolean }) {
  return (
    <div className={`flex justify-between border-b border-slate-100 py-1.5 text-sm ${strong ? 'font-semibold' : ''} ${muted ? 'text-slate-500' : ''}`}>
      <span>{label}</span>
      <span>{value}</span>
    </div>
  )
}

function PnlTab({ from, to }: { from: string; to: string }) {
  const q = useQuery({ queryKey: ['pnl', from, to], queryFn: () => get<Pnl>(`/reports/pnl${qs({ from, to })}`) })
  if (q.isLoading) return <Loading />
  if (q.error) return <ErrorNote error={q.error} />
  const p = q.data!
  const ql = p.quality
  return (
    <div className="space-y-3">
      <div className="card">
        <div className="mb-1 flex items-center gap-2 font-semibold">
          Summary <Chip label="ESTIMATED" />
        </div>
        <Row label="Gross takings (incl. GST)" value={inr(p.grossSalesInclusive)} muted />
        <Row label="GST collected" value={inr(p.gstCollected)} muted />
        <Row label="Net sales (ex-GST)" value={inr(p.netSalesExGst)} strong />
        <Row label="Purchases – stock/ingredients" value={`− ${inr(p.purchasesInventory)}`} />
        <Row label="Purchases – consumables" value={`− ${inr(p.purchasesExpenseTreated)}`} />
        <Row label="Expenses" value={`− ${inr(p.expensesTotal)}`} />
        <Row label="Indicative surplus" value={inr(p.indicativeSurplus)} strong />
        <Row label="Equipment purchases (not in surplus)" value={inr(p.purchasesFixedAssets)} muted />
        <Row label="Cost of goods sold / gross profit" value="Not available yet" muted />
      </div>
      <div className="card">
        <div className="mb-1 font-semibold">Expenses by category</div>
        {p.expensesByCategory.map((c) => (
          <Row key={c.category} label={`${c.category} (${c.count})`} value={inr(c.amount)} />
        ))}
        {p.expensesByCategory.length === 0 && <p className="text-sm text-slate-500">No expenses.</p>}
      </div>
      <div className="card text-sm">
        <div className="mb-1 font-semibold">How solid is this?</div>
        <ul className="list-disc space-y-0.5 pl-5">
          <li>
            {ql.daysWithSales} of {ql.daysInRange} days have sales recorded ({ql.daysMissing} with none and not marked closed).
          </li>
          <li>
            Item-wise only: {ql.daysItemisedOnly} · quick total only: {ql.daysSummaryOnly} · both: {ql.daysBoth} (pending approval {ql.daysPendingApproval}, stale {ql.daysStaleApproval}).
          </li>
          <li>
            {ql.estimatedExpenses} estimated expense(s), {ql.partialRecords} partial record(s), {ql.backdatedRecords} back-dated entr{ql.backdatedRecords === 1 ? 'y' : 'ies'}.
          </li>
        </ul>
        <ul className="mt-2 list-disc space-y-0.5 pl-5 text-xs text-slate-500">
          {p.notes.map((n) => (
            <li key={n}>{n}</li>
          ))}
        </ul>
      </div>
    </div>
  )
}

function MonthsTab() {
  const q = useQuery({ queryKey: ['months'], queryFn: () => get<MonthRow[]>('/reports/months?count=3') })
  if (q.isLoading) return <Loading />
  if (q.error) return <ErrorNote error={q.error} />
  return (
    <div className="card overflow-x-auto">
      <table className="w-full">
        <thead>
          <tr>
            <th className="th">Month</th>
            <th className="th text-right">Net sales</th>
            <th className="th text-right">Purchases</th>
            <th className="th text-right">Expenses</th>
            <th className="th text-right">Surplus</th>
            <th className="th text-right">Days</th>
          </tr>
        </thead>
        <tbody>
          {q.data?.map((m) => (
            <tr key={m.month} className="border-t border-slate-100">
              <td className="td">{m.month}</td>
              <td className="td text-right">{inr(m.netSalesExGst, { decimals: 0 })}</td>
              <td className="td text-right">{inr(m.purchases, { decimals: 0 })}</td>
              <td className="td text-right">{inr(m.expenses, { decimals: 0 })}</td>
              <td className="td text-right font-medium">{inr(m.indicativeSurplus, { decimals: 0 })}</td>
              <td className="td text-right text-xs text-slate-500">
                {m.daysWithSales}/{m.daysInPeriod}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="mt-2 text-xs text-slate-500">
        Indicative only (purchases counted when billed; no stock or recipe costing yet). “Days” = days with sales recorded / days in the period.
      </p>
    </div>
  )
}

function ProductsTab({ from, to }: { from: string; to: string }) {
  const q = useQuery({ queryKey: ['products-report', from, to], queryFn: () => get<ProductRow[]>(`/reports/products${qs({ from, to })}`) })
  if (q.isLoading) return <Loading />
  if (q.error) return <ErrorNote error={q.error} />
  return (
    <div className="card overflow-x-auto">
      <p className="mb-2 text-xs text-slate-500">Based on item-wise bills only. Days recorded as a quick total have no product detail.</p>
      <table className="w-full">
        <thead>
          <tr>
            <th className="th">Product</th>
            <th className="th text-right">Qty</th>
            <th className="th text-right">Sales (incl. GST)</th>
          </tr>
        </thead>
        <tbody>
          {q.data?.map((p) => (
            <tr key={p.productId} className="border-t border-slate-100">
              <td className="td">
                {p.productName} <span className="text-xs text-slate-500">{p.categoryName}</span>
              </td>
              <td className="td text-right">{p.quantity}</td>
              <td className="td text-right">{inr(p.revenueInclusive)}</td>
            </tr>
          ))}
          {q.data?.length === 0 && (
            <tr>
              <td className="td text-slate-500" colSpan={3}>
                No item-wise sales in this range.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}

function HealthTab({ from, to }: { from: string; to: string }) {
  const q = useQuery({ queryKey: ['health', from, to], queryFn: () => get<DataHealth>(`/reports/data-health${qs({ from, to })}`) })
  if (q.isLoading) return <Loading />
  if (q.error) return <ErrorNote error={q.error} />
  const d = q.data!
  const sev: Record<string, string> = { OK: 'bg-emerald-100 text-emerald-800', WARN: 'bg-amber-100 text-amber-800', INFO: 'bg-sky-100 text-sky-800' }
  return (
    <div className="space-y-3">
      <div className="card">
        {d.items.map((i) => (
          <div key={i.code} className="flex items-start justify-between gap-2 border-b border-slate-100 py-2 text-sm last:border-0">
            <span>{i.message}</span>
            <span className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-semibold ${sev[i.severity]}`}>{i.count}</span>
          </div>
        ))}
      </div>
      {[
        ['Days with no sales recorded', d.missingDays, 'MISSING'],
        ['Waiting for approval', d.pendingApprovalDays, 'PENDING_APPROVAL'],
        ['Approval out of date', d.staleApprovalDays, 'STALE'],
      ].map(([title, days, chip]) =>
        (days as string[]).length ? (
          <div key={title as string} className="card">
            <div className="mb-2 flex items-center gap-2 text-sm font-semibold">
              {title as string} <Chip label={chip as string} />
            </div>
            <div className="flex flex-wrap gap-2">
              {(days as string[]).map((x) => (
                <Link key={x} to={`/days/${x}`} className="rounded bg-slate-100 px-2 py-1 text-xs hover:bg-brand-100">
                  {dmy(x)}
                </Link>
              ))}
            </div>
          </div>
        ) : null,
      )}
    </div>
  )
}

export { Stat }
