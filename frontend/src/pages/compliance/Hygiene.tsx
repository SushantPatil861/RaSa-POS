import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post, put, qs } from '../../api'
import { dateTime, dmy, todayIso, toNumber, addDays } from '../../format'
import { ErrorNote, Field, Loading, Modal, PageTitle, ReasonDialog } from '../../components/ui'
import { useAuth } from '../../auth'
import type { HygieneChecklist, HygieneLog, HygieneMissed, HygieneToday, TempLog, TempPoint } from '../../types.phase4'
import { Banner, Empty, Notice, Pill, TabBar, useTab } from './shared'

const FREQ_LABEL: Record<string, string> = { DAILY: 'Daily', WEEKLY: 'Weekly', MONTHLY: 'Monthly' }

/** True when a reading is outside the allowed band (used only to prompt for a corrective action before sending). */
export function outOfRange(temp: string, min: string, max: string): boolean {
  if (temp.trim() === '' || temp.trim() === '-' || Number.isNaN(Number(temp))) return false
  const t = Number(temp)
  return t < Number(min) || t > Number(max)
}

export default function Hygiene() {
  const { can } = useAuth()
  const manage = can('hygiene.manage')
  const log = can('hygiene.log')
  const [tab, setTab] = useTab(['today', 'temps', 'report', ...(manage ? ['setup'] : [])])
  return (
    <div>
      <PageTitle title="Hygiene & temperature" />
      <TabBar
        tabs={[
          { id: 'today', label: 'Checklists' },
          { id: 'temps', label: 'Temperatures' },
          { id: 'report', label: 'Report' },
          ...(manage ? [{ id: 'setup', label: 'Setup' }] : []),
        ]}
        value={tab}
        onChange={setTab}
      />
      {tab === 'today' && <TodayTab canLog={log} canManage={manage} />}
      {tab === 'temps' && <TempTab canLog={log} canManage={manage} />}
      {tab === 'report' && <ReportTab />}
      {tab === 'setup' && manage && <SetupTab />}
    </div>
  )
}

// ------------------------------------------------------------------ today

function TodayTab({ canLog, canManage }: { canLog: boolean; canManage: boolean }) {
  const qc = useQueryClient()
  const [open, setOpen] = useState<HygieneToday | null>(null)
  const [voidId, setVoidId] = useState<number | null>(null)
  const q = useQuery({ queryKey: ['hyg-today'], queryFn: () => get<HygieneToday[]>('/hygiene/today') })
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['hyg-today'] })
    qc.invalidateQueries({ queryKey: ['hyg-logs'] })
  }
  const signOff = useMutation({ mutationFn: (id: number) => post(`/hygiene/logs/${id}/sign-off`), onSuccess: refresh })
  return (
    <div>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      <ErrorNote error={signOff.error} />
      {q.data && q.data.length === 0 && <Empty>No checklists are set up.</Empty>}
      <div className="space-y-2">
        {q.data?.map((t) => (
          <div key={t.checklistId} className="card">
            <div className="flex items-center justify-between gap-2">
              <div>
                <div className="font-semibold">{t.name}</div>
                <div className="text-xs text-slate-500">{FREQ_LABEL[t.frequency] ?? t.frequency} · {t.period}{t.expectedShift ? ` · ${t.expectedShift.toLowerCase()}` : ''}</div>
              </div>
              {t.done ? <Pill tone={t.allOk ? 'green' : 'amber'}>{t.allOk ? 'Done - all OK' : 'Done - issues noted'}</Pill> : <Pill tone="red">Not done</Pill>}
            </div>
            {t.done && <p className="mt-1 text-xs text-slate-500">By {t.completedByName ?? '—'} at {dateTime(t.completedAt)}</p>}
            <div className="mt-2 flex flex-wrap gap-2">
              {!t.done && canLog && <button className="btn-primary" onClick={() => setOpen(t)}>Start checklist</button>}
              {t.done && t.needsSignOff && canManage && t.logId !== null && (
                <button className="btn-primary" disabled={signOff.isPending} onClick={() => signOff.mutate(t.logId as number)}>Sign off</button>
              )}
              {t.done && canManage && t.logId !== null && <button className="btn-danger" onClick={() => setVoidId(t.logId)}>Void log</button>}
            </div>
          </div>
        ))}
      </div>
      <RecentLogs />
      {open && <ChecklistRun today={open} onClose={() => setOpen(null)} onSaved={refresh} />}
      {voidId !== null && (
        <ReasonDialog
          title="Void checklist log"
          confirmLabel="Void"
          danger
          onClose={() => setVoidId(null)}
          onConfirm={async (reason) => {
            await post(`/hygiene/logs/${voidId}/void`, { reason })
            refresh()
          }}
        />
      )}
    </div>
  )
}

function RecentLogs() {
  const from = addDays(todayIso(), -6)
  const q = useQuery({ queryKey: ['hyg-logs', from], queryFn: () => get<HygieneLog[]>(`/hygiene/logs${qs({ from, to: todayIso() })}`) })
  if (!q.data || q.data.length === 0) return null
  return (
    <div className="mt-5">
      <h3 className="mb-2 text-sm font-semibold text-slate-600">Last 7 days</h3>
      <div className="space-y-1">
        {q.data.map((l) => {
          const bad = l.results.filter((r) => !r.ok)
          return (
            <div key={l.id} className="rounded-lg bg-white p-2 text-sm ring-1 ring-slate-200">
              <div className="flex justify-between gap-2">
                <span>{dmy(l.logDate)} · {l.checklistName}</span>
                <Pill tone={l.allOk ? 'green' : 'amber'}>{l.allOk ? 'OK' : `${bad.length} issue${bad.length === 1 ? '' : 's'}`}</Pill>
              </div>
              {bad.map((b) => <p key={b.itemId} className="text-xs text-amber-800">{b.text ?? `Item ${b.itemId}`}: {b.note}</p>)}
              {l.signedOffByName && <p className="text-xs text-slate-500">Signed off by {l.signedOffByName}</p>}
            </div>
          )
        })}
      </div>
    </div>
  )
}

function ChecklistRun({ today, onClose, onSaved }: { today: HygieneToday; onClose: () => void; onSaved: () => void }) {
  const lists = useQuery({ queryKey: ['hyg-checklists', false], queryFn: () => get<HygieneChecklist[]>('/hygiene/checklists') })
  const [answers, setAnswers] = useState<Record<number, { ok: boolean; note: string }>>({})
  const [notes, setNotes] = useState('')
  const c = lists.data?.find((x) => x.id === today.checklistId)
  const items = (c?.items ?? []).filter((i) => i.active)
  const save = useMutation({
    mutationFn: () =>
      post('/hygiene/logs', {
        checklistId: today.checklistId, logDate: todayIso(), shift: today.expectedShift ?? undefined,
        results: items.map((i) => ({ itemId: i.id, ok: answers[i.id].ok, note: answers[i.id].note.trim() || null })),
        notes: notes.trim() || null,
      }),
    onSuccess: () => (onSaved(), onClose()),
  })
  const complete = items.length > 0 && items.every((i) => answers[i.id] && (answers[i.id].ok || answers[i.id].note.trim()))
  const set = (id: number, ok: boolean) => setAnswers((a) => ({ ...a, [id]: { ok, note: a[id]?.note ?? '' } }))
  return (
    <Modal title={today.name} onClose={onClose}>
      {lists.isLoading && <Loading />}
      <ErrorNote error={lists.error} />
      <div className="space-y-3">
        {items.map((i) => {
          const a = answers[i.id]
          return (
            <div key={i.id} className="rounded-lg bg-slate-50 p-3">
              <div className="mb-2 text-sm">{i.text}</div>
              <div className="grid grid-cols-2 gap-2">
                <button className={`rounded-lg py-3 text-base font-semibold ${a?.ok === true ? 'bg-emerald-600 text-white' : 'bg-white ring-1 ring-slate-300'}`} onClick={() => set(i.id, true)}>
                  ✓ OK
                </button>
                <button className={`rounded-lg py-3 text-base font-semibold ${a?.ok === false ? 'bg-red-600 text-white' : 'bg-white ring-1 ring-slate-300'}`} onClick={() => set(i.id, false)}>
                  ✗ Not OK
                </button>
              </div>
              {a && !a.ok && (
                <input
                  className="input mt-2"
                  placeholder="What is wrong? (required)"
                  value={a.note}
                  onChange={(e) => setAnswers((x) => ({ ...x, [i.id]: { ok: false, note: e.target.value } }))}
                />
              )}
            </div>
          )
        })}
        <Field label="Overall notes (optional)"><input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!complete || save.isPending} onClick={() => save.mutate()}>Save checklist</button>
        </div>
      </div>
    </Modal>
  )
}

// ------------------------------------------------------------------ temperatures

function TempTab({ canLog, canManage }: { canLog: boolean; canManage: boolean }) {
  const qc = useQueryClient()
  const [logFor, setLogFor] = useState<TempPoint | null>(null)
  const [voidId, setVoidId] = useState<number | null>(null)
  const points = useQuery({ queryKey: ['hyg-points', false], queryFn: () => get<TempPoint[]>('/hygiene/temperature-points') })
  const from = addDays(todayIso(), -2)
  const logs = useQuery({ queryKey: ['hyg-temp-logs', from], queryFn: () => get<TempLog[]>(`/hygiene/temperature-logs${qs({ from, to: todayIso() })}`) })
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['hyg-points'] })
    qc.invalidateQueries({ queryKey: ['hyg-temp-logs'] })
  }
  return (
    <div>
      <Notice>Read the thermometer and type exactly what it shows. A reading outside the safe range needs a note on what you did about it.</Notice>
      {points.isLoading && <Loading />}
      <ErrorNote error={points.error} />
      {points.data && points.data.length === 0 && <Empty>No freezers or chillers are set up.</Empty>}
      <div className="grid gap-2 sm:grid-cols-2">
        {points.data?.map((p) => (
          <div key={p.id} className="card">
            <div className="flex justify-between gap-2">
              <div>
                <div className="font-semibold">{p.name}</div>
                <div className="text-xs text-slate-500">Safe range {p.minC} to {p.maxC} °C</div>
              </div>
              {p.lastTempC !== null && <Pill tone={p.lastInRange ? 'green' : 'red'}>{p.lastTempC} °C</Pill>}
            </div>
            <p className="mt-1 text-xs text-slate-500">{p.lastLoggedAt ? `Last read ${dateTime(p.lastLoggedAt)}` : 'No reading yet'}</p>
            {canLog && <button className="btn-primary mt-2 w-full py-3" onClick={() => setLogFor(p)}>Log temperature</button>}
          </div>
        ))}
      </div>
      <h3 className="mb-2 mt-5 text-sm font-semibold text-slate-600">Last 3 days</h3>
      {logs.isLoading && <Loading />}
      <ErrorNote error={logs.error} />
      <div className="space-y-1">
        {logs.data?.map((l) => (
          <div key={l.id} className={`rounded-lg bg-white p-2 text-sm ring-1 ${l.inRange ? 'ring-slate-200' : 'ring-red-300'}`}>
            <div className="flex justify-between gap-2">
              <span>{l.pointName} · {dateTime(l.loggedAt)}</span>
              <span className="flex items-center gap-2">
                <Pill tone={l.inRange ? 'green' : 'red'}>{l.tempC} °C</Pill>
                {canManage && <button className="text-xs text-red-600" onClick={() => setVoidId(l.id)}>Void</button>}
              </span>
            </div>
            {l.correctiveAction && <p className="text-xs text-amber-800">Action taken: {l.correctiveAction}</p>}
            <p className="text-xs text-slate-500">by {l.loggedByName ?? '—'}</p>
          </div>
        ))}
        {logs.data && logs.data.length === 0 && <Empty>No readings in the last 3 days.</Empty>}
      </div>
      {logFor && <TempForm point={logFor} onClose={() => setLogFor(null)} onSaved={refresh} />}
      {voidId !== null && (
        <ReasonDialog
          title="Void temperature reading"
          confirmLabel="Void"
          danger
          onClose={() => setVoidId(null)}
          onConfirm={async (reason) => {
            await post(`/hygiene/temperature-logs/${voidId}/void`, { reason })
            refresh()
          }}
        />
      )}
    </div>
  )
}

function TempForm({ point, onClose, onSaved }: { point: TempPoint; onClose: () => void; onSaved: () => void }) {
  const [temp, setTemp] = useState('')
  const [action, setAction] = useState('')
  const [notes, setNotes] = useState('')
  const bad = outOfRange(temp, point.minC, point.maxC)
  const save = useMutation({
    mutationFn: () => post('/hygiene/temperature-logs', { pointId: point.id, tempC: temp, correctiveAction: bad ? action.trim() : null, notes: notes.trim() || null }),
    onSuccess: () => (onSaved(), onClose()),
  })
  const flipSign = () => setTemp((t) => (t.startsWith('-') ? t.slice(1) : '-' + t))
  const valid = temp.trim() !== '' && !Number.isNaN(Number(temp)) && (!bad || action.trim())
  return (
    <Modal title={point.name} onClose={onClose}>
      <div className="space-y-3">
        <div className="flex items-end gap-2">
          <div className="flex-1">
            <Field label="Temperature (°C)">
              <input className="input text-2xl" inputMode="decimal" autoFocus value={temp} onChange={(e) => setTemp(e.target.value)} />
            </Field>
          </div>
          <button className="btn-ghost h-12 w-14 text-xl" onClick={flipSign} aria-label="Switch minus sign">±</button>
        </div>
        <p className="text-xs text-slate-500">Safe range {point.minC} to {point.maxC} °C</p>
        {bad && (
          <>
            <Banner tone="red">Outside the safe range. Write what you did (moved stock, called for repair, checked the door...).</Banner>
            <Field label="Corrective action (required)"><input className="input" value={action} onChange={(e) => setAction(e.target.value)} /></Field>
          </>
        )}
        <Field label="Notes (optional)"><input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!valid || save.isPending} onClick={() => save.mutate()}>Save reading</button>
        </div>
      </div>
    </Modal>
  )
}

// ------------------------------------------------------------------ report

function ReportTab() {
  const [from, setFrom] = useState(addDays(todayIso(), -29))
  const [to, setTo] = useState(todayIso())
  const q = useQuery({ queryKey: ['hyg-report', from, to], queryFn: () => get<HygieneMissed>(`/hygiene/reports/missed${qs({ from, to })}`) })
  const r = q.data
  const pct = (v: string | null) => (v === null ? 'n/a' : `${toNumber(v).toFixed(0)}%`)
  return (
    <div>
      <div className="mb-3 flex flex-wrap items-end gap-2">
        <Field label="From"><input type="date" className="input" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
        <Field label="To"><input type="date" className="input" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
      </div>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      {r && (
        <>
          <div className="mb-3 grid grid-cols-3 gap-2">
            <div className="card text-center"><div className="text-xs text-slate-500">Overall</div><div className="text-xl font-semibold">{pct(r.overallCompliancePct)}</div></div>
            <div className="card text-center"><div className="text-xs text-slate-500">Checklists</div><div className="text-xl font-semibold">{pct(r.checklistCompliancePct)}</div></div>
            <div className="card text-center"><div className="text-xs text-slate-500">Temperatures</div><div className="text-xl font-semibold">{pct(r.temperatureCompliancePct)}</div></div>
          </div>
          <h3 className="mb-1 text-sm font-semibold">Checklists</h3>
          <div className="mb-3 space-y-2">
            {r.checklists.map((c) => (
              <div key={c.checklistId} className="card">
                <div className="flex justify-between"><span className="font-medium">{c.name}</span><span className="tabular-nums">{c.completed}/{c.expected} · {pct(c.compliancePct)}</span></div>
                {c.missed.length > 0 && <p className="mt-1 text-xs text-red-700">Missed: {c.missed.slice(0, 20).map(dmy).join(', ')}{c.missed.length > 20 ? ` and ${c.missed.length - 20} more` : ''}</p>}
              </div>
            ))}
          </div>
          <h3 className="mb-1 text-sm font-semibold">Temperature points</h3>
          <div className="mb-3 space-y-2">
            {r.points.map((p) => (
              <div key={p.pointId} className="card">
                <div className="flex justify-between"><span className="font-medium">{p.name}</span><span className="tabular-nums">{p.loggedDays}/{p.expectedDays} days · {pct(p.compliancePct)}</span></div>
                {p.missedDays.length > 0 && <p className="mt-1 text-xs text-red-700">No reading: {p.missedDays.slice(0, 20).map(dmy).join(', ')}{p.missedDays.length > 20 ? ` and ${p.missedDays.length - 20} more` : ''}</p>}
              </div>
            ))}
          </div>
          <h3 className="mb-1 text-sm font-semibold">Out-of-range readings</h3>
          {r.outOfRange.length === 0 && <Empty>None in this period.</Empty>}
          <div className="space-y-1">
            {r.outOfRange.map((l) => (
              <div key={l.id} className="rounded-lg bg-white p-2 text-sm ring-1 ring-red-200">
                {dateTime(l.loggedAt)} · {l.pointName} · <b>{l.tempC} °C</b> (safe {l.minC} to {l.maxC})
                <p className="text-xs text-slate-600">Action: {l.correctiveAction ?? '—'}</p>
              </div>
            ))}
          </div>
          {r.notes.map((n) => <Notice key={n}>{n}</Notice>)}
        </>
      )}
    </div>
  )
}

// ------------------------------------------------------------------ setup

function SetupTab() {
  const qc = useQueryClient()
  const lists = useQuery({ queryKey: ['hyg-checklists', true], queryFn: () => get<HygieneChecklist[]>(`/hygiene/checklists${qs({ includeInactive: true })}`) })
  const points = useQuery({ queryKey: ['hyg-points', true], queryFn: () => get<TempPoint[]>(`/hygiene/temperature-points${qs({ includeInactive: true })}`) })
  const [editList, setEditList] = useState<HygieneChecklist | 'new' | null>(null)
  const [editPoint, setEditPoint] = useState<TempPoint | 'new' | null>(null)
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['hyg-checklists'] })
    qc.invalidateQueries({ queryKey: ['hyg-points'] })
    qc.invalidateQueries({ queryKey: ['hyg-today'] })
  }
  return (
    <div>
      <Notice>Starter checklists and safe ranges were added as examples. Review them against your FSSAI conditions and your equipment before relying on them.</Notice>
      <div className="mb-2 flex items-center justify-between">
        <h3 className="text-sm font-semibold">Checklists</h3>
        <button className="btn-primary" onClick={() => setEditList('new')}>+ Add</button>
      </div>
      {lists.isLoading && <Loading />}
      <ErrorNote error={lists.error} />
      <div className="mb-5 space-y-2">
        {lists.data?.map((c) => (
          <div key={c.id} className={`card ${c.active ? '' : 'opacity-60'}`}>
            <div className="flex justify-between gap-2">
              <div>
                <div className="font-semibold">{c.name}</div>
                <div className="text-xs text-slate-500">{FREQ_LABEL[c.frequency]} · {c.items.filter((i) => i.active).length} items{c.source !== 'MANUAL' ? ` · ${c.source}` : ''}</div>
              </div>
              <button className="btn-ghost" onClick={() => setEditList(c)}>Edit</button>
            </div>
          </div>
        ))}
      </div>
      <div className="mb-2 flex items-center justify-between">
        <h3 className="text-sm font-semibold">Freezers & chillers</h3>
        <button className="btn-primary" onClick={() => setEditPoint('new')}>+ Add</button>
      </div>
      <ErrorNote error={points.error} />
      <div className="space-y-2">
        {points.data?.map((p) => (
          <div key={p.id} className={`card ${p.active ? '' : 'opacity-60'}`}>
            <div className="flex justify-between gap-2">
              <div>
                <div className="font-semibold">{p.name}</div>
                <div className="text-xs text-slate-500">{p.kind.toLowerCase()} · {p.minC} to {p.maxC} °C{p.notes ? ` · ${p.notes}` : ''}</div>
              </div>
              <button className="btn-ghost" onClick={() => setEditPoint(p)}>Edit</button>
            </div>
          </div>
        ))}
      </div>
      {editList && <ChecklistForm list={editList === 'new' ? undefined : editList} onClose={() => setEditList(null)} onSaved={refresh} />}
      {editPoint && <PointForm point={editPoint === 'new' ? undefined : editPoint} onClose={() => setEditPoint(null)} onSaved={refresh} />}
    </div>
  )
}

interface ItemDraft { id: number | null; text: string; requiresPhoto: boolean; active: boolean }

function ChecklistForm({ list, onClose, onSaved }: { list?: HygieneChecklist; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(list?.name ?? '')
  const [freq, setFreq] = useState(list?.frequency ?? 'DAILY')
  const [shift, setShift] = useState(list?.expectedShift ?? '')
  const [active, setActive] = useState(list?.active ?? true)
  const [notes, setNotes] = useState(list?.notes ?? '')
  const [items, setItems] = useState<ItemDraft[]>(list?.items.map((i) => ({ id: i.id, text: i.text, requiresPhoto: i.requiresPhoto, active: i.active })) ?? [])
  const save = useMutation({
    mutationFn: () => {
      const body = {
        name: name.trim(), frequency: freq, expectedShift: shift || null, active, notes: notes.trim() || null,
        items: items.filter((i) => i.text.trim()).map((i) => ({ id: i.id, text: i.text.trim(), requiresPhoto: i.requiresPhoto, active: i.active })),
      }
      return list ? put(`/hygiene/checklists/${list.id}`, body) : post('/hygiene/checklists', body)
    },
    onSuccess: () => (onSaved(), onClose()),
  })
  return (
    <Modal title={list ? 'Edit checklist' : 'New checklist'} onClose={onClose}>
      <div className="space-y-3">
        <Field label="Name"><input className="input" value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="How often">
            <select className="input" value={freq} onChange={(e) => setFreq(e.target.value)}>
              <option value="DAILY">Daily</option><option value="WEEKLY">Weekly</option><option value="MONTHLY">Monthly</option>
            </select>
          </Field>
          <Field label="Shift">
            <select className="input" value={shift} onChange={(e) => setShift(e.target.value)}>
              <option value="">Any</option><option value="OPENING">Opening</option><option value="CLOSING">Closing</option><option value="OTHER">Other</option>
            </select>
          </Field>
        </div>
        <div>
          <span className="label">Items</span>
          <div className="space-y-2">
            {items.map((it, idx) => (
              <div key={idx} className="flex items-center gap-2">
                <input className="input flex-1" value={it.text} onChange={(e) => setItems(items.map((x, j) => (j === idx ? { ...x, text: e.target.value } : x)))} />
                <label className="flex items-center gap-1 text-xs">
                  <input type="checkbox" checked={it.active} onChange={(e) => setItems(items.map((x, j) => (j === idx ? { ...x, active: e.target.checked } : x)))} /> on
                </label>
              </div>
            ))}
          </div>
          <button className="btn-ghost mt-2" onClick={() => setItems([...items, { id: null, text: '', requiresPhoto: false, active: true }])}>+ Add item</button>
          <p className="mt-1 text-xs text-slate-500">Items are switched off, not deleted, so old logs keep their wording.</p>
        </div>
        <Field label="Notes"><input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Checklist is in use</label>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!name.trim() || save.isPending} onClick={() => save.mutate()}>Save</button>
        </div>
      </div>
    </Modal>
  )
}

function PointForm({ point, onClose, onSaved }: { point?: TempPoint; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(point?.name ?? '')
  const [kind, setKind] = useState(point?.kind ?? 'FREEZER')
  const [min, setMin] = useState(point?.minC ?? '')
  const [max, setMax] = useState(point?.maxC ?? '')
  const [active, setActive] = useState(point?.active ?? true)
  const [notes, setNotes] = useState(point?.notes ?? '')
  const save = useMutation({
    mutationFn: () => {
      const body = { name: name.trim(), kind, minC: min, maxC: max, active, notes: notes.trim() || null }
      return point ? put(`/hygiene/temperature-points/${point.id}`, body) : post('/hygiene/temperature-points', body)
    },
    onSuccess: () => (onSaved(), onClose()),
  })
  const ok = name.trim() && min.trim() !== '' && max.trim() !== '' && !Number.isNaN(Number(min)) && !Number.isNaN(Number(max))
  return (
    <Modal title={point ? 'Edit freezer / chiller' : 'New freezer / chiller'} onClose={onClose}>
      <div className="space-y-3">
        <Field label="Name"><input className="input" value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="Type">
          <select className="input" value={kind} onChange={(e) => setKind(e.target.value)}>
            {['FREEZER', 'CHILLER', 'HOT_HOLD', 'DISPLAY', 'OTHER'].map((k) => <option key={k} value={k}>{k.replace('_', ' ').toLowerCase()}</option>)}
          </select>
        </Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Lowest safe °C"><input className="input" inputMode="decimal" value={min} onChange={(e) => setMin(e.target.value)} /></Field>
          <Field label="Highest safe °C"><input className="input" inputMode="decimal" value={max} onChange={(e) => setMax(e.target.value)} /></Field>
        </div>
        <Field label="Notes"><input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> In use</label>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!ok || save.isPending} onClick={() => save.mutate()}>Save</button>
        </div>
      </div>
    </Modal>
  )
}
