// Phase 2 API shapes (costing, inventory, wastage, cash). Decimals arrive as strings; unknown values are null.
export type Dec = string

export interface Unit { code: string; name: string; dimension: string; baseUnit: string; toBase: Dec }

export interface Ingredient {
  id: number; name: string; kind: 'RAW' | 'PREPARED'; baseUnit: 'g' | 'ml' | 'pcs'; category: string | null
  minLevel: Dec | null; active: boolean; notes: string | null; hasRecipe: boolean
}
export interface PackSpec { id: number; ingredientId: number; name: string; quantityBase: Dec; basis: string; notes: string | null; active: boolean }
export interface CostHistory {
  id: number; ingredientId: number; effectiveDate: string; costPerBase: Dec; source: string
  purchaseItemId: number | null; purchaseId: number | null; notes: string | null; entryAt: string
  createdByName: string | null; voided: boolean; voidReason: string | null
}
export interface IngredientCost {
  ingredientId: number; name: string; kind: string; baseUnit: string; costPerBase: Dec | null
  status: 'COMPLETE' | 'PARTIAL' | 'MISSING'; estimated: boolean; effectiveDate: string | null; source: string | null; missing: string[]
}
export interface UnlinkedItem {
  purchaseItemId: number; purchaseId: number; purchaseNo: string; txnDate: string; supplierName: string | null
  itemName: string; quantity: Dec; unit: string | null; total: Dec
}
export interface PurchaseLink {
  id: number; purchaseItemId: number; purchaseId: number; purchaseNo: string; txnDate: string; ingredientId: number
  ingredientName: string; baseUnit: string; itemName: string; itemTotal: Dec; baseQuantity: Dec; packSpecId: number | null
  status: 'ACTIVE' | 'UNLINKED'; attention: string | null; linkedAt: string; unlinkReason: string | null
}

export interface RecipeVariant {
  variantId: number; productId: number; productName: string; variantName: string; menuPriceInclusive: Dec | null
  hasRecipe: boolean; costPerPortion: Dec | null; status: string; estimated: boolean; marginPct: Dec | null
}
export interface RecipeLine {
  versionId: number; ingredientId: number; ingredientName: string; kind: string; baseUnit: string
  quantityBase: Dec; wastePct: Dec; notes: string | null
}
export interface RecipeVersion {
  id: number; versionNo: number; effectiveFrom: string; outputQty: Dec; reason: string | null; notes: string | null
  createdAt: string; lines: RecipeLine[]
}
export interface RecipeDetail {
  recipeId: number | null; variantId: number | null; ingredientId: number | null; title: string; outputUnit: string
  versions: RecipeVersion[]
}
export interface RecipeLineCost {
  ingredientId: number; name: string; kind: string | null; baseUnit: string | null; quantityBase: Dec; wastePct: Dec
  costPerBase: Dec | null; lineCost: Dec | null; status: string; estimated: boolean; missing: string[]
}
export interface RecipeCost {
  asOf: string; versionId: number | null; versionNo: number | null; outputQty: Dec | null; status: string
  totalCost: Dec | null; costPerOutput: Dec | null; estimated: boolean; missing: string[]
  menuPriceInclusive: Dec | null; menuPriceExGst: Dec | null; marginAmount: Dec | null; marginPct: Dec | null
  lines: RecipeLineCost[]; notes: string[]
}

export interface StockRow {
  ingredientId: number; name: string; kind: string; baseUnit: string; category: string | null; minLevel: Dec | null
  active: boolean; ledgerBalance: Dec; lastCountQty: Dec | null; lastCountDate: string | null
  movementsSinceBaseline: Dec | null; theoreticalUsage: Dec | null; expectedStock: Dec | null; baseline: string
  lowStock: boolean; flags: string[]
}
export interface UsageGaps { from: string; to: string; summaryOnlyDays: number; unrecipedNetSales: Dec; itemisedNetSales: Dec }
export interface StockReport { asOf: string; rows: StockRow[]; gaps: UsageGaps | null; notes: string[] }
export interface UsageRow { ingredientId: number; name: string; baseUnit: string; theoreticalUsage: Dec | null; wastageRecorded: Dec | null }
export interface UsageReport { from: string; to: string; rows: UsageRow[]; gaps: UsageGaps; notes: string[] }
export interface Movement {
  id: number; ingredientId: number; ingredientName: string; baseUnit: string; movementType: string; quantityBase: Dec
  txnDate: string; entryAt: string; reason: string | null; refType: string | null; refId: number | null; source: string
  reversesId: number | null; reversed: boolean
}
export interface CountHeader {
  id: number; countDate: string; status: 'DRAFT' | 'POSTED' | 'VOID'; notes: string | null; createdAt: string
  postedAt: string | null; voidReason: string | null; lineCount: number
}
export interface CountLine {
  id: number; ingredientId: number; ingredientName: string; baseUnit: string; countedQty: Dec; expectedQty: Dec | null
  variance: Dec | null; unitCost: Dec | null; costStatus: string | null; costEstimated: boolean
  varianceValue: Dec | null; note: string | null; live: boolean
}
export interface CountDetail { header: CountHeader; lines: CountLine[]; flags: string[] }
export interface VarianceLine {
  countId: number; countDate: string; ingredientId: number; ingredientName: string; baseUnit: string; countedQty: Dec
  expectedQty: Dec | null; variance: Dec | null; unitCost: Dec | null; varianceValue: Dec | null
  costStatus: string | null; costEstimated: boolean
}
export interface VarianceReport {
  from: string; to: string; rows: VarianceLine[]; totalValueKnown: Dec | null; linesWithoutCost: number
  valuesHidden: boolean; flags: string[]
}
export interface PrepBatch {
  id: number; ingredientId: number; ingredientName: string; baseUnit: string; txnDate: string; plannedQty: Dec
  actualQty: Dec; yieldPct: Dec | null; costTotal: Dec | null; costPerUnit: Dec | null; costStatus: string | null
  costEstimated: boolean; notes: string | null; voidState: string; voidReason: string | null; entryAt: string
}

export interface Wastage {
  id: number; txnDate: string; entryAt: string; ingredientId: number | null; ingredientName: string | null
  baseUnit: string | null; productId: number | null; variantId: number | null; productName: string | null
  variantName: string | null; quantity: Dec; reason: string; unitCost: Dec | null; costTotal: Dec | null
  costStatus: string; costEstimated: boolean; notes: string | null; voidState: string; voidReason: string | null; flags: string[]
}
export interface WastageSummary {
  from: string; to: string; totalCostKnown: Dec | null; entries: number; entriesWithoutCost: number; costsHidden: boolean
  byReason: { reason: string; entries: number; costKnown: Dec | null; entriesWithoutCost: number }[]
  byMonth: { period: string; entries: number; costKnown: Dec | null; entriesWithoutCost: number }[]
  flags: string[]
}

export interface Container {
  id: number; containerCode: string; ingredientId: number; ingredientName: string; baseUnit: string
  packSpecId: number | null; packSpecName: string | null; openedDate: string; openingQty: Dec; quantityBasis: string
  status: 'OPEN' | 'FINISHED'; finishedDate: string | null; closingQty: Dec | null; actualUsage: Dec | null
  theoreticalUsage: Dec | null; wastageRecorded: Dec | null; overServingPct: Dec | null; overServingFlag: boolean | null
  notes: string | null; flags: string[]; theoreticalUsageSoFar: Dec | null; expectedRemaining: Dec | null
  thresholdPct: Dec; summary: string | null
}

export interface Coverage {
  itemisedNetSales: Dec; completeNetSales: Dec; partialNetSales: Dec; noRecipeNetSales: Dec; coveragePct: Dec | null
  itemisedDays: number; summaryOnlyDays: number; summaryOnlyNetSales: Dec; summaryOnlyGross: Dec; summaryOnlyDates: string[]
}
export interface ProductMargin {
  productId: number; variantId: number; productName: string; variantName: string; quantity: Dec; netSales: Dec
  cogs: Dec | null; grossProfit: Dec | null; marginPct: Dec | null; status: 'COMPLETE' | 'PARTIAL' | 'NO_RECIPE'
  estimated: boolean; missingIngredients: string[]
}
export interface ProductMarginReport { from: string; to: string; coverage: Coverage; flags: string[]; rows: ProductMargin[] }
export interface GrossProfit {
  from: string; to: string; coverage: Coverage; itemisedNetSales: Dec; cogsKnown: Dec; grossProfit: Dec; marginPct: Dec | null
  grossProfitCompleteLines: Dec; completeLinesNetSales: Dec; completeLinesCogs: Dec; flags: string[]; notes: string[]
  missingIngredients: string[]
}
export interface RealPnl {
  from: string; to: string
  pnl: {
    grossSalesInclusive: Dec; netSalesExGst: Dec; gstCollected: Dec; purchasesInventory: Dec; purchasesExpenseTreated: Dec
    expensesTotal: Dec; indicativeSurplus: Dec; notes: string[]
  }
  costing: GrossProfit; wastageCost: Dec; wastageEntriesWithoutCost: number; netOperatingProfit: Dec | null
  flags: string[]; notes: string[]
}

export interface CashExplanation { id: number; category: string; amount: Dec; note: string | null; createdAt: string }
export interface CashDay {
  txnDate: string; id: number | null; status: 'NOT_STARTED' | 'DRAFT' | 'CLOSED'; basis: string | null
  openingFloat: Dec; suggestedOpeningFloat: Dec | null; cashSales: Dec | null; cashExpenses: Dec
  supplierCashPayments: Dec; liveExpectedCash: Dec; expectedCash: Dec | null; countedCash: Dec | null
  difference: Dec | null; explained: Dec; unexplained: Dec | null; flags: string[]; explanations: CashExplanation[]
  notes: string | null; reopenCount: number; closedAt: string | null; closedByName: string | null
}
export interface CashDaySummary {
  txnDate: string; status: string; openingFloat: Dec; expectedCash: Dec | null; countedCash: Dec | null
  difference: Dec | null; explained: Dec; unexplained: Dec | null; flags: string[]
}
export interface Settlement {
  id: number; txnDate: string; method: string; receivedAmount: Dec; reference: string | null; notes: string | null
  entryAt: string; voidState: string; voidReason: string | null
}
export interface SettlementSummary {
  from: string; to: string
  rows: { txnDate: string; method: string; expected: Dec | null; received: Dec | null; difference: Dec | null; status: string; entries: number }[]
  totals: { method: string; expected: Dec; received: Dec; difference: Dec; pendingDays: number; daysWithDifference: number }[]
  notes: string[]
}

export interface SupplierBalance {
  supplierId: number; supplierName: string; purchasesTotal: Dec; creditPurchases: Dec; paymentsTotal: Dec; balance: Dec
}
export interface SupplierLedger {
  supplierId: number; supplierName: string; asOf: string; purchasesTotal: Dec; paidAtPurchase: Dec; creditPurchases: Dec
  paymentsTotal: Dec; balance: Dec; advance: Dec; balanceIncludingPaidAtPurchase: Dec
  aging: {
    bucket0to30: Dec; bucket31to60: Dec; bucket60plus: Dec; outstanding: Dec; advance: Dec
    openBills: { id: number; date: string; original: Dec; remaining: Dec; ageDays: number; bucket: string }[]
  }
  purchases: { id: number; purchaseNo: string; txnDate: string; invoiceNo: string | null; totalAmount: Dec; paymentStatus: string; confirmationState: string }[]
  payments: { id: number; txnDate: string; amount: Dec; method: string; reference: string | null; notes: string | null }[]
  flags: string[]; notes: string[]
}
export interface SupplierPayment {
  id: number; supplierId: number; supplierName: string; txnDate: string; entryAt: string; amount: Dec; method: string
  reference: string | null; notes: string | null; voidState: string; voidReason: string | null
}
