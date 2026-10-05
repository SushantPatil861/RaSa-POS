import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, patch, post, put } from '../api'
import { dateTime, dmy } from '../format'
import type { AuditRow, Product, TaxRule, UserRow } from '../types'
import { ErrorNote, Field, Loading, Modal, PageTitle } from '../components/ui'
import { useAuth } from '../auth'

export default function Settings() {
  const { can, user } = useAuth()
  const [tab, setTab] = useState('account')
  const tabs = [
    ['account', 'My account', true],
    ['business', 'Business', can('settings.manage')],
    ['tax', 'GST', can('settings.manage')],
    ['users', 'Users', can('users.manage')],
    ['products', 'Products', can('menu.view')],
    ['audit', 'Audit trail', can('audit.view')],
  ].filter((t) => t[2]) as [string, string, boolean][]
  return (
    <div>
      <PageTitle title="Settings" />
      <div className="mb-3 flex gap-1 overflow-x-auto">
        {tabs.map(([id, label]) => (
          <button
            key={id}
            onClick={() => setTab(id)}
            className={`shrink-0 rounded-full px-3 py-1 text-sm ${tab === id ? 'bg-brand-700 text-white' : 'bg-white ring-1 ring-slate-300'}`}
          >
            {label}
          </button>
        ))}
      </div>
      {tab === 'account' && <Account name={user?.name ?? ''} />}
      {tab === 'business' && <Business />}
      {tab === 'tax' && <Tax />}
      {tab === 'users' && <Users />}
      {tab === 'products' && <Products />}
      {tab === 'audit' && <Audit />}
    </div>
  )
}

function Account({ name }: { name: string }) {
  const [cur, setCur] = useState('')
  const [next, setNext] = useState('')
  const [ok, setOk] = useState(false)
  const m = useMutation({
    mutationFn: () => post('/me/password', { currentPassword: cur, newPassword: next }),
    onSuccess: () => (setOk(true), setCur(''), setNext('')),
  })
  return (
    <div className="card max-w-md space-y-3">
      <div className="font-semibold">{name}</div>
      <Field label="Current password">
        <input className="input" type="password" value={cur} onChange={(e) => setCur(e.target.value)} />
      </Field>
      <Field label="New password (min 8 characters)">
        <input className="input" type="password" value={next} onChange={(e) => setNext(e.target.value)} />
      </Field>
      <ErrorNote error={m.error} />
      {ok && <div className="text-sm text-emerald-700">Password changed.</div>}
      <button className="btn-primary" disabled={!cur || next.length < 8} onClick={() => m.mutate()}>
        Change password
      </button>
    </div>
  )
}

interface Biz { name: string; address: string | null; phone: string | null; whatsappNumber: string | null; gstin: string | null; dayRolloverHour: number; dataStartDate: string; timezone: string }

function Business() {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['biz'], queryFn: () => get<Biz>('/settings/business') })
  const [f, setF] = useState<Biz | null>(null)
  const cur = f ?? q.data
  const save = useMutation({
    mutationFn: () => put('/settings/business', cur),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['biz'] }),
  })
  if (!cur) return <Loading />
  const set = (k: keyof Biz, v: string) => setF({ ...cur, [k]: v })
  return (
    <div className="card max-w-md space-y-3">
      <Field label="Name">
        <input className="input" value={cur.name} onChange={(e) => set('name', e.target.value)} />
      </Field>
      <Field label="Address">
        <input className="input" value={cur.address ?? ''} onChange={(e) => set('address', e.target.value)} />
      </Field>
      <Field label="Phone">
        <input className="input" value={cur.phone ?? ''} onChange={(e) => set('phone', e.target.value)} />
      </Field>
      <Field label="WhatsApp number">
        <input className="input" value={cur.whatsappNumber ?? ''} onChange={(e) => set('whatsappNumber', e.target.value)} />
      </Field>
      <Field label="GSTIN">
        <input className="input" value={cur.gstin ?? ''} onChange={(e) => set('gstin', e.target.value)} />
      </Field>
      <p className="text-xs text-slate-500">
        Data starts {dmy(cur.dataStartDate)}; the business day changes at {String(cur.dayRolloverHour).padStart(2, '0')}:00 ({cur.timezone}). These are fixed so history is never shifted.
      </p>
      <ErrorNote error={save.error} />
      <button className="btn-primary" onClick={() => save.mutate()}>
        Save
      </button>
    </div>
  )
}

function Tax() {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['tax'], queryFn: () => get<TaxRule[]>('/settings/tax-rules') })
  const [open, setOpen] = useState(false)
  const [rate, setRate] = useState('5')
  const [date, setDate] = useState('')
  const [itc, setItc] = useState('')
  const [reason, setReason] = useState('')
  const add = useMutation({
    mutationFn: () => post('/settings/tax-rules', { scope: 'ALL', rate, effectiveFrom: date, itcEligible: itc === '' ? null : itc === 'yes', reason }),
    onSuccess: () => (qc.invalidateQueries(), setOpen(false)),
  })
  return (
    <div>
      <p className="mb-3 text-sm text-slate-600">
        Menu prices include GST. The tax in each bill is extracted using the rate that applied on that bill's date. A rate change is a new rule with an effective date — old rules are kept.
      </p>
      <div className="card mb-3">
        {q.data?.map((r) => (
          <div key={r.id} className="flex justify-between border-b border-slate-100 py-2 text-sm last:border-0">
            <span>
              {r.scope} · {r.rate}% from {dmy(r.effectiveFrom)}
            </span>
            <span className="text-xs text-slate-500">ITC: {r.itcEligible === null ? 'to be confirmed by CA' : r.itcEligible ? 'eligible' : 'not eligible'}</span>
          </div>
        ))}
      </div>
      <button className="btn-primary" onClick={() => setOpen(true)}>
        Add new rate
      </button>
      {open && (
        <Modal title="New GST rule" onClose={() => setOpen(false)}>
          <div className="space-y-3">
            <Field label="Rate %">
              <input className="input" inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} />
            </Field>
            <Field label="Effective from">
              <input type="date" className="input" value={date} onChange={(e) => setDate(e.target.value)} />
            </Field>
            <Field label="Input tax credit">
              <select className="input" value={itc} onChange={(e) => setItc(e.target.value)}>
                <option value="">Not decided</option>
                <option value="yes">Eligible</option>
                <option value="no">Not eligible</option>
              </select>
            </Field>
            <Field label="Reason (required)">
              <input className="input" value={reason} onChange={(e) => setReason(e.target.value)} />
            </Field>
            <ErrorNote error={add.error} />
            <button className="btn-primary w-full" disabled={!date || !reason.trim()} onClick={() => add.mutate()}>
              Save
            </button>
          </div>
        </Modal>
      )}
    </div>
  )
}

function Users() {
  const qc = useQueryClient()
  const users = useQuery({ queryKey: ['users'], queryFn: () => get<UserRow[]>('/users') })
  const roles = useQuery({ queryKey: ['roles'], queryFn: () => get<{ code: string; name: string }[]>('/roles') })
  const [open, setOpen] = useState(false)
  const [f, setF] = useState({ name: '', email: '', mobile: '', roleCode: 'CASHIER', password: '' })
  const create = useMutation({
    mutationFn: () => post('/users', f),
    onSuccess: () => (qc.invalidateQueries({ queryKey: ['users'] }), setOpen(false)),
  })
  const toggle = useMutation({
    mutationFn: (u: UserRow) => patch(`/users/${u.id}`, { active: !u.active }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['users'] }),
  })
  const reset = useMutation({
    mutationFn: ({ id, pw }: { id: number; pw: string }) => post(`/users/${id}/reset-password`, { newPassword: pw }),
  })
  return (
    <div>
      <button className="btn-primary mb-3" onClick={() => setOpen(true)}>
        ➕ Add user
      </button>
      <ErrorNote error={toggle.error || reset.error} />
      <div className="space-y-2">
        {users.data?.map((u) => (
          <div key={u.id} className="card flex items-center justify-between p-3">
            <div>
              <div className="font-medium">
                {u.name} {!u.active && <span className="text-xs text-red-600">(disabled)</span>}
              </div>
              <div className="text-xs text-slate-500">
                {u.email} · {u.roleName}
              </div>
            </div>
            <div className="flex gap-3 text-xs">
              <button
                className="text-brand-700 underline"
                onClick={() => {
                  const pw = window.prompt(`New password for ${u.name} (min 8 characters)`)
                  if (pw && pw.length >= 8) reset.mutate({ id: u.id, pw })
                }}
              >
                Reset password
              </button>
              <button className="text-red-600 underline" onClick={() => toggle.mutate(u)}>
                {u.active ? 'Disable' : 'Enable'}
              </button>
            </div>
          </div>
        ))}
      </div>
      {open && (
        <Modal title="Add user" onClose={() => setOpen(false)}>
          <div className="space-y-3">
            <Field label="Name">
              <input className="input" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />
            </Field>
            <Field label="Email (login)">
              <input className="input" type="email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} />
            </Field>
            <Field label="Mobile">
              <input className="input" value={f.mobile} onChange={(e) => setF({ ...f, mobile: e.target.value })} />
            </Field>
            <Field label="Role">
              <select className="input" value={f.roleCode} onChange={(e) => setF({ ...f, roleCode: e.target.value })}>
                {roles.data?.map((r) => (
                  <option key={r.code} value={r.code}>
                    {r.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Temporary password (min 8 characters)">
              <input className="input" value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} />
            </Field>
            <ErrorNote error={create.error} />
            <button className="btn-primary w-full" disabled={!f.name || !f.email || f.password.length < 8} onClick={() => create.mutate()}>
              Create user
            </button>
          </div>
        </Modal>
      )}
    </div>
  )
}

function Products() {
  const qc = useQueryClient()
  const { can } = useAuth()
  const q = useQuery({ queryKey: ['products'], queryFn: () => get<Product[]>('/products') })
  const [filter, setFilter] = useState('')
  const life = useMutation({
    mutationFn: ({ id, status }: { id: number; status: string }) =>
      post(`/products/${id}/lifecycle`, { status, reason: 'Changed from Settings' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['products'] }),
  })
  return (
    <div>
      <input className="input mb-3" placeholder="Search products…" value={filter} onChange={(e) => setFilter(e.target.value)} />
      <ErrorNote error={life.error} />
      <div className="card divide-y divide-slate-100">
        {q.data
          ?.filter((p) => p.name.toLowerCase().includes(filter.toLowerCase()))
          .map((p) => (
            <div key={p.id} className="flex items-center justify-between py-2 text-sm">
              <span>
                {p.name} <span className="text-xs text-slate-500">{p.categoryName}</span>
              </span>
              {can('products.manage') ? (
                <select className="input w-40 py-1 text-xs" value={p.lifecycleStatus} onChange={(e) => life.mutate({ id: p.id, status: e.target.value })}>
                  {['DRAFT', 'ACTIVE', 'TEMPORARILY_UNAVAILABLE', 'DISCONTINUED', 'ARCHIVED'].map((s) => (
                    <option key={s}>{s}</option>
                  ))}
                </select>
              ) : (
                <span className="text-xs">{p.lifecycleStatus}</span>
              )}
            </div>
          ))}
      </div>
    </div>
  )
}

function Audit() {
  const q = useQuery({ queryKey: ['audit-all'], queryFn: () => get<AuditRow[]>('/audit?limit=200') })
  return (
    <div className="card overflow-x-auto">
      <table className="w-full">
        <thead>
          <tr>
            <th className="th">When</th>
            <th className="th">Who</th>
            <th className="th">What</th>
            <th className="th">Reason</th>
          </tr>
        </thead>
        <tbody>
          {q.data?.map((r) => (
            <tr key={r.id} className="border-t border-slate-100">
              <td className="td text-xs">{dateTime(r.at)}</td>
              <td className="td">{r.userName ?? 'system'}</td>
              <td className="td">
                {r.action} {r.entityType} {r.entityId ?? ''}
              </td>
              <td className="td text-xs">{r.reason}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
