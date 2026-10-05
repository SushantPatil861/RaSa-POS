import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post, put, qs } from '../../api'
import { dmy, inr, todayIso, toNumber, addDays } from '../../format'
import { ErrorNote, Field, Loading, Modal, PageTitle, ReasonDialog } from '../../components/ui'
import { useAuth } from '../../auth'
import type { ComplianceDashboard, ComplianceDoc, ComplianceRule, ComplianceTask } from '../../types.phase4'
import { Banner, Empty, Notice, Pill, TabBar, monthLabel, useTab } from './shared'

const CATEGORIES = ['GST', 'TDS', 'PAYROLL', 'FSSAI', 'LICENCE', 'OTHER']
const CAT_TONE: Record<string, 'sky' | 'violet' | 'green' | 'amber' | 'slate'> = { GST: 'sky', TDS: 'violet', PAYROLL: 'green', FSSAI: 'amber', LICENCE: 'amber', OTHER: 'slate' }

export function dueText(t: Pick<ComplianceTask, 'daysToDue' | 'status'>): string {
  if (t.status !== 'OPEN') return ''
  if (t.daysToDue < 0) return `${-t.daysToDue} day${t.daysToDue === -1 ? '' : 's'} overdue`
  if (t.daysToDue === 0) return 'due today'
  return `in ${t.daysToDue} day${t.daysToDue === 1 ? '' : 's'}`
}

export default function Compliance() {
  const { can } = useAuth()
  const [tab, setTab] = useTab(['dashboard', 'calendar', 'licences', 'rules'])
  return (
    <div>
      <PageTitle title="Compliance calendar" />
      <Banner tone="sky">
        A reminder list, not a filing tool. Due dates follow the usual QRMP pattern and are defaults - <b>confirm with your CA</b>, especially for
        March and the last quarter, where dates can differ.
      </Banner>
      <TabBar
        tabs={[
          { id: 'dashboard', label: 'Overview' },
          { id: 'calendar', label: 'Calendar' },
          { id: 'licences', label: 'Licences' },
          { id: 'rules', label: 'Rules' },
        ]}
        value={tab}
        onChange={setTab}
      />
      {tab === 'dashboard' && <DashboardTab />}
      {tab === 'calendar' && <CalendarTab canManage={can('compliance.manage')} />}
      {tab === 'licences' && <LicencesTab canManage={can('compliance.manage')} />}
      {tab === 'rules' && <RulesTab canConfigure={can('compliance.configure')} />}
    </div>
  )
}

function TaskLine({ t }: { t: ComplianceTask }) {
  return (
    <div className={`flex items-start justify-between gap-2 rounded-lg bg-white p-2 text-sm ring-1 ${t.overdue ? 'ring-red-300' : 'ring-slate-200'}`}>
      <div>
        <div className="font-medium">{t.title}{t.periodLabel ? ` · ${t.periodLabel}` : ''}</div>
        <div className="text-xs text-slate-500">Due {dmy(t.dueDate)} · {dueText(t)}</div>
      </div>
      <Pill tone={CAT_TONE[t.category] ?? 'slate'}>{t.category}</Pill>
    </div>
  )
}

function DashboardTab() {
  const q = useQuery({ queryKey: ['comp-dashboard'], queryFn: () => get<ComplianceDashboard>('/compliance/dashboard') })
  const d = q.data
  return (
    <div>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      {d && (
        <div className="space-y-4">
          {d.overdue.length > 0 && (
            <section>
              <h3 className="mb-1 text-sm font-semibold text-red-700">Overdue ({d.overdue.length})</h3>
              <div className="space-y-1">{d.overdue.map((t) => <TaskLine key={t.id} t={t} />)}</div>
            </section>
          )}
          <section>
            <h3 className="mb-1 text-sm font-semibold">Next 7 days</h3>
            {d.due7.length === 0 ? <Empty>Nothing due this week.</Empty> : <div className="space-y-1">{d.due7.map((t) => <TaskLine key={t.id} t={t} />)}</div>}
          </section>
          <section>
            <h3 className="mb-1 text-sm font-semibold">8 to 30 days</h3>
            {d.due30.length === 0 ? <Empty>Nothing else in the next month.</Empty> : <div className="space-y-1">{d.due30.map((t) => <TaskLine key={t.id} t={t} />)}</div>}
          </section>
          {d.expiringDocuments.length > 0 && (
            <section>
              <h3 className="mb-1 text-sm font-semibold text-amber-800">Licences expiring or expired</h3>
              <div className="space-y-1">
                {d.expiringDocuments.map((x) => (
                  <div key={x.id} className="flex justify-between rounded-lg bg-white p-2 text-sm ring-1 ring-amber-300">
                    <span>{x.name}{x.number ? ` · ${x.number}` : ''}</span>
                    <DocPill d={x} />
                  </div>
                ))}
              </div>
            </section>
          )}
          {d.documentsMissingExpiry > 0 && <Notice>{d.documentsMissingExpiry} licence record(s) have no expiry date entered yet. Add the dates from the actual certificates.</Notice>}
          <div className="grid gap-2 sm:grid-cols-3">
            {d.pendingTds && (
              <Link to="/tds" className="card">
                <div className="text-xs uppercase text-slate-500">TDS to deposit</div>
                <div className="text-xl font-semibold">{inr(d.pendingTds.amount)}</div>
                <div className="text-xs text-slate-500">{d.pendingTds.pendingCount} entries{d.pendingTds.overdueCount > 0 ? ` · ${d.pendingTds.overdueCount} overdue` : ''}{d.pendingTds.checkWithCaCount > 0 ? ` · ${d.pendingTds.checkWithCaCount} for CA` : ''}</div>
              </Link>
            )}
            {d.draftGstWorkpapers && (
              <Link to="/gst" className="card">
                <div className="text-xs uppercase text-slate-500">GST workpapers in draft</div>
                <div className="text-xl font-semibold">{d.draftGstWorkpapers.length}</div>
                <div className="text-xs text-slate-500">{d.draftGstWorkpapers.map((w) => `${monthLabel(w.periodStart)} v${w.version}`).join(', ') || 'None'}</div>
              </Link>
            )}
            {d.payroll && (
              <Link to="/payroll?tab=runs" className="card">
                <div className="text-xs uppercase text-slate-500">Payroll</div>
                <div className="text-sm font-semibold">
                  {d.payroll.unfinishedRun ? `${monthLabel(d.payroll.unfinishedRun.month)} run is ${d.payroll.unfinishedRun.status.toLowerCase()}` : 'No unfinished run'}
                </div>
                {d.payroll.lastMonthRunMissing && <div className="text-xs text-amber-800">No run yet for {monthLabel(d.payroll.lastMonth)}</div>}
              </Link>
            )}
          </div>
          {d.notes.map((n) => <Notice key={n}>{n}</Notice>)}
        </div>
      )}
    </div>
  )
}

function CalendarTab({ canManage }: { canManage: boolean }) {
  const qc = useQueryClient()
  const [showDone, setShowDone] = useState(false)
  const [adding, setAdding] = useState(false)
  const [complete, setComplete] = useState<ComplianceTask | null>(null)
  const [skip, setSkip] = useState<ComplianceTask | null>(null)
  const [reopen, setReopen] = useState<ComplianceTask | null>(null)
  const from = addDays(todayIso(), -120)
  const to = addDays(todayIso(), 180)
  const q = useQuery({ queryKey: ['comp-calendar', from, to], queryFn: () => get<ComplianceTask[]>(`/compliance/calendar${qs({ from, to })}`) })
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['comp-calendar'] })
    qc.invalidateQueries({ queryKey: ['comp-dashboard'] })
  }
  const rows = (q.data ?? []).filter((t) => showDone || t.status === 'OPEN')
  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={showDone} onChange={(e) => setShowDone(e.target.checked)} /> Show done / skipped</label>
        {canManage && <button className="btn-primary ml-auto" onClick={() => setAdding(true)}>+ Add task</button>}
      </div>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      {q.data && rows.length === 0 && <Empty>No tasks to show.</Empty>}
      <div className="space-y-2">
        {rows.map((t) => (
          <div key={t.id} className={`card ${t.overdue ? 'ring-2 ring-red-300' : ''} ${t.status !== 'OPEN' ? 'opacity-70' : ''}`}>
            <div className="flex items-start justify-between gap-2">
              <div>
                <div className="font-semibold">{t.title}{t.periodLabel ? ` · ${t.periodLabel}` : ''}</div>
                <div className="text-xs text-slate-500">Due {dmy(t.dueDate)} {t.status === 'OPEN' && <>· {dueText(t)}</>}</div>
              </div>
              <div className="flex flex-col items-end gap-1">
                <Pill tone={CAT_TONE[t.category] ?? 'slate'}>{t.category}</Pill>
                {t.status === 'DONE' && <Pill tone="green">Done {dmy(t.completedOn)}</Pill>}
                {t.status === 'SKIPPED' && <Pill>Skipped</Pill>}
                {t.overdue && t.status === 'OPEN' && <Pill tone="red">Overdue</Pill>}
              </div>
            </div>
            {t.notes && <p className="mt-1 text-xs text-slate-600">{t.notes}</p>}
            <div className="mt-2 flex flex-wrap gap-2">
              {t.link && <Link className="btn-ghost" to={t.link}>Open</Link>}
              {canManage && t.status === 'OPEN' && (
                <>
                  <button className="btn-primary" onClick={() => setComplete(t)}>Mark done</button>
                  <button className="btn-ghost" onClick={() => setSkip(t)}>Skip</button>
                </>
              )}
              {canManage && t.status !== 'OPEN' && <button className="btn-ghost" onClick={() => setReopen(t)}>Reopen</button>}
            </div>
          </div>
        ))}
      </div>
      {adding && <TaskForm onClose={() => setAdding(false)} onSaved={refresh} />}
      {complete && <CompleteForm task={complete} onClose={() => setComplete(null)} onSaved={refresh} />}
      {skip && (
        <ReasonDialog title="Skip this task" confirmLabel="Skip" onClose={() => setSkip(null)} onConfirm={async (reason) => { await post(`/compliance/tasks/${skip.id}/skip`, { reason }); refresh() }} />
      )}
      {reopen && (
        <ReasonDialog title="Reopen this task" confirmLabel="Reopen" onClose={() => setReopen(null)} onConfirm={async (reason) => { await post(`/compliance/tasks/${reopen.id}/reopen`, { reason }); refresh() }} />
      )}
    </div>
  )
}

function CompleteForm({ task, onClose, onSaved }: { task: ComplianceTask; onClose: () => void; onSaved: () => void }) {
  const [on, setOn] = useState(todayIso())
  const [notes, setNotes] = useState('')
  const save = useMutation({
    mutationFn: () => post(`/compliance/tasks/${task.id}/complete`, { completedOn: on, notes: notes.trim() || null }),
    onSuccess: () => (onSaved(), onClose()),
  })
  return (
    <Modal title={task.title} onClose={onClose}>
      <div className="space-y-3">
        <p className="text-sm text-slate-600">This only ticks the task off in your list. It does not file or pay anything.</p>
        <Field label="Done on"><input type="date" className="input" value={on} onChange={(e) => setOn(e.target.value)} /></Field>
        <Field label="Notes (e.g. ARN or challan number)"><input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={save.isPending} onClick={() => save.mutate()}>Mark done</button>
        </div>
      </div>
    </Modal>
  )
}

function TaskForm({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [title, setTitle] = useState('')
  const [category, setCategory] = useState('OTHER')
  const [due, setDue] = useState(addDays(todayIso(), 7))
  const [notes, setNotes] = useState('')
  const save = useMutation({
    mutationFn: () => post('/compliance/tasks', { title: title.trim(), category, dueDate: due, notes: notes.trim() || null, link: null }),
    onSuccess: () => (onSaved(), onClose()),
  })
  return (
    <Modal title="Add task" onClose={onClose}>
      <div className="space-y-3">
        <Field label="What needs doing"><input className="input" value={title} onChange={(e) => setTitle(e.target.value)} /></Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Type">
            <select className="input" value={category} onChange={(e) => setCategory(e.target.value)}>{CATEGORIES.map((c) => <option key={c}>{c}</option>)}</select>
          </Field>
          <Field label="Due date"><input type="date" className="input" value={due} onChange={(e) => setDue(e.target.value)} /></Field>
        </div>
        <Field label="Notes"><input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!title.trim() || save.isPending} onClick={() => save.mutate()}>Save</button>
        </div>
      </div>
    </Modal>
  )
}

// ------------------------------------------------------------------ licences

function DocPill({ d }: { d: ComplianceDoc }) {
  if (d.expiresOn === null) return <Pill>No expiry entered</Pill>
  if (d.expiryStatus === 'EXPIRED') return <Pill tone="red">Expired {dmy(d.expiresOn)}</Pill>
  if (d.expiryStatus === 'EXPIRING') return <Pill tone="amber">Expires {dmy(d.expiresOn)} ({d.daysToExpiry} days)</Pill>
  return <Pill tone="green">Valid to {dmy(d.expiresOn)}</Pill>
}

function LicencesTab({ canManage }: { canManage: boolean }) {
  const qc = useQueryClient()
  const [all, setAll] = useState(false)
  const [edit, setEdit] = useState<ComplianceDoc | 'new' | null>(null)
  const q = useQuery({ queryKey: ['comp-docs', all], queryFn: () => get<ComplianceDoc[]>(`/compliance/documents${qs({ includeInactive: all })}`) })
  return (
    <div>
      <Notice>The licences listed at the start are placeholders. Enter the real number and expiry date from each certificate.</Notice>
      <div className="mb-3 flex items-center gap-3">
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} /> Show inactive</label>
        {canManage && <button className="btn-primary ml-auto" onClick={() => setEdit('new')}>+ Add licence</button>}
      </div>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      {q.data && q.data.length === 0 && <Empty>No licences recorded.</Empty>}
      <div className="space-y-2">
        {q.data?.map((d) => (
          <div key={d.id} className={`card ${d.active ? '' : 'opacity-60'}`}>
            <div className="flex items-start justify-between gap-2">
              <div>
                <div className="font-semibold">{d.name}</div>
                <div className="text-xs text-slate-500">{d.number ? `No. ${d.number}` : 'Number not entered'}{d.issuedOn ? ` · issued ${dmy(d.issuedOn)}` : ''}</div>
              </div>
              <DocPill d={d} />
            </div>
            {d.notes && <p className="mt-1 text-xs text-slate-600">{d.notes}</p>}
            {canManage && <button className="btn-ghost mt-2" onClick={() => setEdit(d)}>Edit</button>}
          </div>
        ))}
      </div>
      {edit && <DocForm doc={edit === 'new' ? undefined : edit} onClose={() => setEdit(null)} onSaved={() => { qc.invalidateQueries({ queryKey: ['comp-docs'] }); qc.invalidateQueries({ queryKey: ['comp-dashboard'] }) }} />}
    </div>
  )
}

function DocForm({ doc, onClose, onSaved }: { doc?: ComplianceDoc; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(doc?.name ?? '')
  const [number, setNumber] = useState(doc?.number ?? '')
  const [issued, setIssued] = useState(doc?.issuedOn ?? '')
  const [expires, setExpires] = useState(doc?.expiresOn ?? '')
  const [remind, setRemind] = useState(String(doc?.reminderDays ?? 30))
  const [notes, setNotes] = useState(doc?.notes ?? '')
  const [active, setActive] = useState(doc?.active ?? true)
  const save = useMutation({
    mutationFn: () => {
      const body = {
        name: name.trim(), number: number.trim() || null, issuedOn: issued || null, expiresOn: expires || null,
        reminderDays: Math.max(0, Math.round(toNumber(remind))), fileDocumentId: doc?.fileDocumentId ?? null, notes: notes.trim() || null, active,
      }
      return doc ? put(`/compliance/documents/${doc.id}`, body) : post('/compliance/documents', body)
    },
    onSuccess: () => (onSaved(), onClose()),
  })
  return (
    <Modal title={doc ? 'Edit licence' : 'Add licence'} onClose={onClose}>
      <div className="space-y-3">
        <Field label="Licence / registration"><input className="input" value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="Number"><input className="input" value={number} onChange={(e) => setNumber(e.target.value)} /></Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Issued on"><input type="date" className="input" value={issued} onChange={(e) => setIssued(e.target.value)} /></Field>
          <Field label="Expires on"><input type="date" className="input" value={expires} onChange={(e) => setExpires(e.target.value)} /></Field>
        </div>
        <Field label="Remind me this many days before"><input className="input" inputMode="numeric" value={remind} onChange={(e) => setRemind(e.target.value)} /></Field>
        <Field label="Notes"><input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Still relevant</label>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!name.trim() || save.isPending} onClick={() => save.mutate()}>Save</button>
        </div>
      </div>
    </Modal>
  )
}

// ------------------------------------------------------------------ rules

function RulesTab({ canConfigure }: { canConfigure: boolean }) {
  const qc = useQueryClient()
  const [edit, setEdit] = useState<ComplianceRule | null>(null)
  const q = useQuery({ queryKey: ['comp-rules'], queryFn: () => get<ComplianceRule[]>('/compliance/rules') })
  return (
    <div>
      <Notice>These rules create the recurring tasks. They are defaults - nothing here has been confirmed by a CA unless the note says so. Changes apply to tasks created from now on.</Notice>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      <div className="space-y-2">
        {q.data?.map((r) => (
          <div key={r.id} className={`card ${r.active ? '' : 'opacity-60'}`}>
            <div className="flex items-start justify-between gap-2">
              <div>
                <div className="font-semibold">{r.title}</div>
                <div className="text-xs text-slate-500">
                  {r.frequency.toLowerCase()} · day {r.dueDay}{r.monthOffset > 0 ? ` of ${r.monthOffset === 1 ? 'next month' : `month +${r.monthOffset}`}` : ''}
                  {r.overrideDueDay !== null ? ` · exception: day ${r.overrideDueDay}` : ''}
                </div>
              </div>
              <div className="flex flex-col items-end gap-1">
                <Pill tone={CAT_TONE[r.category] ?? 'slate'}>{r.category}</Pill>
                {!r.active && <Pill>Off</Pill>}
              </div>
            </div>
            {r.notes && <p className="mt-1 text-xs text-slate-600">{r.notes}</p>}
            {canConfigure && <button className="btn-ghost mt-2" onClick={() => setEdit(r)}>Edit</button>}
          </div>
        ))}
      </div>
      {edit && <RuleForm rule={edit} onClose={() => setEdit(null)} onSaved={() => { qc.invalidateQueries({ queryKey: ['comp-rules'] }); qc.invalidateQueries({ queryKey: ['comp-calendar'] }) }} />}
    </div>
  )
}

function RuleForm({ rule, onClose, onSaved }: { rule: ComplianceRule; onClose: () => void; onSaved: () => void }) {
  const [title, setTitle] = useState(rule.title)
  const [dueDay, setDueDay] = useState(String(rule.dueDay))
  const [offset, setOffset] = useState(String(rule.monthOffset))
  const [active, setActive] = useState(rule.active)
  const [notes, setNotes] = useState(rule.notes ?? '')
  const [reason, setReason] = useState('')
  const save = useMutation({
    mutationFn: () =>
      put(`/compliance/rules/${rule.id}`, {
        code: rule.code, title: title.trim(), category: rule.category, dueDay: Math.round(toNumber(dueDay)), frequency: rule.frequency,
        monthOffset: Math.round(toNumber(offset)), appliesMonths: rule.appliesMonths, dueMonth: rule.dueMonth,
        overridePeriodMonth: rule.overridePeriodMonth, overrideMonthOffset: rule.overrideMonthOffset, overrideDueDay: rule.overrideDueDay,
        notes: notes.trim() || null, link: rule.link, active, reason: reason.trim(),
      }),
    onSuccess: () => (onSaved(), onClose()),
  })
  return (
    <Modal title="Edit rule" onClose={onClose}>
      <div className="space-y-3">
        <Field label="Title"><input className="input" value={title} onChange={(e) => setTitle(e.target.value)} /></Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Due day of month"><input className="input" inputMode="numeric" value={dueDay} onChange={(e) => setDueDay(e.target.value)} /></Field>
          <Field label="Months after period"><input className="input" inputMode="numeric" value={offset} onChange={(e) => setOffset(e.target.value)} /></Field>
        </div>
        <Field label="Notes (e.g. confirmed with CA on ...)"><input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Rule is on</label>
        <Field label="Reason for change (required)"><input className="input" value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!title.trim() || !reason.trim() || save.isPending} onClick={() => save.mutate()}>Save</button>
        </div>
      </div>
    </Modal>
  )
}
