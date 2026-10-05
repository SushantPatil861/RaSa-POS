import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../auth'
import { navItems as p2 } from '../routes/phase2'
import { navItems as p3 } from '../routes/phase3'
import { navItems as p4 } from '../routes/phase4'
import { NotificationBell } from '../pages/channels/Notifications'

interface Item { to: string; label: string; icon: string; perm?: string }

const CORE: Item[] = [
  { to: '/', label: 'Home', icon: '🏠', perm: 'reports.view' },
  { to: '/orders', label: 'Orders', icon: '🧾', perm: 'orders.view' },
  { to: '/sales/new', label: 'New sale', icon: '➕', perm: 'sales.create' },
  { to: '/sales/daily', label: 'Daily entry', icon: '📒', perm: 'sales.create' },
  { to: '/sales', label: 'Sales', icon: '💰', perm: 'sales.view' },
  { to: '/menus', label: 'Menu', icon: '🍦', perm: 'menu.view' },
  { to: '/expenses', label: 'Expenses', icon: '💸', perm: 'expenses.view' },
  { to: '/purchases', label: 'Purchases', icon: '📦', perm: 'purchases.view' },
  { to: '/reports', label: 'Reports', icon: '📊', perm: 'reports.view' },
  { to: '/settings', label: 'Settings', icon: '⚙️' },
]

interface Group { title: string; items: Item[] }
const GROUPS: Group[] = [
  { title: 'Daily', items: CORE.filter((i) => ['/', '/orders', '/sales/new', '/sales/daily', '/sales'].includes(i.to)) },
  { title: 'Money', items: CORE.filter((i) => ['/menus', '/expenses', '/purchases', '/reports'].includes(i.to)) },
  { title: 'Stock & costing', items: p2 },
  { title: 'Channels & documents', items: p3 },
  { title: 'Compliance & people', items: p4 },
  { title: 'Admin', items: CORE.filter((i) => i.to === '/settings') },
]
const ITEMS: Item[] = GROUPS.flatMap((g) => g.items)

// Bottom bar on phones shows the five most-used; everything is in the drawer on desktop sidebar.
const MOBILE = ['/', '/orders', '/sales/new', '/sales', '/more']

export default function Layout() {
  const { user, can, signOut } = useAuth()
  const items = ITEMS.filter((i) => !i.perm || can(i.perm))
  const mobile = items.filter((i) => MOBILE.includes(i.to)).slice(0, 4)
  return (
    <div className="min-h-screen md:flex">
      <aside className="hidden w-56 shrink-0 flex-col bg-brand-700 p-4 text-white md:flex">
        <div className="mb-4 flex items-center justify-between text-lg font-bold"><span>🍨 Valentina</span><NotificationBell /></div>
        <nav className="flex-1 space-y-3 overflow-y-auto">
          {GROUPS.map((g) => {
            const vis = g.items.filter((i) => !i.perm || can(i.perm))
            if (!vis.length) return null
            return (
              <div key={g.title}>
                <div className="mb-1 px-3 text-[10px] font-semibold uppercase tracking-wider opacity-60">{g.title}</div>
                {vis.map((i) => (
                  <NavLink
                    key={i.to}
                    to={i.to}
                    end={i.to === '/' || i.to === '/sales'}
                    className={({ isActive }) =>
                      `flex items-center gap-2 rounded-lg px-3 py-1.5 text-sm ${isActive ? 'bg-white/20 font-semibold' : 'hover:bg-white/10'}`
                    }
                  >
                    <span>{i.icon}</span>
                    {i.label}
                  </NavLink>
                ))}
              </div>
            )
          })}
        </nav>
        <div className="mt-4 border-t border-white/20 pt-3 text-xs">
          <div className="font-medium">{user?.name}</div>
          <div className="opacity-70">{user?.role}</div>
          <button className="mt-2 underline" onClick={signOut}>
            Sign out
          </button>
        </div>
      </aside>
      <main className="mx-auto w-full max-w-5xl flex-1 p-4 pb-24 md:pb-8">
        <Outlet />
      </main>
      <nav className="fixed inset-x-0 bottom-0 z-40 flex border-t border-slate-200 bg-white md:hidden">
        {mobile.map((i) => (
          <NavLink
            key={i.to}
            to={i.to}
            end
            className={({ isActive }) =>
              `flex flex-1 flex-col items-center py-2 text-[11px] ${isActive ? 'font-semibold text-brand-700' : 'text-slate-500'}`
            }
          >
            <span className="text-lg">{i.icon}</span>
            {i.label}
          </NavLink>
        ))}
        <NavLink
          to="/more"
          className={({ isActive }) =>
            `flex flex-1 flex-col items-center py-2 text-[11px] ${isActive ? 'font-semibold text-brand-700' : 'text-slate-500'}`
          }
        >
          <span className="text-lg">☰</span>
          More
        </NavLink>
      </nav>
    </div>
  )
}

export function MorePage() {
  const { user, can, signOut } = useAuth()
  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-semibold text-brand-700">More</h1>
        <NotificationBell />
      </div>
      {GROUPS.map((g) => {
        const vis = g.items.filter((i) => !i.perm || can(i.perm))
        if (!vis.length) return null
        return (
          <div key={g.title} className="mb-4">
            <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">{g.title}</div>
            <div className="grid grid-cols-2 gap-3">
              {vis.map((i) => (
                <NavLink key={i.to} to={i.to} className="card flex items-center gap-2 text-sm font-medium">
                  <span className="text-xl">{i.icon}</span>
                  {i.label}
                </NavLink>
              ))}
            </div>
          </div>
        )
      })}
      <div className="card mt-4 text-sm">
        Signed in as <b>{user?.name}</b> ({user?.role})
        <div>
          <button className="btn-ghost mt-3" onClick={signOut}>
            Sign out
          </button>
        </div>
      </div>
    </div>
  )
}
