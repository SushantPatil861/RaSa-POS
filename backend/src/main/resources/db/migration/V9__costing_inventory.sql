-- V9: Phase 2 - ingredients, recipes, ingredient costs, inventory ledger, stock counts, wastage, containers,
-- prep batches, supplier ledger, cash and payment reconciliation.
-- Principles: append-only history (triggers), effective-dated costs/recipes, void-with-reason, unknown stays NULL.

-- ------------------------------------------------------------------ permissions
insert into permissions (code, description) values
 ('inventory.view',        'View ingredients, stock, counts and inventory reports'),
 ('inventory.manage',      'Manage ingredients, stock movements, stock counts, prep batches and purchase links'),
 ('recipes.view',          'View recipes'),
 ('recipes.edit',          'Create recipes and add recipe versions'),
 ('wastage.create',        'Record wastage'),
 ('wastage.void',          'Void wastage entries'),
 ('costing.view',          'View ingredient costs, COGS and margins (also needs finance.view_profit)'),
 ('cash.view',             'View cash and payment reconciliation'),
 ('cash.close',            'Count, close and reopen the cash day; record payment settlements'),
 ('containers.manage',     'Open and finish ingredient containers (tubs)'),
 ('supplier_ledger.view',  'View supplier ledger and payments'),
 ('supplier_ledger.manage','Record and void supplier payments');

insert into role_permissions (role_id, permission_code)
select r.id, p from roles r cross join unnest(array[
 'inventory.view','inventory.manage','recipes.view','recipes.edit','wastage.create','wastage.void','costing.view',
 'cash.view','cash.close','containers.manage','supplier_ledger.view','supplier_ledger.manage'
]) as p where r.code = 'OWNER';

-- Manager: operations (no cost/margin figures unless the owner adds costing.view; supplier ledger view only)
insert into role_permissions (role_id, permission_code)
select r.id, p from roles r cross join unnest(array[
 'inventory.view','inventory.manage','recipes.view','recipes.edit','wastage.create','wastage.void',
 'cash.view','cash.close','containers.manage','supplier_ledger.view'
]) as p where r.code = 'MANAGER';

insert into role_permissions (role_id, permission_code)
select r.id, p from roles r cross join unnest(array[
 'inventory.view','recipes.view','costing.view','cash.view','supplier_ledger.view'
]) as p where r.code = 'ACCOUNTANT';

insert into role_permissions (role_id, permission_code)
select r.id, p from roles r cross join unnest(array['inventory.view','wastage.create','containers.manage']) as p
where r.code = 'KITCHEN';

insert into role_permissions (role_id, permission_code)
select r.id, 'cash.view' from roles r where r.code = 'CASHIER';

-- ------------------------------------------------------------------ units
-- Conversion only inside one dimension. litre <-> kg is NEVER automatic (needs a pack spec).
create table units (
  code      text primary key,
  name      text not null,
  dimension text not null check (dimension in ('MASS','VOLUME','COUNT')),
  base_unit text not null check (base_unit in ('g','ml','pcs')),
  to_base   numeric(14,6) not null check (to_base > 0)
);
insert into units (code, name, dimension, base_unit, to_base) values
 ('g',   'gram',       'MASS',   'g',   1),
 ('kg',  'kilogram',   'MASS',   'g',   1000),
 ('ml',  'millilitre', 'VOLUME', 'ml',  1),
 ('l',   'litre',      'VOLUME', 'ml',  1000),
 ('pcs', 'piece',      'COUNT',  'pcs', 1);

-- ------------------------------------------------------------------ ingredients
create table ingredients (
  id          bigserial primary key,
  business_id bigint not null default 1 references business(id),
  name        text   not null,
  kind        text   not null default 'RAW' check (kind in ('RAW','PREPARED')),
  base_unit   text   not null references units(code) check (base_unit in ('g','ml','pcs')),
  category    text,
  min_level   numeric(16,4) check (min_level is null or min_level >= 0),   -- par level in base units
  active      boolean not null default true,
  notes       text,
  created_at  timestamptz not null default now(),
  created_by  bigint references users(id),
  updated_at  timestamptz not null default now()
);
create unique index ingredients_name_uq on ingredients (business_id, lower(name));

create table ingredient_pack_specs (
  id            bigserial primary key,
  ingredient_id bigint not null references ingredients(id),
  name          text   not null,                                   -- e.g. "1.4 kg tub"
  quantity_base numeric(16,4) not null check (quantity_base > 0),  -- e.g. 1400 (g)
  basis         text   not null default 'SUPPLIER_SPEC' check (basis in ('EXACT','ESTIMATED','SUPPLIER_SPEC')),
  notes         text,
  active        boolean not null default true,
  created_at    timestamptz not null default now()
);
create unique index pack_specs_name_uq on ingredient_pack_specs (ingredient_id, lower(name));

-- ------------------------------------------------------------------ ingredient cost history (append-only)
-- purchase_item_id / purchase_id are references only: PurchaseService replaces item rows on edit, so no FK to purchase_items.
create table ingredient_cost_history (
  id               bigserial primary key,
  business_id      bigint not null default 1 references business(id),
  ingredient_id    bigint not null references ingredients(id),
  effective_date   date   not null,
  cost_per_base    numeric(16,6) not null check (cost_per_base >= 0),
  source           text   not null check (source in ('PURCHASE','MANUAL','ESTIMATE')),
  purchase_item_id bigint,
  purchase_id      bigint references purchases(id),
  notes            text,
  entry_at         timestamptz not null default now(),
  created_by       bigint references users(id)
);
create index cost_history_ix on ingredient_cost_history (ingredient_id, effective_date desc, id desc);
create trigger cost_history_no_update before update on ingredient_cost_history for each row execute function forbid_modification();
create trigger cost_history_no_delete before delete on ingredient_cost_history for each row execute function forbid_modification();

-- A cost row is never edited or deleted; a wrong one is voided here (resolver ignores voided rows).
create table ingredient_cost_voids (
  cost_id   bigint primary key references ingredient_cost_history(id),
  reason    text not null,
  voided_at timestamptz not null default now(),
  voided_by bigint references users(id)
);
create trigger cost_voids_no_update before update on ingredient_cost_voids for each row execute function forbid_modification();
create trigger cost_voids_no_delete before delete on ingredient_cost_voids for each row execute function forbid_modification();

-- ------------------------------------------------------------------ recipes (versions are immutable, effective-dated)
create table recipes (
  id            bigserial primary key,
  business_id   bigint not null default 1 references business(id),
  variant_id    bigint references product_variants(id),
  ingredient_id bigint references ingredients(id),     -- PREPARED ingredient recipe
  created_at    timestamptz not null default now(),
  created_by    bigint references users(id),
  check ((variant_id is not null)::int + (ingredient_id is not null)::int = 1)
);
create unique index recipes_variant_uq    on recipes (variant_id)    where variant_id is not null;
create unique index recipes_ingredient_uq on recipes (ingredient_id) where ingredient_id is not null;

create table recipe_versions (
  id             bigserial primary key,
  recipe_id      bigint not null references recipes(id),
  version_no     int    not null,
  effective_from date   not null,
  output_qty     numeric(16,4) not null default 1 check (output_qty > 0),  -- portions (variant) or base units (prepared)
  reason         text,
  notes          text,
  created_at     timestamptz not null default now(),
  created_by     bigint references users(id),
  unique (recipe_id, version_no),
  unique (recipe_id, effective_from)
);

create table recipe_lines (
  id            bigserial primary key,
  version_id    bigint not null references recipe_versions(id),
  ingredient_id bigint not null references ingredients(id),
  quantity_base numeric(16,4) not null check (quantity_base > 0),
  waste_pct     numeric(6,2)  not null default 0 check (waste_pct >= 0 and waste_pct < 100),
  notes         text,
  unique (version_id, ingredient_id)
);

-- A new version must start AFTER every existing version (the timeline only grows; no overlap, no back-insertion).
create or replace function recipe_version_guard() returns trigger as $$
begin
  if exists (select 1 from recipe_versions v where v.recipe_id = new.recipe_id and v.effective_from >= new.effective_from) then
    raise exception 'A new recipe version must start after the latest existing version (no overlap, past versions are never changed)'
      using errcode = '23514';
  end if;
  return new;
end;
$$ language plpgsql;
create trigger recipe_versions_guard before insert on recipe_versions for each row execute function recipe_version_guard();

create or replace function recipe_line_guard() returns trigger as $$
begin
  if exists (select 1 from recipe_versions v join recipes r on r.id = v.recipe_id
             where v.id = new.version_id and r.ingredient_id = new.ingredient_id) then
    raise exception 'A prepared ingredient cannot contain itself' using errcode = '23514';
  end if;
  return new;
end;
$$ language plpgsql;
create trigger recipe_lines_guard before insert on recipe_lines for each row execute function recipe_line_guard();

create trigger recipe_versions_no_update before update on recipe_versions for each row execute function forbid_modification();
create trigger recipe_versions_no_delete before delete on recipe_versions for each row execute function forbid_modification();
create trigger recipe_lines_no_update before update on recipe_lines for each row execute function forbid_modification();
create trigger recipe_lines_no_delete before delete on recipe_lines for each row execute function forbid_modification();
create trigger recipes_no_delete before delete on recipes for each row execute function forbid_modification();

-- ------------------------------------------------------------------ inventory ledger (append-only)
create table inventory_movements (
  id            bigserial primary key,
  business_id   bigint not null default 1 references business(id),
  ingredient_id bigint not null references ingredients(id),
  movement_type text   not null check (movement_type in
      ('OPENING','PURCHASE','WASTAGE','ADJUSTMENT','COUNT_ADJUSTMENT','PREP_PRODUCE','PREP_CONSUME','REVERSAL')),
  quantity_base numeric(16,4) not null check (quantity_base <> 0),
  txn_date      date   not null,
  entry_at      timestamptz not null default now(),
  reason        text,
  ref_type      text,
  ref_id        bigint,
  source        text   not null default 'MANUAL'
      check (source in ('MANUAL','PURCHASE_LINK','WASTAGE','COUNT','PREP','SYSTEM','IMPORT')),
  reverses_id   bigint unique references inventory_movements(id),
  created_by    bigint references users(id),
  check ((movement_type in ('OPENING','PURCHASE','PREP_PRODUCE') and quantity_base > 0)
      or (movement_type in ('WASTAGE','PREP_CONSUME') and quantity_base < 0)
      or movement_type in ('ADJUSTMENT','COUNT_ADJUSTMENT','REVERSAL')),
  check (movement_type <> 'REVERSAL' or (reverses_id is not null and reason is not null)),
  check (movement_type <> 'ADJUSTMENT' or reason is not null)
);
create index inv_mv_ingredient_ix on inventory_movements (business_id, ingredient_id, txn_date);
create index inv_mv_ref_ix on inventory_movements (ref_type, ref_id);
create trigger inv_mv_no_update before update on inventory_movements for each row execute function forbid_modification();
create trigger inv_mv_no_delete before delete on inventory_movements for each row execute function forbid_modification();

-- ------------------------------------------------------------------ stock counts
create table stock_counts (
  id          bigserial primary key,
  business_id bigint not null default 1 references business(id),
  count_date  date   not null,
  status      text   not null default 'DRAFT' check (status in ('DRAFT','POSTED','VOID')),
  notes       text,
  created_at  timestamptz not null default now(),
  created_by  bigint references users(id),
  posted_at   timestamptz,
  posted_by   bigint references users(id),
  void_reason text,
  voided_at   timestamptz,
  voided_by   bigint references users(id)
);
create index stock_counts_date_ix on stock_counts (business_id, count_date) where status = 'POSTED';

create table stock_count_lines (
  id            bigserial primary key,
  count_id      bigint not null references stock_counts(id),
  ingredient_id bigint not null references ingredients(id),
  counted_qty   numeric(16,4) not null check (counted_qty >= 0),
  expected_qty  numeric(16,4),          -- snapshot at posting; NULL = no baseline (first count)
  variance      numeric(16,4),          -- counted - expected; NULL when expected unknown
  unit_cost     numeric(16,6),          -- snapshot at posting; NULL = cost missing
  cost_status   text check (cost_status in ('COMPLETE','PARTIAL','MISSING')),
  cost_estimated boolean not null default false,
  note          text,
  unique (count_id, ingredient_id)
);

create or replace function guard_stock_count_header() returns trigger as $$
begin
  if tg_op = 'DELETE' then
    raise exception 'DELETE on stock_counts is not allowed (void instead)' using errcode = '42501';
  end if;
  if old.status = 'DRAFT' then
    return new;
  end if;
  if old.status = 'POSTED' and new.status = 'VOID' and new.count_date = old.count_date
     and new.notes is not distinct from old.notes and new.void_reason is not null then
    return new;
  end if;
  raise exception 'A posted stock count is immutable (void it with a reason instead)' using errcode = '42501';
end;
$$ language plpgsql;
create trigger stock_counts_guard before update or delete on stock_counts
  for each row execute function guard_stock_count_header();

create or replace function guard_stock_count_lines() returns trigger as $$
declare st text;
begin
  select status into st from stock_counts where id = coalesce(new.count_id, old.count_id);
  if st is distinct from 'DRAFT' then
    raise exception 'Lines of a posted stock count are immutable' using errcode = '42501';
  end if;
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$ language plpgsql;
create trigger stock_count_lines_guard before insert or update or delete on stock_count_lines
  for each row execute function guard_stock_count_lines();

-- ------------------------------------------------------------------ prep batches (semi-finished production)
create table prep_batches (
  id               bigserial primary key,
  business_id      bigint not null default 1 references business(id),
  ingredient_id    bigint not null references ingredients(id),
  txn_date         date   not null,
  entry_at         timestamptz not null default now(),
  planned_qty      numeric(16,4) not null check (planned_qty > 0),   -- what the recipe was scaled to
  actual_qty       numeric(16,4) not null check (actual_qty > 0),    -- what was really produced (actual yield)
  recipe_version_id bigint not null references recipe_versions(id),
  cost_total       numeric(14,2),
  cost_per_unit    numeric(16,6),
  cost_status      text not null default 'MISSING' check (cost_status in ('COMPLETE','PARTIAL','MISSING')),
  cost_estimated   boolean not null default false,
  notes            text,
  void_state       text not null default 'ACTIVE' check (void_state in ('ACTIVE','VOIDED')),
  void_reason      text,
  voided_by        bigint references users(id),
  voided_at        timestamptz,
  created_by       bigint references users(id)
);
create index prep_batches_ix on prep_batches (business_id, txn_date);
create trigger prep_batches_no_delete before delete on prep_batches for each row execute function forbid_modification();

-- ------------------------------------------------------------------ wastage
create table wastage_entries (
  id            bigserial primary key,
  business_id   bigint not null default 1 references business(id),
  txn_date      date   not null,
  entry_at      timestamptz not null default now(),
  ingredient_id bigint references ingredients(id),
  product_id    bigint references products(id),
  variant_id    bigint references product_variants(id),
  quantity      numeric(16,4) not null check (quantity > 0),     -- ingredient: base units; product: portions
  reason        text   not null check (reason in ('EXPIRED','SPOILED','SPILLAGE','OVER_PORTION','STAFF_MEAL','SAMPLE','OTHER')),
  unit_cost     numeric(16,6),                                   -- snapshot; NULL = cost missing
  cost_total    numeric(14,2),
  cost_status   text   not null default 'MISSING' check (cost_status in ('COMPLETE','PARTIAL','MISSING')),
  cost_estimated boolean not null default false,
  notes         text,
  void_state    text   not null default 'ACTIVE' check (void_state in ('ACTIVE','VOIDED')),
  void_reason   text,
  voided_by     bigint references users(id),
  voided_at     timestamptz,
  created_by    bigint references users(id),
  check ((ingredient_id is not null)::int + (variant_id is not null)::int = 1),
  check (variant_id is null or product_id is not null)
);
create index wastage_date_ix on wastage_entries (business_id, txn_date) where void_state = 'ACTIVE';
create trigger wastage_no_delete before delete on wastage_entries for each row execute function forbid_modification();

-- ------------------------------------------------------------------ opened containers (tubs)
create table stock_containers (
  id               bigserial primary key,
  business_id      bigint not null default 1 references business(id),
  container_code   text   not null,
  ingredient_id    bigint not null references ingredients(id),
  pack_spec_id     bigint references ingredient_pack_specs(id),
  opened_date      date   not null,
  entry_at         timestamptz not null default now(),
  opening_qty      numeric(16,4) not null check (opening_qty > 0),
  quantity_basis   text   not null default 'ESTIMATED' check (quantity_basis in ('MEASURED','ESTIMATED')),
  status           text   not null default 'OPEN' check (status in ('OPEN','FINISHED')),
  finished_date    date,
  finished_at      timestamptz,
  closing_qty      numeric(16,4) check (closing_qty is null or closing_qty >= 0),
  actual_usage     numeric(16,4),
  theoretical_usage numeric(16,4),
  wastage_recorded numeric(16,4),
  over_serving_pct numeric(10,2),
  over_serving_flag boolean,
  usage_flags      text,                       -- comma separated: SUMMARY_DAYS_EXCLUDED, OVERLAPPING_CONTAINERS, NO_THEORETICAL
  notes            text,
  created_by       bigint references users(id),
  finished_by      bigint references users(id),
  unique (business_id, container_code),
  check (status = 'OPEN' or (finished_date is not null and closing_qty is not null))
);
create index containers_ing_ix on stock_containers (business_id, ingredient_id, opened_date);
create trigger containers_no_delete before delete on stock_containers for each row execute function forbid_modification();
create or replace function guard_container() returns trigger as $$
begin
  if old.status = 'FINISHED' then
    raise exception 'A finished container is immutable' using errcode = '42501';
  end if;
  return new;
end;
$$ language plpgsql;
create trigger containers_guard before update on stock_containers for each row execute function guard_container();

-- ------------------------------------------------------------------ purchase -> ingredient links
-- Own table (purchase tables are untouched). purchase_item_id has NO foreign key on purpose: PurchaseService replaces
-- item rows when a purchase is edited; an edited purchase shows up as ITEM_REMOVED here and must be re-linked.
create table purchase_item_links (
  id               bigserial primary key,
  business_id      bigint not null default 1 references business(id),
  purchase_item_id bigint not null unique,
  purchase_id      bigint not null references purchases(id),
  ingredient_id    bigint not null references ingredients(id),
  base_quantity    numeric(16,4) not null check (base_quantity > 0),
  pack_spec_id     bigint references ingredient_pack_specs(id),
  item_name        text   not null,           -- snapshots at link time
  item_total       numeric(14,2) not null,
  cost_history_id  bigint references ingredient_cost_history(id),
  movement_id      bigint references inventory_movements(id),
  status           text   not null default 'ACTIVE' check (status in ('ACTIVE','UNLINKED')),
  linked_at        timestamptz not null default now(),
  linked_by        bigint references users(id),
  unlinked_at      timestamptz,
  unlinked_by      bigint references users(id),
  unlink_reason    text
);
create index item_links_ing_ix on purchase_item_links (ingredient_id);
create trigger item_links_no_delete before delete on purchase_item_links for each row execute function forbid_modification();

-- ------------------------------------------------------------------ supplier payments
create table supplier_payments (
  id          bigserial primary key,
  business_id bigint not null default 1 references business(id),
  supplier_id bigint not null references suppliers(id),
  txn_date    date   not null,
  entry_at    timestamptz not null default now(),
  amount      numeric(14,2) not null check (amount > 0),
  method      text   not null check (method in ('CASH','UPI','CARD','BANK','CHEQUE','OTHER')),
  reference   text,
  notes       text,
  void_state  text   not null default 'ACTIVE' check (void_state in ('ACTIVE','VOIDED')),
  void_reason text,
  voided_by   bigint references users(id),
  voided_at   timestamptz,
  created_by  bigint references users(id)
);
create index supplier_payments_ix on supplier_payments (business_id, supplier_id, txn_date);
create trigger supplier_payments_no_delete before delete on supplier_payments for each row execute function forbid_modification();

-- ------------------------------------------------------------------ cash reconciliation
create table cash_days (
  id            bigserial primary key,
  business_id   bigint not null default 1 references business(id),
  txn_date      date   not null,
  opening_float numeric(14,2) not null default 0 check (opening_float >= 0),
  expected_cash numeric(14,2),               -- snapshot of the computed figure (live figure is recomputed on read)
  counted_cash  numeric(14,2) check (counted_cash is null or counted_cash >= 0),
  difference    numeric(14,2),               -- counted - expected
  status        text   not null default 'DRAFT' check (status in ('DRAFT','CLOSED')),
  notes         text,
  reopen_count  int    not null default 0,
  closed_by     bigint references users(id),
  closed_at     timestamptz,
  created_at    timestamptz not null default now(),
  created_by    bigint references users(id),
  updated_at    timestamptz not null default now(),
  unique (business_id, txn_date),
  check (status = 'DRAFT' or (counted_cash is not null and closed_at is not null))
);
create trigger cash_days_no_delete before delete on cash_days for each row execute function forbid_modification();

create table cash_day_explanations (
  id          bigserial primary key,
  cash_day_id bigint not null references cash_days(id),
  category    text   not null check (category in
      ('CHANGE_SHORT','CASH_TAKEN_BY_OWNER','BANK_DEPOSIT','REFUND','ROUNDING','OTHER','UNKNOWN')),
  amount      numeric(14,2) not null check (amount <> 0),   -- signed, same sign as the difference it explains
  note        text,
  created_at  timestamptz not null default now(),
  created_by  bigint references users(id)
);
create or replace function guard_cash_explanations() returns trigger as $$
declare st text;
begin
  select status into st from cash_days where id = coalesce(new.cash_day_id, old.cash_day_id);
  if st is distinct from 'DRAFT' then
    raise exception 'The cash day is closed: reopen it (with a reason) before changing explanations' using errcode = '42501';
  end if;
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$ language plpgsql;
create trigger cash_explanations_guard before insert or update or delete on cash_day_explanations
  for each row execute function guard_cash_explanations();

-- ------------------------------------------------------------------ payment settlements (UPI/card/aggregator payouts)
create table payment_settlements (
  id              bigserial primary key,
  business_id     bigint not null default 1 references business(id),
  txn_date        date   not null,      -- the sales day being settled
  method          text   not null check (method in ('UPI','CARD','WHATSAPP','SWIGGY','ZOMATO','OTHER_ONLINE')),
  received_amount numeric(14,2) not null check (received_amount >= 0),
  reference       text,
  notes           text,
  entry_at        timestamptz not null default now(),
  void_state      text   not null default 'ACTIVE' check (void_state in ('ACTIVE','VOIDED')),
  void_reason     text,
  voided_by       bigint references users(id),
  voided_at       timestamptz,
  created_by      bigint references users(id)
);
create index settlements_ix on payment_settlements (business_id, txn_date, method) where void_state = 'ACTIVE';
create trigger settlements_no_delete before delete on payment_settlements for each row execute function forbid_modification();
