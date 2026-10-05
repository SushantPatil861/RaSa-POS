import type { ReactNode } from 'react'
import { Route } from 'react-router-dom'
import Gst from '../pages/compliance/Gst'
import Tds from '../pages/compliance/Tds'
import Payroll, { PayslipPage } from '../pages/compliance/Payroll'
import Hygiene from '../pages/compliance/Hygiene'
import Compliance from '../pages/compliance/Compliance'

export const navItems: { to: string; label: string; icon: string; perm?: string }[] = [
  { to: '/gst', label: 'GST', icon: '🧾', perm: 'gst.view' },
  { to: '/tds', label: 'TDS / TCS', icon: '📑', perm: 'tds.view' },
  { to: '/payroll', label: 'Payroll', icon: '👥', perm: 'payroll.view' },
  { to: '/attendance', label: 'Attendance', icon: '🗓️', perm: 'attendance.manage' },
  { to: '/hygiene', label: 'Hygiene', icon: '🧼', perm: 'hygiene.view' },
  { to: '/compliance', label: 'Compliance', icon: '📅', perm: 'compliance.view' },
]

export const routeElements: ReactNode = (
  <>
    <Route path="gst" element={<Gst />} />
    <Route path="tds" element={<Tds />} />
    <Route path="payroll" element={<Payroll />} />
    <Route path="payroll/payslip/:id" element={<PayslipPage />} />
    <Route path="attendance" element={<Payroll initialTab="attendance" />} />
    <Route path="hygiene" element={<Hygiene />} />
    <Route path="compliance" element={<Compliance />} />
  </>
)
