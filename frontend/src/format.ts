// Indian formatting: ₹ with lakh/crore grouping, dd-MMM-yyyy dates. Amounts arrive from the API as strings.

export function toNumber(v: string | number | null | undefined): number {
  if (v === null || v === undefined || v === '') return 0
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : 0
}

export function inr(v: string | number | null | undefined, opts: { decimals?: number; blankIfNull?: boolean } = {}): string {
  if ((v === null || v === undefined || v === '') && opts.blankIfNull) return '—'
  const decimals = opts.decimals ?? 2
  const n = toNumber(v)
  const formatted = new Intl.NumberFormat('en-IN', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }).format(Math.abs(n))
  return `${n < 0 ? '-' : ''}₹${formatted}`
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/** "2026-05-01" -> "01-May-2026" (no timezone maths: the string is a calendar date). */
export function dmy(iso: string | null | undefined): string {
  if (!iso) return '—'
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso)
  if (!m) return iso
  return `${m[3]}-${MONTHS[Number(m[2]) - 1]}-${m[1]}`
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' })
}

export function isoDate(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

export function addDays(iso: string, days: number): string {
  const [y, m, d] = iso.split('-').map(Number)
  return isoDate(new Date(y, m - 1, d + days))
}

export function todayIso(): string {
  return isoDate(new Date())
}

export function monthStartIso(): string {
  const n = new Date()
  return isoDate(new Date(n.getFullYear(), n.getMonth(), 1))
}
