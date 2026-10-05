import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import { download, get, post } from '../../api'
import { ErrorNote, Field, Loading, PageTitle, ReasonDialog } from '../../components/ui'
import { useAuth } from '../../auth'
import { todayIso } from '../../format'
import type { Confidence, DocDetail, RecordType } from '../../types.phase3'
import { Badge } from './shared'

interface Cat {
  id: number
  name: string
}
interface Supplier {
  id: number
  name: string
}
interface ItemForm {
  itemName: string
  quantity: string
  unitPrice: string
  total: string
}

const METHODS = ['CASH', 'UPI', 'CARD', 'BANK', 'OTHER']
const TYPES: { value: RecordType; label: string }[] = [
  { value: 'PURCHASE', label: 'Purchase (supplier bill)' },
  { value: 'EXPENSE', label: 'Expense' },
  { value: 'DAILY_SALES', label: 'Daily sales' },
]

const num = (s: string) => (s.trim() === '' ? null : s.trim())

export default function DocumentReview() {
  const { id } = useParams()
  const docId = Number(id)
  const { can } = useAuth()
  const nav = useNavigate()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['document', docId], queryFn: () => get<DocDetail>(`/documents/${docId}`) })
  const cats = useQuery({ queryKey: ['expense-cats'], queryFn: () => get<Cat[]>('/expense-categories') })
  const sups = useQuery({ queryKey: ['suppliers'], queryFn: () => get<Supplier[]>('/suppliers') })

  const [fileUrl, setFileUrl] = useState<string | null>(null)
  const [fileErr, setFileErr] = useState<unknown>(null)
  useEffect(() => {
    let url: string | null = null
    let dead = false
    download(`/documents/${docId}/file`)
      .then((b) => {
        if (dead) return
        url = URL.createObjectURL(b)
        setFileUrl(url)
      })
      .catch((e) => !dead && setFileErr(e))
    return () => {
      dead = true
      if (url) URL.revokeObjectURL(url)
    }
  }, [docId])

  const [type, setType] = useState<RecordType>('PURCHASE')
  const [f, setF] = useState<Record<string, string>>({})
  const [edited, setEdited] = useState<Set<string>>(new Set())
  const [items, setItems] = useState<ItemForm[]>([])
  const [estimated, setEstimated] = useState(false)
  const [reject, setReject] = useState(false)
  const [loaded, setLoaded] = useState(false)

  const d = q.data
  useEffect(() => {
    if (!d || loaded) return
    const p = d.draft?.proposed
    setType(d.draft?.proposedType ?? 'PURCHASE')
    setF({ txnDate: todayIso(), paymentMethod: 'CASH', ...(p?.fields ?? {}) })
    setItems(
      (p?.items ?? []).map((i) => ({
        itemName: i.itemName ?? '',
        quantity: i.quantity ?? '',
        unitPrice: i.unitPrice ?? '',
        total: i.total ?? '',
      })),
    )
    setLoaded(true)
  }, [d, loaded])

  const set = (k: string, v: string) => {
    setF((o) => ({ ...o, [k]: v }))
    setEdited((s) => new Set(s).add(k))
  }
  const conf = (k: string): Confidence | null => d?.draft?.proposed.confidence?.[k] ?? null
  const flag = (k: string) => {
    const c = conf(k)
    return c && !edited.has(k) ? <Badge value={c} /> : null
  }

  const confirm = useMutation({
    mutationFn: () =>
      post<DocDetail>(`/documents/${docId}/confirm`, {
        type,
        txnDate: f.txnDate || null,
        totalAmount: num(f.totalAmount ?? ''),
        categoryId: f.categoryId ? Number(f.categoryId) : null,
        paymentMethod: f.paymentMethod || null,
        paidTo: num(f.paidTo ?? ''),
        notes: num(f.notes ?? ''),
        supplierId: f.supplierId ? Number(f.supplierId) : null,
        supplierName: num(f.supplierName ?? ''),
        invoiceNo: num(f.invoiceNo ?? ''),
        invoiceDate: f.invoiceDate || null,
        gstin: num(f.gstin ?? ''),
        paymentStatus: f.paymentStatus || null,
        items: items
          .filter((i) => i.itemName.trim())
          .map((i) => ({
            itemName: i.itemName.trim(),
            quantity: num(i.quantity),
            unit: null,
            unitPrice: num(i.unitPrice),
            total: num(i.total),
          })),
        cash: num(f.cash ?? ''),
        upi: num(f.upi ?? ''),
        card: num(f.card ?? ''),
        whatsapp: num(f.whatsapp ?? ''),
        swiggy: num(f.swiggy ?? ''),
        zomato: num(f.zomato ?? ''),
        ordersCount: f.ordersCount ? Number(f.ordersCount) : null,
        discounts: num(f.discounts ?? ''),
        refunds: num(f.refunds ?? ''),
        estimated,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['document', docId] })
      qc.invalidateQueries({ queryKey: ['documents'] })
    },
  })

  if (q.isLoading) return <Loading />
  if (!d) return <ErrorNote error={q.error} />
  const doc = d.document
  const open = doc.status !== 'CONFIRMED' && doc.status !== 'REJECTED'
  const canConfirm = can('documents.confirm') && open
  const p = d.draft?.proposed

  const text = (k: string, label: string, kind: 'text' | 'number' | 'date' = 'text') => (
    <Field label={label}>
      <div className="flex items-center gap-2">
        <input
          className="input"
          type={kind === 'number' ? 'text' : kind}
          inputMode={kind === 'number' ? 'decimal' : undefined}
          value={f[k] ?? ''}
          disabled={!canConfirm}
          onChange={(e) => set(k, e.target.value)}
        />
        {flag(k)}
      </div>
    </Field>
  )

  return (
    <div>
      <PageTitle title={doc.originalName}>
        <button className="btn-ghost" onClick={() => nav('/documents')}>
          Back
        </button>
      </PageTitle>
      <div className="mb-3 flex items-center gap-2 text-sm">
        <Badge value={doc.status} />
        {doc.statusReason && <span className="text-slate-500">“{doc.statusReason}”</span>}
        {doc.entityType && (
          <span className="text-slate-500">
            → {doc.entityType} #{doc.entityId}
          </span>
        )}
      </div>

      <div className="card mb-3">
        {fileErr ? (
          <ErrorNote error={fileErr} />
        ) : !fileUrl ? (
          <Loading />
        ) : doc.image ? (
          <img src={fileUrl} alt="Uploaded document" className="mx-auto max-h-96 w-auto rounded-lg" />
        ) : (
          <div className="space-y-2">
            <iframe src={fileUrl} title="Document" className="h-96 w-full rounded-lg" />
            <a className="btn-ghost block text-center" href={fileUrl} download={doc.originalName}>
              Download PDF
            </a>
          </div>
        )}
      </div>

      {p?.notice && <div className="mb-3 rounded-lg bg-amber-50 p-3 text-sm text-amber-800">{p.notice}</div>}
      {p?.needsManualEntry && (
        <div className="mb-3 rounded-lg bg-amber-50 p-3 text-sm text-amber-800">
          We could not read this document. Please type the details in yourself.
        </div>
      )}

      {canConfirm && (
        <div className="card space-y-3">
          <h2 className="font-semibold">Check and correct the details</h2>
          <p className="text-xs text-slate-500">
            Badges show how sure the reader was. Please check every field marked Low before confirming.
          </p>
          <Field label="Save as">
            <select className="input" value={type} onChange={(e) => setType(e.target.value as RecordType)}>
              {TYPES.map((t) => (
                <option key={t.value} value={t.value}>
                  {t.label}
                </option>
              ))}
            </select>
          </Field>
          {text('txnDate', 'Date', 'date')}
          {type !== 'DAILY_SALES' && text('totalAmount', 'Total amount (₹)', 'number')}

          {type === 'EXPENSE' && (
            <>
              <Field label="Category">
                <select className="input" value={f.categoryId ?? ''} onChange={(e) => set('categoryId', e.target.value)}>
                  <option value="">Uncategorised</option>
                  {cats.data?.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Paid via">
                <select className="input" value={f.paymentMethod ?? 'CASH'} onChange={(e) => set('paymentMethod', e.target.value)}>
                  {METHODS.map((m) => (
                    <option key={m}>{m}</option>
                  ))}
                </select>
              </Field>
              {text('paidTo', 'Paid to')}
              {text('notes', 'Notes')}
            </>
          )}

          {type === 'PURCHASE' && (
            <>
              <Field label="Supplier">
                <select className="input" value={f.supplierId ?? ''} onChange={(e) => set('supplierId', e.target.value)}>
                  <option value="">Not in my list</option>
                  {sups.data?.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
              </Field>
              {!f.supplierId && text('supplierName', 'Supplier name on the bill')}
              {text('invoiceNo', 'Invoice number')}
              {text('invoiceDate', 'Invoice date', 'date')}
              {text('gstin', 'Supplier GSTIN')}
              <Field label="Payment status">
                <select className="input" value={f.paymentStatus ?? ''} onChange={(e) => set('paymentStatus', e.target.value)}>
                  <option value="">Not sure</option>
                  <option value="PAID">Paid</option>
                  <option value="UNPAID">Unpaid</option>
                  <option value="PARTIAL">Part paid</option>
                </select>
              </Field>
              <div>
                <span className="label">Items</span>
                {items.map((it, i) => (
                  <div key={i} className="mb-2 grid grid-cols-6 gap-1">
                    <input
                      className="input col-span-6"
                      placeholder="Item"
                      value={it.itemName}
                      onChange={(e) => setItems(items.map((x, j) => (j === i ? { ...x, itemName: e.target.value } : x)))}
                    />
                    <input
                      className="input col-span-2"
                      placeholder="Qty"
                      inputMode="decimal"
                      value={it.quantity}
                      onChange={(e) => setItems(items.map((x, j) => (j === i ? { ...x, quantity: e.target.value } : x)))}
                    />
                    <input
                      className="input col-span-2"
                      placeholder="Rate"
                      inputMode="decimal"
                      value={it.unitPrice}
                      onChange={(e) => setItems(items.map((x, j) => (j === i ? { ...x, unitPrice: e.target.value } : x)))}
                    />
                    <input
                      className="input col-span-2"
                      placeholder="Amount"
                      inputMode="decimal"
                      value={it.total}
                      onChange={(e) => setItems(items.map((x, j) => (j === i ? { ...x, total: e.target.value } : x)))}
                    />
                    <button className="btn-ghost col-span-6 text-xs" onClick={() => setItems(items.filter((_, j) => j !== i))}>
                      Remove item
                    </button>
                  </div>
                ))}
                <button
                  className="btn-ghost w-full"
                  onClick={() => setItems([...items, { itemName: '', quantity: '', unitPrice: '', total: '' }])}
                >
                  Add item
                </button>
              </div>
              {text('notes', 'Notes')}
            </>
          )}

          {type === 'DAILY_SALES' && (
            <div className="grid grid-cols-2 gap-2">
              {text('cash', 'Cash (₹)', 'number')}
              {text('upi', 'UPI (₹)', 'number')}
              {text('card', 'Card (₹)', 'number')}
              {text('whatsapp', 'WhatsApp (₹)', 'number')}
              {text('swiggy', 'Swiggy (₹)', 'number')}
              {text('zomato', 'Zomato (₹)', 'number')}
              {text('ordersCount', 'Orders', 'number')}
              {text('discounts', 'Discounts (₹)', 'number')}
              {text('refunds', 'Refunds (₹)', 'number')}
            </div>
          )}

          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={estimated} onChange={(e) => setEstimated(e.target.checked)} />
            Mark as estimated (numbers are approximate)
          </label>
          <ErrorNote error={confirm.error} />
          <div className="flex gap-2">
            <button className="btn-primary flex-1" disabled={confirm.isPending} onClick={() => confirm.mutate()}>
              {confirm.isPending ? 'Saving…' : 'Confirm and save'}
            </button>
            <button className="btn-danger" onClick={() => setReject(true)}>
              Reject
            </button>
          </div>
        </div>
      )}

      {d.extraction && (
        <details className="card mt-3">
          <summary className="cursor-pointer text-sm font-medium">
            Text the reader found ({d.extraction.engine}
            {d.extraction.avgConfidence ? `, ${d.extraction.avgConfidence}% sure` : ''})
          </summary>
          <pre className="mt-2 whitespace-pre-wrap break-words text-xs text-slate-700">{d.extraction.rawText || '(nothing)'}</pre>
        </details>
      )}

      {reject && (
        <ReasonDialog
          title="Reject this document"
          confirmLabel="Reject"
          danger
          onClose={() => setReject(false)}
          onConfirm={async (reason) => {
            await post(`/documents/${docId}/reject`, { reason })
            qc.invalidateQueries({ queryKey: ['document', docId] })
            qc.invalidateQueries({ queryKey: ['documents'] })
          }}
        />
      )}
    </div>
  )
}
