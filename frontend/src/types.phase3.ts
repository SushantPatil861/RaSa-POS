// Phase 3 types: importer, documents + OCR, WhatsApp ordering, notifications, customer profiles.
// Money arrives from the API as strings.

// ---------------------------------------------------------------- importer
export type ImportTarget = 'EXPENSES' | 'DAILY_SALES' | 'PURCHASES'
export type ImportStatus = 'UPLOADED' | 'MAPPED' | 'PREVIEWED' | 'COMMITTED' | 'REJECTED' | 'UNDONE'
export type ImportRowStatus = 'PENDING' | 'VALID' | 'ERROR' | 'DUPLICATE' | 'COMMITTED' | 'UNDONE'

export interface ImportBatch {
  id: number
  fileName: string
  targetType: ImportTarget
  status: ImportStatus
  statusReason: string | null
  totalRows: number
  validRows: number
  errorRows: number
  duplicateRows: number
  createdByName: string | null
  createdAt: string
  committedAt: string | null
}

export interface ImportFieldSpec {
  key: string
  label: string
  required: boolean
}

export interface ImportSampleRow {
  rowNo: number
  cells: string[]
}

export interface ImportDetail {
  batch: ImportBatch
  headers: string[]
  hasHeader: boolean
  sampleRows: ImportSampleRow[]
  fields: ImportFieldSpec[]
  suggestedMapping: Record<string, number>
  suggestedDateFormat: string
  mapping: Record<string, number>
  dateFormat: string
  dateFormats: string[]
}

export interface ImportRow {
  rowNo: number
  cells: string[]
  mapped: Record<string, string>
  status: ImportRowStatus
  error: string | null
  createdRecordType: string | null
  createdRecordId: number | null
}

export interface ImportPreview {
  batch: ImportBatch
  rows: ImportRow[]
  truncated: boolean
  unknownCategories: string[]
  unknownSuppliers: string[]
}

export interface ImportCommitResult {
  batch: ImportBatch
  created: number
  skippedErrors: number
  skippedDuplicates: number
  unknownCategories: string[]
  unknownSuppliers: string[]
}

// ---------------------------------------------------------------- documents
export type DocType = 'RECEIPT' | 'BILL' | 'HANDWRITTEN' | 'OTHER'
export type DocStatus = 'UPLOADED' | 'PROCESSED' | 'DRAFTED' | 'CONFIRMED' | 'REJECTED'
export type RecordType = 'EXPENSE' | 'PURCHASE' | 'DAILY_SALES'
export type Confidence = 'HIGH' | 'MEDIUM' | 'LOW'

export interface DocumentInfo {
  id: number
  originalName: string
  contentType: string
  sizeBytes: number
  docType: DocType
  status: DocStatus
  statusReason: string | null
  uploadedByName: string | null
  uploadedAt: string
  entityType: string | null
  entityId: number | null
  image: boolean
}

export interface ProposedItem {
  itemName: string
  quantity: string | null
  unitPrice: string | null
  total: string | null
  confidence: Confidence
}

export interface DraftProposal {
  type?: RecordType
  needsManualEntry?: boolean
  notice?: string | null
  fields?: Record<string, string>
  confidence?: Record<string, Confidence>
  items?: ProposedItem[]
}

export interface DocDraft {
  id: number
  proposedType: RecordType
  proposed: DraftProposal
  status: 'PENDING' | 'CONFIRMED' | 'REJECTED'
  confirmedRecordType: string | null
  confirmedRecordId: number | null
  confirmedAt: string | null
  rejectReason: string | null
}

export interface DocExtraction {
  engine: string
  rawText: string | null
  avgConfidence: string | null
  createdAt: string
}

export interface DocDetail {
  document: DocumentInfo
  draft: DocDraft | null
  extraction: DocExtraction | null
}

// ---------------------------------------------------------------- WhatsApp
export interface WaParsedItem {
  text: string
  productId: number
  variantId: number
  name: string
  qty: string
  unitPrice: string
  confidence: number
}

export interface WaInboxItem {
  messageId: number
  waNumber: string
  customerName: string | null
  body: string
  messageStatus: string
  createdAt: string
  draftId: number | null
  parsedItems: WaParsedItem[]
  unmatchedText: string | null
  draftStatus: 'PENDING_REVIEW' | 'CONFIRMED' | 'REJECTED' | null
  orderId: number | null
  orderNo: string | null
  rejectReason: string | null
  customerId: number | null
}

export interface WaMenuOption {
  productId: number
  variantId: number
  name: string
  variantName: string
  price: string
  availability: string
}

export interface WaThreadMsg {
  id: number
  direction: 'IN' | 'OUT'
  body: string
  status: string
  createdAt: string
}

export interface WaStatus {
  enabled: boolean
  upiConfigured: boolean
  webhookSecured: boolean
  verifyTokenSet: boolean
}

export interface WaConfirmResult {
  orderId: number
  orderNo: string
  total: string
}

export interface WaSentResult {
  messageId: number
  status: string
  to: string
  text: string
}

// ---------------------------------------------------------------- notifications
export interface AppNotification {
  id: number
  type: string
  title: string
  body: string | null
  link: string | null
  createdAt: string
  read: boolean
}

// ---------------------------------------------------------------- customers
export interface CustomerLite {
  id: number
  name: string
  mobile: string | null
  notes: string | null
}

export interface CustomerOrderBrief {
  id: number
  orderNo: string
  orderDate: string
  source: string
  status: string
  paymentStatus: string
  totalAmount: string
  items: string | null
}

export interface CustomerProfileData {
  customer: { id: number; name: string; mobile: string | null; notes: string | null; createdAt: string }
  ordersCount: number
  completedOrders: number
  totalSpend: string
  averageOrder: string | null
  firstOrderDate: string | null
  lastOrderDate: string | null
  favouriteItems: { productId: number; name: string; quantity: string; orders: number }[]
  recentOrders: CustomerOrderBrief[]
  whatsappNumber: string | null
}
