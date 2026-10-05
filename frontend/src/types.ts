// Shapes returned by the API (money values are strings).
export type Money = string

export interface SaleHeader {
  id: number; saleNo: string; txnDate: string; entryAt: string; channel: string; source: string
  totalInclusive: Money; netTaxable: Money; taxAmount: Money; discountTotal: Money; grossInclusive: Money
  voidState: string; voidReason: string | null; notes: string | null; revision: number; version: number
  partial: boolean; estimated: boolean
}
export interface SaleItem {
  id: number; productId: number; variantId: number; productName: string; variantName: string; categoryName: string
  quantity: string; listPriceAtSale: Money | null; unitPrice: Money; discount: Money; lineTotal: Money
  taxRate: string | null; taxStatus: string
}
export interface Payment { id: number; method: string; amount: Money; status: string; reference: string | null }
export interface SaleDetail { sale: SaleHeader; flags: string[]; backdatedDays: number; items: SaleItem[]; payments: Payment[] }
export interface SaleSummary {
  id: number; saleNo: string; txnDate: string; channel: string; source: string
  totalInclusive: Money; netTaxable: Money; taxAmount: Money; voidState: string; itemCount: number; flags: string[]
}

export interface MenuVersion {
  id: number; name: string; effectiveFrom: string | null; status: string; reason: string | null
  basedOnVersionId: number | null; itemCount: number; editable: boolean
}
export interface MenuItem {
  id: number; menuVersionId: number; productId: number; variantId: number; price: Money; displayName: string
  categoryName: string; subcategory: string | null; variantName: string; sizeLabel: string | null
  availability: string; addon: boolean; sortOrder: number
}
export interface MenuDetail { version: MenuVersion; items: MenuItem[] }

export interface ComparisonRow {
  productId: number; variantId: number; name: string; variantName: string
  oldPrice: Money | null; newPrice: Money | null; delta: Money | null; pct: string | null; changes: string[]
}
export interface Comparison {
  a: MenuVersion; b: MenuVersion; rows: ComparisonRow[]
  added: number; removed: number; renamed: number; priceUp: number; priceDown: number; unchanged: number
}

export interface DayRow {
  txnDate: string; basis: string; reviewStatus: string; chosen: string; itemisedTotal: Money; summaryTotal: Money
  difference: Money | null; totalInclusive: Money | null; ordersCount: number | null; netTaxable: Money | null
  taxAmount: Money | null; cash: Money | null; upi: Money | null; card: Money | null; whatsapp: Money | null
  swiggy: Money | null; zomato: Money | null; otherOnline: Money | null
}
export interface Quality {
  daysInRange: number; daysWithSales: number; daysItemisedOnly: number; daysSummaryOnly: number; daysBoth: number
  daysPendingApproval: number; daysStaleApproval: number; daysMissing: number
  estimatedExpenses: number; partialRecords: number; backdatedRecords: number
}
export interface Pnl {
  from: string; to: string; grossSalesInclusive: Money; netSalesExGst: Money; gstCollected: Money
  purchasesInventory: Money; purchasesExpenseTreated: Money; purchasesFixedAssets: Money; expensesTotal: Money
  expensesByCategory: { categoryId: number | null; category: string; amount: Money; count: number }[]
  indicativeSurplus: Money; cogsAvailable: boolean; notes: string[]; quality: Quality
}
export interface MonthRow {
  month: string; netSalesExGst: Money; gstCollected: Money; grossSales: Money; purchases: Money
  expenses: Money; indicativeSurplus: Money; daysWithSales: number; daysInPeriod: number
}
export interface HealthItem { code: string; severity: string; count: number; message: string }
export interface DataHealth {
  items: HealthItem[]; missingDays: string[]; pendingApprovalDays: string[]; staleApprovalDays: string[]
}
export interface Dashboard {
  date: string; todayGross: Money; todayOrders: number; todayExpenses: Money; monthGross: Money
  monthNetExGst: Money; monthExpenses: Money; monthPurchases: Money; todayChannels: { method: string; amount: Money }[]
  pendingApprovals: number; missingDaysThisMonth: number; openOrders: number
}
export interface ProductRow {
  productId: number; productName: string; categoryName: string; quantity: string
  revenueInclusive: Money; netExGst: Money; discount: Money
}
export interface Expense {
  id: number; txnDate: string; entryAt: string; categoryId: number | null; categoryName: string | null; amount: Money
  paymentMethod: string | null; paidTo: string | null; recurring: boolean; notes: string | null; source: string
  confirmationState: string; estimated: boolean; partial: boolean; revision: number; voidState: string
  voidReason: string | null; version: number; flags: string[]
}
export interface Supplier { id: number; name: string; contact: string | null; category: string | null; active: boolean }
export interface PurchaseItem { id: number; itemName: string; quantity: string; unit: string | null; unitPrice: Money | null; total: Money; treatment: string }
export interface Purchase {
  id: number; purchaseNo: string; txnDate: string; supplierId: number | null; supplierName: string | null
  invoiceNo: string | null; paymentStatus: string; totalAmount: Money; notes: string | null; revision: number
  voidState: string; version: number; flags: string[]; items: PurchaseItem[]
}
export interface Order {
  id: number; orderNo: string; orderDate: string; orderTime: string; source: string; orderType: string; status: string
  paymentStatus: string; totalAmount: Money; customerName: string | null; customerMobile: string | null
  saleId: number | null; specialInstructions: string | null; version: number
}
export interface OrderItem { id: number; itemName: string; quantity: string; unitPrice: Money; discount: Money; lineTotal: Money }
export interface OrderDetail { order: Order; items: OrderItem[] }
export interface DayView {
  date: string; calendarType: string | null; basis: string; reviewStatus: string; chosen: string
  itemisedCount: number; itemisedTotal: Money; summaryNetTotal: Money | null; difference: Money | null
  review: { decision: string; reason: string | null; approvedByName: string | null; approvedAt: string } | null
}
export interface SummaryDto {
  summary: {
    id: number; txnDate: string; totalSales: Money; cash: Money; upi: Money; card: Money; whatsapp: Money
    swiggy: Money; zomato: Money; otherOnline: Money; ordersCount: number | null; discounts: Money; refunds: Money
    wastage: Money; otherIncome: Money; notes: string | null; revision: number; voidState: string; version: number
  }
  netCollected: Money; tenderTotal: Money; tenderDifference: Money; flags: string[]; backdatedDays: number
}
export interface Product { id: number; name: string; categoryId: number; categoryName: string; subcategory: string | null; lifecycleStatus: string; discontinuedOn: string | null }
export interface UserRow { id: number; name: string; email: string; mobile: string | null; roleCode: string; roleName: string; active: boolean }
export interface TaxRule { id: number; scope: string; rate: string; itcEligible: boolean | null; effectiveFrom: string; notes: string | null }
export interface AuditRow { id: number; entityType: string; entityId: number | null; action: string; txnDate: string | null; reason: string | null; source: string | null; userName: string | null; at: string }
