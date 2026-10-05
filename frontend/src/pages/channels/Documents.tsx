import { useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { get, qs, upload } from '../../api'
import { ErrorNote, Field, Loading, PageTitle } from '../../components/ui'
import { useAuth } from '../../auth'
import { dateTime } from '../../format'
import type { DocDetail, DocStatus, DocType, DocumentInfo } from '../../types.phase3'
import { Badge, Empty, Tabs } from './shared'

const DOC_TYPES: { value: DocType; label: string }[] = [
  { value: 'BILL', label: 'Supplier bill' },
  { value: 'RECEIPT', label: 'Receipt' },
  { value: 'HANDWRITTEN', label: 'Handwritten record' },
  { value: 'OTHER', label: 'Other' },
]

type Filter = 'ALL' | DocStatus

export default function Documents() {
  const { can } = useAuth()
  const nav = useNavigate()
  const qc = useQueryClient()
  const [filter, setFilter] = useState<Filter>('ALL')
  const [docType, setDocType] = useState<DocType>('BILL')
  const camera = useRef<HTMLInputElement>(null)
  const chooser = useRef<HTMLInputElement>(null)

  const list = useQuery({
    queryKey: ['documents', filter],
    queryFn: () => get<DocumentInfo[]>(`/documents${qs({ status: filter === 'ALL' ? '' : filter })}`),
  })
  const up = useMutation({
    mutationFn: (file: File) => {
      const f = new FormData()
      f.append('file', file)
      f.append('docType', docType)
      return upload<DocDetail>('/documents', f)
    },
    onSuccess: (d) => {
      qc.invalidateQueries({ queryKey: ['documents'] })
      nav(`/documents/${d.document.id}`)
    },
  })
  const pick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0]
    e.target.value = ''
    if (f) up.mutate(f)
  }

  return (
    <div>
      <PageTitle title="Documents" />
      {can('documents.upload') && (
        <div className="card mb-4 space-y-3">
          <p className="text-sm text-slate-600">
            Take a photo of a bill or receipt. We read it and show you a draft. Nothing is saved until you confirm.
          </p>
          <Field label="What is it?">
            <select className="input" value={docType} onChange={(e) => setDocType(e.target.value as DocType)}>
              {DOC_TYPES.map((t) => (
                <option key={t.value} value={t.value}>
                  {t.label}
                </option>
              ))}
            </select>
          </Field>
          <input ref={camera} type="file" accept="image/*" capture="environment" className="hidden" onChange={pick} />
          <input ref={chooser} type="file" accept="image/*,application/pdf" className="hidden" onChange={pick} />
          <div className="grid grid-cols-2 gap-2">
            <button className="btn-primary" disabled={up.isPending} onClick={() => camera.current?.click()}>
              Take photo
            </button>
            <button className="btn-ghost" disabled={up.isPending} onClick={() => chooser.current?.click()}>
              Choose file
            </button>
          </div>
          {up.isPending && <p className="text-sm text-slate-500">Uploading and reading the document…</p>}
          <ErrorNote error={up.error} />
        </div>
      )}
      <Tabs<Filter>
        value={filter}
        onChange={setFilter}
        options={[
          { value: 'ALL', label: 'All' },
          { value: 'DRAFTED', label: 'Needs review' },
          { value: 'CONFIRMED', label: 'Confirmed' },
          { value: 'REJECTED', label: 'Rejected' },
        ]}
      />
      {list.isLoading && <Loading />}
      <ErrorNote error={list.error} />
      {list.data && list.data.length === 0 && <Empty>No documents here.</Empty>}
      <div className="space-y-2">
        {list.data?.map((d) => (
          <button key={d.id} className="card w-full text-left" onClick={() => nav(`/documents/${d.id}`)}>
            <div className="flex items-center justify-between gap-2">
              <span className="truncate font-medium">{d.originalName}</span>
              <Badge value={d.status} />
            </div>
            <div className="mt-1 text-xs text-slate-500">
              {d.docType} · {dateTime(d.uploadedAt)} {d.uploadedByName ? `· ${d.uploadedByName}` : ''}
            </div>
          </button>
        ))}
      </div>
    </div>
  )
}
