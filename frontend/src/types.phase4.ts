// Phase 4 (GST workpapers, TDS/TCS, payroll, hygiene, compliance) API shapes. Money values are strings.
export type Money = string

// ------------------------------------------------------------------ GST
export interface GstMonthInfo { start: string; end: string; label: string; qrmpPaymentMonth: boolean }
export interface GstQuarter {
  start: string; end: string; label: string; quarter: number; months: GstMonthInfo[]
  latestWorkpaperId: number | null; latestVersion: number | null; latestStatus: string | null
}
export interface RateLine { rate: string | null; taxable: Money; cgst: Money; sgst: Money; totalTax: Money; days: number }
export interface OutwardTotals { taxable: Money; cgst: Money; sgst: Money; totalTax: Money; days: number }
export interface ItcTotals { taxable: Money; cgst: Money; sgst: Money; igst: Money; totalTax: Money; count: number }
export interface ItcSummary { eligible: ItcTotals; undecided: ItcTotals; ineligible: ItcTotals }
export interface Credit { igst: Money; cgst: Money; sgst: Money }
export interface Settlement {
  outputCgst: Money; outputSgst: Money; itcUsed: Money; payableCgst: Money; payableSgst: Money; payableTotal: Money
  carryForward: Credit; carryForwardTotal: Money
}
export interface GstMonthFigures {
  start: string; end: string; label: string; qrmpPaymentMonth: boolean; outwardByRate: RateLine[]
  outward: OutwardTotals; itc: ItcSummary; settlement: Settlement; pmt06Estimate: Money | null; note: string
}
export interface GstItcLine {
  purchaseId: number; purchaseNo: string; invoiceNo: string | null; invoiceDate: string | null; txnDate: string; itcDate: string
  supplierName: string | null; supplierGstin: string | null; taxableValue: Money; cgst: Money; sgst: Money; igst: Money
  totalTax: Money; itcEligible: boolean | null
}
export interface GstFigures {
  periodType: string; periodStart: string; periodEnd: string; label: string; fyLabel: string
  months: GstMonthFigures[]
  gstr1: { placeOfSupply: string; lines: RateLine[]; totals: OutwardTotals; note: string }
  gstr3b: {
    table31a: { taxableValue: Money; igst: Money; cgst: Money; sgst: Money; cess: Money; note: string }
    table31bToE: string; table4: ItcSummary; openingCredit: Credit; table61: Settlement
    pmt06PaidLogged: Money; pmt06FilingsLogged: number; balanceAfterPmt06: Money; note: string
  }
  itcLines: GstItcLine[]; notes: string[]
}
export interface GstWarning { code: string; severity: 'ERROR' | 'WARN' | 'INFO'; message: string; dates: string[] }
export interface GstComputed { figures: GstFigures; warnings: GstWarning[] }
export interface GstWorkpaperRow {
  id: number; periodType: string; periodStart: string; periodEnd: string; status: string; version: number
  preparedAt: string; preparedByName: string | null; reviewedAt: string | null; reviewedByName: string | null
  notes: string | null; warningCount: number
}
export interface GstWorkpaper extends Omit<GstWorkpaperRow, 'warningCount'> {
  latest: boolean; figures: GstFigures; warnings: GstWarning[]
}
export interface GstFiling {
  id: number; returnType: string; periodStart: string; periodEnd: string; filedOn: string; arn: string | null
  taxPaid: Money | null; workpaperId: number | null; notes: string | null; voidState: string; voidReason: string | null
  version: number; createdAt: string; createdByName: string | null
}
export interface ItcPurchase {
  purchaseId: number; purchaseNo: string; txnDate: string; invoiceNo: string | null; invoiceDate: string | null
  supplierName: string | null; supplierMasterGstin: string | null; totalAmount: Money; detailId: number | null
  taxableValue: Money | null; cgst: Money | null; sgst: Money | null; igst: Money | null; totalTax: Money | null
  supplierGstin: string | null; itcEligible: boolean | null; notes: string | null; version: number | null
}

// ------------------------------------------------------------------ TDS / TCS
export interface TdsSection {
  id: number; code: string; description: string; payeeType: string; rate: string; singleThreshold: Money | null
  annualThreshold: Money | null; effectiveFrom: string; verifyNote: string | null; source: string; current: boolean
}
export interface TdsEntry {
  id: number; txnDate: string; entryAt: string; deducteeName: string; deducteePan: string | null; section: string
  payeeType: string; paymentAmount: Money; rateApplied: string | null; tdsAmount: Money | null
  thresholdStatus: 'BELOW_THRESHOLD' | 'DEDUCT' | 'CHECK_WITH_CA'; thresholdNote: string | null
  depositDueDate: string | null; depositedOn: string | null; challanNo: string | null; status: 'PENDING' | 'DEPOSITED'
  expenseId: number | null; notes: string | null; source: string; revision: number; version: number
  voidState: string; voidReason: string | null; fyLabel: string; createdByName: string | null
  panMissing: boolean; overdue: boolean; flags: string[]
}
export interface TdsThresholdRow {
  fyStartYear: number; deducteeKey: string; deducteeName: string; deducteePan: string | null; section: string
  payeeType: string; entries: number; totalPaid: Money; totalTds: Money | null; singleThreshold: Money | null
  annualThreshold: Money | null; annualCrossed: boolean; remainingToAnnual: Money | null; panMissing: boolean
}
export interface TdsThresholds { fyLabel: string; fyStartYear: number; rows: TdsThresholdRow[]; note: string }
export interface TdsPending { count: number; totalTds: Money; overdueCount: number; checkWithCaCount: number; items: TdsEntry[] }
export interface TcsCredit {
  id: number; platform: string; platformName: string | null; periodStart: string; periodEnd: string; tcsAmount: Money
  gstTcs: boolean; notes: string | null; source: string; voidState: string; voidReason: string | null; version: number
  createdAt: string
}
export interface TcsSummary { rows: TcsCredit[]; totalGstTcs: Money; totalOtherTcs: Money }

// ------------------------------------------------------------------ Payroll / HR
export interface Employee {
  id: number; name: string; designation: string | null; mobile: string | null; joinedOn: string | null; leftOn: string | null
  salaryType: 'MONTHLY' | 'DAILY' | 'HOURLY'; baseRate: Money; overtimeMultiplier: string; userId: number | null
  upiOrBankNote: string | null; notes: string | null; active: boolean; version: number
}
export interface RosterRow { id: number; name: string; designation: string | null; active: boolean; joinedOn: string | null; leftOn: string | null }
export interface AttendanceCell {
  employeeId: number; attDate: string; status: string; hours: string | null; overtimeHours: string; notes: string | null
}
export interface AttendanceGrid {
  month: string; daysInMonth: number; employees: RosterRow[]; cells: AttendanceCell[]; locked: boolean; lockedReason: string | null
}
export interface Advance {
  id: number; employeeId: number; employeeName: string; txnDate: string; amount: Money; recoverPerMonth: Money
  outstanding: Money; recovered: Money; notes: string | null; voidState: string; voidReason: string | null
}
export interface AdvanceSummary {
  employeeId: number; employeeName: string; totalAdvanced: Money; recovered: Money; outstanding: Money
  monthlyInstallment: Money; advances: Advance[]
}
export interface PayrollRun {
  id: number; month: string; status: 'DRAFT' | 'APPROVED' | 'PAID'; totalGross: Money; totalDeductions: Money; totalNet: Money
  approvedAt: string | null; approvedByName: string | null; paidOn: string | null; paymentMethod: string | null
  expenseId: number | null; notes: string | null; voidState: string; voidReason: string | null; version: number
  createdAt: string; payslipCount: number
}
export interface Payslip {
  id: number; runId: number; employeeId: number; employeeName: string; designation: string | null; salaryType: string
  baseRate: Money; paidDays: string; hoursWorked: string | null; overtimeHours: string; gross: Money; overtimePay: Money
  otherAdditions: Money; otherAdditionsLabel: string | null; advanceRecovery: Money; otherDeductions: Money
  otherDeductionsLabel: string | null; netPay: Money
  calcBreakdown: { calc?: Record<string, string>; warnings?: string[]; attendance?: Record<string, string | number | null>; advance?: Record<string, string>; edits?: { by: string; reason: string; netBefore: string; netAfter: string }[] }
  notes: string | null; editReason: string | null; edited: boolean
}
export interface PayrollRunDetail { run: PayrollRun; payslips: Payslip[]; notes: string[] }
export interface PayslipView {
  business: { name: string; address: string | null; phone: string | null }; month: string; monthLabel: string
  runStatus: string; paidOn: string | null; payslip: Payslip; notes: string[]
}

// ------------------------------------------------------------------ Hygiene
export interface HygieneItem { id: number; checklistId: number; text: string; sortOrder: number; requiresPhoto: boolean; active: boolean }
export interface HygieneChecklist {
  id: number; name: string; frequency: string; expectedShift: string | null; active: boolean; startDate: string
  source: string; notes: string | null; items: HygieneItem[]
}
export interface HygieneToday {
  checklistId: number; name: string; frequency: string; expectedShift: string | null; done: boolean; logId: number | null
  allOk: boolean | null; completedByName: string | null; completedAt: string | null; period: string; needsSignOff: boolean
}
export interface HygieneLog {
  id: number; checklistId: number; checklistName: string; logDate: string; shift: string; completedByName: string | null
  completedAt: string; results: { itemId: number; text?: string; ok: boolean; note: string | null }[]; allOk: boolean
  notes: string | null; voidState: string; voidReason: string | null; signedOffByName: string | null; signedOffAt: string | null
}
export interface TempPoint {
  id: number; name: string; kind: string; minC: string; maxC: string; active: boolean; startDate: string; source: string
  notes: string | null; lastTempC: string | null; lastLoggedAt: string | null; lastInRange: boolean | null
}
export interface TempLog {
  id: number; pointId: number; pointName: string; loggedAt: string; logDate: string; tempC: string; minC: string; maxC: string
  inRange: boolean; correctiveAction: string | null; notes: string | null; loggedByName: string | null
  voidState: string; voidReason: string | null
}
export interface HygieneMissed {
  from: string; to: string
  checklists: { checklistId: number; name: string; frequency: string; expected: number; completed: number; compliancePct: string | null; missed: string[] }[]
  points: { pointId: number; name: string; expectedDays: number; loggedDays: number; compliancePct: string | null; missedDays: string[] }[]
  outOfRange: TempLog[]
  checklistCompliancePct: string | null; temperatureCompliancePct: string | null; overallCompliancePct: string | null
  notes: string[]
}

// ------------------------------------------------------------------ Compliance
export interface ComplianceTask {
  id: number; ruleCode: string | null; periodStart: string | null; periodLabel: string | null; title: string; category: string
  dueDate: string; status: 'OPEN' | 'DONE' | 'SKIPPED'; completedOn: string | null; completedByName: string | null
  notes: string | null; link: string | null; generated: boolean; overdue: boolean; daysToDue: number
}
export interface ComplianceRule {
  id: number; code: string; title: string; category: string; dueDay: number; frequency: string; monthOffset: number
  appliesMonths: string | null; dueMonth: number | null; overridePeriodMonth: number | null
  overrideMonthOffset: number | null; overrideDueDay: number | null; notes: string | null; link: string | null
  active: boolean; source: string; updatedAt: string
}
export interface ComplianceDoc {
  id: number; name: string; number: string | null; issuedOn: string | null; expiresOn: string | null; reminderDays: number
  fileDocumentId: number | null; notes: string | null; active: boolean; expiryStatus: 'ACTIVE' | 'EXPIRING' | 'EXPIRED'
  daysToExpiry: number | null
}
export interface ComplianceDashboard {
  today: string; overdue: ComplianceTask[]; due7: ComplianceTask[]; due30: ComplianceTask[]
  expiringDocuments: ComplianceDoc[]; documentsMissingExpiry: number
  pendingTds: { pendingCount: number; amount: Money; overdueCount: number; checkWithCaCount: number } | null
  draftGstWorkpapers: { id: number; periodType: string; periodStart: string; version: number }[] | null
  payroll: { unfinishedRun: { runId: number; month: string; status: string } | null; lastMonthRunMissing: boolean; lastMonth: string } | null
  notes: string[]
}
