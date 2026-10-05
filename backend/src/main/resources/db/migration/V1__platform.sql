-- V1: platform (business, identity, audit, settings, sequences)
create extension if not exists btree_gist;

create table business (
  id                bigint primary key,
  name              text not null,
  address           text,
  phone             text,
  whatsapp_number   text,
  gstin             text,
  opening_time      time,
  closing_time      time,
  day_rollover_hour int  not null default 4 check (day_rollover_hour between 0 and 12),
  data_start_date   date not null,
  currency          text not null default 'INR',
  timezone          text not null default 'Asia/Kolkata',
  created_at        timestamptz not null default now()
);

create table permissions (
  code        text primary key,
  description text not null
);

create table roles (
  id          bigserial primary key,
  business_id bigint not null default 1 references business(id),
  code        text not null,
  name        text not null,
  unique (business_id, code)
);

create table role_permissions (
  role_id         bigint not null references roles(id) on delete cascade,
  permission_code text   not null references permissions(code),
  primary key (role_id, permission_code)
);

create table users (
  id            bigserial primary key,
  business_id   bigint not null default 1 references business(id),
  name          text not null,
  email         text not null,
  mobile        text,
  password_hash text not null,
  role_id       bigint not null references roles(id),
  active        boolean not null default true,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  last_login_at timestamptz
);
create unique index users_email_uq on users (business_id, lower(email));

create table refresh_tokens (
  id         bigserial primary key,
  user_id    bigint not null references users(id),
  token_hash text   not null unique,
  expires_at timestamptz not null,
  revoked    boolean not null default false,
  created_at timestamptz not null default now()
);

create table settings (
  business_id bigint not null default 1 references business(id),
  key         text not null,
  value       text not null,
  updated_at  timestamptz not null default now(),
  updated_by  bigint references users(id),
  primary key (business_id, key)
);

-- OPEN is the default for a day with no row. CLOSED/HOLIDAY days are not reported as "missing sales".
create table business_calendar (
  business_id bigint not null default 1 references business(id),
  cal_date    date   not null,
  day_type    text   not null check (day_type in ('OPEN','CLOSED','HOLIDAY')),
  note        text,
  primary key (business_id, cal_date)
);

create table notes (
  id          bigserial primary key,
  business_id bigint not null default 1 references business(id),
  entity_type text   not null,
  entity_id   bigint not null,
  note        text   not null,
  created_by  bigint references users(id),
  created_at  timestamptz not null default now()
);
create index notes_entity_ix on notes (business_id, entity_type, entity_id);

create table document_sequences (
  business_id bigint not null default 1,
  seq_type    text   not null,
  period      text   not null default '',
  last_value  bigint not null default 0,
  primary key (business_id, seq_type, period)
);

-- Append-only audit trail. Rows can never be updated or deleted.
create table audit_log (
  id           bigserial primary key,
  business_id  bigint not null default 1,
  entity_type  text   not null,
  entity_id    bigint,
  action       text   not null,
  txn_date     date,
  before_value jsonb,
  after_value  jsonb,
  reason       text,
  source       text,
  user_id      bigint,
  user_name    text,
  at           timestamptz not null default now()
);
create index audit_entity_ix on audit_log (business_id, entity_type, entity_id, at desc);
create index audit_at_ix on audit_log (business_id, at desc);

create or replace function forbid_modification() returns trigger as $$
begin
  raise exception '% on % is not allowed (append-only / void instead of delete)', tg_op, tg_table_name
    using errcode = '42501';
end;
$$ language plpgsql;

create trigger audit_log_no_update before update on audit_log
  for each row execute function forbid_modification();
create trigger audit_log_no_delete before delete on audit_log
  for each row execute function forbid_modification();
