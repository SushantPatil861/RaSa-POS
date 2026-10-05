import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { get, post, qs } from '../../api'
import { ErrorNote, Field, Loading, Modal, PageTitle } from '../../components/ui'
import { useAuth } from '../../auth'
import type { CustomerLite } from '../../types.phase3'
import { Empty } from './shared'

export default function CustomersPage() {
  const { can } = useAuth()
  const nav = useNavigate()
  const [search, setSearch] = useState('')
  const [adding, setAdding] = useState(false)
  const q = useQuery({
    queryKey: ['customers', search],
    queryFn: () => get<CustomerLite[]>(`/customers${qs({ q: search })}`),
  })
  return (
    <div>
      <PageTitle title="Customers">
        {can('customers.manage') && (
          <button className="btn-primary" onClick={() => setAdding(true)}>
            Add customer
          </button>
        )}
      </PageTitle>
      <input className="input mb-3" placeholder="Search by name or mobile" value={search} onChange={(e) => setSearch(e.target.value)} />
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error} />
      {q.data && q.data.length === 0 && <Empty>No customers found.</Empty>}
      <div className="space-y-2">
        {q.data?.map((c) => (
          <button key={c.id} className="card w-full text-left" onClick={() => nav(`/customers/${c.id}`)}>
            <div className="font-medium">{c.name}</div>
            <div className="text-xs text-slate-500">{c.mobile ?? 'No mobile number'}</div>
          </button>
        ))}
      </div>
      {adding && <AddCustomer onClose={() => setAdding(false)} />}
    </div>
  )
}

function AddCustomer({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [mobile, setMobile] = useState('')
  const [notes, setNotes] = useState('')
  const m = useMutation({
    mutationFn: () => post('/customers', { name, mobile: mobile || null, notes: notes || null }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['customers'] })
      onClose()
    },
  })
  return (
    <Modal title="Add customer" onClose={onClose}>
      <div className="space-y-3">
        <Field label="Name">
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </Field>
        <Field label="Mobile">
          <input className="input" inputMode="tel" value={mobile} onChange={(e) => setMobile(e.target.value)} />
        </Field>
        <Field label="Notes">
          <textarea className="input" rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <ErrorNote error={m.error} />
        <button className="btn-primary w-full" disabled={!name.trim() || m.isPending} onClick={() => m.mutate()}>
          Save
        </button>
      </div>
    </Modal>
  )
}
