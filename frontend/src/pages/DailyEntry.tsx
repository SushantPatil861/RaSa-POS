import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { post } from '../api'
import { inr, todayIso, toNumber } from '../format'
import { ErrorNote, Field, PageTitle } from '../components/ui'

const CHANNELS: [string, string][] = [
  ['cash', 'Cash'],
  ['upi', 'UPI'],
  ['card', 'Card'],
  ['whatsapp', 'WhatsApp orders'],
  ['swiggy', 'Swiggy'],
  ['zomato', 'Zomato'],
  ['otherOnline', 'Other online'],
]

/**
 * Quick daily total. This is a separate record from itemised sales: the two are never added together.
 * If both exist for a day and differ, the day goes to "pending approval".
 */
export default function DailyEntry() {
  const nav = useNavigate()
  const qc = useQueryClient()
  const [date, setDate] = useState(todayIso())
  const [total, setTotal] = useState('')
  const [split, setSplit] = useState<Record<string, string>>({})
  const [orders, setOrders] = useState('')
  const [discounts, setDiscounts] = useState('')
  const [refunds, setRefunds] = useState('')
  const [wastage, setWastage] = useState('')
  const [notes, setNotes] = useState('')

  const tender = Object.values(split).reduce((s, v) => s + toNumber(v), 0)
  const net = toNumber(total) - toNumber(discounts) - toNumber(refunds)
  const diff = Math.round((net - tender) * 100) / 100

  const save = useMutation({
    mutationFn: () => {
      const body: Record<string, unknown> = {
        txnDate: date,
        totalSales: total || '0',
        ordersCount: orders ? Number(orders) : null,
        discounts: discounts || null,
        refunds: refunds || null,
        wastage: wastage || null,
        notes: notes || null,
      }
      for (const [k] of CHANNELS) body[k] = split[k] || null
      return post('/daily-summaries', body)
    },
    onSuccess: () => {
      qc.invalidateQueries()
      nav(`/days/${date}`)
    },
  })

  return (
    <div className="mx-auto max-w-lg">
      <PageTitle title="Daily total (quick entry)" />
      <div className="card space-y-3">
        <p className="rounded bg-sky-50 p-2 text-xs text-sky-800">
          Use this when you only have the day's total. It is stored separately from item-wise bills and is never added to them.
        </p>
        <Field label="Date">
          <input type="date" className="input" value={date} max={todayIso()} onChange={(e) => setDate(e.target.value)} />
        </Field>
        <Field label="Total sales (incl. GST)">
          <input className="input text-lg" inputMode="decimal" value={total} onChange={(e) => setTotal(e.target.value)} autoFocus />
        </Field>
        <div>
          <div className="label">Paid via</div>
          <div className="grid grid-cols-2 gap-2">
            {CHANNELS.map(([k, label]) => (
              <label key={k} className="text-xs">
                {label}
                <input
                  className="input"
                  inputMode="decimal"
                  value={split[k] ?? ''}
                  onChange={(e) => setSplit({ ...split, [k]: e.target.value })}
                />
              </label>
            ))}
          </div>
          {tender > 0 && diff !== 0 && (
            <div className="mt-1 text-xs text-amber-700">
              Split differs from net total by {inr(Math.abs(diff))}. You can still save; the difference is flagged, not hidden.
            </div>
          )}
        </div>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Orders (count)">
            <input className="input" inputMode="numeric" value={orders} onChange={(e) => setOrders(e.target.value)} />
          </Field>
          <Field label="Discounts ₹">
            <input className="input" inputMode="decimal" value={discounts} onChange={(e) => setDiscounts(e.target.value)} />
          </Field>
          <Field label="Refunds ₹">
            <input className="input" inputMode="decimal" value={refunds} onChange={(e) => setRefunds(e.target.value)} />
          </Field>
          <Field label="Wastage ₹">
            <input className="input" inputMode="decimal" value={wastage} onChange={(e) => setWastage(e.target.value)} />
          </Field>
        </div>
        <Field label="Notes">
          <input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <ErrorNote error={save.error} />
        <button className="btn-primary w-full py-3" disabled={save.isPending || !total} onClick={() => save.mutate()}>
          Save daily total
        </button>
      </div>
    </div>
  )
}
