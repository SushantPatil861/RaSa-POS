import type { ReactNode } from 'react'
import { Route } from 'react-router-dom'
import Ingredients from '../pages/inventory/Ingredients'
import Recipes from '../pages/inventory/Recipes'
import Stock from '../pages/inventory/Stock'
import Wastage from '../pages/inventory/Wastage'
import Containers from '../pages/inventory/Containers'
import CashClose from '../pages/inventory/CashClose'
import Costing from '../pages/inventory/Costing'
import SupplierLedger from '../pages/inventory/SupplierLedger'

// Phase 2: costing, inventory, wastage, cash reconciliation. Wired into App.tsx / Layout.tsx by the integrator.
export const navItems: { to: string; label: string; icon: string; perm?: string }[] = [
  { to: '/stock', label: 'Stock', icon: '📦', perm: 'inventory.view' },
  { to: '/ingredients', label: 'Ingredients', icon: '🥛', perm: 'inventory.view' },
  { to: '/recipes', label: 'Recipes', icon: '📖', perm: 'recipes.view' },
  { to: '/wastage', label: 'Wastage', icon: '🗑️', perm: 'wastage.create' },
  { to: '/containers', label: 'Containers', icon: '🍨', perm: 'containers.manage' },
  { to: '/cash-close', label: 'Cash close', icon: '💵', perm: 'cash.view' },
  { to: '/costing', label: 'Costing', icon: '🧮', perm: 'costing.view' },
  { to: '/supplier-ledger', label: 'Supplier ledger', icon: '🏷️', perm: 'supplier_ledger.view' },
]

export const routeElements: ReactNode = (
  <>
    <Route path="stock" element={<Stock />} />
    <Route path="ingredients" element={<Ingredients />} />
    <Route path="recipes" element={<Recipes />} />
    <Route path="wastage" element={<Wastage />} />
    <Route path="containers" element={<Containers />} />
    <Route path="cash-close" element={<CashClose />} />
    <Route path="costing" element={<Costing />} />
    <Route path="supplier-ledger" element={<SupplierLedger />} />
  </>
)
