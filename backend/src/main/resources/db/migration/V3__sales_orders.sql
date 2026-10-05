-- V3: customers, orders, sales, payments, quick daily summaries, day reviews
create table customers (
  id          bigserial primary key,
  business_id bigint not null default 1 references business(id),
  name        text not null,
  mobile      text,
  notes       text,
  created_at  timestamptz not null default now()
);
create unique index customers_mobile_uq on customers (business_id, mobile) where mobile is not null;

create table orders (
  id                   bigserial primary key,
  business_id          bigint not null default 1 references business(id),
  order_no             text   not null,
  order_date           date   not null,
  order_time           time   not null default localtime,
  customer_id          bigint references customers(id),
  source               text   not null default 'COUNTER' check (source in ('COUNTER','WHATSAPP','PHONE','OTHER')),
  order_type           text   not null default 'TAKEAWAY' check (order_type in ('PICKUP','DELIVERY','DINE_IN','TAKEAWAY')),
  status               text   not null default 'NEW'
      check (status in ('NEW','CONFIRMED','PREPARING','READY','OUT_FOR_DELIVERY','COMPLETED','CANCELLED')),
  payment_status       text   not null default 'PENDING'
      check (payment_status in ('PENDING','INITIATED','PAID','PARTIALLY_PAID','FAILED','REFUNDED')),
  delivery_charge      numeric(14,2) not null default 0,
  total_amount         numeric(14,2) not null default 0,
  special_instructions text,
  sale_id              bigint,
  version              int not null default 0,
  entry_at             timestamptz not null default now(),
  created_by           bigint references users(id),
  updated_at           timestamptz not null default now(),
  unique (business_id, order_no)
);
create index orders_status_ix on orders (business_id, status);

create table order_items (
  id           bigserial primary key,
  order_id     bigint not null references orders(id),
  product_id   bigint not null references products(id),
  variant_id   bigint not null references product_variants(id),
  menu_item_id bigint references menu_items(id),
  item_name    text not null,
  quantity     numeric(12,3) not null check (quantity > 0),
  unit_price   numeric(14,2) not null check (unit_price >= 0),
  discount     numeric(14,2) not null default 0 check (discount >= 0),
  line_total   numeric(14,2) not null
);

-- SALES: the accounting fact. Amounts GST-inclusive as charged; tax is extracted into *_taxable columns.
-- channel = where the order came from; source = how the RECORD got into the system.
create table sales (
  id                bigserial primary key,
  business_id       bigint not null default 1 references business(id),
  sale_no           text   not null,
  txn_date          date   not null,
  entry_at          timestamptz not null default now(),
  channel           text   not null default 'COUNTER' check (channel in ('COUNTER','WHATSAPP','PHONE','OTHER')),
  source            text   not null default 'MANUAL'
      check (source in ('MANUAL','EXCEL','CSV','OCR','WHATSAPP','COUNTER','IMPORT','SYSTEM','OTHER')),
  customer_id       bigint references customers(id),
  order_id          bigint references orders(id),
  gross_inclusive   numeric(14,2) not null default 0,   -- sum(qty*unit_price)
  discount_total    numeric(14,2) not null default 0,   -- inclusive
  total_inclusive   numeric(14,2) not null default 0,   -- gross - discount
  gross_taxable     numeric(14,2) not null default 0,   -- ex-GST
  discount_taxable  numeric(14,2) not null default 0,   -- ex-GST
  net_taxable       numeric(14,2) not null default 0,   -- ex-GST
  tax_amount        numeric(14,2) not null default 0,
  confirmation_state text  not null default 'CONFIRMED' check (confirmation_state in ('DRAFT','CONFIRMED')),
  is_estimated      boolean not null default false,
  is_partial        boolean not null default false,
  revision          int    not null default 0,
  void_state        text   not null default 'ACTIVE' check (void_state in ('ACTIVE','VOIDED')),
  void_reason       text,
  voided_by         bigint references users(id),
  voided_at         timestamptz,
  notes             text,
  version           int    not null default 0,
  created_by        bigint references users(id),
  updated_by        bigint references users(id),
  updated_at        timestamptz not null default now(),
  unique (business_id, sale_no)
);
create index sales_date_ix on sales (business_id, txn_date) where void_state = 'ACTIVE';
alter table orders add constraint orders_sale_fk foreign key (sale_id) references sales(id);

create table sale_items (
  id                 bigserial primary key,
  sale_id            bigint not null references sales(id),
  product_id         bigint not null references products(id),
  variant_id         bigint not null references product_variants(id),
  menu_item_id       bigint references menu_items(id),       -- reference only
  product_name       text   not null,                        -- snapshot
  variant_name       text   not null,                        -- snapshot
  category_name      text   not null,                        -- snapshot
  quantity           numeric(12,3) not null check (quantity > 0),
  list_price_at_sale numeric(14,2),                          -- menu price on txn date (reference)
  unit_price         numeric(14,2) not null check (unit_price >= 0),   -- ACTUAL price charged (inclusive)
  discount           numeric(14,2) not null default 0 check (discount >= 0),
  line_total         numeric(14,2) not null,                 -- qty*unit_price - discount
  tax_rate           numeric(6,3),
  tax_status         text   not null default 'EXTRACTED' check (tax_status in ('EXTRACTED','NOT_CONFIGURED')),
  gross_taxable      numeric(14,2) not null,
  discount_taxable   numeric(14,2) not null,
  net_taxable        numeric(14,2) not null,
  tax_amount         numeric(14,2) not null
);
create index sale_items_sale_ix on sale_items (sale_id);
create index sale_items_product_ix on sale_items (product_id);

create table payments (
  id         bigserial primary key,
  sale_id    bigint not null references sales(id),
  method     text   not null check (method in ('CASH','UPI','CARD','WHATSAPP','SWIGGY','ZOMATO','OTHER_ONLINE')),
  amount     numeric(14,2) not null check (amount >= 0),
  status     text   not null default 'PAID' check (status in ('PENDING','INITIATED','PAID','PARTIALLY_PAID','FAILED','REFUNDED')),
  reference  text,
  created_at timestamptz not null default now()
);
create index payments_sale_ix on payments (sale_id);

-- QUICK DAILY ENTRY. total_sales = gross billed (GST-inclusive, before discounts and refunds).
create table daily_sales_summaries (
  id               bigserial primary key,
  business_id      bigint not null default 1 references business(id),
  txn_date         date   not null,
  entry_at         timestamptz not null default now(),
  total_sales      numeric(14,2) not null check (total_sales >= 0),
  cash             numeric(14,2) not null default 0,
  upi              numeric(14,2) not null default 0,
  card             numeric(14,2) not null default 0,
  whatsapp         numeric(14,2) not null default 0,
  swiggy           numeric(14,2) not null default 0,
  zomato           numeric(14,2) not null default 0,
  other_online     numeric(14,2) not null default 0,
  orders_count     int,
  discounts        numeric(14,2) not null default 0,
  refunds          numeric(14,2) not null default 0,
  wastage          numeric(14,2),
  other_income     numeric(14,2),
  notes            text,
  tax_rate         numeric(6,3),
  tax_status       text   not null default 'EXTRACTED' check (tax_status in ('EXTRACTED','NOT_CONFIGURED')),
  gross_taxable    numeric(14,2) not null default 0,
  discount_taxable numeric(14,2) not null default 0,
  refund_taxable   numeric(14,2) not null default 0,
  net_taxable      numeric(14,2) not null default 0,
  tax_amount       numeric(14,2) not null default 0,
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
create unique index dss_one_active_per_day on daily_sales_summaries (business_id, txn_date) where void_state = 'ACTIVE';

-- When a day has BOTH itemised sales and a quick total, an approver decides which to trust.
create table day_reviews (
  id              bigserial primary key,
  business_id     bigint not null default 1 references business(id),
  txn_date        date   not null,
  decision        text   not null check (decision in ('ACCEPT_ITEMISED','ACCEPT_SUMMARY','ACCEPT_EXPLAINED')),
  reason          text,
  itemised_total  numeric(14,2) not null,   -- snapshot at approval (for stale detection)
  summary_total   numeric(14,2) not null,
  approved_by     bigint references users(id),
  approved_at     timestamptz not null default now(),
  unique (business_id, txn_date)
);

create trigger sales_no_delete before delete on sales for each row execute function forbid_modification();
create trigger orders_no_delete before delete on orders for each row execute function forbid_modification();
create trigger dss_no_delete before delete on daily_sales_summaries for each row execute function forbid_modification();
