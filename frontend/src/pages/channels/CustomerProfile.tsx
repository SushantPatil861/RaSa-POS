import { useQuery } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import { get } from '../../api'
import { ErrorNote, Loading, PageTitle, Stat } from '../../components/ui'
import { dmy, inr } from '../../format'
import type { CustomerProfileData } from '../../types.phase3'
import { Badge, Empty } from './shared'

export default function CustomerProfile() {
  const { id } = useParams()
  const nav = useNavigate()
  const q = useQuery({
    queryKey: ['customer-profile', id],
    queryFn: () => get<CustomerProfileData>(`/customers/${id}/profile`),
  })
  if (q.isLoading) return <Loading />
  const p = q.data
  if (!p) return <ErrorNote error={q.error} />
  return (
    <div>
      <PageTitle title={p.customer.name}>
        <button className="btn-ghost" onClick={() => nav('/customers')}>
          Back
        </button>
      </PageTitle>
      <div className="mb-3 text-sm text-slate-600">
        <div>Mobile: {p.customer.mobile ?? 'not recorded'}</div>
        <div>WhatsApp: {p.whatsappNumber ? `+${p.whatsappNumber}` : 'no WhatsApp chats yet'}</div>
        {p.customer.notes && <div>Notes: {p.customer.notes}</div>}
      </div>
      <div className="mb-4 grid grid-cols-2 gap-3">
        <Stat label="Orders" value={p.ordersCount} sub={`${p.completedOrders} completed`} />
        <Stat label="Total spend" value={inr(p.totalSpend)} />
        <Stat label="Average order" value={inr(p.averageOrder, { blankIfNull: true })} />
        <Stat label="Last order" value={dmy(p.lastOrderDate)} sub={`First: ${dmy(p.firstOrderDate)}`} />
      </div>

      <h2 className="mb-2 font-semibold">Favourite items</h2>
      {p.favouriteItems.length === 0 && <Empty>No orders yet.</Empty>}
      <div className="mb-4 space-y-1">
        {p.favouriteItems.map((f) => (
          <div key={f.productId} className="card flex justify-between text-sm">
            <span>{f.name}</span>
            <span className="text-slate-500">
              {f.quantity} in {f.orders} orders
            </span>
          </div>
        ))}
      </div>

      <h2 className="mb-2 font-semibold">Recent orders</h2>
      <div className="space-y-2">
        {p.recentOrders.map((o) => (
          <div key={o.id} className="card text-sm">
            <div className="flex items-center justify-between">
              <span className="font-medium">
                {o.orderNo} · {dmy(o.orderDate)}
              </span>
              <span>{inr(o.totalAmount)}</span>
            </div>
            <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-slate-500">
              <Badge value={o.status} />
              <span>{o.source}</span>
              <span>{o.paymentStatus}</span>
            </div>
            {o.items && <div className="mt-1 text-xs text-slate-600">{o.items}</div>}
          </div>
        ))}
      </div>
    </div>
  )
}
