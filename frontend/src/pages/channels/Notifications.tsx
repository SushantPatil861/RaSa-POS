import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { get, post } from '../../api'
import { ErrorNote, Loading, PageTitle } from '../../components/ui'
import type { AppNotification } from '../../types.phase3'
import { ago, Empty } from './shared'

/** Small bell with an unread count; the integrator may drop it into the header. */
export function NotificationBell() {
  const q = useQuery({
    queryKey: ['notif-count'],
    queryFn: () => get<{ count: number }>('/notifications/unread-count'),
    refetchInterval: 30000,
  })
  const n = q.data?.count ?? 0
  return (
    <Link to="/notifications" className="relative px-2 text-xl" aria-label={`Notifications, ${n} unread`}>
      🔔
      {n > 0 && (
        <span className="absolute -right-1 -top-1 rounded-full bg-red-600 px-1.5 text-[10px] font-bold text-white">
          {n > 99 ? '99+' : n}
        </span>
      )}
    </Link>
  )
}

export default function Notifications() {
  const qc = useQueryClient()
  const nav = useNavigate()
  const q = useQuery({
    queryKey: ['notifications'],
    queryFn: () => get<AppNotification[]>('/notifications'),
    refetchInterval: 30000,
  })
  const done = () => {
    qc.invalidateQueries({ queryKey: ['notifications'] })
    qc.invalidateQueries({ queryKey: ['notif-count'] })
  }
  const read = useMutation({ mutationFn: (id: number) => post(`/notifications/${id}/read`), onSuccess: done })
  const readAll = useMutation({ mutationFn: () => post('/notifications/read-all'), onSuccess: done })
  const unread = q.data?.filter((n) => !n.read).length ?? 0

  return (
    <div>
      <PageTitle title="Notifications">
        <button className="btn-ghost" disabled={unread === 0 || readAll.isPending} onClick={() => readAll.mutate()}>
          Mark all read
        </button>
      </PageTitle>
      {q.isLoading && <Loading />}
      <ErrorNote error={q.error || read.error || readAll.error} />
      {q.data && q.data.length === 0 && <Empty>Nothing new.</Empty>}
      <div className="space-y-2">
        {q.data?.map((n) => (
          <button
            key={n.id}
            className={`card w-full text-left ${n.read ? '' : 'border-l-4 border-l-brand-700'}`}
            onClick={() => {
              if (!n.read) read.mutate(n.id)
              if (n.link) nav(n.link)
            }}
          >
            <div className="flex justify-between gap-2">
              <span className={n.read ? 'text-slate-600' : 'font-semibold'}>{n.title}</span>
              <span className="shrink-0 text-xs text-slate-500">{ago(n.createdAt)}</span>
            </div>
            {n.body && <div className="mt-1 text-sm text-slate-600">{n.body}</div>}
          </button>
        ))}
      </div>
    </div>
  )
}
