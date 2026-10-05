-- V4: expenses, suppliers, purchases
create table expense_categories (
  id          bigserial primary key,
  business_id bigint not null default 1 references business(id),
  name        text not null,
  parent_id   bigint references expense_categories(id),
  sort_order  int  not null default 0,
  active      boolean not null default true
);
create unique index expense_categories_uq on expense_categories (business_id, lower(name));

create table expenses (
  id               bigserial primary key,
  business_id      bigint not null default 1 references business(id),
  txn_date         date   not null,
  entry_at         timestamptz not null default now(),
  category_id      bigint references expense_categories(id),      -- nullable: "record what is known"
  amount           numeric(14,2) not null check (amount >= 0),
  payment_method   text check (payment_method in ('CASH','UPI','CARD','BANK','OTHER')),
  paid_to          text,
  recurring        boolean not null default false,
  notes            text,
  source           text   not null default 'MANUAL'
      check (source in ('MANUAL','EXCEL','CSV','OCR','WHATSAPP','COUNTER','IMPORT','SYSTEM','OTHER')),
  confirmation_state text not null default 'CONFIRMED' check (confirmation_state in ('DRAFT','CONFIRMED')),
  is_estimated     boolean not null default false,
  is_partial       boolean not null default false,
  revision         int    not null default 0,
  void_state       text   not null default 'ACTIVE' check (void_state in ('ACTIVE','VOIDED')),
  void_reason      text,
  voided_by        bigint references users(id),
  voided_at        timestamptz,
  version          int    not null default 0,
  created_by       bigint references users(id),
  updated_by       bigint references users(id),
  updated_at       timestamptz not null default now()
);
create index expenses_date_ix on expenses (business_id, txn_date) where void_state = 'ACTIVE';

create table suppliers (
  id            bigserial primary key,
  business_id   bigint not null default 1 references business(id),
  name          text not null,
  contact       text,
  category      text,
  payment_terms text,
  gstin         text,
  pan           text,
  notes         text,
  active        boolean not null default true,
  created_at    timestamptz not null default now()
);
create unique index suppliers_name_uq on suppliers (business_id, lower(name));

create table purchases (
  id               bigserial primary key,
  business_id      bigint not null default 1 references business(id),
  purchase_no      text   not null,
  txn_date         date   not null,
  entry_at         timestamptz not null default now(),
  supplier_id      bigint references suppliers(id),           -- nullable: may be unknown
  invoice_no       text,
  invoice_date     date,
  payment_status   text   not null default 'UNPAID' check (payment_status in ('UNPAID','PARTIAL','PAID')),
  total_amount     numeric(14,2) not null check (total_amount >= 0),
  notes            text,
  source           text   not null default 'MANUAL'
      check (source in ('MANUAL','EXCEL','CSV','OCR','WHATSAPP','COUNTER','IMPORT','SYSTEM','OTHER')),
  confirmation_state text not null default 'CONFIRMED' check (confirmation_state in ('DRAFT','CONFIRMED')),
  is_estimated     boolean not null default false,
  is_partial       boolean not null default false,
  revision         int    not null default 0,
  void_state       text   not null default 'ACTIVE' check (void_state in ('ACTIVE','VOIDED')),
  void_reason      text,
  voided_by        bigint references users(id),
  voided_at        timestamptz,
  version          int    not null default 0,
  created_by       bigint references users(id),
  updated_by       bigint references users(id),
  updated_at       timestamptz not null default now(),
  unique (business_id, purchase_no)
);
create index purchases_date_ix on purchases (business_id, txn_date) where void_state = 'ACTIVE';

create table purchase_items (
  id          bigserial primary key,
  purchase_id bigint not null references purchases(id),
  item_name   text   not null,
  quantity    numeric(14,4) not null check (quantity >= 0),
  unit        text,
  unit_price  numeric(14,4),
  total       numeric(14,2) not null check (total >= 0),
  treatment   text   not null default 'INVENTORY' check (treatment in ('INVENTORY','EXPENSE','FIXED_ASSET'))
);

create trigger expenses_no_delete  before delete on expenses  for each row execute function forbid_modification();
create trigger purchases_no_delete before delete on purchases for each row execute function forbid_modification();
