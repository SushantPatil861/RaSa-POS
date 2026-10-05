import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post, put, qs } from '../../api'
import { ErrorNote, Field, Loading, Modal, PageTitle, ReasonDialog } from '../../components/ui'
import { useAuth } from '../../auth'
import { inr, toNumber } from '../../format'
import type {
  WaConfirmResult,
  WaInboxItem,
  WaMenuOption,
  WaParsedItem,
  WaSentResult,
  WaStatus,
  WaThreadMsg,
} from '../../types.phase3'
import { ago, Badge, Empty, Tabs } from './shared'

type Filter = 'REVIEW' | 'CONFIRMED' | 'REJECTED' | 'ALL'
const ORDER_TYPES = ['PICKUP', 'DELIVERY', 'DINE_IN', 'TAKEAWAY']

export default function WhatsAppInbox() {
  const { can } = useAuth()
  const [filter, setFilter] = useState<Filter>('REVIEW')
  const [thread, setThread] = useState<string | null>(null)
  const status = useQuery({ queryKey: ['wa-status'], queryFn: () => get<WaStatus>('/whatsapp/status') })
  const inbox = useQuery({
    queryKey: ['wa-inbox', filter],
    queryFn: () => get<WaInboxItem[]>(`/whatsapp/inbox${qs({ status: filter === 'ALL' ? '' : filter })}`),
    refetchInterval: 15000,
  })
  const manage = can('whatsapp.manage')

  return (
    <div>
      <PageTitle title="WhatsApp orders" />
      {status.data && !status.data.enabled && (
        <div className="mb-3 rounded-lg bg-amber-50 p-3 text-sm text-amber-800">
          WhatsApp is not connected yet. Messages will not arrive, and replies are only saved here, not sent.
        </div>
      )}
      {status.data?.enabled && !status.data.webhookSecured && (
        <div className="mb-3 rounded-lg bg-amber-50 p-3 text-sm text-amber-800">
          The app secret is not set, so incoming messages are refused. Ask your administrator to finish the setup.
        </div>
      )}
      <Tabs<Filter>
        value={filter}
        onChange={setFilter}
        options={[
          { value: 'REVIEW', label: 'Needs review' },
          { value: 'CONFIRMED', label: 'Confirmed' },
          { value: 'REJECTED', label: 'Rejected' },
          { value: 'ALL', label: 'All' },
        ]}
      />
      {inbox.isLoading && <Loading />}
      <ErrorNote error={inbox.error} />
      {inbox.data && inbox.data.length === 0 && <Empty>No messages here.</Empty>}
      <div className="space-y-3">
        {inbox.data?.map((m) => (
          <MessageCard key={m.messageId} m={m} manage={manage} upi={!!status.data?.upiConfigured} onThread={setThread} />
        ))}
      </div>
      {thread && <ThreadModal number={thread} manage={manage} onClose={() => setThread(null)} />}
    </div>
  )
}

function MessageCard({
  m,
  manage,
  upi,
  onThread,
}: {
  m: WaInboxItem
  manage: boolean
  upi: boolean
  onThread: (n: string) => void
}) {
  const qc = useQueryClient()
  const pending = m.draftStatus === 'PENDING_REVIEW'
  const [items, setItems] = useState<WaParsedItem[] | null>(null)
  const [orderType, setOrderType] = useState('PICKUP')
  const [instr, setInstr] = useState('')
  const [reject, setReject] = useState(false)
  const [search, setSearch] = useState('')
  const [info, setInfo] = useState<string | null>(null)
  const cur = items ?? m.parsedItems
  const refresh = () => qc.invalidateQueries({ queryKey: ['wa-inbox'] })

  const menu = useQuery({
    queryKey: ['wa-menu', search],
    queryFn: () => get<WaMenuOption[]>(`/whatsapp/menu${qs({ q: search })}`),
    enabled: pending && manage && search.trim().length >= 2,
  })

  const confirm = useMutation({
    mutationFn: async () => {
      await put(`/whatsapp/drafts/${m.draftId}/items`, {
        items: cur.map((i) => ({ productId: i.productId, variantId: i.variantId, qty: i.qty })),
        unmatchedText: m.unmatchedText,
      })
      return post<WaConfirmResult>(`/whatsapp/drafts/${m.draftId}/confirm`, {
        orderType,
        specialInstructions: instr || null,
        customerName: m.customerName,
      })
    },
    onSuccess: refresh,
  })
  const notify = useMutation({
    mutationFn: (template: string) => post<WaSentResult>(`/whatsapp/orders/${m.orderId}/notify`, { template }),
    onSuccess: (r) => setInfo(r.status === 'SENT' ? 'Message sent.' : `Message status: ${r.status}`),
  })
  const pay = useMutation({
    mutationFn: () => post<WaSentResult>(`/whatsapp/orders/${m.orderId}/payment-request`),
    onSuccess: (r) => setInfo(r.status === 'SENT' ? 'Payment request sent.' : `Message status: ${r.status}`),
  })

  const total = cur.reduce((s, i) => s + toNumber(i.qty) * toNumber(i.unitPrice), 0)

  return (
    <div className="card space-y-2">
      <div className="flex items-start justify-between gap-2">
        <div>
          <div className="font-medium">{m.customerName || m.waNumber}</div>
          <div className="text-xs text-slate-500">
            +{m.waNumber} · {ago(m.createdAt)}
          </div>
        </div>
        {m.draftStatus ? <Badge value={m.draftStatus} /> : <Badge value={m.messageStatus} />}
      </div>
      <div className="whitespace-pre-wrap rounded-lg bg-slate-50 p-2 text-sm">{m.body}</div>

      {m.draftId !== null && (
        <>
          {cur.length === 0 && <p className="text-sm text-amber-700">No menu items recognised in this message.</p>}
          {cur.map((i, idx) => (
            <div key={`${i.productId}-${i.variantId}-${idx}`} className="flex items-center gap-2 text-sm">
              <div className="min-w-0 flex-1">
                <div className="truncate">{i.name}</div>
                <div className="text-xs text-slate-500">
                  {inr(i.unitPrice)} each{i.confidence < 0.7 ? ' · please check this match' : ''}
                </div>
              </div>
              {pending && manage ? (
                <>
                  <input
                    className="input w-16 text-center"
                    inputMode="decimal"
                    value={i.qty}
                    onChange={(e) => setItems(cur.map((x, j) => (j === idx ? { ...x, qty: e.target.value } : x)))}
                  />
                  <button className="btn-ghost px-2" aria-label="Remove" onClick={() => setItems(cur.filter((_, j) => j !== idx))}>
                    ✕
                  </button>
                </>
              ) : (
                <span>× {i.qty}</span>
              )}
            </div>
          ))}
          {m.unmatchedText && <p className="text-xs text-amber-700">Not understood: “{m.unmatchedText}”</p>}
          {cur.length > 0 && <div className="text-right text-sm font-semibold">Total {inr(total)}</div>}

          {pending && manage && (
            <div className="space-y-2 border-t pt-2">
              <input
                className="input"
                placeholder="Add an item: type 2 letters of its name"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
              {menu.data && search.trim().length >= 2 && (
                <div className="max-h-40 overflow-y-auto rounded-lg ring-1 ring-slate-200">
                  {menu.data.length === 0 && <div className="p-2 text-xs text-slate-500">No match.</div>}
                  {menu.data.map((o) => (
                    <button
                      key={`${o.productId}-${o.variantId}`}
                      className="flex w-full justify-between p-2 text-left text-sm hover:bg-slate-50"
                      onClick={() => {
                        setItems([
                          ...cur,
                          {
                            text: o.name,
                            productId: o.productId,
                            variantId: o.variantId,
                            name: o.variantName ? `${o.name} (${o.variantName})` : o.name,
                            qty: '1',
                            unitPrice: o.price,
                            confidence: 1,
                          },
                        ])
                        setSearch('')
                      }}
                    >
                      <span>{o.variantName ? `${o.name} (${o.variantName})` : o.name}</span>
                      <span className="text-slate-500">{inr(o.price)}</span>
                    </button>
                  ))}
                </div>
              )}
              <div className="grid grid-cols-2 gap-2">
                <Field label="Order type">
                  <select className="input" value={orderType} onChange={(e) => setOrderType(e.target.value)}>
                    {ORDER_TYPES.map((t) => (
                      <option key={t} value={t}>
                        {t.replace('_', ' ')}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="Instructions">
                  <input className="input" value={instr} onChange={(e) => setInstr(e.target.value)} />
                </Field>
              </div>
              <ErrorNote error={confirm.error} />
              <div className="flex gap-2">
                <button className="btn-primary flex-1" disabled={cur.length === 0 || confirm.isPending} onClick={() => confirm.mutate()}>
                  {confirm.isPending ? 'Creating order…' : 'Confirm order'}
                </button>
                <button className="btn-danger" onClick={() => setReject(true)}>
                  Reject
                </button>
              </div>
            </div>
          )}
        </>
      )}

      {m.draftStatus === 'CONFIRMED' && (
        <div className="text-sm text-emerald-700">Order {m.orderNo ?? `#${m.orderId}`} created.</div>
      )}
      {m.draftStatus === 'REJECTED' && m.rejectReason && <div className="text-sm text-red-700">Rejected: “{m.rejectReason}”</div>}

      {m.draftStatus === 'CONFIRMED' && m.orderId !== null && manage && (
        <div className="flex flex-wrap gap-2">
          <button className="btn-ghost" disabled={notify.isPending} onClick={() => notify.mutate('CONFIRMED')}>
            Tell: order confirmed
          </button>
          <button className="btn-ghost" disabled={notify.isPending} onClick={() => notify.mutate('READY')}>
            Tell: order ready
          </button>
          <button className="btn-ghost" disabled={pay.isPending || !upi} title={upi ? '' : 'UPI ID not set up'} onClick={() => pay.mutate()}>
            Ask for UPI payment
          </button>
        </div>
      )}
      {info && <p className="text-sm text-emerald-700">{info}</p>}
      <ErrorNote error={notify.error || pay.error} />
      <button className="text-sm text-brand-700 underline" onClick={() => onThread(m.waNumber)}>
        Open chat
      </button>

      {reject && (
        <ReasonDialog
          title="Reject this order message"
          confirmLabel="Reject"
          danger
          onClose={() => setReject(false)}
          onConfirm={async (reason) => {
            await post(`/whatsapp/drafts/${m.draftId}/reject`, { reason })
            refresh()
          }}
        />
      )}
    </div>
  )
}

function ThreadModal({ number, manage, onClose }: { number: string; manage: boolean; onClose: () => void }) {
  const qc = useQueryClient()
  const [text, setText] = useState('')
  const thread = useQuery({
    queryKey: ['wa-thread', number],
    queryFn: () => get<WaThreadMsg[]>(`/whatsapp/thread${qs({ number })}`),
  })
  const send = useMutation({
    mutationFn: () => post<WaSentResult>('/whatsapp/send', { to: number, text }),
    onSuccess: () => {
      setText('')
      qc.invalidateQueries({ queryKey: ['wa-thread', number] })
    },
  })
  return (
    <Modal title={`Chat with +${number}`} onClose={onClose}>
      {thread.isLoading && <Loading />}
      <ErrorNote error={thread.error} />
      <div className="mb-3 max-h-80 space-y-2 overflow-y-auto">
        {thread.data?.map((t) => (
          <div
            key={t.id}
            className={`max-w-[85%] whitespace-pre-wrap rounded-xl p-2 text-sm ${
              t.direction === 'IN' ? 'bg-slate-100' : 'ml-auto bg-emerald-100'
            }`}
          >
            {t.body}
            <div className="mt-1 text-[10px] text-slate-500">
              {ago(t.createdAt)}
              {t.direction === 'OUT' ? ` · ${t.status}` : ''}
            </div>
          </div>
        ))}
      </div>
      {manage && (
        <>
          <textarea className="input" rows={2} value={text} onChange={(e) => setText(e.target.value)} placeholder="Type a reply" />
          <ErrorNote error={send.error} />
          <button className="btn-primary mt-2 w-full" disabled={!text.trim() || send.isPending} onClick={() => send.mutate()}>
            Send reply
          </button>
          <p className="mt-1 text-xs text-slate-500">WhatsApp only lets you send free text within 24 hours of the customer's last message.</p>
        </>
      )}
    </Modal>
  )
}
