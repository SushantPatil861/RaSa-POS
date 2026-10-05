-- V10 (Phase 3): Excel/CSV importer, uploaded documents + OCR drafts, WhatsApp ordering, in-app notifications.
-- Rules: OCR and imports NEVER write business records by themselves; the staff confirm step calls the normal services.
-- Nothing here is deleted: documents, import batches and WhatsApp messages are protected by no-delete triggers.

-- ------------------------------------------------------------------ permissions
insert into permissions (code, description) values
 ('imports.manage',    'Upload, preview, commit and undo Excel/CSV imports'),
 ('documents.view',    'View uploaded documents (bills, receipts, handwritten records)'),
 ('documents.upload',  'Upload / scan documents'),
 ('documents.confirm', 'Review OCR drafts and confirm or reject them into real records'),
 ('whatsapp.view',     'View the WhatsApp inbox and order drafts'),
 ('whatsapp.manage',   'Confirm WhatsApp order drafts, reply and notify customers');

insert into role_permissions (role_id, permission_code)
select r.id, p from roles r cross join unnest(array[
  'imports.manage','documents.view','documents.upload','documents.confirm','whatsapp.view','whatsapp.manage'
]) as p where r.code = 'OWNER';

insert into role_permissions (role_id, permission_code)
select r.id, p from roles r cross join unnest(array[
  'imports.manage','documents.view','documents.upload','documents.confirm','whatsapp.view','whatsapp.manage'
]) as p where r.code = 'MANAGER';

insert into role_permissions (role_id, permission_code)
select r.id, p from roles r cross join unnest(array[
  'imports.manage','documents.view','documents.upload','documents.confirm'
]) as p where r.code = 'ACCOUNTANT';

insert into role_permissions (role_id, permission_code)
select r.id, p from roles r cross join unnest(array[
  'documents.upload','whatsapp.view','whatsapp.manage'
]) as p where r.code = 'CASHIER';

-- ------------------------------------------------------------------ importer
create table import_batches (
  id             bigserial primary key,
  business_id    bigint not null default 1 references business(id),
  file_name      text   not null,
  file_sha256    text   not null,
  target_type    text   not null check (target_type in ('EXPENSES','DAILY_SALES','PURCHASES')),
  status         text   not null default 'UPLOADED'
      check (status in ('UPLOADED','MAPPED','PREVIEWED','COMMITTED','REJECTED','UNDONE')),
  status_reason  text,                      -- why it was rejected / undone
  total_rows     int    not null default 0,
  valid_rows     int    not null default 0,
  error_rows     int    not null default 0,
  duplicate_rows int    not null default 0,
  mapping        jsonb,                     -- {"date":0,"amount":2,...} field -> column index
  options        jsonb,                     -- {"dateFormat":"dd/MM/yyyy","hasHeader":true,"headers":[...],"includeDuplicates":false}
  created_by     bigint references users(id),
  created_at     timestamptz not null default now(),
  committed_at   timestamptz
);
-- The same file cannot be imported twice, unless the earlier batch was REJECTED or UNDONE.
create unique index import_batches_file_uq on import_batches (business_id, file_sha256)
  where status not in ('REJECTED','UNDONE');
create index import_batches_created_ix on import_batches (business_id, created_at desc);

create table import_rows (
  id                  bigserial primary key,
  batch_id            bigint not null references import_batches(id),
  row_no              int    not null,       -- 1-based line number in the file (header = 1 when present)
  raw                 jsonb  not null,       -- the cells exactly as read
  mapped              jsonb,                 -- normalised values after mapping
  status              text   not null default 'PENDING'
      check (status in ('PENDING','VALID','ERROR','DUPLICATE','COMMITTED','UNDONE')),
  error               text,
  created_record_type text,                  -- EXPENSE | DAILY_SUMMARY | PURCHASE
  created_record_id   bigint,
  unique (batch_id, row_no)
);
create index import_rows_batch_ix on import_rows (batch_id, row_no);

-- ------------------------------------------------------------------ documents + OCR
create table uploaded_documents (
  id            bigserial primary key,
  business_id   bigint not null default 1 references business(id),
  original_name text   not null,            -- sanitised, display only
  content_type  text   not null,            -- from magic bytes, never from the client
  size_bytes    bigint not null check (size_bytes > 0),
  sha256        text   not null,
  storage_path  text   not null,            -- relative file name under cafe.storage.dir
  doc_type      text   not null default 'OTHER' check (doc_type in ('RECEIPT','BILL','HANDWRITTEN','OTHER')),
  status        text   not null default 'UPLOADED'
      check (status in ('UPLOADED','PROCESSED','DRAFTED','CONFIRMED','REJECTED')),
  status_reason text,                       -- reject reason
  uploaded_by   bigint references users(id),
  uploaded_at   timestamptz not null default now(),
  entity_type   text,                       -- EXPENSE | PURCHASE | DAILY_SUMMARY once confirmed
  entity_id     bigint
);
create unique index uploaded_documents_sha_uq on uploaded_documents (business_id, sha256);
create index uploaded_documents_status_ix on uploaded_documents (business_id, status, uploaded_at desc);

create table ocr_extractions (
  id             bigserial primary key,
  document_id    bigint not null references uploaded_documents(id),
  engine         text   not null,
  raw_text       text,
  avg_confidence numeric(5,2),
  created_at     timestamptz not null default now()
);
create index ocr_extractions_doc_ix on ocr_extractions (document_id);

create table document_drafts (
  id                    bigserial primary key,
  document_id           bigint not null references uploaded_documents(id),
  proposed_type         text   not null check (proposed_type in ('EXPENSE','PURCHASE','DAILY_SALES')),
  proposed              jsonb  not null,
  status                text   not null default 'PENDING' check (status in ('PENDING','CONFIRMED','REJECTED')),
  confirmed_record_type text,
  confirmed_record_id   bigint,
  confirmed_by          bigint references users(id),
  confirmed_at          timestamptz,
  reject_reason         text
);
create index document_drafts_doc_ix on document_drafts (document_id);

-- ------------------------------------------------------------------ WhatsApp
create table whatsapp_messages (
  id            bigserial primary key,
  business_id   bigint not null default 1 references business(id),
  direction     text   not null check (direction in ('IN','OUT')),
  wa_message_id text,
  wa_number     text   not null,
  customer_name text,
  body          text,
  payload       jsonb,
  status        text   not null default 'RECEIVED' check (status in ('RECEIVED','PARSED','DRAFTED','SENT','FAILED')),
  created_at    timestamptz not null default now()
);
create unique index whatsapp_messages_wamid_uq on whatsapp_messages (business_id, wa_message_id)
  where wa_message_id is not null;
create index whatsapp_messages_number_ix on whatsapp_messages (business_id, wa_number, created_at desc);

create table whatsapp_order_drafts (
  id             bigserial primary key,
  message_id     bigint not null references whatsapp_messages(id),
  parsed_items   jsonb  not null default '[]'::jsonb,   -- [{text,productId,variantId,name,qty,unitPrice,confidence}]
  unmatched_text text,
  customer_id    bigint references customers(id),
  status         text   not null default 'PENDING_REVIEW' check (status in ('PENDING_REVIEW','CONFIRMED','REJECTED')),
  order_id       bigint references orders(id),
  reviewed_by    bigint references users(id),
  reviewed_at    timestamptz,
  reject_reason  text
);
create unique index whatsapp_order_drafts_msg_uq on whatsapp_order_drafts (message_id);
create index whatsapp_order_drafts_status_ix on whatsapp_order_drafts (status);

-- ------------------------------------------------------------------ notifications (in-app)
-- user_id null  = everybody who holds perm_code (or every user when perm_code is also null).
create table notifications (
  id          bigserial primary key,
  business_id bigint not null default 1 references business(id),
  user_id     bigint references users(id),
  perm_code   text,
  type        text not null,
  title       text not null,
  body        text,
  link        text,
  created_at  timestamptz not null default now()
);
create index notifications_created_ix on notifications (business_id, created_at desc);

create table notification_reads (
  notification_id bigint not null references notifications(id),
  user_id         bigint not null references users(id),
  read_at         timestamptz not null default now(),
  primary key (notification_id, user_id)
);

-- ------------------------------------------------------------------ protect history
create trigger import_batches_no_delete    before delete on import_batches    for each row execute function forbid_modification();
create trigger uploaded_documents_no_delete before delete on uploaded_documents for each row execute function forbid_modification();
create trigger whatsapp_messages_no_delete before delete on whatsapp_messages for each row execute function forbid_modification();
