import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post, put, qs } from '../../api'
import { dmy, inr, todayIso, toNumber } from '../../format'
import { ErrorNote, Field, HistoryDrawer, Loading, Modal, PageTitle, ReasonDialog } from '../../components/ui'
import { useAuth } from '../../auth'
import type { TcsSummary, TdsEntry, TdsPending, TdsSection, TdsThresholds } from '../../types.phase4'
import { Banner, Empty, Notice, Pill, Row, TabBar, fyStartYear, useTab } from './shared'

const PAYEE_LABEL: Record<string, string> = { ANY: 'Any payee', INDIVIDUAL_HUF: 'Individual / HUF', OTHER: 'Company / firm / other' }

export default function Tds() {
  const { can } = useAuth()
  const [tab, setTab] = useTab(['register', 'thresholds', 'tcs', 'sections'])
  const canManage = can('tds.manage')
  return (
    <div>
      <PageTitle title="TDS / TCS register" />
      <Banner tone="sky">
        This is a <b>register for your CA</b>, not a filing. Rates and thresholds are seed values - <b>confirm with your CA</b> before deducting or
        depositing. Anything the app cannot decide is marked "check with CA".
      </Banner>
      <TabBar
        tabs={[
          { id: 'register', label: 'Register' },
          { id: 'thresholds', label: 'Thresholds' },
          { id: 'tcs', label: 'TCS credits' },
          { id: 'sections', label: 'Sections' },
        ]}
        value={tab}
        onChange={setTab}
      />
      {tab === 'register' && <RegisterTab canManage={canManage} />}
      {tab === 'thresholds' && <ThresholdTab />}
      {tab === 'tcs' && <TcsTab canManage={canManage} />}
      {tab === 'sections' && <SectionsTab canConfigure={can('tds.configure')} />}
    </div>
  )
}

function statusPill(e: TdsEntry) {
  if (e.voidState !== 'ACTIVE') return <Pill tone="slate">VOID</Pill>
  if (e.status === 'DEPOSITED') return <Pill tone="green">Deposited</Pill>
  if (e.overdue) return <Pill tone="red">Overdue</Pill>
  return <Pill tone="amber">Pending</Pill>
}

// ------------------------------------------------------------------ register

function RegisterTab({ canManage }: { canManage: boolean }) {
  const [status, setStatus] = useState('')
  const [fy, setFy] = useState(fyStartYear(todayIso()))
  const [adding, setAdding] = useState(false)
  const [edit, setEdit] = useState<TdsEntry | null>(null)
  const [depositFor, setDepositFor] = useState<TdsEntry | null>(null)
  const [voidFor, setVoidFor] = useState<TdsEntry | null>(null)
  const [unmarkFor, setUnmarkFor] = useState<TdsEntry | null>(null)
  const [history, setHistory] = useState<number | null>(null)
  const qc = useQueryClient()
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['tds-entries'] })
    qc.invalidateQueries({ queryKey: ['tds-pending'] })
    qc.invalidateQueries({ queryKey: ['tds-thresholds'] })
  }
  const from = `${fy}-04-01`
  const to = `${fy + 1}-03-31`
  const pending = useQuery({ queryKey: ['tds-pending'], queryFn: () => get<TdsPending>('/tds/pending') })
  const list = useQuery({
    queryKey: ['tds-entries', fy, status],
    queryFn: () => get<TdsEntry[]>(`/tds/entries${qs({ from, to, status: status || undefined, includeVoided: true })}`),
  })
  const p = pending.data
  return (
    <div>
      {p && p.count > 0 && (
        <Banner tone={p.overdueCount > 0 ? 'red' : 'amber'}>
          <b>{p.count}</b> TDS entr{p.count === 1 ? 'y' : 'ies'} not yet marked deposited, total <b>{inr(p.totalTds)}</b>
          {p.overdueCount > 0 && <> - <b>{p.overdueCount} past the due date</b></>}
          {p.checkWithCaCount > 0 && <> - {p.checkWithCaCount} need a CA decision</>}.
        </Banner>
      )}
      <div className="mb-3 flex flex-wrap items-end gap-2">
        <button className="btn-ghost" onClick={() => setFy(fy - 1)} aria-label="Previous year">‹</button>
        <div className="text-sm font-semibold">FY {fy}-{String((fy + 1) % 100).padStart(2, '0')}</div>
        <button className="btn-ghost" onClick={() => setFy(fy + 1)} aria-label="Next year">›</button>
        <select className="input w-auto" value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status filter">
          <option value="">All</option>
          <option value="PENDING">Pending</option>
          <option value="DEPOSITED">Deposited</option>
        </select>
        {canManage && (
          <button className="btn-primary ml-auto" onClick={() => setAdding(true)}>
            + Add entry
          </button>
        )}
      </div>
      {list.isLoading && <Loading />}
      <ErrorNote error={list.error} />
      {list.data && list.data.length === 0 && <Empty>No TDS entries in this financial year.</Empty>}
      <div className="space-y-2">
        {list.data?.map((e) => (
          <div key={e.id} className={`card ${e.voidState !== 'ACTIVE' ? 'opacity-60' : ''}`}>
            <div className="flex items-start justify-between gap-2">
              <div>
                <div className="font-semibold">{e.deducteeName}</div>
                <div className="text-xs text-slate-500">
                  {dmy(e.txnDate)} · Sec {e.section} · {PAYEE_LABEL[e.payeeType] ?? e.payeeType} · PAN {e.deducteePan ?? 'not recorded'}
                </div>
              </div>
              <div className="text-right">
                <div className="font-semibold tabular-nums">{e.tdsAmount === null ? '—' : inr(e.tdsAmount)}</div>
                <div className="text-xs text-slate-500">on {inr(e.paymentAmount)}</div>
              </div>
            </div>
            <div className="mt-2 flex flex-wrap gap-1">
              {statusPill(e)}
              {e.thresholdStatus === 'CHECK_WITH_CA' && <Pill tone="violet">Check with CA</Pill>}
              {e.thresholdStatus === 'BELOW_THRESHOLD' && <Pill>Below threshold</Pill>}
              {e.panMissing && <Pill tone="amber">PAN missing</Pill>}
              {e.revision > 1 && <Pill tone="sky">Corrected r{e.revision}</Pill>}
              {e.source !== 'MANUAL' && <Pill tone="slate">{e.source}</Pill>}
            </div>
            {e.thresholdNote && <p className="mt-1 text-xs text-slate-600">{e.thresholdNote}</p>}
            <div className="mt-1 text-xs text-slate-500">
              {e.depositedOn ? (
                <>Deposited {dmy(e.depositedOn)}{e.challanNo ? ` · challan ${e.challanNo}` : ''}</>
              ) : e.depositDueDate ? (
                <>Deposit due {dmy(e.depositDueDate)}</>
              ) : null}
              {e.voidReason && <> · Voided: {e.voidReason}</>}
            </div>
            <div className="mt-2 flex flex-wrap gap-2">
              <button className="btn-ghost" onClick={() => setHistory(e.id)}>History</button>
              {canManage && e.voidState === 'ACTIVE' && (
                <>
                  {e.status === 'PENDING' && e.thresholdStatus === 'DEDUCT' && (
                    <button className="btn-primary" onClick={() => setDepositFor(e)}>Mark deposited</button>
                  )}
                  {e.status === 'DEPOSITED' && <button className="btn-ghost" onClick={() => setUnmarkFor(e)}>Undo deposit</button>}
                  {e.status === 'PENDING' && <button className="btn-ghost" onClick={() => setEdit(e)}>Correct</button>}
                  <button className="btn-danger" onClick={() => setVoidFor(e)}>Void</button>
                </>
              )}
            </div>
          </div>
        ))}
      </div>
      {adding && <EntryForm onClose={() => setAdding(false)} onSaved={refresh} />}
      {edit && <EntryForm entry={edit} onClose={() => setEdit(null)} onSaved={refresh} />}
      {depositFor && <DepositForm entry={depositFor} onClose={() => setDepositFor(null)} onSaved={refresh} />}
      {voidFor && (
        <ReasonDialog
          title="Void TDS entry"
          confirmLabel="Void entry"
          danger
          onClose={() => setVoidFor(null)}
          onConfirm={async (reason) => {
            await post(`/tds/entries/${voidFor.id}/void`, { reason })
            refresh()
          }}
        />
      )}
      {unmarkFor && (
        <ReasonDialog
          title="Undo deposit marking"
          confirmLabel="Undo"
          onClose={() => setUnmarkFor(null)}
          onConfirm={async (reason) => {
            await post(`/tds/entries/${unmarkFor.id}/unmark-deposit`, { reason })
            refresh()
          }}
        />
      )}
      {history !== null && <HistoryDrawer entityType="TDS_ENTRY" entityId={history} onClose={() => setHistory(null)} />}
    </div>
  )
}

function EntryForm({ entry, onClose, onSaved }: { entry?: TdsEntry; onClose: () => void; onSaved: () => void }) {
  const sections = useQuery({ queryKey: ['tds-sections', 'current'], queryFn: () => get<TdsSection[]>('/tds/sections') })
  const [txnDate, setTxnDate] = useState(entry?.txnDate ?? todayIso())
  const [name, setName] = useState(entry?.deducteeName ?? '')
  const [pan, setPan] = useState(entry?.deducteePan ?? '')
  const [section, setSection] = useState(entry?.section ?? '')
  const [payeeType, setPayeeType] = useState(entry?.payeeType ?? 'INDIVIDUAL_HUF')
  const [amount, setAmount] = useState(entry?.paymentAmount ?? '')
  const [notes, setNotes] = useState(entry?.notes ?? '')
  const [reason, setReason] = useState('')
  const codes = Array.from(new Set((sections.data ?? []).map((s) => s.code)))
  const save = useMutation({
    mutationFn: () => {
      const body = {
        txnDate, deducteeName: name.trim(), deducteePan: pan.trim().toUpperCase() || null, section: section || codes[0],
        payeeType, paymentAmount: amount, notes: notes.trim() || null, source: 'MANUAL',
        reason: entry ? reason.trim() : undefined, version: entry?.version,
      }
      return entry ? put(`/tds/entries/${entry.id}`, body) : post('/tds/entries', body)
    },
    onSuccess: () => (onSaved(), onClose()),
  })
  const valid = name.trim() && toNumber(amount) > 0 && (!entry || reason.trim())
  return (
    <Modal title={entry ? 'Correct TDS entry' : 'Add TDS entry'} onClose={onClose}>
      <div className="space-y-3">
        <Field label="Payment date">
          <input type="date" className="input" value={txnDate} onChange={(e) => setTxnDate(e.target.value)} />
        </Field>
        <Field label="Paid to (deductee)">
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="PAN (leave blank if not known)">
          <input className="input uppercase" maxLength={10} value={pan} onChange={(e) => setPan(e.target.value)} />
        </Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Section">
            <select className="input" value={section || codes[0] || ''} onChange={(e) => setSection(e.target.value)}>
              {codes.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
          </Field>
          <Field label="Payee type">
            <select className="input" value={payeeType} onChange={(e) => setPayeeType(e.target.value)}>
              <option value="INDIVIDUAL_HUF">Individual / HUF</option>
              <option value="OTHER">Company / firm / other</option>
            </select>
          </Field>
        </div>
        <Field label="Payment amount (Rs)">
          <input className="input" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
        </Field>
        <Field label="Notes">
          <input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} />
        </Field>
        {entry && (
          <Field label="Reason for correction (required)">
            <input className="input" value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
        )}
        <Notice>The app works out the rate and whether the threshold is crossed, but only as a suggestion. Please confirm with your CA.</Notice>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!valid || save.isPending} onClick={() => save.mutate()}>Save</button>
        </div>
      </div>
    </Modal>
  )
}

function DepositForm({ entry, onClose, onSaved }: { entry: TdsEntry; onClose: () => void; onSaved: () => void }) {
  const [on, setOn] = useState(todayIso())
  const [challan, setChallan] = useState('')
  const [amount, setAmount] = useState(entry.tdsAmount ?? '')
  const save = useMutation({
    mutationFn: () => post(`/tds/entries/${entry.id}/deposit`, { depositedOn: on, challanNo: challan.trim() || null, tdsAmount: amount || null }),
    onSuccess: () => (onSaved(), onClose()),
  })
  return (
    <Modal title="Mark TDS as deposited" onClose={onClose}>
      <p className="mb-3 text-sm text-slate-600">
        This only records that you paid the challan. It does not pay or file anything.
      </p>
      <div className="space-y-3">
        <Field label="Deposited on">
          <input type="date" className="input" value={on} onChange={(e) => setOn(e.target.value)} />
        </Field>
        <Field label="Challan number (optional)">
          <input className="input" value={challan} onChange={(e) => setChallan(e.target.value)} />
        </Field>
        <Field label="TDS amount deposited (Rs)">
          <input className="input" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
        </Field>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={save.isPending || toNumber(amount) <= 0} onClick={() => save.mutate()}>Mark deposited</button>
        </div>
      </div>
    </Modal>
  )
}

// ------------------------------------------------------------------ thresholds

function ThresholdTab() {
  const [fy, setFy] = useState(fyStartYear(todayIso()))
  const q = useQuery({ queryKey: ['tds-thresholds', fy], queryFn: () => get<TdsThresholds>(`/tds/thresholds${qs({ fy })}`) })
  return (
    <div>
      <div className="mb-3 flex items-center gap-2">
        <button className="btn-ghost" onClick={() => setFy(fy - 1)} aria-label="Previous year">‹</button>
        <div className="text-sm font-semibold">FY {fy}-{String((fy + 1) % 100).padStart(2, '0')}</div>
        <button className="btn-ghost" onClick={() => setFy(fy + 1)} aria-label="Next year">›</button>
      </div>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      {q.data && q.data.rows.length === 0 && <Empty>No payments recorded in this financial year.</Empty>}
      <div className="space-y-2">
        {q.data?.rows.map((r) => (
          <div key={r.deducteeKey + r.section} className="card">
            <div className="flex justify-between gap-2">
              <div>
                <div className="font-semibold">{r.deducteeName}</div>
                <div className="text-xs text-slate-500">Sec {r.section} · {PAYEE_LABEL[r.payeeType] ?? r.payeeType} · {r.entries} payment{r.entries === 1 ? '' : 's'}</div>
              </div>
              <div className="text-right font-semibold tabular-nums">{inr(r.totalPaid)}</div>
            </div>
            <div className="mt-1 text-xs text-slate-600">
              Annual threshold: {r.annualThreshold === null ? 'none set' : inr(r.annualThreshold)}
              {r.annualThreshold !== null && (r.annualCrossed ? ' - crossed' : r.remainingToAnnual !== null ? ` - ${inr(r.remainingToAnnual)} to go` : '')}
              {r.singleThreshold !== null && <> · per-payment: {inr(r.singleThreshold)}</>}
            </div>
            <div className="mt-1 flex flex-wrap gap-1">
              {r.annualCrossed && <Pill tone="red">Annual threshold crossed</Pill>}
              {r.panMissing && <Pill tone="amber">PAN missing</Pill>}
              {r.totalTds !== null && <Pill tone="sky">TDS {inr(r.totalTds)}</Pill>}
            </div>
          </div>
        ))}
      </div>
      {q.data && <Notice>{q.data.note}</Notice>}
    </div>
  )
}

// ------------------------------------------------------------------ TCS

function TcsTab({ canManage }: { canManage: boolean }) {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['tds-tcs'], queryFn: () => get<TcsSummary>('/tds/tcs') })
  const [adding, setAdding] = useState(false)
  const [voidId, setVoidId] = useState<number | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: ['tds-tcs'] })
  return (
    <div>
      <Notice>
        TCS is tax collected from you by delivery platforms. Enter the amounts shown on their statements so your CA can claim credit. Nothing is
        calculated for you.
      </Notice>
      {canManage && <button className="btn-primary mb-3" onClick={() => setAdding(true)}>+ Add TCS credit</button>}
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      {q.data && (
        <div className="card mb-3">
          <Row label="GST TCS recorded" value={inr(q.data.totalGstTcs)} />
          <Row label="Income-tax TCS recorded" value={inr(q.data.totalOtherTcs)} />
        </div>
      )}
      {q.data && q.data.rows.length === 0 && <Empty>No TCS credits recorded yet.</Empty>}
      <div className="space-y-2">
        {q.data?.rows.map((r) => (
          <div key={r.id} className={`card ${r.voidState !== 'ACTIVE' ? 'opacity-60' : ''}`}>
            <div className="flex justify-between">
              <div>
                <div className="font-semibold">{r.platformName ?? r.platform}</div>
                <div className="text-xs text-slate-500">{dmy(r.periodStart)} to {dmy(r.periodEnd)}</div>
              </div>
              <div className="text-right font-semibold tabular-nums">{inr(r.tcsAmount)}</div>
            </div>
            <div className="mt-1 flex flex-wrap gap-1">
              <Pill tone={r.gstTcs ? 'sky' : 'violet'}>{r.gstTcs ? 'GST TCS' : 'Income-tax TCS'}</Pill>
              {r.voidState !== 'ACTIVE' && <Pill>VOID</Pill>}
            </div>
            {r.notes && <p className="mt-1 text-xs text-slate-600">{r.notes}</p>}
            {r.voidReason && <p className="mt-1 text-xs text-slate-600">Voided: {r.voidReason}</p>}
            {canManage && r.voidState === 'ACTIVE' && (
              <button className="btn-danger mt-2" onClick={() => setVoidId(r.id)}>Void</button>
            )}
          </div>
        ))}
      </div>
      {adding && <TcsForm onClose={() => setAdding(false)} onSaved={refresh} />}
      {voidId !== null && (
        <ReasonDialog
          title="Void TCS credit"
          confirmLabel="Void"
          danger
          onClose={() => setVoidId(null)}
          onConfirm={async (reason) => {
            await post(`/tds/tcs/${voidId}/void`, { reason })
            refresh()
          }}
        />
      )}
    </div>
  )
}

function TcsForm({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const t = todayIso()
  const [platform, setPlatform] = useState('SWIGGY')
  const [platformName, setPlatformName] = useState('')
  const [from, setFrom] = useState(t.slice(0, 8) + '01')
  const [to, setTo] = useState(t)
  const [amount, setAmount] = useState('')
  const [gst, setGst] = useState(true)
  const [notes, setNotes] = useState('')
  const save = useMutation({
    mutationFn: () =>
      post('/tds/tcs', {
        platform, platformName: platform === 'OTHER' ? platformName.trim() || null : null, periodStart: from, periodEnd: to,
        tcsAmount: amount, gstTcs: gst, notes: notes.trim() || null, source: 'MANUAL',
      }),
    onSuccess: () => (onSaved(), onClose()),
  })
  return (
    <Modal title="Add TCS credit" onClose={onClose}>
      <div className="space-y-3">
        <Field label="Platform">
          <select className="input" value={platform} onChange={(e) => setPlatform(e.target.value)}>
            <option value="SWIGGY">Swiggy</option>
            <option value="ZOMATO">Zomato</option>
            <option value="OTHER">Other</option>
          </select>
        </Field>
        {platform === 'OTHER' && (
          <Field label="Platform name">
            <input className="input" value={platformName} onChange={(e) => setPlatformName(e.target.value)} />
          </Field>
        )}
        <div className="grid grid-cols-2 gap-2">
          <Field label="Period from"><input type="date" className="input" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label="Period to"><input type="date" className="input" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
        </div>
        <Field label="TCS amount (Rs)">
          <input className="input" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
        </Field>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={gst} onChange={(e) => setGst(e.target.checked)} /> This is GST TCS (otherwise income-tax TCS)
        </label>
        <Field label="Notes"><input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={save.isPending || toNumber(amount) <= 0} onClick={() => save.mutate()}>Save</button>
        </div>
      </div>
    </Modal>
  )
}

// ------------------------------------------------------------------ sections

function SectionsTab({ canConfigure }: { canConfigure: boolean }) {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['tds-sections', 'all'], queryFn: () => get<TdsSection[]>(`/tds/sections${qs({ history: true })}`) })
  const [adding, setAdding] = useState(false)
  return (
    <div>
      <Notice>
        Rates and thresholds below are starting values. They are never edited in place - a change is added as a new row with an effective date, so
        past entries keep the rate that applied then. Please confirm each value with your CA.
      </Notice>
      {canConfigure && <button className="btn-primary mb-3" onClick={() => setAdding(true)}>+ New rate / threshold</button>}
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      <div className="space-y-2">
        {q.data?.map((s) => (
          <div key={s.id} className={`card ${s.current ? '' : 'opacity-60'}`}>
            <div className="flex justify-between gap-2">
              <div>
                <div className="font-semibold">Sec {s.code} · {PAYEE_LABEL[s.payeeType] ?? s.payeeType}</div>
                <div className="text-xs text-slate-500">{s.description}</div>
              </div>
              <div className="text-right font-semibold">{s.rate}%</div>
            </div>
            <div className="mt-1 text-xs text-slate-600">
              Per payment: {s.singleThreshold === null ? 'none' : inr(s.singleThreshold)} · Annual: {s.annualThreshold === null ? 'none' : inr(s.annualThreshold)} · from {dmy(s.effectiveFrom)}
            </div>
            <div className="mt-1 flex flex-wrap gap-1">
              {s.current ? <Pill tone="green">Current</Pill> : <Pill>Superseded / future</Pill>}
              {s.verifyNote && <Pill tone="amber">{s.verifyNote}</Pill>}
            </div>
          </div>
        ))}
      </div>
      {adding && <SectionForm onClose={() => setAdding(false)} onSaved={() => qc.invalidateQueries({ queryKey: ['tds-sections'] })} />}
    </div>
  )
}

function SectionForm({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [code, setCode] = useState('')
  const [description, setDescription] = useState('')
  const [payeeType, setPayeeType] = useState('ANY')
  const [rate, setRate] = useState('')
  const [single, setSingle] = useState('')
  const [annual, setAnnual] = useState('')
  const [from, setFrom] = useState(todayIso())
  const [note, setNote] = useState('')
  const save = useMutation({
    mutationFn: () =>
      post('/tds/sections', {
        code: code.trim(), description: description.trim(), payeeType, rate, singleThreshold: single || null,
        annualThreshold: annual || null, effectiveFrom: from, verifyNote: note.trim() || null,
      }),
    onSuccess: () => (onSaved(), onClose()),
  })
  return (
    <Modal title="New rate / threshold" onClose={onClose}>
      <div className="space-y-3">
        <div className="grid grid-cols-2 gap-2">
          <Field label="Section code"><input className="input" value={code} onChange={(e) => setCode(e.target.value)} /></Field>
          <Field label="Payee type">
            <select className="input" value={payeeType} onChange={(e) => setPayeeType(e.target.value)}>
              <option value="ANY">Any</option>
              <option value="INDIVIDUAL_HUF">Individual / HUF</option>
              <option value="OTHER">Company / firm / other</option>
            </select>
          </Field>
        </div>
        <Field label="Description"><input className="input" value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
        <div className="grid grid-cols-3 gap-2">
          <Field label="Rate %"><input className="input" inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} /></Field>
          <Field label="Per payment"><input className="input" inputMode="decimal" value={single} onChange={(e) => setSingle(e.target.value)} /></Field>
          <Field label="Annual"><input className="input" inputMode="decimal" value={annual} onChange={(e) => setAnnual(e.target.value)} /></Field>
        </div>
        <Field label="Effective from"><input type="date" className="input" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
        <Field label="Note (e.g. who confirmed it)"><input className="input" value={note} onChange={(e) => setNote(e.target.value)} /></Field>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={save.isPending || !code.trim() || !description.trim() || toNumber(rate) <= 0} onClick={() => save.mutate()}>Save</button>
        </div>
      </div>
    </Modal>
  )
}
