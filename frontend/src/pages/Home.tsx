import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { get } from '../api'
import { dmy, inr, todayIso } from '../format'
import type { Dashboard } from '../types'
import { ErrorNote, Loading, PageTitle, Stat } from '../components/ui'
import { useAuth } from '../auth'

export default function Home() {
  const { can } = useAuth()
  const q = useQuery({ queryKey: ['dashboard', todayIso()], queryFn: () => get<Dashboard>(`/reports/dashboard?date=${todayIso()}`), refetchInterval: 60000 })
  if (q.isLoading) return <Loading />
  if (q.error) return <ErrorNote error={q.error} />
  const d = q.data!
  return (
    <div>
      <PageTitle title={`Today · ${dmy(d.date)}`} />
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Stat label="Today's sales" value={inr(d.todayGross)} sub={`${d.todayOrders} orders · incl. GST`} />
        <Stat label="Today's expenses" value={inr(d.todayExpenses)} />
        <Stat label="This month (incl. GST)" value={inr(d.monthGross)} sub={`Ex-GST ${inr(d.monthNetExGst)}`} />
        <Stat label="Open orders" value={d.openOrders} />
      </div>

      {(d.pendingApprovals > 0 || d.missingDaysThisMonth > 0) && (
        <div className="card mt-4 space-y-1 border-l-4 border-amber-400 text-sm">
          <div className="font-semibold">Needs attention</div>
          {d.pendingApprovals > 0 && (
            <div>
              {d.pendingApprovals} day(s) have differing itemised vs quick totals waiting for approval.{' '}
              <Link className="text-brand-700 underline" to="/reports?tab=health">
                Review
              </Link>
            </div>
          )}
          {d.missingDaysThisMonth > 0 && (
            <div>
              {d.missingDaysThisMonth} day(s) this month have no sales recorded.{' '}
              <Link className="text-brand-700 underline" to="/reports?tab=health">
                See which
              </Link>
            </div>
          )}
        </div>
      )}

      <div className="card mt-4">
        <div className="mb-2 text-sm font-semibold">Today by payment method</div>
        {d.todayChannels.filter((c) => Number(c.amount) !== 0).length === 0 ? (
          <p className="text-sm text-slate-500">Nothing recorded yet today.</p>
        ) : (
          <ul className="text-sm">
            {d.todayChannels
              .filter((c) => Number(c.amount) !== 0)
              .map((c) => (
                <li key={c.method} className="flex justify-between border-b border-slate-100 py-1 last:border-0">
                  <span>{c.method.replace('_', ' ')}</span>
                  <span>{inr(c.amount)}</span>
                </li>
              ))}
          </ul>
        )}
      </div>

      <div className="mt-4 grid grid-cols-2 gap-3 md:grid-cols-4">
        {can('sales.create') && (
          <Link to="/sales/new" className="btn-primary py-4">
            ➕ New sale
          </Link>
        )}
        {can('sales.create') && (
          <Link to="/sales/daily" className="btn-ghost py-4">
            📒 Daily total
          </Link>
        )}
        {can('orders.manage') && (
          <Link to="/orders" className="btn-ghost py-4">
            🧾 Orders
          </Link>
        )}
        {can('expenses.create') && (
          <Link to="/expenses" className="btn-ghost py-4">
            💸 Expense
          </Link>
        )}
      </div>
    </div>
  )
}
