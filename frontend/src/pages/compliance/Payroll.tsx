import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post, put, qs } from '../../api'
import { dmy, inr, todayIso, toNumber } from '../../format'
import { ErrorNote, Field, Loading, Modal, PageTitle, ReasonDialog } from '../../components/ui'
import { useAuth } from '../../auth'
import type {
  Advance, AdvanceSummary, AttendanceCell, AttendanceGrid, Employee, Payslip, PayrollRun, PayrollRunDetail, PayslipView,
} from '../../types.phase4'
import { Banner, Empty, Notice, Pill, Row, TabBar, addMonths, daysInMonth, monthLabel, monthStart, useTab } from './shared'

const SALARY_LABEL: Record<string, string> = { MONTHLY: 'Monthly', DAILY: 'Per day', HOURLY: 'Per hour' }
const STATUS_ORDER = ['PRESENT', 'HALF_DAY', 'ABSENT', 'PAID_LEAVE', 'WEEKLY_OFF', 'HOLIDAY']
const STATUS_SHORT: Record<string, string> = { PRESENT: 'P', HALF_DAY: 'H', ABSENT: 'A', PAID_LEAVE: 'L', WEEKLY_OFF: 'W', HOLIDAY: 'F' }
const STATUS_TONE: Record<string, string> = {
  PRESENT: 'bg-emerald-100 text-emerald-800',
  HALF_DAY: 'bg-amber-100 text-amber-800',
  ABSENT: 'bg-red-100 text-red-700',
  PAID_LEAVE: 'bg-sky-100 text-sky-800',
  WEEKLY_OFF: 'bg-slate-200 text-slate-700',
  HOLIDAY: 'bg-violet-100 text-violet-800',
}

export function nextStatus(current: string | undefined): string {
  if (!current) return STATUS_ORDER[0]
  const i = STATUS_ORDER.indexOf(current)
  return STATUS_ORDER[(i + 1) % STATUS_ORDER.length]
}

export default function Payroll({ initialTab }: { initialTab?: string }) {
  const { can } = useAuth()
  const canView = can('payroll.view')
  const canAtt = can('attendance.manage')
  const ids = [...(canView ? ['employees'] : []), ...(canView || canAtt ? ['attendance'] : []), ...(canView ? ['advances', 'runs'] : [])]
  const [tab, setTab] = useTab(ids, initialTab)
  const labels: Record<string, string> = { employees: 'Employees', attendance: 'Attendance', advances: 'Advances', runs: 'Pay runs' }
  return (
    <div>
      <PageTitle title={canView ? 'Payroll & staff' : 'Attendance'} />
      {canView && (
        <Banner tone="sky">
          Payroll here is a <b>worksheet</b>: it works out gross, advance recovery and net pay from attendance. PF, ESI and professional tax are
          not calculated - enter them as "other deductions" after checking with your CA.
        </Banner>
      )}
      <TabBar tabs={ids.map((id) => ({ id, label: labels[id] }))} value={tab} onChange={setTab} />
      {tab === 'employees' && <EmployeesTab canManage={can('payroll.manage')} />}
      {tab === 'attendance' && <AttendanceTab canMark={canAtt} />}
      {tab === 'advances' && <AdvancesTab canManage={can('payroll.manage')} />}
      {tab === 'runs' && <RunsTab canManage={can('payroll.manage')} />}
    </div>
  )
}

// ------------------------------------------------------------------ employees

function EmployeesTab({ canManage }: { canManage: boolean }) {
  const [all, setAll] = useState(false)
  const [edit, setEdit] = useState<Employee | 'new' | null>(null)
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['hr-employees', all], queryFn: () => get<Employee[]>(`/hr/employees${qs({ includeInactive: all })}`) })
  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} /> Show former staff
        </label>
        {canManage && <button className="btn-primary ml-auto" onClick={() => setEdit('new')}>+ Add employee</button>}
      </div>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      {q.data && q.data.length === 0 && <Empty>No employees yet.</Empty>}
      <div className="space-y-2">
        {q.data?.map((e) => (
          <div key={e.id} className={`card ${e.active ? '' : 'opacity-60'}`}>
            <div className="flex justify-between gap-2">
              <div>
                <div className="font-semibold">{e.name}</div>
                <div className="text-xs text-slate-500">
                  {e.designation ?? 'No designation'}{e.mobile ? ` · ${e.mobile}` : ''}{e.joinedOn ? ` · joined ${dmy(e.joinedOn)}` : ''}
                </div>
              </div>
              <div className="text-right">
                <div className="font-semibold tabular-nums">{inr(e.baseRate)}</div>
                <div className="text-xs text-slate-500">{SALARY_LABEL[e.salaryType]}</div>
              </div>
            </div>
            <div className="mt-1 flex flex-wrap gap-1">
              {!e.active && <Pill>Left{e.leftOn ? ` ${dmy(e.leftOn)}` : ''}</Pill>}
              <Pill tone="sky">Overtime x{e.overtimeMultiplier}</Pill>
            </div>
            {canManage && <button className="btn-ghost mt-2" onClick={() => setEdit(e)}>Edit</button>}
          </div>
        ))}
      </div>
      {edit && (
        <EmployeeForm
          employee={edit === 'new' ? undefined : edit}
          onClose={() => setEdit(null)}
          onSaved={() => qc.invalidateQueries({ queryKey: ['hr-employees'] })}
        />
      )}
    </div>
  )
}

function EmployeeForm({ employee, onClose, onSaved }: { employee?: Employee; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(employee?.name ?? '')
  const [designation, setDesignation] = useState(employee?.designation ?? '')
  const [mobile, setMobile] = useState(employee?.mobile ?? '')
  const [joinedOn, setJoinedOn] = useState(employee?.joinedOn ?? '')
  const [leftOn, setLeftOn] = useState(employee?.leftOn ?? '')
  const [salaryType, setSalaryType] = useState(employee?.salaryType ?? 'MONTHLY')
  const [baseRate, setBaseRate] = useState(employee?.baseRate ?? '')
  const [ot, setOt] = useState(employee?.overtimeMultiplier ?? '1.5')
  const [pay, setPay] = useState(employee?.upiOrBankNote ?? '')
  const [notes, setNotes] = useState(employee?.notes ?? '')
  const [active, setActive] = useState(employee?.active ?? true)
  const [reason, setReason] = useState('')
  const save = useMutation({
    mutationFn: () => {
      const body = {
        name: name.trim(), designation: designation.trim() || null, mobile: mobile.trim() || null, joinedOn: joinedOn || null,
        leftOn: leftOn || null, salaryType, baseRate, overtimeMultiplier: ot, upiOrBankNote: pay.trim() || null,
        notes: notes.trim() || null, active, reason: reason.trim() || undefined, version: employee?.version,
      }
      return employee ? put(`/hr/employees/${employee.id}`, body) : post('/hr/employees', body)
    },
    onSuccess: () => (onSaved(), onClose()),
  })
  const rateChanged = employee && employee.baseRate !== baseRate
  const valid = name.trim() && toNumber(baseRate) > 0 && (!rateChanged || reason.trim())
  return (
    <Modal title={employee ? 'Edit employee' : 'Add employee'} onClose={onClose}>
      <div className="space-y-3">
        <Field label="Name"><input className="input" value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Designation"><input className="input" value={designation} onChange={(e) => setDesignation(e.target.value)} /></Field>
          <Field label="Mobile"><input className="input" inputMode="tel" value={mobile} onChange={(e) => setMobile(e.target.value)} /></Field>
        </div>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Joined on"><input type="date" className="input" value={joinedOn} onChange={(e) => setJoinedOn(e.target.value)} /></Field>
          <Field label="Left on"><input type="date" className="input" value={leftOn} onChange={(e) => setLeftOn(e.target.value)} /></Field>
        </div>
        <div className="grid grid-cols-3 gap-2">
          <Field label="Pay type">
            <select className="input" value={salaryType} onChange={(e) => setSalaryType(e.target.value as Employee['salaryType'])}>
              <option value="MONTHLY">Monthly</option>
              <option value="DAILY">Per day</option>
              <option value="HOURLY">Per hour</option>
            </select>
          </Field>
          <Field label="Rate (Rs)"><input className="input" inputMode="decimal" value={baseRate} onChange={(e) => setBaseRate(e.target.value)} /></Field>
          <Field label="OT multiple"><input className="input" inputMode="decimal" value={ot} onChange={(e) => setOt(e.target.value)} /></Field>
        </div>
        <Field label="How paid (UPI id / bank note)"><input className="input" value={pay} onChange={(e) => setPay(e.target.value)} /></Field>
        <Field label="Notes"><input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Currently working here
        </label>
        {employee && (
          <Field label={rateChanged ? 'Reason for changing the rate (required)' : 'Reason for the change'}>
            <input className="input" value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
        )}
        <Notice>Past pay runs keep the rate they were generated with. A new rate applies to runs generated from now on.</Notice>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!valid || save.isPending} onClick={() => save.mutate()}>Save</button>
        </div>
      </div>
    </Modal>
  )
}

// ------------------------------------------------------------------ attendance

function AttendanceTab({ canMark }: { canMark: boolean }) {
  const [month, setMonth] = useState(monthStart(todayIso()))
  const [cellEdit, setCellEdit] = useState<{ employeeId: number; name: string; date: string; cell?: AttendanceCell } | null>(null)
  const qc = useQueryClient()
  const key = ['hr-attendance', month]
  const q = useQuery({ queryKey: key, queryFn: () => get<AttendanceGrid>(`/hr/attendance${qs({ month })}`) })
  const mark = useMutation({
    mutationFn: (b: { date: string; entries: { employeeId: number; status: string; hours?: string | null; overtimeHours?: string | null; notes?: string | null }[] }) =>
      post('/hr/attendance/mark-day', b),
    onSuccess: () => qc.invalidateQueries({ queryKey: key }),
  })
  const g = q.data
  const cellMap = new Map<string, AttendanceCell>()
  g?.cells.forEach((c) => cellMap.set(`${c.employeeId}|${c.attDate}`, c))
  const days = g ? Array.from({ length: g.daysInMonth }, (_, i) => `${month.slice(0, 8)}${String(i + 1).padStart(2, '0')}`) : []
  const today = todayIso()
  const disabled = !canMark || !!g?.locked || mark.isPending
  const activeOn = (e: AttendanceGrid['employees'][number], d: string) => (!e.joinedOn || e.joinedOn <= d) && (!e.leftOn || e.leftOn >= d)

  return (
    <div>
      <div className="mb-3 flex items-center justify-between">
        <button className="btn-ghost" onClick={() => setMonth(addMonths(month, -1))} aria-label="Previous month">‹</button>
        <div className="font-semibold">{monthLabel(month)}</div>
        <button className="btn-ghost" onClick={() => setMonth(addMonths(month, 1))} aria-label="Next month">›</button>
      </div>
      {g?.locked && <Banner tone="amber">{g.lockedReason ?? 'This month has an approved or paid pay run, so attendance is locked.'}</Banner>}
      {canMark && !g?.locked && <Notice>Tap a day to cycle P (present), H (half day), A (absent), L (paid leave), W (weekly off), F (holiday). Tap a date number to mark everyone not yet marked as present.</Notice>}
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      <ErrorNote error={mark.error} />
      {g && g.employees.length === 0 && <Empty>No employees to show.</Empty>}
      {g && g.employees.length > 0 && (
        <div className="card overflow-x-auto p-0">
          <table className="min-w-full border-collapse text-xs">
            <thead>
              <tr>
                <th className="sticky left-0 z-10 bg-white px-2 py-2 text-left">Staff</th>
                {days.map((d) => (
                  <th key={d} className={`px-0.5 py-1 ${d === today ? 'text-brand-700' : 'text-slate-500'}`}>
                    <button
                      disabled={disabled}
                      className="h-8 w-8 rounded hover:bg-slate-100 disabled:hover:bg-transparent"
                      title="Mark everyone present"
                      onClick={() =>
                        mark.mutate({ date: d, entries: g.employees.filter((e) => e.active && activeOn(e, d) && !cellMap.has(`${e.id}|${d}`)).map((e) => ({ employeeId: e.id, status: 'PRESENT' })) })
                      }
                    >
                      {Number(d.slice(8))}
                    </button>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {g.employees.map((e) => (
                <tr key={e.id} className="border-t border-slate-100">
                  <td className="sticky left-0 z-10 bg-white px-2 py-1 font-medium whitespace-nowrap">{e.name}</td>
                  {days.map((d) => {
                    const c = cellMap.get(`${e.id}|${d}`)
                    const inactive = !activeOn(e, d)
                    return (
                      <td key={d} className="px-0.5 py-0.5 text-center">
                        <button
                          disabled={disabled || inactive}
                          className={`h-8 w-8 rounded font-semibold ${c ? STATUS_TONE[c.status] : 'bg-slate-50 text-slate-300'} ${inactive ? 'opacity-30' : ''}`}
                          onClick={() => mark.mutate({ date: d, entries: [{ employeeId: e.id, status: nextStatus(c?.status), hours: c?.hours ?? null, overtimeHours: c?.overtimeHours ?? null, notes: c?.notes ?? null }] })}
                          onContextMenu={(ev) => {
                            ev.preventDefault()
                            if (!disabled) setCellEdit({ employeeId: e.id, name: e.name, date: d, cell: c })
                          }}
                        >
                          {c ? STATUS_SHORT[c.status] : '·'}
                          {c && toNumber(c.overtimeHours) > 0 ? <span className="align-super text-[8px]">+</span> : null}
                        </button>
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {canMark && g && !g.locked && g.employees.length > 0 && (
        <div className="mt-3">
          <p className="mb-1 text-xs text-slate-500">Need hours or overtime for a day? Pick the staff member and date here.</p>
          <HoursPicker employees={g.employees.filter((e) => e.active)} month={month} onPick={(employeeId, name, date) => setCellEdit({ employeeId, name, date, cell: cellMap.get(`${employeeId}|${date}`) })} />
        </div>
      )}
      <div className="mt-3 flex flex-wrap gap-2 text-xs">
        {STATUS_ORDER.map((s) => (
          <span key={s} className={`rounded px-2 py-0.5 ${STATUS_TONE[s]}`}>{STATUS_SHORT[s]} = {s.replace('_', ' ').toLowerCase()}</span>
        ))}
      </div>
      {cellEdit && (
        <HoursModal
          info={cellEdit}
          onClose={() => setCellEdit(null)}
          onSave={async (b) => {
            await mark.mutateAsync({ date: cellEdit.date, entries: [{ employeeId: cellEdit.employeeId, ...b }] })
            setCellEdit(null)
          }}
        />
      )}
    </div>
  )
}

function HoursPicker({ employees, month, onPick }: { employees: AttendanceGrid['employees']; month: string; onPick: (id: number, name: string, date: string) => void }) {
  const [emp, setEmp] = useState('')
  const [day, setDay] = useState(todayIso().slice(0, 7) === month.slice(0, 7) ? todayIso() : month)
  const e = employees.find((x) => String(x.id) === emp)
  return (
    <div className="flex flex-wrap items-end gap-2">
      <select className="input w-auto" value={emp} onChange={(ev) => setEmp(ev.target.value)} aria-label="Staff">
        <option value="">Staff…</option>
        {employees.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
      </select>
      <input type="date" className="input w-auto" value={day} min={month} max={month.slice(0, 8) + String(daysInMonth(month))} onChange={(ev) => setDay(ev.target.value)} />
      <button className="btn-ghost" disabled={!e} onClick={() => e && onPick(e.id, e.name, day)}>Hours / overtime</button>
    </div>
  )
}

function HoursModal({
  info, onClose, onSave,
}: {
  info: { employeeId: number; name: string; date: string; cell?: AttendanceCell }
  onClose: () => void
  onSave: (b: { status: string; hours: string | null; overtimeHours: string | null; notes: string | null }) => Promise<unknown>
}) {
  const [status, setStatus] = useState(info.cell?.status ?? 'PRESENT')
  const [hours, setHours] = useState(info.cell?.hours ?? '')
  const [ot, setOt] = useState(info.cell && toNumber(info.cell.overtimeHours) > 0 ? info.cell.overtimeHours : '')
  const [notes, setNotes] = useState(info.cell?.notes ?? '')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<unknown>(null)
  return (
    <Modal title={`${info.name} · ${dmy(info.date)}`} onClose={onClose}>
      <div className="space-y-3">
        <Field label="Status">
          <select className="input" value={status} onChange={(e) => setStatus(e.target.value)}>
            {STATUS_ORDER.map((s) => <option key={s} value={s}>{s.replace('_', ' ')}</option>)}
          </select>
        </Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Hours worked"><input className="input" inputMode="decimal" value={hours} onChange={(e) => setHours(e.target.value)} /></Field>
          <Field label="Overtime hours"><input className="input" inputMode="decimal" value={ot} onChange={(e) => setOt(e.target.value)} /></Field>
        </div>
        <Field label="Notes"><input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <ErrorNote error={err} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button
            className="btn-primary"
            disabled={busy}
            onClick={async () => {
              setBusy(true)
              setErr(null)
              try {
                await onSave({ status, hours: hours || null, overtimeHours: ot || null, notes: notes.trim() || null })
              } catch (e) {
                setErr(e)
              } finally {
                setBusy(false)
              }
            }}
          >
            Save
          </button>
        </div>
      </div>
    </Modal>
  )
}

// ------------------------------------------------------------------ advances

function AdvancesTab({ canManage }: { canManage: boolean }) {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['hr-advances'], queryFn: () => get<AdvanceSummary[]>('/hr/advances') })
  const [adding, setAdding] = useState(false)
  const [voidFor, setVoidFor] = useState<Advance | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: ['hr-advances'] })
  return (
    <div>
      <Notice>Advances are recovered from salary automatically when a pay run is generated, oldest advance first, never more than the net pay.</Notice>
      {canManage && <button className="btn-primary mb-3" onClick={() => setAdding(true)}>+ Give advance</button>}
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      {q.data && q.data.length === 0 && <Empty>No advances recorded.</Empty>}
      <div className="space-y-2">
        {q.data?.map((s) => (
          <div key={s.employeeId} className="card">
            <div className="flex justify-between">
              <div className="font-semibold">{s.employeeName}</div>
              <div className="text-right">
                <div className="font-semibold tabular-nums">{inr(s.outstanding)}</div>
                <div className="text-xs text-slate-500">outstanding</div>
              </div>
            </div>
            <div className="text-xs text-slate-500">
              Advanced {inr(s.totalAdvanced)} · recovered {inr(s.recovered)} · next instalment {inr(s.monthlyInstallment)}
            </div>
            <ul className="mt-2 space-y-1">
              {s.advances.map((a) => (
                <li key={a.id} className={`flex items-center justify-between gap-2 rounded bg-slate-50 px-2 py-1 text-sm ${a.voidState !== 'ACTIVE' ? 'line-through opacity-60' : ''}`}>
                  <span>
                    {dmy(a.txnDate)} · {inr(a.amount)}
                    <span className="text-xs text-slate-500"> · {inr(a.recoverPerMonth)}/month · left {inr(a.outstanding)}</span>
                    {a.notes && <span className="text-xs text-slate-500"> · {a.notes}</span>}
                  </span>
                  {canManage && a.voidState === 'ACTIVE' && <button className="text-xs text-red-600" onClick={() => setVoidFor(a)}>Void</button>}
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
      {adding && <AdvanceForm onClose={() => setAdding(false)} onSaved={refresh} />}
      {voidFor && (
        <ReasonDialog
          title="Void advance"
          confirmLabel="Void"
          danger
          onClose={() => setVoidFor(null)}
          onConfirm={async (reason) => {
            await post(`/hr/advances/${voidFor.id}/void`, { reason })
            refresh()
          }}
        />
      )}
    </div>
  )
}

function AdvanceForm({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const emps = useQuery({ queryKey: ['hr-employees', false], queryFn: () => get<Employee[]>('/hr/employees') })
  const [emp, setEmp] = useState('')
  const [date, setDate] = useState(todayIso())
  const [amount, setAmount] = useState('')
  const [per, setPer] = useState('')
  const [notes, setNotes] = useState('')
  const save = useMutation({
    mutationFn: () => post('/hr/advances', { employeeId: Number(emp), txnDate: date, amount, recoverPerMonth: per, notes: notes.trim() || null }),
    onSuccess: () => (onSaved(), onClose()),
  })
  return (
    <Modal title="Give advance" onClose={onClose}>
      <div className="space-y-3">
        <Field label="Staff member">
          <select className="input" value={emp} onChange={(e) => setEmp(e.target.value)}>
            <option value="">Choose…</option>
            {emps.data?.map((e) => <option key={e.id} value={e.id}>{e.name}</option>)}
          </select>
        </Field>
        <Field label="Date"><input type="date" className="input" value={date} onChange={(e) => setDate(e.target.value)} /></Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Amount (Rs)"><input className="input" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
          <Field label="Recover per month (Rs)"><input className="input" inputMode="decimal" value={per} onChange={(e) => setPer(e.target.value)} /></Field>
        </div>
        <Field label="Notes"><input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!emp || toNumber(amount) <= 0 || toNumber(per) <= 0 || save.isPending} onClick={() => save.mutate()}>Save</button>
        </div>
      </div>
    </Modal>
  )
}

// ------------------------------------------------------------------ runs

function runTone(s: string): 'amber' | 'sky' | 'green' {
  return s === 'DRAFT' ? 'amber' : s === 'APPROVED' ? 'sky' : 'green'
}

function RunsTab({ canManage }: { canManage: boolean }) {
  const qc = useQueryClient()
  const [openId, setOpenId] = useState<number | null>(null)
  const [month, setMonth] = useState(monthStart(addMonths(todayIso(), -1)))
  const q = useQuery({ queryKey: ['hr-runs'], queryFn: () => get<PayrollRun[]>('/hr/payroll-runs') })
  const gen = useMutation({
    mutationFn: () => post<PayrollRunDetail>('/hr/payroll-runs', { month }),
    onSuccess: (d) => {
      qc.invalidateQueries({ queryKey: ['hr-runs'] })
      setOpenId(d.run.id)
    },
  })
  if (openId !== null) return <RunDetail id={openId} canManage={canManage} onBack={() => setOpenId(null)} />
  return (
    <div>
      {canManage && (
        <div className="card mb-3">
          <div className="mb-2 text-sm font-semibold">Start a pay run</div>
          <div className="flex flex-wrap items-end gap-2">
            <Field label="Month">
              <input type="month" className="input" value={month.slice(0, 7)} onChange={(e) => e.target.value && setMonth(e.target.value + '-01')} />
            </Field>
            <button className="btn-primary" disabled={gen.isPending} onClick={() => gen.mutate()}>Generate draft</button>
          </div>
          <p className="mt-2 text-xs text-slate-500">Generate runs in month order so advance recovery carries forward correctly.</p>
          <ErrorNote error={gen.error} />
        </div>
      )}
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      {q.data && q.data.length === 0 && <Empty>No pay runs yet.</Empty>}
      <div className="space-y-2">
        {q.data?.map((r) => (
          <button key={r.id} className="card w-full text-left" onClick={() => setOpenId(r.id)}>
            <div className="flex justify-between gap-2">
              <div>
                <div className="font-semibold">{monthLabel(r.month)}</div>
                <div className="mt-1 flex gap-1">
                  <Pill tone={runTone(r.status)}>{r.status}</Pill>
                  {r.voidState !== 'ACTIVE' && <Pill>VOID</Pill>}
                </div>
              </div>
              <div className="text-right">
                <div className="font-semibold tabular-nums">{inr(r.totalNet)}</div>
                <div className="text-xs text-slate-500">net · {r.payslipCount} staff</div>
              </div>
            </div>
          </button>
        ))}
      </div>
    </div>
  )
}

function RunDetail({ id, canManage, onBack }: { id: number; canManage: boolean; onBack: () => void }) {
  const qc = useQueryClient()
  const key = ['hr-run', id]
  const q = useQuery({ queryKey: key, queryFn: () => get<PayrollRunDetail>(`/hr/payroll-runs/${id}`) })
  const [dlg, setDlg] = useState<'regen' | 'reopen' | 'void' | 'pay' | null>(null)
  const [edit, setEdit] = useState<Payslip | null>(null)
  const apply = (d: PayrollRunDetail) => {
    qc.setQueryData(key, d)
    qc.invalidateQueries({ queryKey: ['hr-runs'] })
    qc.invalidateQueries({ queryKey: ['hr-advances'] })
    qc.invalidateQueries({ queryKey: ['hr-attendance'] })
  }
  const approve = useMutation({ mutationFn: () => post<PayrollRunDetail>(`/hr/payroll-runs/${id}/approve`), onSuccess: apply })
  const d = q.data
  const active = d?.run.voidState === 'ACTIVE'
  return (
    <div>
      <button className="btn-ghost mb-2" onClick={onBack}>‹ All runs</button>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      <ErrorNote error={approve.error} />
      {d && (
        <>
          <div className="card mb-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <div className="text-lg font-semibold">{monthLabel(d.run.month)}</div>
                <div className="mt-1 flex gap-1">
                  <Pill tone={runTone(d.run.status)}>{d.run.status}</Pill>
                  {!active && <Pill>VOID</Pill>}
                </div>
              </div>
              <div className="text-right">
                <div className="text-xl font-semibold tabular-nums">{inr(d.run.totalNet)}</div>
                <div className="text-xs text-slate-500">gross {inr(d.run.totalGross)} − deductions {inr(d.run.totalDeductions)}</div>
              </div>
            </div>
            {d.run.paidOn && <p className="mt-1 text-xs text-slate-500">Paid {dmy(d.run.paidOn)} by {d.run.paymentMethod}. An expense entry was created under "Staff salary".</p>}
            {d.run.voidReason && <p className="mt-1 text-xs text-red-700">Voided: {d.run.voidReason}</p>}
            {canManage && active && (
              <div className="mt-3 flex flex-wrap gap-2">
                {d.run.status === 'DRAFT' && (
                  <>
                    <button className="btn-ghost" onClick={() => setDlg('regen')}>Regenerate</button>
                    <button className="btn-primary" disabled={approve.isPending} onClick={() => approve.mutate()}>Approve</button>
                  </>
                )}
                {d.run.status === 'APPROVED' && (
                  <>
                    <button className="btn-ghost" onClick={() => setDlg('reopen')}>Reopen</button>
                    <button className="btn-primary" onClick={() => setDlg('pay')}>Mark paid</button>
                  </>
                )}
                {d.run.status !== 'PAID' && <button className="btn-danger" onClick={() => setDlg('void')}>Void run</button>}
              </div>
            )}
          </div>
          {d.notes.map((n) => <Notice key={n}>{n}</Notice>)}
          <div className="space-y-2">
            {d.payslips.map((p) => (
              <div key={p.id} className="card">
                <div className="flex justify-between gap-2">
                  <div>
                    <div className="font-semibold">{p.employeeName}</div>
                    <div className="text-xs text-slate-500">{SALARY_LABEL[p.salaryType]} {inr(p.baseRate)} · {p.paidDays} paid days{toNumber(p.overtimeHours) > 0 ? ` · ${p.overtimeHours} h overtime` : ''}</div>
                  </div>
                  <div className="text-right font-semibold tabular-nums">{inr(p.netPay)}</div>
                </div>
                <div className="mt-2 grid grid-cols-2 gap-x-4 text-xs sm:grid-cols-4">
                  <Row label="Gross" value={inr(p.gross)} />
                  <Row label="Overtime" value={inr(p.overtimePay)} />
                  <Row label="Advance" value={`− ${inr(p.advanceRecovery)}`} />
                  <Row label="Other ded." value={`− ${inr(p.otherDeductions)}`} />
                </div>
                {p.edited && <div className="mt-1"><Pill tone="sky">Edited: {p.editReason}</Pill></div>}
                {p.calcBreakdown.warnings?.map((w) => <p key={w} className="mt-1 text-xs text-amber-800">{w}</p>)}
                <div className="mt-2 flex gap-2">
                  <Link className="btn-ghost" to={`/payroll/payslip/${p.id}`}>View / print</Link>
                  {canManage && active && d.run.status === 'DRAFT' && <button className="btn-ghost" onClick={() => setEdit(p)}>Adjust</button>}
                </div>
              </div>
            ))}
          </div>
        </>
      )}
      {dlg === 'regen' && (
        <ReasonDialog title="Regenerate draft" confirmLabel="Regenerate" onClose={() => setDlg(null)} onConfirm={async (reason) => apply(await post<PayrollRunDetail>(`/hr/payroll-runs/${id}/regenerate`, { reason }))} />
      )}
      {dlg === 'reopen' && (
        <ReasonDialog title="Reopen run" confirmLabel="Reopen" onClose={() => setDlg(null)} onConfirm={async (reason) => apply(await post<PayrollRunDetail>(`/hr/payroll-runs/${id}/reopen`, { reason }))} />
      )}
      {dlg === 'void' && (
        <ReasonDialog title="Void pay run" confirmLabel="Void" danger onClose={() => setDlg(null)} onConfirm={async (reason) => apply(await post<PayrollRunDetail>(`/hr/payroll-runs/${id}/void`, { reason }))} />
      )}
      {dlg === 'pay' && d && <PayForm total={d.run.totalNet} onClose={() => setDlg(null)} onPaid={apply} id={id} />}
      {edit && <PayslipEditor payslip={edit} onClose={() => setEdit(null)} onSaved={apply} />}
    </div>
  )
}

function PayForm({ id, total, onClose, onPaid }: { id: number; total: string; onClose: () => void; onPaid: (d: PayrollRunDetail) => void }) {
  const [on, setOn] = useState(todayIso())
  const [method, setMethod] = useState('CASH')
  const save = useMutation({
    mutationFn: () => post<PayrollRunDetail>(`/hr/payroll-runs/${id}/pay`, { paidOn: on, paymentMethod: method }),
    onSuccess: (d) => (onPaid(d), onClose()),
  })
  return (
    <Modal title="Mark salaries paid" onClose={onClose}>
      <p className="mb-3 text-sm">Records that {inr(total)} was paid and creates one "Staff salary" expense for that day.</p>
      <div className="space-y-3">
        <Field label="Paid on"><input type="date" className="input" value={on} onChange={(e) => setOn(e.target.value)} /></Field>
        <Field label="Paid by">
          <select className="input" value={method} onChange={(e) => setMethod(e.target.value)}>
            {['CASH', 'UPI', 'CARD', 'BANK', 'OTHER'].map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
        </Field>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={save.isPending} onClick={() => save.mutate()}>Mark paid</button>
        </div>
      </div>
    </Modal>
  )
}

function PayslipEditor({ payslip: p, onClose, onSaved }: { payslip: Payslip; onClose: () => void; onSaved: (d: PayrollRunDetail) => void }) {
  const [paidDays, setPaidDays] = useState(p.paidDays)
  const [hours, setHours] = useState(p.hoursWorked ?? '')
  const [ot, setOt] = useState(p.overtimeHours)
  const [add, setAdd] = useState(p.otherAdditions)
  const [addL, setAddL] = useState(p.otherAdditionsLabel ?? '')
  const [adv, setAdv] = useState(p.advanceRecovery)
  const [ded, setDed] = useState(p.otherDeductions)
  const [dedL, setDedL] = useState(p.otherDeductionsLabel ?? '')
  const [notes, setNotes] = useState(p.notes ?? '')
  const [reason, setReason] = useState('')
  const save = useMutation({
    mutationFn: () =>
      put<PayrollRunDetail>(`/hr/payslips/${p.id}`, {
        paidDays, hoursWorked: hours || null, overtimeHours: ot, otherAdditions: add, otherAdditionsLabel: addL.trim() || null,
        advanceRecovery: adv, otherDeductions: ded, otherDeductionsLabel: dedL.trim() || null, notes: notes.trim() || null, reason: reason.trim(),
      }),
    onSuccess: (d) => (onSaved(d), onClose()),
  })
  const num = (label: string, v: string, set: (s: string) => void) => (
    <Field label={label}><input className="input" inputMode="decimal" value={v} onChange={(e) => set(e.target.value)} /></Field>
  )
  return (
    <Modal title={`Adjust ${p.employeeName}`} onClose={onClose}>
      <div className="space-y-3">
        <div className="grid grid-cols-3 gap-2">
          {num('Paid days', paidDays, setPaidDays)}
          {num('Hours', hours, setHours)}
          {num('OT hours', ot, setOt)}
        </div>
        <div className="grid grid-cols-2 gap-2">
          {num('Other additions (Rs)', add, setAdd)}
          <Field label="Label"><input className="input" value={addL} onChange={(e) => setAddL(e.target.value)} /></Field>
        </div>
        {num('Advance recovery (Rs)', adv, setAdv)}
        <div className="grid grid-cols-2 gap-2">
          {num('Other deductions (Rs)', ded, setDed)}
          <Field label="Label (e.g. PF, ESI)"><input className="input" value={dedL} onChange={(e) => setDedL(e.target.value)} /></Field>
        </div>
        <Field label="Notes"><input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <Field label="Reason for adjusting (required)"><input className="input" value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!reason.trim() || save.isPending} onClick={() => save.mutate()}>Save</button>
        </div>
      </div>
    </Modal>
  )
}

// ------------------------------------------------------------------ printable payslip

export function PayslipPage() {
  const { id } = useParams()
  const q = useQuery({ queryKey: ['hr-payslip', id], queryFn: () => get<PayslipView>(`/hr/payslips/${id}`), enabled: !!id })
  const v = q.data
  const p = v?.payslip
  return (
    <div>
      <div className="mb-3 flex gap-2 print:hidden">
        <Link className="btn-ghost" to="/payroll?tab=runs">‹ Back</Link>
        <button className="btn-primary" onClick={() => window.print()}>Print</button>
      </div>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      {v && p && (
        <div className="card mx-auto max-w-xl">
          <div className="border-b border-slate-200 pb-2 text-center">
            <div className="text-lg font-bold">{v.business.name}</div>
            {v.business.address && <div className="text-xs text-slate-500">{v.business.address}</div>}
            <div className="mt-1 text-sm font-semibold">Salary slip - {v.monthLabel}</div>
            <div className="text-xs text-slate-500">
              {v.runStatus === 'PAID' ? `Paid ${dmy(v.paidOn)}` : <b>{v.runStatus} - not yet paid</b>}
            </div>
          </div>
          <div className="py-2">
            <Row label="Name" value={p.employeeName} />
            <Row label="Designation" value={p.designation ?? '—'} />
            <Row label={`Pay basis (${SALARY_LABEL[p.salaryType]})`} value={inr(p.baseRate)} />
            <Row label="Paid days" value={p.paidDays} />
            {p.hoursWorked !== null && <Row label="Hours worked" value={p.hoursWorked} />}
          </div>
          <div className="border-t border-slate-200 py-2">
            <Row label="Gross pay" value={inr(p.gross)} />
            <Row label={`Overtime (${p.overtimeHours} h)`} value={inr(p.overtimePay)} />
            {toNumber(p.otherAdditions) !== 0 && <Row label={p.otherAdditionsLabel ?? 'Other additions'} value={inr(p.otherAdditions)} />}
            <Row label="Advance recovery" value={`− ${inr(p.advanceRecovery)}`} />
            {toNumber(p.otherDeductions) !== 0 && <Row label={p.otherDeductionsLabel ?? 'Other deductions'} value={`− ${inr(p.otherDeductions)}`} />}
            <Row strong label="Net pay" value={inr(p.netPay)} />
          </div>
          {p.edited && <p className="text-xs text-slate-500">Adjusted by the owner: {p.editReason}</p>}
          {v.notes.map((n) => <p key={n} className="mt-1 text-xs text-slate-500">{n}</p>)}
        </div>
      )}
    </div>
  )
}
