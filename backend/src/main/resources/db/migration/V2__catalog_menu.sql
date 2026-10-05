-- V2: catalog, menu versions, price history, tax rules
create table categories (
  id          bigserial primary key,
  business_id bigint not null default 1 references business(id),
  name        text not null,
  parent_id   bigint references categories(id),
  sort_order  int  not null default 0,
  active      boolean not null default true,
  created_at  timestamptz not null default now()
);
create unique index categories_name_uq on categories (business_id, lower(name), coalesce(parent_id, 0));

-- A product is a permanent identity. Price/name/category AS OFFERED live in menu_items (per menu version).
create table products (
  id               bigserial primary key,
  business_id      bigint not null default 1 references business(id),
  code             text,
  name             text not null,
  category_id      bigint not null references categories(id),
  subcategory      text,
  unit             text not null default 'unit',
  description      text,
  image_url        text,
  lifecycle_status text not null default 'ACTIVE'
      check (lifecycle_status in ('DRAFT','ACTIVE','TEMPORARILY_UNAVAILABLE','DISCONTINUED','ARCHIVED')),
  discontinued_on  date,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);
create unique index products_name_uq on products (business_id, category_id, lower(name), coalesce(lower(subcategory), ''));

create table product_variants (
  id         bigserial primary key,
  product_id bigint not null references products(id),
  name       text   not null,
  size       text,
  status     text   not null default 'ACTIVE' check (status in ('ACTIVE','INACTIVE')),
  sort_order int    not null default 0,
  unique (product_id, name)
);

create table product_aliases (
  id         bigserial primary key,
  product_id bigint not null references products(id),
  alias      text   not null,
  unique (product_id, alias)
);

-- Stored status is DRAFT or SCHEDULED. ACTIVE / ARCHIVED are RESOLVED from effective dates when read,
-- so a missed nightly job can never leave the wrong menu live.
create table menu_versions (
  id                  bigserial primary key,
  business_id         bigint not null default 1 references business(id),
  name                text   not null,
  effective_from      date,
  status              text   not null default 'DRAFT' check (status in ('DRAFT','SCHEDULED')),
  reason              text,
  based_on_version_id bigint references menu_versions(id),
  created_at          timestamptz not null default now(),
  created_by          bigint references users(id),
  updated_at          timestamptz not null default now(),
  check (status = 'DRAFT' or effective_from is not null)
);
create unique index menu_versions_effective_uq on menu_versions (business_id, effective_from) where status = 'SCHEDULED';

create table menu_items (
  id               bigserial primary key,
  menu_version_id  bigint not null references menu_versions(id),
  product_id       bigint not null references products(id),
  variant_id       bigint not null references product_variants(id),
  price            numeric(14,2) not null check (price >= 0),   -- GST-inclusive
  display_name     text   not null,   -- snapshot as offered in this version
  category_name    text   not null,   -- snapshot
  subcategory      text,
  variant_name     text   not null,   -- snapshot
  size_label       text,
  description      text,
  image_url        text,
  availability     text   not null default 'AVAILABLE'
      check (availability in ('AVAILABLE','OUT_OF_STOCK','TEMPORARILY_UNAVAILABLE')),
  is_addon         boolean not null default false,
  sort_order       int    not null default 0,
  unique (menu_version_id, variant_id)
);
create index menu_items_product_ix on menu_items (product_id);

-- Append-only log of price changes (event log). Latest event per (variant, new_menu_version) is the current one.
create table price_history (
  id                  bigserial primary key,
  business_id         bigint not null default 1 references business(id),
  product_id          bigint not null references products(id),
  variant_id          bigint not null references product_variants(id),
  old_price           numeric(14,2),
  new_price           numeric(14,2) not null,
  effective_date      date not null,
  old_menu_version_id bigint references menu_versions(id),
  new_menu_version_id bigint not null references menu_versions(id),
  reason              text,
  changed_by          bigint references users(id),
  changed_at          timestamptz not null default now()
);
create index price_history_product_ix on price_history (product_id, effective_date);

-- Effective-dated tax rules. GST rate is DATA, never hard-coded.
create table tax_rules (
  id             bigserial primary key,
  business_id    bigint not null default 1 references business(id),
  tax_type       text   not null default 'GST',
  scope          text   not null default 'ALL',      -- 'ALL' or a category name
  rate           numeric(6,3) not null check (rate >= 0 and rate <= 100),
  itc_eligible   boolean,                            -- NULL = not decided (ask the CA)
  effective_from date   not null,
  notes          text,
  created_at     timestamptz not null default now(),
  unique (business_id, tax_type, scope, effective_from)
);
