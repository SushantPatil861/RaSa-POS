-- V5: reference data: business, permissions, roles, GST rule, expense categories
insert into business (id, name, phone, whatsapp_number, data_start_date)
values (1, 'Valentina Ice Cream & Café', '+91-9272467796', '+91-9272467796', date '2026-05-01');

insert into permissions (code, description) values
 ('sales.view',                 'View sales and daily entries'),
 ('sales.create',               'Create sales and quick daily entries'),
 ('sales.backdate',             'Enter transactions for past dates'),
 ('sales.edit',                 'Edit existing sales / daily entries'),
 ('sales.void',                 'Void sales / daily entries'),
 ('sales.approve_day_variance', 'Approve differences between itemised and quick daily totals'),
 ('orders.view',                'View orders / order board'),
 ('orders.manage',              'Create orders, take payment and complete or cancel orders'),
 ('orders.prepare',             'Move orders through preparing / ready (kitchen)'),
 ('menu.view',                  'View menus'),
 ('menu.edit',                  'Edit DRAFT / SCHEDULED menu versions'),
 ('menu.publish',               'Schedule (publish) a menu version with an effective date'),
 ('products.manage',            'Create and edit products, variants, categories, lifecycle'),
 ('expenses.view',              'View expenses'),
 ('expenses.create',            'Create expenses'),
 ('expenses.edit',              'Edit / void expenses'),
 ('purchases.view',             'View purchases and suppliers'),
 ('purchases.create',           'Create purchases and suppliers'),
 ('purchases.edit',             'Edit / void purchases'),
 ('customers.view',             'View customers'),
 ('customers.manage',           'Create and edit customers'),
 ('reports.view',               'View reports and dashboard'),
 ('finance.view_profit',        'See profit, margins and cost figures'),
 ('audit.view',                 'View the audit trail'),
 ('users.manage',               'Create users and change roles'),
 ('settings.manage',            'Change business settings, tax rules, calendar');

insert into roles (business_id, code, name) values
 (1, 'OWNER',      'Owner'),
 (1, 'MANAGER',    'Manager'),
 (1, 'CASHIER',    'Cashier / Counter'),
 (1, 'KITCHEN',    'Kitchen'),
 (1, 'ACCOUNTANT', 'Accountant / CA (read-only)');

-- Owner: everything
insert into role_permissions (role_id, permission_code)
select r.id, p.code from roles r cross join permissions p where r.code = 'OWNER';

-- Manager: operations, but not user admin / settings / approvals / publishing prices
insert into role_permissions (role_id, permission_code)
select r.id, p.code from roles r cross join permissions p
where r.code = 'MANAGER'
  and p.code not in ('users.manage','settings.manage','sales.approve_day_variance','menu.publish');

insert into role_permissions (role_id, permission_code)
select r.id, p from roles r cross join unnest(array[
  'sales.view','sales.create','orders.view','orders.manage','orders.prepare','menu.view','customers.view','customers.manage'
]) as p where r.code = 'CASHIER';

insert into role_permissions (role_id, permission_code)
select r.id, p from roles r cross join unnest(array['orders.view','orders.prepare','menu.view']) as p where r.code = 'KITCHEN';

insert into role_permissions (role_id, permission_code)
select r.id, p from roles r cross join unnest(array[
  'sales.view','orders.view','menu.view','expenses.view','purchases.view','customers.view',
  'reports.view','finance.view_profit','audit.view'
]) as p where r.code = 'ACCOUNTANT';

-- GST: regular scheme, 5%, prices GST-inclusive. To be confirmed by the CA (ITC eligibility left undecided).
insert into tax_rules (business_id, tax_type, scope, rate, itc_eligible, effective_from, notes)
values (1, 'GST', 'ALL', 5.000, null, date '2026-05-01',
        'Seeded from owner input: regular scheme, 5%, QRMP. CA to confirm rate per category and ITC eligibility.');

insert into expense_categories (business_id, name, sort_order)
select 1, n, row_number() over () from unnest(array[
 'Rent','Electricity','Gas','Water','Staff salary','Packaging','Repairs','Maintenance','Marketing','Delivery',
 'Commission','Cleaning','Internet','Telephone','Software','Licences','Professional fees','Bank charges','Miscellaneous'
]) as n;
