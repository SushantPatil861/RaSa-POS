import { describe, expect, it } from 'vitest'
import { addDays, dmy, inr, toNumber } from './format'

describe('format', () => {
  it('formats rupees with Indian grouping', () => {
    expect(inr('1234567.5')).toBe('₹12,34,567.50')
    expect(inr(0)).toBe('₹0.00')
    expect(inr('-250')).toBe('-₹250.00')
    expect(inr('1999', { decimals: 0 })).toBe('₹1,999')
  })
  it('shows a dash for missing values when asked', () => {
    expect(inr(null, { blankIfNull: true })).toBe('—')
  })
  it('parses numbers safely', () => {
    expect(toNumber('12.5')).toBe(12.5)
    expect(toNumber('abc')).toBe(0)
    expect(toNumber(undefined)).toBe(0)
  })
  it('formats calendar dates without timezone shifts', () => {
    expect(dmy('2026-05-01')).toBe('01-May-2026')
    expect(dmy(null)).toBe('—')
  })
  it('adds days across month ends', () => {
    expect(addDays('2026-05-31', 1)).toBe('2026-06-01')
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28')
  })
})
