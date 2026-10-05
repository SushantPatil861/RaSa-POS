import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth'
import Layout, { MorePage } from './components/Layout'
import { Loading } from './components/ui'
import Login from './pages/Login'
import Home from './pages/Home'
import NewSale from './pages/NewSale'
import DailyEntry from './pages/DailyEntry'
import { DayPage, SaleDetailPage, SalesList } from './pages/Sales'
import Orders from './pages/Orders'
import { MenuCompare, MenuDetailPage, MenuList } from './pages/Menus'
import Expenses from './pages/Expenses'
import Purchases from './pages/Purchases'
import Reports from './pages/Reports'
import Settings from './pages/Settings'
import { routeElements as phase2Routes } from './routes/phase2'
import { routeElements as phase3Routes } from './routes/phase3'
import { routeElements as phase4Routes } from './routes/phase4'

export default function App() {
  const { user, loading, can } = useAuth()
  if (loading) return <Loading />
  if (!user) return <Login />
  // Land on the dashboard if allowed, otherwise on the first thing this role can do.
  const home = can('reports.view') ? <Home /> : <Navigate to={can('orders.view') ? '/orders' : can('sales.view') ? '/sales' : '/settings'} replace />
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={home} />
        <Route path="more" element={<MorePage />} />
        <Route path="orders" element={<Orders />} />
        <Route path="sales" element={<SalesList />} />
        <Route path="sales/new" element={<NewSale />} />
        <Route path="sales/daily" element={<DailyEntry />} />
        <Route path="sales/:id" element={<SaleDetailPage />} />
        <Route path="sales/:id/edit" element={<NewSale />} />
        <Route path="days/:date" element={<DayPage />} />
        <Route path="menus" element={<MenuList />} />
        <Route path="menus/compare" element={<MenuCompare />} />
        <Route path="menus/:id" element={<MenuDetailPage />} />
        <Route path="expenses" element={<Expenses />} />
        <Route path="purchases" element={<Purchases />} />
        <Route path="reports" element={<Reports />} />
        <Route path="settings" element={<Settings />} />
        {phase2Routes}
        {phase3Routes}
        {phase4Routes}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}
