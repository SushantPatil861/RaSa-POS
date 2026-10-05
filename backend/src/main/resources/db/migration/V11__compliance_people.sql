-- V11: Phase 4 - GST workpapers, TDS/TCS register, payroll/HR, hygiene & temperature logs, compliance calendar.
-- Everything here is a WORKPAPER / REGISTER for the owner's CA. Nothing is filed from this system.
-- References only V1..V7 tables (business, users, expenses, purchases, tax_rules, v_daily_sales, ...).

-- =====================================================================================================================
-- PERMISSIONS
-- =====================================================================================================================
insert into permissions (code, description) values
 ('gst.view',           'View GST workpapers, ITC entries and filing log'),
 ('gst.prepare',        'Prepare GST workpapers, enter ITC details, mark reviewed, log filings'),
 ('tds.view',           'View the TDS / TCS register'),
 ('tds.manage',         'Add TDS / TCS entries, mark deposits, void entries'),
 ('tds.configure',      'Change TDS section rates and thresholds (adds a new effective-dated row)'),
 ('payroll.view',       'View employees, salary rates, payroll runs and payslips'),
 ('payroll.manage',     'Manage employees, advances and payroll runs (approve, pay, void)'),
 ('attendance.manage',  'Mark and correct staff attendance'),
 ('hygiene.view',       'View hygiene checklists, temperature logs and reports'),
 ('hygiene.log',        'Complete hygiene checklists and log temperatures'),
 ('hygiene.manage',     'Manage checklists, temperature points, sign off and void logs'),
 ('compliance.view',    'View the compliance calendar, licences and dashboard'),
 ('compliance.manage',  'Complete / add compliance tasks and maintain licence records'),
 ('compliance.configure','Edit compliance rule due dates (owner only)');

-- OWNER: everything in this phase
insert into role_permissions (role_id, permission_code)
select r.id, p from roles r cross join unnest(array[
  'gst.view','gst.prepare','tds.view','tds.manage','tds.configure','payroll.view','payroll.manage','attendance.manage',
  'hygiene.view','hygiene.log','hygiene.manage','compliance.view','compliance.manage','compliance.configure'
]) as p where r.code = 'OWNER';

insert into role_permissions (role_id, permission_code)
select r.id, p from roles r cross join unnest(array[
  'gst.view','tds.view','attendance.manage','hygiene.view','hygiene.log','hygiene.manage','compliance.view'
]) as p where r.code = 'MANAGER';

insert into role_permissions (role_id, permission_code)
select r.id, p from roles r cross join unnest(array[
  'gst.view','gst.prepare','tds.view','tds.manage','payroll.view','hygiene.view','compliance.view','compliance.manage'
]) as p where r.code = 'ACCOUNTANT';

insert into role_permissions (role_id, permission_code)
select r.id, p from roles r cross join unnest(array['hygiene.view','hygiene.log']) as p where r.code in ('CASHIER','KITCHEN');

-- =====================================================================================================================
-- GST (QRMP) workpapers
-- =====================================================================================================================
-- ITC / input-tax details entered by staff against an EXISTING purchase (purchases itself is never altered).
-- itc_eligible NULL = undecided ("ask the CA"); only TRUE rows are counted as ITC.
create table purchase_tax_details (
  id             bigserial primary key,
  business_id    bigint not null default 1 references business(id),
  purchase_id    bigint not null references purchases(id),
  taxable_value  numeric(14,2) not null check (taxable_value >= 0),
  cgst           numeric(14,2) not null default 0 check (cgst >= 0),
  sgst           numeric(14,2) not null default 0 check (sgst >= 0),
  igst           numeric(14,2) not null default 0 check (igst >= 0),
  total_tax      numeric(14,2) not null check (total_tax >= 0),
  supplier_gstin text,
  itc_eligible   boolean,
  notes          text,
  version        int    not null default 0,
  created_by     bigint references users(id),
  created_at     timestamptz not null default now(),
  updated_by     bigint references users(id),
  updated_at     timestamptz not null default now(),
  constraint ptd_total_ck check (total_tax = cgst + sgst + igst),
  unique (purchase_id)
);
create index ptd_business_ix on purchase_tax_details (business_id);
create trigger ptd_no_delete before delete on purchase_tax_details for each row execute function forbid_modification();

-- A workpaper is a SNAPSHOT: figures/warnings can never change after insert. Recompute = new version.
create table gst_workpapers (
  id            bigserial primary key,
  business_id   bigint not null default 1 references business(id),
  period_type   text   not null check (period_type in ('QUARTER','MONTH')),
  period_start  date   not null,
  period_end    date   not null,
  status        text   not null default 'DRAFT' check (status in ('DRAFT','REVIEWED','FILED_MARKED')),
  figures       jsonb  not null,
  warnings      jsonb  not null default '[]'::jsonb,
  version       int    not null default 1,
  prepared_by   bigint references users(id),
  prepared_at   timestamptz not null default now(),
  reviewed_by   bigint references users(id),
  reviewed_at   timestamptz,
  notes         text,
  unique (business_id, period_type, period_start, version)
);
create index gst_workpapers_period_ix on gst_workpapers (business_id, period_type, period_start, version desc);

create or replace function gst_workpapers_guard() returns trigger as $$
begin
  if tg_op = 'DELETE' then
    raise exception 'DELETE on gst_workpapers is not allowed (recompute creates a new version)' using errcode = '42501';
  end if;
  if new.figures is distinct from old.figures or new.warnings is distinct from old.warnings
     or new.period_start <> old.period_start or new.period_end <> old.period_end or new.version <> old.version
     or new.period_type <> old.period_type then
    raise exception 'A GST workpaper snapshot cannot be changed; recompute to create a new version' using errcode = '42501';
  end if;
  return new;
end;
$$ language plpgsql;
create trigger gst_workpapers_guard_upd before update on gst_workpapers for each row execute function gst_workpapers_guard();
create trigger gst_workpapers_guard_del before delete on gst_workpapers for each row execute function gst_workpapers_guard();

-- Manual log of what was filed / paid on the GST portal (the system never files anything).
create table gst_filings (
  id            bigserial primary key,
  business_id   bigint not null default 1 references business(id),
  return_type   text   not null check (return_type in ('GSTR1','GSTR3B','PMT06')),
  period_start  date   not null,
  period_end    date   not null,
  filed_on      date   not null,
  arn           text,
  tax_paid      numeric(14,2) check (tax_paid is null or tax_paid >= 0),
  workpaper_id  bigint references gst_workpapers(id),
  notes         text,
  void_state    text   not null default 'ACTIVE' check (void_state in ('ACTIVE','VOIDED')),
  void_reason   text,
  voided_by     bigint references users(id),
  voided_at     timestamptz,
  version       int    not null default 0,
  created_by    bigint references users(id),
  created_at    timestamptz not null default now()
);
create index gst_filings_period_ix on gst_filings (business_id, period_start);
create trigger gst_filings_no_delete before delete on gst_filings for each row execute function forbid_modification();

-- =====================================================================================================================
-- TDS / TCS
-- =====================================================================================================================
-- Effective-dated section master. Never edited in place: a change inserts a new row with a later effective_from.
create table tds_sections (
  id               bigserial primary key,
  business_id      bigint not null default 1 references business(id),
  code             text   not null,
  description      text   not null,
  payee_type       text   not null default 'ANY' check (payee_type in ('ANY','INDIVIDUAL_HUF','OTHER')),
  rate             numeric(6,3) not null check (rate >= 0 and rate <= 100),
  single_threshold numeric(14,2) check (single_threshold is null or single_threshold >= 0),
  annual_threshold numeric(14,2) check (annual_threshold is null or annual_threshold >= 0),
  effective_from   date   not null,
  verify_note      text,
  source           text   not null default 'USER',
  created_by       bigint references users(id),
  created_at       timestamptz not null default now(),
  unique (business_id, code, payee_type, effective_from)
);
create trigger tds_sections_no_change before update or delete on tds_sections
  for each row execute function forbid_modification();

insert into tds_sections (business_id, code, description, payee_type, rate, single_threshold, annual_threshold, effective_from, verify_note, source) values
 (1, '194C',    'Payment to contractors / sub-contractors (e.g. catering, transport, repair contracts)', 'INDIVIDUAL_HUF', 1.000, 30000, 100000, date '2026-04-01',
     'SEED - verify with CA: threshold is per payment or in aggregate during the financial year.', 'SEED - verify with CA'),
 (1, '194C',    'Payment to contractors / sub-contractors (e.g. catering, transport, repair contracts)', 'OTHER',          2.000, 30000, 100000, date '2026-04-01',
     'SEED - verify with CA: threshold is per payment or in aggregate during the financial year.', 'SEED - verify with CA'),
 (1, '194H',    'Commission or brokerage (not insurance commission)', 'ANY', 2.000, null, 20000, date '2026-04-01',
     'SEED - rate was revised in recent years; verify the current rate and threshold with your CA.', 'SEED - verify with CA'),
 (1, '194I(b)', 'Rent of land, building or furniture', 'ANY', 10.000, null, 600000, date '2026-04-01',
     'SEED - verify with CA: annual threshold and rate; lower-rate cases (plant and machinery 194I(a)) are not seeded.', 'SEED - verify with CA'),
 (1, '194J',    'Fees for professional or technical services', 'ANY', 10.000, null, 50000, date '2026-04-01',
     'SEED - verify with CA: some technical-service payments attract a lower rate.', 'SEED - verify with CA');

create table tds_entries (
  id                bigserial primary key,
  business_id       bigint not null default 1 references business(id),
  txn_date          date   not null,
  entry_at          timestamptz not null default now(),
  deductee_name     text   not null,
  deductee_pan      text,
  section           text   not null,
  payee_type        text   not null default 'ANY' check (payee_type in ('ANY','INDIVIDUAL_HUF','OTHER')),
  payment_amount    numeric(14,2) not null check (payment_amount >= 0),
  rate_applied      numeric(6,3),                       -- NULL when the section/rate is unknown
  tds_amount        numeric(14,2),                      -- NULL = unknown (CHECK_WITH_CA); 0 = below threshold
  threshold_status  text   not null check (threshold_status in ('BELOW_THRESHOLD','DEDUCT','CHECK_WITH_CA')),
  threshold_note    text,
  section_id        bigint references tds_sections(id),
  deposit_due_date  date,
  deposited_on      date,
  challan_no        text,
  status            text   not null default 'PENDING' check (status in ('PENDING','DEPOSITED')),
  expense_id        bigint references expenses(id),
  notes             text,
  source            text   not null default 'MANUAL'
      check (source in ('MANUAL','EXCEL','CSV','OCR','WHATSAPP','COUNTER','IMPORT','SYSTEM','OTHER')),
  revision          int    not null default 0,
  version           int    not null default 0,
  void_state        text   not null default 'ACTIVE' check (void_state in ('ACTIVE','VOIDED')),
  void_reason       text,
  voided_by         bigint references users(id),
  voided_at         timestamptz,
  created_by        bigint references users(id),
  updated_by        bigint references users(id),
  updated_at        timestamptz not null default now(),
  -- PAN when known, else the lower-cased name: groups a deductee for the annual-threshold view
  deductee_key      text generated always as (coalesce(nullif(upper(btrim(deductee_pan)), ''), lower(btrim(deductee_name)))) stored,
  -- financial year (Apr-Mar) identified by its starting calendar year
  fy_start_year     int generated always as (extract(year from txn_date)::int - case when extract(month from txn_date) < 4 then 1 else 0 end) stored,
  constraint tds_deposit_ck check ((status = 'DEPOSITED') = (deposited_on is not null))
);
create index tds_entries_fy_ix on tds_entries (business_id, fy_start_year, section, deductee_key) where void_state = 'ACTIVE';
create index tds_entries_pending_ix on tds_entries (business_id, deposit_due_date) where void_state = 'ACTIVE' and status = 'PENDING';
create trigger tds_entries_no_delete before delete on tds_entries for each row execute function forbid_modification();

-- TCS / TDS credits received via delivery platforms (shown to the CA; claimed only on their advice).
create table tcs_credits (
  id           bigserial primary key,
  business_id  bigint not null default 1 references business(id),
  platform     text   not null check (platform in ('SWIGGY','ZOMATO','OTHER')),
  platform_name text,
  period_start date   not null,
  period_end   date   not null,
  tcs_amount   numeric(14,2) not null check (tcs_amount >= 0),
  gst_tcs      boolean not null default false,
  notes        text,
  source       text   not null default 'MANUAL' check (source in ('MANUAL','IMPORT')),
  void_state   text   not null default 'ACTIVE' check (void_state in ('ACTIVE','VOIDED')),
  void_reason  text,
  voided_by    bigint references users(id),
  voided_at    timestamptz,
  version      int    not null default 0,
  created_by   bigint references users(id),
  created_at   timestamptz not null default now(),
  check (period_end >= period_start)
);
create index tcs_credits_period_ix on tcs_credits (business_id, period_start) where void_state = 'ACTIVE';
create trigger tcs_credits_no_delete before delete on tcs_credits for each row execute function forbid_modification();

-- =====================================================================================================================
-- PAYROLL / HR   (no Aadhaar or other identity numbers are stored)
-- =====================================================================================================================
create table employees (
  id                  bigserial primary key,
  business_id         bigint not null default 1 references business(id),
  name                text   not null,
  designation         text,
  mobile              text,
  joined_on           date,
  left_on             date,
  salary_type         text   not null check (salary_type in ('MONTHLY','DAILY','HOURLY')),
  base_rate           numeric(14,2) not null check (base_rate >= 0),
  overtime_multiplier numeric(4,2) not null default 1.5 check (overtime_multiplier >= 0),
  user_id             bigint references users(id),
  upi_or_bank_note    text,
  notes               text,
  active              boolean not null default true,
  version             int    not null default 0,
  created_by          bigint references users(id),
  created_at          timestamptz not null default now(),
  updated_by          bigint references users(id),
  updated_at          timestamptz not null default now(),
  check (left_on is null or joined_on is null or left_on >= joined_on)
);
create index employees_active_ix on employees (business_id, active);
create trigger employees_no_delete before delete on employees for each row execute function forbid_modification();

create table attendance (
  id             bigserial primary key,
  business_id    bigint not null default 1 references business(id),
  employee_id    bigint not null references employees(id),
  att_date       date   not null,
  status         text   not null check (status in ('PRESENT','ABSENT','HALF_DAY','PAID_LEAVE','WEEKLY_OFF','HOLIDAY')),
  hours          numeric(5,2) check (hours is null or (hours >= 0 and hours <= 24)),
  overtime_hours numeric(5,2) not null default 0 check (overtime_hours >= 0 and overtime_hours <= 24),
  notes          text,
  marked_by      bigint references users(id),
  marked_at      timestamptz not null default now(),
  unique (employee_id, att_date)
);
create index attendance_date_ix on attendance (business_id, att_date);

create table employee_advances (
  id               bigserial primary key,
  business_id      bigint not null default 1 references business(id),
  employee_id      bigint not null references employees(id),
  txn_date         date   not null,
  entry_at         timestamptz not null default now(),
  amount           numeric(14,2) not null check (amount > 0),
  recover_per_month numeric(14,2) not null default 0 check (recover_per_month >= 0),
  notes            text,
  void_state       text   not null default 'ACTIVE' check (void_state in ('ACTIVE','VOIDED')),
  void_reason      text,
  voided_by        bigint references users(id),
  voided_at        timestamptz,
  created_by       bigint references users(id)
);
create index advances_emp_ix on employee_advances (employee_id) where void_state = 'ACTIVE';
create trigger advances_no_delete before delete on employee_advances for each row execute function forbid_modification();

create table payroll_runs (
  id              bigserial primary key,
  business_id     bigint not null default 1 references business(id),
  month           date   not null check (extract(day from month) = 1),
  status          text   not null default 'DRAFT' check (status in ('DRAFT','APPROVED','PAID')),
  total_gross     numeric(14,2) not null default 0,
  total_deductions numeric(14,2) not null default 0,
  total_net       numeric(14,2) not null default 0,
  approved_by     bigint references users(id),
  approved_at     timestamptz,
  paid_on         date,
  paid_by         bigint references users(id),
  payment_method  text check (payment_method is null or payment_method in ('CASH','UPI','CARD','BANK','OTHER')),
  expense_id      bigint references expenses(id),
  notes           text,
  void_state      text   not null default 'ACTIVE' check (void_state in ('ACTIVE','VOIDED')),
  void_reason     text,
  voided_by       bigint references users(id),
  voided_at       timestamptz,
  version         int    not null default 0,
  created_by      bigint references users(id),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);
create unique index payroll_runs_one_active_per_month on payroll_runs (business_id, month) where void_state = 'ACTIVE';
create trigger payroll_runs_no_delete before delete on payroll_runs for each row execute function forbid_modification();

create table payslips (
  id                    bigserial primary key,
  run_id                bigint not null references payroll_runs(id),
  employee_id           bigint not null references employees(id),
  employee_name         text   not null,              -- snapshot
  designation           text,                         -- snapshot
  salary_type           text   not null,              -- snapshot
  base_rate             numeric(14,2) not null,       -- snapshot
  paid_days             numeric(6,2)  not null default 0,
  hours_worked          numeric(8,2),
  overtime_hours        numeric(8,2)  not null default 0,
  gross                 numeric(14,2) not null default 0,
  overtime_pay          numeric(14,2) not null default 0,
  other_additions       numeric(14,2) not null default 0,
  other_additions_label text,
  advance_recovery      numeric(14,2) not null default 0,
  other_deductions      numeric(14,2) not null default 0,
  other_deductions_label text,                        -- PF / ESI / PT are NOT computed here: enter what your CA advises
  net_pay               numeric(14,2) not null default 0,
  calc_breakdown        jsonb  not null default '{}'::jsonb,
  notes                 text,
  edit_reason           text,
  edited                boolean not null default false,
  unique (run_id, employee_id)
);

-- Approved / paid payslips are locked at the database level too.
create or replace function payslips_lock() returns trigger as $$
declare st text;
begin
  select status into st from payroll_runs where id = coalesce(old.run_id, new.run_id);
  if st is distinct from 'DRAFT' then
    raise exception 'Payslips of an approved or paid payroll run cannot be changed' using errcode = '42501';
  end if;
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$ language plpgsql;
create trigger payslips_lock_upd before update or delete on payslips for each row execute function payslips_lock();

-- =====================================================================================================================
-- HYGIENE & TEMPERATURE LOGS
-- =====================================================================================================================
create table hygiene_checklists (
  id             bigserial primary key,
  business_id    bigint not null default 1 references business(id),
  name           text   not null,
  frequency      text   not null check (frequency in ('DAILY','WEEKLY','MONTHLY')),
  expected_shift text   check (expected_shift is null or expected_shift in ('OPENING','CLOSING','OTHER')),
  active         boolean not null default true,
  start_date     date   not null default current_date,   -- missed-check reports never look before this
  source         text   not null default 'USER',
  notes          text,
  created_at     timestamptz not null default now()
);
create unique index hygiene_checklists_name_uq on hygiene_checklists (business_id, lower(name));

create table hygiene_checklist_items (
  id             bigserial primary key,
  checklist_id   bigint not null references hygiene_checklists(id),
  text           text   not null,
  sort_order     int    not null default 0,
  requires_photo boolean not null default false,
  active         boolean not null default true
);
create index hygiene_items_ix on hygiene_checklist_items (checklist_id, sort_order);

create table hygiene_logs (
  id            bigserial primary key,
  business_id   bigint not null default 1 references business(id),
  checklist_id  bigint not null references hygiene_checklists(id),
  log_date      date   not null,
  shift         text   not null default 'OTHER' check (shift in ('OPENING','CLOSING','OTHER')),
  completed_by  bigint references users(id),
  completed_at  timestamptz not null default now(),
  results       jsonb  not null default '[]'::jsonb,      -- [{itemId, ok, note}]
  all_ok        boolean not null default false,
  notes         text,
  void_state    text   not null default 'ACTIVE' check (void_state in ('ACTIVE','VOIDED')),
  void_reason   text,
  voided_by     bigint references users(id),
  voided_at     timestamptz,
  signed_off_by bigint references users(id),
  signed_off_at timestamptz
);
create unique index hygiene_logs_one_active on hygiene_logs (checklist_id, log_date, shift) where void_state = 'ACTIVE';
create index hygiene_logs_date_ix on hygiene_logs (business_id, log_date);
create trigger hygiene_logs_no_delete before delete on hygiene_logs for each row execute function forbid_modification();

create table temperature_points (
  id          bigserial primary key,
  business_id bigint not null default 1 references business(id),
  name        text   not null,
  kind        text   not null check (kind in ('FREEZER','CHILLER','HOT_HOLD','DISPLAY','OTHER')),
  min_c       numeric(5,1) not null,
  max_c       numeric(5,1) not null,
  active      boolean not null default true,
  start_date  date   not null default current_date,
  source      text   not null default 'USER',
  notes       text,
  created_at  timestamptz not null default now(),
  check (max_c >= min_c)
);
create unique index temperature_points_name_uq on temperature_points (business_id, lower(name));

create table temperature_logs (
  id                bigserial primary key,
  business_id       bigint not null default 1 references business(id),
  point_id          bigint not null references temperature_points(id),
  logged_at         timestamptz not null default now(),
  log_date          date   not null,                      -- business day of the reading
  temp_c            numeric(5,1) not null,
  min_c             numeric(5,1) not null,                -- range in force at the time (snapshot)
  max_c             numeric(5,1) not null,
  in_range          boolean not null,
  corrective_action text,
  notes             text,
  logged_by         bigint references users(id),
  void_state        text   not null default 'ACTIVE' check (void_state in ('ACTIVE','VOIDED')),
  void_reason       text,
  voided_by         bigint references users(id),
  voided_at         timestamptz,
  constraint temp_corrective_ck check (in_range or (corrective_action is not null and btrim(corrective_action) <> ''))
);
create index temperature_logs_ix on temperature_logs (point_id, log_date);
create trigger temperature_logs_no_delete before delete on temperature_logs for each row execute function forbid_modification();

-- Seed templates (labelled; the owner reviews them for the real equipment and routine)
insert into hygiene_checklists (business_id, name, frequency, expected_shift, source, notes) values
 (1, 'Opening checklist',  'DAILY',  'OPENING', 'SEED - review for your operation', 'Starter template; edit items to match your café.'),
 (1, 'Closing checklist',  'DAILY',  'CLOSING', 'SEED - review for your operation', 'Starter template; edit items to match your café.'),
 (1, 'Weekly deep clean',  'WEEKLY', null,      'SEED - review for your operation', 'Starter template; edit items to match your café.');

insert into hygiene_checklist_items (checklist_id, text, sort_order, requires_photo)
select c.id, i.text, i.ord, false
from hygiene_checklists c
join (values
  ('Opening checklist', 1, 'Staff wearing clean uniform, apron and hair cover'),
  ('Opening checklist', 2, 'Hands washed with soap; sanitiser available at counter'),
  ('Opening checklist', 3, 'Counters, display glass and billing area wiped and sanitised'),
  ('Opening checklist', 4, 'Freezer and chiller temperatures checked and logged'),
  ('Opening checklist', 5, 'Scoops, tubs and utensils clean and stored covered'),
  ('Opening checklist', 6, 'Floor swept and mopped; no pests seen'),
  ('Opening checklist', 7, 'Dustbins empty with lids and liners in place'),
  ('Opening checklist', 8, 'Drinking water and hand-wash station stocked'),
  ('Closing checklist', 1, 'All food and ice-cream tubs covered and returned to freezer / chiller'),
  ('Closing checklist', 2, 'Scoops, utensils and machines washed and sanitised'),
  ('Closing checklist', 3, 'Counters, display glass and sinks cleaned'),
  ('Closing checklist', 4, 'Waste removed; dustbins washed and re-lined'),
  ('Closing checklist', 5, 'Floor swept and mopped'),
  ('Closing checklist', 6, 'Freezer and chiller doors shut; temperatures logged'),
  ('Closing checklist', 7, 'Gas, lights and non-essential equipment switched off'),
  ('Weekly deep clean', 1, 'Defrost / wipe inside of chillers and display freezer'),
  ('Weekly deep clean', 2, 'Clean behind and under equipment'),
  ('Weekly deep clean', 3, 'Descale and deep-clean coffee machine and blenders'),
  ('Weekly deep clean', 4, 'Wash walls, shelves and storage racks'),
  ('Weekly deep clean', 5, 'Check for pests, expired stock and damaged packaging'),
  ('Weekly deep clean', 6, 'Clean drains and dustbin area')
) as i(cname, ord, text) on i.cname = c.name
where c.business_id = 1;

insert into temperature_points (business_id, name, kind, min_c, max_c, source, notes) values
 (1, 'Display freezer', 'DISPLAY', -22.0, -16.0, 'SEED - review ranges for your equipment', 'Target about -18 C.'),
 (1, 'Storage freezer', 'FREEZER', -22.0, -16.0, 'SEED - review ranges for your equipment', 'Target about -18 C.'),
 (1, 'Chiller',         'CHILLER',   0.0,   5.0, 'SEED - review ranges for your equipment', 'Dairy / toppings storage.');

-- =====================================================================================================================
-- COMPLIANCE CALENDAR
-- =====================================================================================================================
-- Due date of a period = due_day of (period month + month_offset months). MONTHLY: period = a calendar month
-- (only months in applies_months when given). QUARTERLY: period = Apr-Jun, Jul-Sep, Oct-Dec, Jan-Mar, anchored on the
-- quarter-END month. ANNUAL: due_day of due_month every year. ONE_TIME: never generated (use manual tasks).
-- The override_* columns replace offset/day when the period month equals override_period_month.
create table compliance_rules (
  id                   bigserial primary key,
  business_id          bigint not null default 1 references business(id),
  code                 text   not null,
  title                text   not null,
  category             text   not null check (category in ('GST','TDS','PAYROLL','FSSAI','LICENCE','OTHER')),
  due_day              int    not null check (due_day between 1 and 31),
  frequency            text   not null check (frequency in ('MONTHLY','QUARTERLY','ANNUAL','ONE_TIME')),
  month_offset         int    not null default 0 check (month_offset between 0 and 12),
  applies_months       int[],
  due_month            int    check (due_month is null or due_month between 1 and 12),
  override_period_month int   check (override_period_month is null or override_period_month between 1 and 12),
  override_month_offset int   check (override_month_offset is null or override_month_offset between 0 and 12),
  override_due_day     int    check (override_due_day is null or override_due_day between 1 and 31),
  notes                text,
  link                 text,
  active               boolean not null default true,
  source               text   not null default 'SEED',
  updated_by           bigint references users(id),
  updated_at           timestamptz not null default now(),
  unique (business_id, code)
);

insert into compliance_rules (business_id, code, title, category, due_day, frequency, month_offset, applies_months,
                              due_month, override_period_month, override_month_offset, override_due_day, notes, link, active) values
 (1, 'GSTR1_QRMP', 'GSTR-1 (quarterly)', 'GST', 13, 'QUARTERLY', 1, null, null, null, null, null,
     'Default - confirm with your CA. Quarterly return, due the 13th of the month after the quarter ends.', '/gst', true),
 (1, 'GSTR3B_QRMP', 'GSTR-3B (quarterly)', 'GST', 22, 'QUARTERLY', 1, null, null, null, null, null,
     'Default - confirm with your CA. 22nd is the default for Maharashtra (category 1 states); other states use the 24th.', '/gst', true),
 (1, 'PMT06_QRMP', 'GST monthly payment (PMT-06)', 'GST', 25, 'MONTHLY', 1, array[1,2,4,5,7,8,10,11], null, null, null, null,
     'Default - confirm with your CA. Paid by the 25th for the first two months of each quarter; the third month is settled in GSTR-3B.', '/gst', true),
 (1, 'TDS_DEPOSIT', 'TDS deposit', 'TDS', 7, 'MONTHLY', 1, null, null, 3, 1, 30,
     'Default - confirm with your CA. Due the 7th of the next month; for March deductions the due date is 30 April.', '/tds', true),
 (1, 'TDS_RETURN_QTR', 'TDS quarterly return (24Q / 26Q)', 'TDS', 31, 'QUARTERLY', 1, null, null, 3, 2, 31,
     'Default - confirm with your CA. Due 31 Jul, 31 Oct, 31 Jan and 31 May.', '/tds', true),
 (1, 'PAYROLL_MONTHLY', 'Prepare and pay staff salary', 'PAYROLL', 5, 'MONTHLY', 1, null, null, null, null, null,
     'Owner target date, not a statutory deadline - change as you like.', '/payroll', true),
 (1, 'GSTR9_ANNUAL', 'GSTR-9 annual return (if applicable)', 'GST', 31, 'ANNUAL', 0, null, 12, null, null, null,
     'Default - confirm with your CA whether an annual return is required for your turnover. Switched off by default.', '/gst', false);

create table compliance_tasks (
  id           bigserial primary key,
  business_id  bigint not null default 1 references business(id),
  rule_code    text,                                  -- NULL for manual tasks
  period_start date,                                  -- identifies the generated period (idempotency key)
  period_label text,
  title        text   not null,
  category     text   not null check (category in ('GST','TDS','PAYROLL','FSSAI','LICENCE','OTHER')),
  due_date     date   not null,
  status       text   not null default 'OPEN' check (status in ('OPEN','DONE','SKIPPED')),
  completed_on date,
  completed_by bigint references users(id),
  notes        text,
  link         text,
  generated    boolean not null default false,
  created_by   bigint references users(id),
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create unique index compliance_tasks_generated_uq on compliance_tasks (business_id, rule_code, period_start) where rule_code is not null;
create index compliance_tasks_due_ix on compliance_tasks (business_id, due_date);
create trigger compliance_tasks_no_delete before delete on compliance_tasks for each row execute function forbid_modification();

create table compliance_documents (
  id               bigserial primary key,
  business_id      bigint not null default 1 references business(id),
  name             text   not null,
  number           text,
  issued_on        date,
  expires_on       date,
  reminder_days    int    not null default 30 check (reminder_days >= 0),
  file_document_id bigint,                            -- reference to a stored document (no FK on purpose)
  notes            text,
  active           boolean not null default true,
  created_by       bigint references users(id),
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);
create trigger compliance_documents_no_delete before delete on compliance_documents for each row execute function forbid_modification();

insert into compliance_documents (business_id, name, notes) values
 (1, 'FSSAI licence / registration', 'Placeholder - enter the number and expiry date from your certificate.'),
 (1, 'Shop & Establishment registration', 'Placeholder - enter the number and expiry date from your certificate.'),
 (1, 'GST registration certificate', 'Placeholder - enter the GSTIN and any validity date from your certificate.');
