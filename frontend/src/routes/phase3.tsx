import type { ReactNode } from 'react'
import { Route } from 'react-router-dom'
import ImportWizard from '../pages/channels/ImportWizard'
import Documents from '../pages/channels/Documents'
import DocumentReview from '../pages/channels/DocumentReview'
import WhatsAppInbox from '../pages/channels/WhatsAppInbox'
import CustomersPage from '../pages/channels/CustomersPage'
import CustomerProfile from '../pages/channels/CustomerProfile'
import Notifications from '../pages/channels/Notifications'

export const navItems: { to: string; label: string; icon: string; perm?: string }[] = [
  { to: '/imports', label: 'Import', icon: '📥', perm: 'imports.manage' },
  { to: '/documents', label: 'Documents', icon: '📷', perm: 'documents.view' },
  { to: '/whatsapp', label: 'WhatsApp', icon: '💬', perm: 'whatsapp.view' },
  { to: '/customers', label: 'Customers', icon: '🧑‍🤝‍🧑', perm: 'customers.view' },
  { to: '/notifications', label: 'Notifications', icon: '🔔' },
]

export const routeElements: ReactNode = (
  <>
    <Route path="imports" element={<ImportWizard />} />
    <Route path="documents" element={<Documents />} />
    <Route path="documents/:id" element={<DocumentReview />} />
    <Route path="whatsapp" element={<WhatsAppInbox />} />
    <Route path="customers" element={<CustomersPage />} />
    <Route path="customers/:id" element={<CustomerProfile />} />
    <Route path="notifications" element={<Notifications />} />
  </>
)
