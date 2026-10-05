import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post, put, upload } from '../../api'
import { ErrorNote, Field, Loading, PageTitle, ReasonDialog } from '../../components/ui'
import { dateTime } from '../../format'
import type {
  ImportBatch,
  ImportCommitResult,
  ImportDetail,
  ImportPreview,
  ImportRow,
  ImportTarget,
} from '../../types.phase3'
import { Badge, Empty, Tabs } from './shared'

const TARGETS: { value: ImportTarget; label: string }[] = [
  { value: 'EXPENSES', label: 'Expenses' },
  { value: 'PURCHASES', label: 'Purchases' },
  { value: 'DAILY_SALES', label: 'Daily sales' },
]

type RowFilter = 'ALL' | 'VALID' | 'ERROR' | 'DUPLICATE'

export default function ImportWizard() {
  const qc = useQueryClient()
  const [batchId, setBatchId] = useState<number | null>(null)
  const batches = useQuery({ queryKey: ['imports'], queryFn: () => get<ImportBatch[]>('/imports') })

  if (batchId !== null) {
    return (
      <BatchFlow
        id={batchId}
        onBack={() => {
          setBatchId(null)
          qc.invalidateQueries({ queryKey: ['imports'] })
        }}
      />
    )
  }
  return (
    <div>
      <PageTitle title="Import from Excel / CSV" />
      <UploadCard onDone={(id) => setBatchId(id)} />
      <h2 className="mb-2 mt-6 font-semibold">Earlier imports</h2>
      {batches.isLoading && <Loading />}
      <ErrorNote error={batches.error} />
      {batches.data && batches.data.length === 0 && <Empty>No imports yet.</Empty>}
      <div className="space-y-2">
        {batches.data?.map((b) => (
          <button key={b.id} className="card w-full text-left" onClick={() => setBatchId(b.id)}>
            <div className="flex items-center justify-between gap-2">
              <span className="truncate font-medium">{b.fileName}</span>
              <Badge value={b.status} />
            </div>
            <div className="mt-1 text-xs text-slate-500">
              {b.targetType.replace('_', ' ')} · {b.totalRows} rows · {b.validRows} ok · {b.errorRows} errors ·{' '}
              {b.duplicateRows} duplicates · {dateTime(b.createdAt)}
            </div>
          </button>
        ))}
      </div>
    </div>
  )
}

function UploadCard({ onDone }: { onDone: (id: number) => void }) {
  const [file, setFile] = useState<File | null>(null)
  const [target, setTarget] = useState<ImportTarget>('EXPENSES')
  const [hasHeader, setHasHeader] = useState(true)
  const m = useMutation({
    mutationFn: () => {
      const f = new FormData()
      f.append('file', file as File)
      f.append('targetType', target)
      f.append('hasHeader', String(hasHeader))
      return upload<ImportDetail>('/imports', f)
    },
    onSuccess: (d) => onDone(d.batch.id),
  })
  return (
    <div className="card space-y-3">
      <p className="text-sm text-slate-600">
        Step 1 of 4: choose an .xlsx or .csv file. Nothing is saved until you check the preview and press Import.
      </p>
      <Field label="What does the file contain?">
        <select className="input" value={target} onChange={(e) => setTarget(e.target.value as ImportTarget)}>
          {TARGETS.map((t) => (
            <option key={t.value} value={t.value}>
              {t.label}
            </option>
          ))}
        </select>
      </Field>
      <Field label="File">
        <input
          type="file"
          className="input"
          accept=".xlsx,.xls,.csv,text/csv"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        />
      </Field>
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={hasHeader} onChange={(e) => setHasHeader(e.target.checked)} />
        First row has column names
      </label>
      <ErrorNote error={m.error} />
      <button className="btn-primary w-full" disabled={!file || m.isPending} onClick={() => m.mutate()}>
        {m.isPending ? 'Reading file…' : 'Upload and continue'}
      </button>
    </div>
  )
}

function BatchFlow({ id, onBack }: { id: number; onBack: () => void }) {
  const qc = useQueryClient()
  const detail = useQuery({ queryKey: ['import', id], queryFn: () => get<ImportDetail>(`/imports/${id}`) })
  const [preview, setPreview] = useState<ImportPreview | null>(null)
  const [result, setResult] = useState<ImportCommitResult | null>(null)
  const [mapping, setMapping] = useState<Record<string, number> | null>(null)
  const [dateFormat, setDateFormat] = useState<string | null>(null)
  const [includeDup, setIncludeDup] = useState(false)
  const [filter, setFilter] = useState<RowFilter>('ALL')
  const [undo, setUndo] = useState(false)
  const [reject, setReject] = useState(false)

  const d = detail.data
  const status = d?.batch.status
  const curMapping = mapping ?? (d && Object.keys(d.mapping).length ? d.mapping : d?.suggestedMapping) ?? {}
  const curFormat = dateFormat ?? (d ? d.dateFormat || d.suggestedDateFormat : '')

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['import', id] })
    qc.invalidateQueries({ queryKey: ['imports'] })
  }

  const doPreview = useMutation({
    mutationFn: async () => {
      await put<ImportDetail>(`/imports/${id}/mapping`, {
        targetType: d?.batch.targetType,
        mapping: curMapping,
        dateFormat: curFormat,
      })
      return post<ImportPreview>(`/imports/${id}/preview`)
    },
    onSuccess: (p) => {
      setPreview(p)
      setFilter('ALL')
      refresh()
    },
  })
  const doCommit = useMutation({
    mutationFn: () => post<ImportCommitResult>(`/imports/${id}/commit`, { includeDuplicates: includeDup }),
    onSuccess: (r) => {
      setResult(r)
      setPreview(null)
      refresh()
    },
  })

  if (detail.isLoading) return <Loading />
  if (!d) return <ErrorNote error={detail.error} />

  const missingRequired = d.fields.filter((f) => f.required && curMapping[f.key] === undefined)
  const rows: ImportRow[] = (preview?.rows ?? []).filter((r) => filter === 'ALL' || r.status === filter)
  const finished = status === 'COMMITTED' || status === 'REJECTED' || status === 'UNDONE'

  return (
    <div>
      <PageTitle title={d.batch.fileName}>
        <button className="btn-ghost" onClick={onBack}>
          Back
        </button>
      </PageTitle>
      <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
        <Badge value={d.batch.status} />
        <span className="text-slate-500">
          {d.batch.targetType.replace('_', ' ')} · {d.batch.totalRows} rows
        </span>
        {d.batch.statusReason && <span className="text-slate-500">· “{d.batch.statusReason}”</span>}
      </div>

      {result && (
        <div className="card mb-3 border-emerald-300 bg-emerald-50 text-sm">
          <div className="font-semibold text-emerald-800">Imported {result.created} records.</div>
          <div>
            Skipped {result.skippedErrors} rows with errors and {result.skippedDuplicates} duplicates.
          </div>
          {result.unknownCategories.length > 0 && (
            <div className="mt-1 text-amber-800">
              Categories not found (left blank, noted on the record): {result.unknownCategories.join(', ')}
            </div>
          )}
          {result.unknownSuppliers.length > 0 && (
            <div className="mt-1 text-amber-800">
              Suppliers not found (left blank, noted on the record): {result.unknownSuppliers.join(', ')}
            </div>
          )}
        </div>
      )}

      {!finished && (
        <div className="card mb-3 space-y-3">
          <h2 className="font-semibold">Step 2: match columns</h2>
          <p className="text-xs text-slate-500">We guessed the matches from your column names. Please check them.</p>
          {d.fields.map((f) => (
            <Field key={f.key} label={`${f.label}${f.required ? ' *' : ''}`}>
              <select
                className="input"
                value={curMapping[f.key] ?? ''}
                onChange={(e) => {
                  const next = { ...curMapping }
                  if (e.target.value === '') delete next[f.key]
                  else next[f.key] = Number(e.target.value)
                  setMapping(next)
                }}
              >
                <option value="">— not in file —</option>
                {d.headers.map((h, i) => (
                  <option key={i} value={i}>
                    {h || `Column ${i + 1}`}
                  </option>
                ))}
              </select>
            </Field>
          ))}
          <Field label="Date format in the file">
            <select className="input" value={curFormat} onChange={(e) => setDateFormat(e.target.value)}>
              {d.dateFormats.map((f) => (
                <option key={f} value={f}>
                  {f}
                </option>
              ))}
            </select>
          </Field>
          {d.sampleRows.length > 0 && (
            <div className="overflow-x-auto">
              <table className="min-w-full text-xs">
                <thead>
                  <tr>
                    {d.headers.map((h, i) => (
                      <th key={i} className="th">
                        {h || `Column ${i + 1}`}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {d.sampleRows.map((r) => (
                    <tr key={r.rowNo}>
                      {r.cells.map((c, i) => (
                        <td key={i} className="td">
                          {c}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {missingRequired.length > 0 && (
            <div className="text-sm text-amber-700">Still needed: {missingRequired.map((f) => f.label).join(', ')}</div>
          )}
          <ErrorNote error={doPreview.error} />
          <div className="flex gap-2">
            <button
              className="btn-primary flex-1"
              disabled={missingRequired.length > 0 || doPreview.isPending}
              onClick={() => doPreview.mutate()}
            >
              {doPreview.isPending ? 'Checking…' : 'Step 3: preview'}
            </button>
            <button className="btn-ghost" onClick={() => setReject(true)}>
              Reject file
            </button>
          </div>
        </div>
      )}

      {preview && !finished && (
        <div className="card mb-3 space-y-3">
          <h2 className="font-semibold">Step 3: check the rows</h2>
          <div className="text-sm text-slate-600">
            {preview.batch.validRows} ready · {preview.batch.errorRows} with errors · {preview.batch.duplicateRows}{' '}
            look like duplicates
          </div>
          {preview.unknownCategories.length > 0 && (
            <div className="rounded-lg bg-amber-50 p-2 text-sm text-amber-800">
              Categories not in your list (will be left blank): {preview.unknownCategories.join(', ')}
            </div>
          )}
          {preview.unknownSuppliers.length > 0 && (
            <div className="rounded-lg bg-amber-50 p-2 text-sm text-amber-800">
              Suppliers not in your list (will be left blank): {preview.unknownSuppliers.join(', ')}
            </div>
          )}
          <Tabs<RowFilter>
            value={filter}
            onChange={setFilter}
            options={[
              { value: 'ALL', label: 'All' },
              { value: 'VALID', label: 'Ready' },
              { value: 'ERROR', label: 'Errors' },
              { value: 'DUPLICATE', label: 'Duplicates' },
            ]}
          />
          <div className="max-h-96 space-y-1 overflow-y-auto">
            {rows.map((r) => (
              <div
                key={r.rowNo}
                className={`rounded-lg p-2 text-xs ${
                  r.status === 'ERROR' ? 'bg-red-50' : r.status === 'DUPLICATE' ? 'bg-orange-50' : 'bg-slate-50'
                }`}
              >
                <div className="flex items-center justify-between">
                  <span className="font-semibold">Row {r.rowNo}</span>
                  <Badge value={r.status} />
                </div>
                <div className="mt-1 break-words text-slate-700">
                  {Object.entries(r.mapped)
                    .map(([k, v]) => `${k}: ${v}`)
                    .join(' · ') || r.cells.join(' | ')}
                </div>
                {r.error && <div className="mt-1 text-red-700">{r.error}</div>}
              </div>
            ))}
            {rows.length === 0 && <p className="text-sm text-slate-500">No rows in this view.</p>}
            {preview.truncated && <p className="text-xs text-slate-500">Only the first rows are shown here.</p>}
          </div>
          {preview.batch.duplicateRows > 0 && (
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={includeDup} onChange={(e) => setIncludeDup(e.target.checked)} />
              Import the possible duplicates too
            </label>
          )}
          <ErrorNote error={doCommit.error} />
          <button
            className="btn-primary w-full"
            disabled={doCommit.isPending || preview.batch.validRows + (includeDup ? preview.batch.duplicateRows : 0) === 0}
            onClick={() => doCommit.mutate()}
          >
            {doCommit.isPending ? 'Importing…' : 'Step 4: import these rows'}
          </button>
          <p className="text-xs text-slate-500">Imported records are labelled “Imported”. You can undo the whole file afterwards.</p>
        </div>
      )}

      {status === 'COMMITTED' && (
        <button className="btn-danger w-full" onClick={() => setUndo(true)}>
          Undo this import
        </button>
      )}
      {undo && (
        <ReasonDialog
          title="Undo this import"
          confirmLabel="Undo import"
          danger
          onClose={() => setUndo(false)}
          onConfirm={async (reason) => {
            await post(`/imports/${id}/undo`, { reason })
            refresh()
          }}
        />
      )}
      {reject && (
        <ReasonDialog
          title="Reject this file"
          confirmLabel="Reject"
          danger
          required={false}
          onClose={() => setReject(false)}
          onConfirm={async (reason) => {
            await post(`/imports/${id}/reject`, { reason })
            refresh()
            onBack()
          }}
        />
      )}
    </div>
  )
}
