import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { download, get, post, put, qs } from '../../api'
import { dmy, inr, todayIso, toNumber } from '../../format'
import { ErrorNote, Field, Loading, Modal, PageTitle, ReasonDialog } from '../../components/ui'
import { useAuth } from '../../auth'
import type {
  GstComputed, GstFiling, GstFigures, GstQuarter, GstWarning, GstWorkpaper, GstWorkpaperRow, ItcPurchase, RateLine,
} from '../../types.phase4'
import { Banner, Empty, Notice, Pill, Row, TabBar, saveBlob, useTab, fyStartYear } from './shared'

export default function Gst() {
  const { can } = useAuth()
  const [tab, setTab] = useTab(['workpaper', 'itc', 'filings'])
  const [fy, setFy] = useState(fyStartYear(todayIso()))
  const [qStart, setQStart] = useState<string | null>(null)
  const quarters = useQuery({ queryKey: ['gst-quarters', fy], queryFn: () => get<GstQuarter[]>(`/gst/quarters${qs({ fy })}`) })
  const qlist = quarters.data ?? []
  const today = todayIso()
  const current = qlist.find((q) => q.start === qStart) ?? qlist.find((q) => q.start <= today && today <= q.end) ?? qlist[0]

  return (
    <div>
      <PageTitle title="GST workpapers" />
      <Banner tone="sky">
        These are <b>workpapers for your CA</b> (QRMP: quarterly return, monthly payment). Nothing is filed from here. Figures are estimates built
        from your recorded sales and the purchase tax details entered below - please confirm with your CA before filing.
      </Banner>

      <div className="card mb-3">
        <div className="mb-2 flex items-center justify-between">
          <button className="btn-ghost" onClick={() => (setFy(fy - 1), setQStart(null))} aria-label="Previous financial year">
            ‹
          </button>
          <div className="text-sm font-semibold">
            FY {fy}-{String((fy + 1) % 100).padStart(2, '0')}
          </div>
          <button className="btn-ghost" onClick={() => (setFy(fy + 1), setQStart(null))} aria-label="Next financial year">
            ›
          </button>
        </div>
        {quarters.isLoading && <Loading />}
        <ErrorNote error={quarters.error} />
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {qlist.map((q) => (
            <button
              key={q.start}
              onClick={() => setQStart(q.start)}
              className={`rounded-lg p-2 text-left text-sm ring-1 ${current?.start === q.start ? 'bg-brand-50 ring-brand-500' : 'bg-white ring-slate-200'}`}
            >
              <div className="font-semibold">Q{q.quarter}</div>
              <div className="text-xs text-slate-500">{q.label.replace(/^Q\d FY\d\d-\d\d /, '')}</div>
              <div className="mt-1">
                {q.latestStatus ? (
                  <Pill tone={q.latestStatus === 'DRAFT' ? 'amber' : q.latestStatus === 'REVIEWED' ? 'sky' : 'green'}>
                    {q.latestStatus.replace('_', ' ')} v{q.latestVersion}
                  </Pill>
                ) : (
                  <Pill>No workpaper</Pill>
                )}
              </div>
            </button>
          ))}
        </div>
      </div>

      <TabBar
        tabs={[
          { id: 'workpaper', label: 'Workpaper' },
          { id: 'itc', label: 'ITC entry' },
          { id: 'filings', label: 'Filings log' },
        ]}
        value={tab}
        onChange={setTab}
      />
      {current && tab === 'workpaper' && <WorkpaperTab key={current.start} quarter={current} canPrepare={can('gst.prepare')} />}
      {current && tab === 'itc' && <ItcTab key={current.start} quarter={current} canPrepare={can('gst.prepare')} />}
      {current && tab === 'filings' && <FilingsTab key={current.start} quarter={current} canPrepare={can('gst.prepare')} />}
    </div>
  )
}

// ------------------------------------------------------------------ workpaper tab

function WorkpaperTab({ quarter, canPrepare }: { quarter: GstQuarter; canPrepare: boolean }) {
  const qc = useQueryClient()
  const versions = useQuery({
    queryKey: ['gst-versions', quarter.start],
    queryFn: () => get<GstWorkpaperRow[]>(`/gst/workpapers${qs({ periodType: 'QUARTER', periodStart: quarter.start })}`),
  })
  const [picked, setPicked] = useState<number | null>(null)
  const list = versions.data ?? []
  const selectedId = picked ?? list[0]?.id ?? null
  const [recompute, setRecompute] = useState(false)
  const [review, setReview] = useState<'review' | 'filed' | null>(null)
  const [busyCsv, setBusyCsv] = useState(false)
  const [csvErr, setCsvErr] = useState<unknown>(null)

  const saved = useQuery({
    queryKey: ['gst-workpaper', selectedId],
    queryFn: () => get<GstWorkpaper>(`/gst/workpapers/${selectedId}`),
    enabled: selectedId !== null,
  })
  const preview = useQuery({
    queryKey: ['gst-preview', quarter.start],
    queryFn: () => get<GstComputed>(`/gst/preview${qs({ periodType: 'QUARTER', periodStart: quarter.start })}`),
    enabled: versions.isSuccess && list.length === 0,
  })

  const exportCsv = async () => {
    if (selectedId === null) return
    setBusyCsv(true)
    setCsvErr(null)
    try {
      saveBlob(await download(`/gst/workpapers/${selectedId}/export.csv`), `gst-workpaper-${quarter.start}-v${saved.data?.version ?? ''}.csv`)
    } catch (e) {
      setCsvErr(e)
    } finally {
      setBusyCsv(false)
    }
  }

  if (versions.isLoading) return <Loading />
  const wp = saved.data
  return (
    <div>
      <ErrorNote error={versions.error} />
      {list.length > 0 && (
        <div className="mb-3 flex flex-wrap items-center gap-2">
          {list.map((v) => (
            <button
              key={v.id}
              onClick={() => setPicked(v.id)}
              className={`rounded-full px-3 py-1 text-xs ring-1 ${selectedId === v.id ? 'bg-brand-700 text-white ring-brand-700' : 'bg-white ring-slate-300'}`}
            >
              v{v.version} · {v.status.replace('_', ' ')} · {dmy(v.preparedAt)}
            </button>
          ))}
        </div>
      )}

      {list.length === 0 && (
        <>
          <Banner tone="amber">
            <b>PREVIEW - not saved.</b> No workpaper has been saved for {quarter.label} yet. Saving creates a DRAFT snapshot that your CA can review.
          </Banner>
          {canPrepare && (
            <button className="btn-primary mb-3" onClick={() => setRecompute(true)}>
              Save as workpaper (DRAFT)
            </button>
          )}
          {preview.isLoading && <Loading />}
          <ErrorNote error={preview.error} />
          {preview.data && <WorkpaperView figures={preview.data.figures} warnings={preview.data.warnings} />}
        </>
      )}

      {list.length > 0 && (
        <>
          {saved.isLoading && <Loading />}
          <ErrorNote error={saved.error} />
          {wp && (
            <>
              <div className="card mb-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <div className="font-semibold">
                      {wp.figures.label} · version {wp.version}{' '}
                      <Pill tone={wp.status === 'DRAFT' ? 'amber' : wp.status === 'REVIEWED' ? 'sky' : 'green'}>{wp.status.replace('_', ' ')}</Pill>
                      {!wp.latest && <> <Pill tone="violet">OLDER VERSION</Pill></>}
                    </div>
                    <div className="text-xs text-slate-500">
                      Prepared {dmy(wp.preparedAt)} by {wp.preparedByName ?? '—'}
                      {wp.reviewedAt && ` · reviewed ${dmy(wp.reviewedAt)} by ${wp.reviewedByName ?? '—'}`}
                    </div>
                    {wp.notes && <div className="mt-1 text-sm">“{wp.notes}”</div>}
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <button className="btn-ghost" disabled={busyCsv} onClick={exportCsv}>
                      ⬇ CSV for CA
                    </button>
                    {canPrepare && (
                      <button className="btn-ghost" onClick={() => setRecompute(true)}>
                        Recompute (new version)
                      </button>
                    )}
                    {canPrepare && wp.latest && wp.status === 'DRAFT' && (
                      <button className="btn-primary" onClick={() => setReview('review')}>
                        Mark reviewed
                      </button>
                    )}
                    {canPrepare && wp.status === 'REVIEWED' && (
                      <button className="btn-primary" onClick={() => setReview('filed')}>
                        Mark as filed
                      </button>
                    )}
                  </div>
                </div>
                <ErrorNote error={csvErr} />
                <Notice>
                  A saved workpaper is a snapshot: it never changes. Recomputing creates a new version and never overwrites a reviewed one.
                  “Mark as filed” only records that your CA filed it - nothing is sent anywhere from this system.
                </Notice>
              </div>
              <WorkpaperView figures={wp.figures} warnings={wp.warnings} />
            </>
          )}
        </>
      )}

      {recompute && (
        <RecomputeModal
          quarter={quarter}
          first={list.length === 0}
          onClose={() => setRecompute(false)}
          onSaved={(id) => {
            setPicked(id)
            qc.invalidateQueries()
          }}
        />
      )}
      {review && selectedId !== null && (
        <ReasonDialog
          title={review === 'review' ? 'Mark this workpaper as reviewed?' : 'Record that this return was filed?'}
          confirmLabel={review === 'review' ? 'Mark reviewed' : 'Mark as filed'}
          required={false}
          onConfirm={async (notes) => {
            await post(`/gst/workpapers/${selectedId}/${review === 'review' ? 'review' : 'mark-filed'}`, { notes: notes || null })
            qc.invalidateQueries()
          }}
          onClose={() => setReview(null)}
        />
      )}
    </div>
  )
}

function RecomputeModal({ quarter, first, onClose, onSaved }: { quarter: GstQuarter; first: boolean; onClose: () => void; onSaved: (id: number) => void }) {
  const [ig, setIg] = useState('')
  const [cg, setCg] = useState('')
  const [sg, setSg] = useState('')
  const [notes, setNotes] = useState('')
  const save = useMutation({
    mutationFn: () =>
      post<GstWorkpaper>('/gst/workpapers', {
        periodType: 'QUARTER',
        periodStart: quarter.start,
        openingCreditIgst: ig || null,
        openingCreditCgst: cg || null,
        openingCreditSgst: sg || null,
        notes: notes || null,
      }),
    onSuccess: (w) => {
      onSaved(w.id)
      onClose()
    },
  })
  return (
    <Modal title={first ? 'Save workpaper' : 'Recompute workpaper'} onClose={onClose}>
      <p className="mb-3 text-sm text-slate-600">
        This takes a fresh snapshot of {quarter.label} from today's sales and purchase tax details and saves it as a new DRAFT version. Earlier versions stay as they are.
      </p>
      <p className="label">Opening ITC credit balance carried in (optional, from your CA)</p>
      <div className="grid grid-cols-3 gap-2">
        <Field label="IGST ₹">
          <input className="input" inputMode="decimal" value={ig} onChange={(e) => setIg(e.target.value)} />
        </Field>
        <Field label="CGST ₹">
          <input className="input" inputMode="decimal" value={cg} onChange={(e) => setCg(e.target.value)} />
        </Field>
        <Field label="SGST ₹">
          <input className="input" inputMode="decimal" value={sg} onChange={(e) => setSg(e.target.value)} />
        </Field>
      </div>
      <div className="mt-3">
        <Field label="Note (optional)">
          <input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} />
        </Field>
      </div>
      <ErrorNote error={save.error} />
      <div className="mt-4 flex justify-end gap-2">
        <button className="btn-ghost" onClick={onClose}>
          Cancel
        </button>
        <button className="btn-primary" disabled={save.isPending} onClick={() => save.mutate()}>
          {save.isPending ? 'Saving…' : 'Save new version'}
        </button>
      </div>
    </Modal>
  )
}

// ------------------------------------------------------------------ the workpaper itself

const SEVERITY_TONE: Record<string, 'red' | 'amber' | 'sky'> = { ERROR: 'red', WARN: 'amber', INFO: 'sky' }

function WorkpaperView({ figures: f, warnings }: { figures: GstFigures; warnings: GstWarning[] }) {
  const [open, setOpen] = useState<string | null>(null)
  const serious = warnings.filter((w) => w.severity !== 'INFO')
  const t61 = f.gstr3b.table61
  return (
    <div className="space-y-3">
      {warnings.length > 0 && (
        <div className={`rounded-lg p-3 text-sm ring-1 ${serious.length ? 'bg-amber-50 ring-amber-200' : 'bg-sky-50 ring-sky-200'}`}>
          <div className="mb-1 font-semibold">
            Data-quality warnings ({warnings.length}) - nothing below was silently dropped
          </div>
          <ul className="space-y-2">
            {warnings.map((w) => (
              <li key={w.code}>
                <div className="flex items-start gap-2">
                  <Pill tone={SEVERITY_TONE[w.severity] ?? 'slate'}>{w.severity}</Pill>
                  <span>{w.message}</span>
                </div>
                {w.dates.length > 0 && (
                  <button className="ml-1 text-xs text-brand-700 underline" onClick={() => setOpen(open === w.code ? null : w.code)}>
                    {open === w.code ? 'Hide' : 'Show'} {w.dates.length} item(s)
                  </button>
                )}
                {open === w.code && (
                  <div className="mt-1 flex flex-wrap gap-1 text-xs">
                    {w.dates.map((d) => (
                      <span key={d} className="rounded bg-white px-1.5 py-0.5 ring-1 ring-slate-200">
                        {/^\d{4}-\d{2}-\d{2}$/.test(d) ? dmy(d) : d}
                      </span>
                    ))}
                  </div>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
      {warnings.length === 0 && <Banner tone="green">No data-quality warnings for this period.</Banner>}

      <div className="card">
        <h2 className="mb-2 font-semibold">Month by month (QRMP)</h2>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[34rem] text-sm">
            <thead>
              <tr>
                <th className="th">Month</th>
                <th className="th text-right">Taxable</th>
                <th className="th text-right">Output tax</th>
                <th className="th text-right">Eligible ITC</th>
                <th className="th text-right">Payable est.</th>
                <th className="th text-right">PMT-06 est.</th>
              </tr>
            </thead>
            <tbody>
              {f.months.map((m) => (
                <tr key={m.start} className="border-t border-slate-100">
                  <td className="td">{m.label}</td>
                  <td className="td text-right">{inr(m.outward.taxable)}</td>
                  <td className="td text-right">{inr(m.outward.totalTax)}</td>
                  <td className="td text-right">{inr(m.itc.eligible.totalTax)}</td>
                  <td className="td text-right">{inr(m.settlement.payableTotal)}</td>
                  <td className="td text-right">{m.pmt06Estimate === null ? <span className="text-xs text-slate-500">in GSTR-3B</span> : inr(m.pmt06Estimate)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <Notice>
          First two months of a quarter: monthly payment (PMT-06) estimate = output tax less eligible ITC, never below zero, with unused credit carried to the next
          month. Your CA may prefer the fixed-sum (35%) method instead. The third month is settled in GSTR-3B.
        </Notice>
      </div>

      <div className="card">
        <h2 className="mb-1 font-semibold">GSTR-1 style: B2C supplies by rate</h2>
        <div className="mb-2 text-xs text-slate-500">Place of supply: {f.gstr1.placeOfSupply}</div>
        <RateTable lines={f.gstr1.lines} totals={f.gstr1.totals} />
        <Notice>{f.gstr1.note}</Notice>
      </div>

      <div className="card">
        <h2 className="mb-2 font-semibold">GSTR-3B style</h2>
        <h3 className="label">3.1(a) Outward taxable supplies</h3>
        <Row label="Taxable value" value={inr(f.gstr3b.table31a.taxableValue)} />
        <Row label="IGST" value={inr(f.gstr3b.table31a.igst)} muted />
        <Row label="CGST" value={inr(f.gstr3b.table31a.cgst)} />
        <Row label="SGST / UTGST" value={inr(f.gstr3b.table31a.sgst)} />
        <Row label="Cess" value={inr(f.gstr3b.table31a.cess)} muted />
        <p className="mt-1 text-xs text-slate-500">3.1(b)-(e): {f.gstr3b.table31bToE}</p>

        <h3 className="label mt-4">4. Input tax credit</h3>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[28rem] text-sm">
            <thead>
              <tr>
                <th className="th">Group</th>
                <th className="th text-right">Bills</th>
                <th className="th text-right">IGST</th>
                <th className="th text-right">CGST</th>
                <th className="th text-right">SGST</th>
              </tr>
            </thead>
            <tbody>
              {(
                [
                  ['Eligible (counted)', f.gstr3b.table4.eligible],
                  ['Undecided - ask CA (NOT counted)', f.gstr3b.table4.undecided],
                  ['Not eligible (NOT counted)', f.gstr3b.table4.ineligible],
                ] as const
              ).map(([label, t]) => (
                <tr key={label} className="border-t border-slate-100">
                  <td className="td">{label}</td>
                  <td className="td text-right">{t.count}</td>
                  <td className="td text-right">{inr(t.igst)}</td>
                  <td className="td text-right">{inr(t.cgst)}</td>
                  <td className="td text-right">{inr(t.sgst)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <h3 className="label mt-4">6.1 Payment of tax (estimate)</h3>
        <Row label="Output tax (CGST + SGST)" value={inr(toNumber(t61.outputCgst) + toNumber(t61.outputSgst))} />
        <Row label="Less: eligible ITC set off" value={inr(t61.itcUsed)} />
        <Row label="Cash payable - CGST" value={inr(t61.payableCgst)} />
        <Row label="Cash payable - SGST" value={inr(t61.payableSgst)} />
        <Row label="Estimated tax payable" value={inr(t61.payableTotal)} strong />
        <Row label="Credit carried forward" value={inr(t61.carryForwardTotal)} muted />
        <Row label={`PMT-06 payments logged (${f.gstr3b.pmt06FilingsLogged})`} value={inr(f.gstr3b.pmt06PaidLogged)} muted />
        <Row label="Balance after PMT-06 payments" value={inr(f.gstr3b.balanceAfterPmt06)} strong />
        <Notice>{f.gstr3b.note}</Notice>
      </div>

      <div className="card">
        <h2 className="mb-2 font-semibold">Purchases with ITC details ({f.itcLines.length})</h2>
        {f.itcLines.length === 0 && <Empty>No purchase tax details entered for this period yet.</Empty>}
        <div className="space-y-2">
          {f.itcLines.map((l) => (
            <div key={l.purchaseId} className="flex flex-wrap justify-between gap-2 border-b border-slate-100 pb-2 text-sm">
              <div>
                <div className="font-medium">
                  {l.purchaseNo} · {l.supplierName ?? 'Supplier not recorded'}
                </div>
                <div className="text-xs text-slate-500">
                  {dmy(l.itcDate)} · {l.supplierGstin ?? 'GSTIN missing'}
                </div>
              </div>
              <div className="text-right">
                <div>
                  {inr(l.totalTax)} <span className="text-xs text-slate-500">on {inr(l.taxableValue)}</span>
                </div>
                {l.itcEligible === null ? <Pill tone="amber">UNDECIDED - not counted</Pill> : l.itcEligible ? <Pill tone="green">ELIGIBLE</Pill> : <Pill>NOT ELIGIBLE</Pill>}
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="card">
        <h2 className="mb-1 font-semibold">Assumptions and notes</h2>
        <ul className="list-disc space-y-1 pl-5 text-sm text-slate-700">
          {f.notes.map((n, i) => (
            <li key={i}>{n}</li>
          ))}
        </ul>
      </div>
    </div>
  )
}

function RateTable({ lines, totals }: { lines: RateLine[]; totals: { taxable: string; cgst: string; sgst: string; totalTax: string; days: number } }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[28rem] text-sm">
        <thead>
          <tr>
            <th className="th">Rate</th>
            <th className="th text-right">Taxable value</th>
            <th className="th text-right">CGST</th>
            <th className="th text-right">SGST</th>
            <th className="th text-right">Total tax</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((l, i) => (
            <tr key={i} className="border-t border-slate-100">
              <td className="td">{l.rate === null ? <Pill tone="red">rate not known</Pill> : `${toNumber(l.rate)}%`}</td>
              <td className="td text-right">{inr(l.taxable)}</td>
              <td className="td text-right">{inr(l.cgst)}</td>
              <td className="td text-right">{inr(l.sgst)}</td>
              <td className="td text-right">{inr(l.totalTax)}</td>
            </tr>
          ))}
          {lines.length === 0 && (
            <tr>
              <td className="td text-slate-500" colSpan={5}>
                No sales in this period.
              </td>
            </tr>
          )}
          <tr className="border-t-2 border-slate-200 font-semibold">
            <td className="td">Total</td>
            <td className="td text-right">{inr(totals.taxable)}</td>
            <td className="td text-right">{inr(totals.cgst)}</td>
            <td className="td text-right">{inr(totals.sgst)}</td>
            <td className="td text-right">{inr(totals.totalTax)}</td>
          </tr>
        </tbody>
      </table>
    </div>
  )
}

// ------------------------------------------------------------------ ITC entry against purchases

const FILTERS = [
  { id: 'ALL', label: 'All' },
  { id: 'MISSING', label: 'No tax details' },
  { id: 'UNDECIDED', label: 'ITC undecided' },
  { id: 'ELIGIBLE', label: 'Eligible' },
]

function ItcTab({ quarter, canPrepare }: { quarter: GstQuarter; canPrepare: boolean }) {
  const [filter, setFilter] = useState('ALL')
  const [openId, setOpenId] = useState<number | null>(null)
  const list = useQuery({
    queryKey: ['gst-itc', quarter.start, filter],
    queryFn: () => get<ItcPurchase[]>(`/gst/itc/purchases${qs({ from: quarter.start, to: quarter.end, filter })}`),
  })
  return (
    <div>
      <Notice>
        Enter the GST shown on each supplier bill. ITC is counted in the workpaper <b>only</b> when you mark it eligible; leave it “undecided” if you are not
        sure and ask your CA. Purchases themselves are never changed here.
      </Notice>
      <div className="mb-3 flex gap-1 overflow-x-auto">
        {FILTERS.map((f) => (
          <button
            key={f.id}
            onClick={() => setFilter(f.id)}
            className={`shrink-0 rounded-full px-3 py-1 text-sm ${filter === f.id ? 'bg-brand-700 text-white' : 'bg-white ring-1 ring-slate-300'}`}
          >
            {f.label}
          </button>
        ))}
      </div>
      {list.isLoading && <Loading />}
      <ErrorNote error={list.error} />
      <div className="space-y-2">
        {list.data?.map((p) => (
          <div key={p.purchaseId} className="card p-3">
            <div className="flex flex-wrap items-start justify-between gap-2">
              <div>
                <div className="font-medium">
                  {p.purchaseNo} · {p.supplierName ?? 'Supplier not recorded'}
                </div>
                <div className="text-xs text-slate-500">
                  {dmy(p.invoiceDate ?? p.txnDate)} · bill total {inr(p.totalAmount)}
                  {p.invoiceNo ? ` · inv ${p.invoiceNo}` : ''}
                </div>
              </div>
              <div className="text-right text-sm">
                {p.detailId === null ? (
                  <Pill tone="amber">NO TAX DETAILS</Pill>
                ) : (
                  <>
                    <div>{inr(p.totalTax)} tax</div>
                    {p.itcEligible === null ? <Pill tone="amber">ITC UNDECIDED</Pill> : p.itcEligible ? <Pill tone="green">ELIGIBLE</Pill> : <Pill>NOT ELIGIBLE</Pill>}
                  </>
                )}
              </div>
            </div>
            {canPrepare && (
              <button className="mt-2 text-xs text-brand-700 underline" onClick={() => setOpenId(openId === p.purchaseId ? null : p.purchaseId)}>
                {openId === p.purchaseId ? 'Close' : p.detailId === null ? 'Enter GST details' : 'Edit GST details'}
              </button>
            )}
            {openId === p.purchaseId && <ItcEditor purchase={p} onDone={() => setOpenId(null)} />}
          </div>
        ))}
        {list.data?.length === 0 && <Empty>No purchases match in this quarter.</Empty>}
      </div>
    </div>
  )
}

function ItcEditor({ purchase: p, onDone }: { purchase: ItcPurchase; onDone: () => void }) {
  const qc = useQueryClient()
  const [taxable, setTaxable] = useState(p.taxableValue ?? '')
  const [cgst, setCgst] = useState(p.cgst ?? '')
  const [sgst, setSgst] = useState(p.sgst ?? '')
  const [igst, setIgst] = useState(p.igst ?? '')
  const [gstin, setGstin] = useState(p.supplierGstin ?? p.supplierMasterGstin ?? '')
  const [elig, setElig] = useState(p.itcEligible === null ? 'UNDECIDED' : p.itcEligible ? 'YES' : 'NO')
  const [notes, setNotes] = useState(p.notes ?? '')
  const [reason, setReason] = useState('')
  const isEdit = p.detailId !== null
  const save = useMutation({
    mutationFn: () =>
      put(`/gst/itc/purchases/${p.purchaseId}`, {
        taxableValue: taxable || null,
        cgst: cgst || null,
        sgst: sgst || null,
        igst: igst || null,
        supplierGstin: gstin || null,
        itcEligible: elig === 'UNDECIDED' ? null : elig === 'YES',
        notes: notes || null,
        reason: isEdit ? reason : null,
        version: p.version,
      }),
    onSuccess: () => {
      qc.invalidateQueries()
      onDone()
    },
  })
  const total = toNumber(cgst) + toNumber(sgst) + toNumber(igst)
  return (
    <div className="mt-3 space-y-3 rounded-lg bg-slate-50 p-3">
      <div className="grid grid-cols-2 gap-2">
        <Field label="Taxable value ₹">
          <input className="input" inputMode="decimal" value={taxable} onChange={(e) => setTaxable(e.target.value)} />
        </Field>
        <Field label="Supplier GSTIN">
          <input className="input uppercase" value={gstin} onChange={(e) => setGstin(e.target.value)} maxLength={15} />
        </Field>
        <Field label="CGST ₹">
          <input className="input" inputMode="decimal" value={cgst} onChange={(e) => setCgst(e.target.value)} />
        </Field>
        <Field label="SGST ₹">
          <input className="input" inputMode="decimal" value={sgst} onChange={(e) => setSgst(e.target.value)} />
        </Field>
        <Field label="IGST ₹">
          <input className="input" inputMode="decimal" value={igst} onChange={(e) => setIgst(e.target.value)} />
        </Field>
        <div className="flex items-end pb-2 text-sm">Total tax: {inr(total)}</div>
      </div>
      <Field label="Can this GST be claimed as ITC?">
        <select className="input" value={elig} onChange={(e) => setElig(e.target.value)}>
          <option value="UNDECIDED">Undecided - ask the CA (not counted)</option>
          <option value="YES">Eligible (counted)</option>
          <option value="NO">Not eligible</option>
        </select>
      </Field>
      <Field label="Notes">
        <input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} />
      </Field>
      {isEdit && (
        <Field label="Reason for change (required)">
          <input className="input" value={reason} onChange={(e) => setReason(e.target.value)} />
        </Field>
      )}
      <ErrorNote error={save.error} />
      <button className="btn-primary w-full" disabled={!taxable || save.isPending || (isEdit && !reason.trim())} onClick={() => save.mutate()}>
        Save GST details
      </button>
    </div>
  )
}

// ------------------------------------------------------------------ filings log (manual record)

function FilingsTab({ quarter, canPrepare }: { quarter: GstQuarter; canPrepare: boolean }) {
  const qc = useQueryClient()
  const [adding, setAdding] = useState(false)
  const [voiding, setVoiding] = useState<GstFiling | null>(null)
  const list = useQuery({
    queryKey: ['gst-filings', quarter.start],
    queryFn: () => get<GstFiling[]>(`/gst/filings${qs({ from: quarter.start, to: quarter.end })}`),
  })
  return (
    <div>
      <Notice>
        This is a manual log of what you or your CA filed or paid on the GST portal. The system does not file or pay anything.
      </Notice>
      {canPrepare && (
        <button className="btn-primary mb-3" onClick={() => setAdding(true)}>
          ➕ Log a filing / payment
        </button>
      )}
      {list.isLoading && <Loading />}
      <ErrorNote error={list.error} />
      <div className="space-y-2">
        {list.data?.map((f) => (
          <div key={f.id} className="card p-3">
            <div className="flex justify-between gap-2">
              <div className="font-medium">
                {f.returnType} <span className="text-xs font-normal text-slate-500">{dmy(f.periodStart)} to {dmy(f.periodEnd)}</span>
              </div>
              <div className="text-sm">{f.taxPaid !== null ? inr(f.taxPaid) : 'amount not recorded'}</div>
            </div>
            <div className="text-xs text-slate-500">
              Filed / paid {dmy(f.filedOn)} · ARN {f.arn ?? 'not recorded'} · logged by {f.createdByName ?? '—'}
            </div>
            {f.notes && <div className="text-sm">{f.notes}</div>}
            {f.voidState === 'VOIDED' ? (
              <div className="mt-1 text-xs text-red-700">Voided: {f.voidReason}</div>
            ) : (
              canPrepare && (
                <button className="mt-1 text-xs text-red-600 underline" onClick={() => setVoiding(f)}>
                  Void
                </button>
              )
            )}
          </div>
        ))}
        {list.data?.length === 0 && <Empty>No filings logged for this quarter.</Empty>}
      </div>
      {adding && <FilingForm quarter={quarter} onClose={() => setAdding(false)} />}
      {voiding && (
        <ReasonDialog
          title="Void this filing record?"
          confirmLabel="Void"
          danger
          onConfirm={async (reason) => {
            await post(`/gst/filings/${voiding.id}/void`, { reason })
            qc.invalidateQueries()
          }}
          onClose={() => setVoiding(null)}
        />
      )}
    </div>
  )
}

function FilingForm({ quarter, onClose }: { quarter: GstQuarter; onClose: () => void }) {
  const qc = useQueryClient()
  const [type, setType] = useState('GSTR1')
  const [ps, setPs] = useState(quarter.start)
  const [pe, setPe] = useState(quarter.end)
  const [on, setOn] = useState(todayIso())
  const [arn, setArn] = useState('')
  const [paid, setPaid] = useState('')
  const [notes, setNotes] = useState('')
  useEffect(() => {
    if (type === 'PMT06') {
      setPs(quarter.months[0].start)
      setPe(quarter.months[0].end)
    } else {
      setPs(quarter.start)
      setPe(quarter.end)
    }
  }, [type, quarter])
  const save = useMutation({
    mutationFn: () =>
      post('/gst/filings', { returnType: type, periodStart: ps, periodEnd: pe, filedOn: on, arn: arn || null, taxPaid: paid || null, notes: notes || null }),
    onSuccess: () => {
      qc.invalidateQueries()
      onClose()
    },
  })
  return (
    <Modal title="Log a filing / payment" onClose={onClose}>
      <div className="space-y-3">
        <Field label="What was filed or paid?">
          <select className="input" value={type} onChange={(e) => setType(e.target.value)}>
            <option value="GSTR1">GSTR-1 (quarterly return)</option>
            <option value="GSTR3B">GSTR-3B (quarterly return)</option>
            <option value="PMT06">PMT-06 (monthly payment)</option>
          </select>
        </Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Period from">
            <input type="date" className="input" value={ps} onChange={(e) => setPs(e.target.value)} />
          </Field>
          <Field label="Period to">
            <input type="date" className="input" value={pe} onChange={(e) => setPe(e.target.value)} />
          </Field>
          <Field label="Filed / paid on">
            <input type="date" className="input" value={on} max={todayIso()} onChange={(e) => setOn(e.target.value)} />
          </Field>
          <Field label="Tax paid ₹ (if any)">
            <input className="input" inputMode="decimal" value={paid} onChange={(e) => setPaid(e.target.value)} />
          </Field>
        </div>
        <Field label="ARN / reference (optional)">
          <input className="input" value={arn} onChange={(e) => setArn(e.target.value)} />
        </Field>
        <Field label="Notes">
          <input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <ErrorNote error={save.error} />
        <button className="btn-primary w-full" disabled={save.isPending} onClick={() => save.mutate()}>
          Save record
        </button>
      </div>
    </Modal>
  )
}
