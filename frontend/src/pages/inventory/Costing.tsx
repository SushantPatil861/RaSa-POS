import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { get, qs } from '../../api'
import { dmy, inr, monthStartIso, todayIso } from '../../format'
import { useAuth } from '../../auth'
import { DateRange, ErrorNote, Loading, PageTitle, Stat } from '../../components/ui'
import type { GrossProfit, ProductMarginReport, RealPnl } from '../../types.phase2'
import { Banner, Tabs, Tag, Tags, pct, qty } from './shared'

export default function Costing() {
  const { can } = useAuth()
  const [from, setFrom] = useState(monthStartIso())
  const [to, setTo] = useState(todayIso())
  const [tab, setTab] = useState('gp')
  const allowed = can('costing.view') && can('finance.view_profit')
  const gp = useQuery({ queryKey: ['gp', from, to], queryFn: () => get<GrossProfit>(`/reports/costing/gross-profit${qs({ from, to })}`), enabled: allowed })
  const products = useQuery({
    queryKey: ['margins', from, to],
    queryFn: () => get<ProductMarginReport>(`/reports/costing/products${qs({ from, to })}`),
    enabled: allowed && tab === 'products',
  })
  const pnl = useQuery({
    queryKey: ['real-pnl', from, to],
    queryFn: () => get<RealPnl>(`/reports/costing/pnl${qs({ from, to })}`),
    enabled: allowed && tab === 'pnl' && can('reports.view'),
  })
  if (!allowed) {
    return (
      <div>
        <PageTitle title="Costing" />
        <p className="text-sm text-slate-500">Cost and margin figures need the “costing” and “profit” permissions.</p>
      </div>
    )
  }
  const g = gp.data
  return (
    <div>
      <PageTitle title="Costing & gross profit" />
      <div className="card mb-3">
        <DateRange from={from} to={to} onChange={(f, t) => (setFrom(f), setTo(t))} />
      </div>
      {gp.isLoading && <Loading />}
      <ErrorNote error={gp.error} />
      {g && <CoverageBanner g={g} />}
      <Tabs
        tabs={[
          { id: 'gp', label: 'Gross profit' },
          { id: 'products', label: 'Products' },
          ...(can('reports.view') ? [{ id: 'pnl', label: 'Real P&L' }] : []),
        ]}
        value={tab}
        onChange={setTab}
      />
      {tab === 'gp' && g && (
        <div className="space-y-3">
          <div className="grid grid-cols-2 gap-2">
            <Stat label="Net sales (itemised, ex-GST)" value={inr(g.itemisedNetSales)} />
            <Stat label="Cost of goods (known)" value={inr(g.cogsKnown)} sub={g.flags.includes('ESTIMATED') ? 'includes estimated costs' : undefined} />
            <Stat label="Gross profit" value={inr(g.grossProfit)} sub={g.flags.includes('PARTIAL') ? 'estimated: cost data incomplete' : undefined} />
            <Stat label="Margin" value={pct(g.marginPct)} />
          </div>
          <div className="card text-sm">
            <div className="label">Only fully costed products</div>
            <div className="flex justify-between">
              <span>Net sales {inr(g.completeLinesNetSales)} − cost {inr(g.completeLinesCogs)}</span>
              <b>{inr(g.grossProfitCompleteLines)}</b>
            </div>
          </div>
          {g.missingIngredients.length > 0 && (
            <Banner tone="warn">
              Missing ingredient costs: {g.missingIngredients.join(', ')}. Add a cost (or link a purchase) to include them.
            </Banner>
          )}
          {g.notes.map((n) => (
            <p key={n} className="text-xs text-slate-500">
              {n}
            </p>
          ))}
        </div>
      )}
      {tab === 'products' && (
        <div>
          {products.isLoading && <Loading />}
          <ErrorNote error={products.error} />
          <div className="space-y-2">
            {products.data?.rows.map((r) => (
              <div key={r.variantId} className="card p-3">
                <div className="flex items-start justify-between gap-2">
                  <div>
                    <div className="font-medium">
                      {r.productName} <span className="text-xs text-slate-500">{r.variantName}</span>
                    </div>
                    <div className="text-xs text-slate-500">
                      sold {qty(r.quantity, undefined, 0)} · net sales {inr(r.netSales)}
                    </div>
                  </div>
                  <Tags labels={[r.status, r.estimated ? 'ESTIMATED' : null]} />
                </div>
                <div className="mt-1 grid grid-cols-3 gap-2 text-sm">
                  <div>
                    <div className="text-xs text-slate-500">Cost</div>
                    {r.cogs === null ? <Tag label="MISSING" /> : inr(r.cogs)}
                  </div>
                  <div>
                    <div className="text-xs text-slate-500">Gross profit</div>
                    {r.grossProfit === null ? '—' : inr(r.grossProfit)}
                  </div>
                  <div>
                    <div className="text-xs text-slate-500">Margin</div>
                    {pct(r.marginPct)}
                  </div>
                </div>
                {r.missingIngredients.length > 0 && <div className="mt-1 text-xs text-red-700">No cost for: {r.missingIngredients.join(', ')}</div>}
              </div>
            ))}
            {products.data?.rows.length === 0 && <p className="text-sm text-slate-500">No itemised sales in this range.</p>}
          </div>
        </div>
      )}
      {tab === 'pnl' && (
        <div className="space-y-3">
          {pnl.isLoading && <Loading />}
          <ErrorNote error={pnl.error} />
          {pnl.data && (
            <>
              <div className="card text-sm">
                <div className="label">Existing P&L (all days)</div>
                <Line k="Gross sales (incl. GST)" v={inr(pnl.data.pnl.grossSalesInclusive)} />
                <Line k="Net sales ex-GST" v={inr(pnl.data.pnl.netSalesExGst)} />
                <Line k="GST collected" v={inr(pnl.data.pnl.gstCollected)} />
                <Line k="Purchases (inventory)" v={inr(pnl.data.pnl.purchasesInventory)} />
                <Line k="Purchases treated as expense" v={inr(pnl.data.pnl.purchasesExpenseTreated)} />
                <Line k="Expenses" v={inr(pnl.data.pnl.expensesTotal)} />
                <Line k="Indicative surplus (purchases basis)" v={inr(pnl.data.pnl.indicativeSurplus)} />
              </div>
              <div className="card text-sm">
                <div className="label">Costing (itemised days only)</div>
                <Line k="Net sales covered" v={inr(pnl.data.costing.itemisedNetSales)} />
                <Line k="Cost of goods sold (known)" v={inr(pnl.data.costing.cogsKnown)} />
                <Line k="Gross profit" v={inr(pnl.data.costing.grossProfit)} />
                <Line k="Wastage cost (not deducted)" v={inr(pnl.data.wastageCost)} />
                <Line k="Net operating profit" v={pnl.data.netOperatingProfit === null ? 'Not available' : inr(pnl.data.netOperatingProfit)} />
                <div className="mt-2">
                  <Tags labels={pnl.data.flags} />
                </div>
              </div>
              {pnl.data.notes.map((n) => (
                <p key={n} className="text-xs text-slate-500">
                  {n}
                </p>
              ))}
            </>
          )}
        </div>
      )}
    </div>
  )
}

function Line({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex justify-between border-t border-slate-100 py-1">
      <span>{k}</span>
      <span className="font-medium">{v}</span>
    </div>
  )
}

function CoverageBanner({ g }: { g: GrossProfit }) {
  const c = g.coverage
  const full = c.coveragePct !== null && Number(c.coveragePct) >= 100 && c.summaryOnlyDays === 0
  const tone = c.itemisedNetSales === '0.00' ? 'bad' : full ? 'info' : 'warn'
  return (
    <Banner tone={tone}>
      <div className="mb-1 flex flex-wrap items-center gap-2 font-semibold">
        Cost coverage: {c.coveragePct === null ? 'no itemised sales' : `${c.coveragePct}% of itemised sales`}
        <Tags labels={g.flags} />
      </div>
      <div>
        Fully costed {inr(c.completeNetSales)} · partly costed {inr(c.partialNetSales)} · no recipe {inr(c.noRecipeNetSales)} (net sales ex-GST).
      </div>
      {c.summaryOnlyDays > 0 && (
        <div className="mt-1">
          {c.summaryOnlyDays} day(s) have only a quick daily total ({inr(c.summaryOnlyNetSales)} net): they contribute <b>no cost of goods</b> and are not in gross profit
          {c.summaryOnlyDates.length <= 6 ? ` (${c.summaryOnlyDates.map(dmy).join(', ')})` : ''}.
        </div>
      )}
      {!full && <div className="mt-1">Gross profit is estimated because cost data is incomplete.</div>}
    </Banner>
  )
}
