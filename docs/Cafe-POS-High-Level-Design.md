# Café Business Management App — High-Level Design (HLD)

**Project:** Cafe-POS (Indian ice-cream parlour & café business register)
**Stack:** Java + Spring Boot · React PWA · PostgreSQL · Docker
**Status:** DRAFT for review — no code written yet
**Source:** Requirements document `DOC-20260927-WA0001.docx` (sections 1–123, the 37 screenshot pages 43–86, and the GST/TDS/Payroll/Hygiene/Scanner addendum)
**Date:** 4 October 2026 · **Revision 4** (menu prices and roles confirmed; adds: regular GST scheme at 5% with QRMP filing, suggested staff roles, and the confirmed menu start date of 1 May 2026) Earlier revisions: day-total approval, payment methods, GST-inclusive prices, individual logins.

---

## 0. How to read this document

This HLD turns your requirements into an architecture, a data model, the key business mechanisms (the hard parts), a screen map, and a phased build plan. Section references: a bare `§6.5` points to a section of **this** document; "your §24" or a bare `§24`-style number in a traceability row points to your requirements document (numbered 1–42 and 87–123 in the text). The screenshot pages (43–86) are not reliably numbered, so I cite them by topic. Section 17 lists the assumptions I made and the questions I still need answered. Section 18 maps every requirement section to a module and phase so nothing gets lost.

You asked for **everything in detail**, so all modules are covered. Phases 1–2 are designed deeply because they are built first and everything depends on them. Phases 3–5 are designed to the level needed to make sure Phase 1–2 decisions do not block them.

---

## 1. Summary and decisions already made

| Topic | Decision |
|---|---|
| Business model | **Single café now, scalable later.** Every table carries a `business_id` from day one (default: one business) so multi-branch or SaaS is a configuration step later, not a rewrite. |
| Frontend | **React PWA** (installable on Android, camera upload, responsive desktop). Online-first. |
| Backend | **Java 21+ / Spring Boot**, REST API, **modular monolith** (not microservices). |
| Database | **PostgreSQL** with Flyway migrations, run in **Docker**. |
| Build order | Architecture and data model for everything; build in your own phase order (core ledger first). |
| Data philosophy | Record what is known, never invent what is unknown, label every number's quality, never rewrite history silently. |

### What this system is, in one sentence

A **single central transaction database** (menu → product → recipe → ingredient → stock → sale → payment → P&L) with a **trust layer** on top: every figure knows where it came from, how complete it is, whether it is estimated, and who changed it.

---

## 2. Your 17 rules → how the design enforces each

The rules in your document are the real specification. Each one is enforced by a concrete mechanism, not just by convention.

| # | Rule | Enforcement mechanism |
|---|---|---|
| 1 | Never overwrite historical financial data | Financial rows are changed only through a service that writes an `audit_log` entry (before/after JSON, reason, user). A database trigger on core tables writes the audit row as a safety net, and the application DB role has **no DELETE** on financial tables. |
| 2 | Backdated transactions allowed | Every transaction has an explicit `txn_date` chosen by the user. No code path assumes "today". |
| 3 | Transaction date ≠ entry date | Two columns on every transaction: `txn_date` (DATE) and `entry_at` (timestamptz, set by the server, not editable). Reports always group by `txn_date`. |
| 4 | Historical menu versions are permanent | `menu_versions` in status ACTIVE/ARCHIVED are frozen by the service layer and by a DB trigger. `menu_items` store snapshots (name, category, description, price) so later product edits cannot change them. |
| 5 | Historical prices are permanent | `price_history` is append-only. Sale lines also store the actual price charged. |
| 6 | Historical recipes/costs preserved | `recipe_versions` and `ingredient_cost_history` are effective-dated and append-only. |
| 7 | Actual transaction price beats menu price | `sale_items.unit_price` is the truth. `menu_items.price` is stored only as the reference `list_price_at_sale`. |
| 8 | Missing data stays missing | Gaps are **derived** (a day with no data is MISSING by absence). Nothing is auto-filled. Unknown values are NULL, never 0. |
| 9 | Estimates clearly labelled | An `estimates` table stores the value, method, inputs, date and confidence. Any figure built from an estimate carries the ESTIMATED flag all the way up to the dashboard. |
| 10 | OCR must be verified | OCR output lands only in `document_drafts`. Posting to the ledger needs an explicit user confirmation; the resulting record has `source=OCR` and a link to the original image. |
| 11 | WhatsApp and counter share the same DB | One `orders` / `sales` / `customers` / `payments` model. WhatsApp is just `order_source = WHATSAPP` plus a conversation layer. |
| 12 | Completed WhatsApp orders flow into sales | Domain event `OrderCompleted` → `SalesService` creates the sale. No re-entry. |
| 13 | No permanent deletion of financial transactions | `VOID` status with reason/user/time. No DELETE endpoint for financial data. |
| 14 | Audit trail for important changes | Central `audit_log`, append-only, queryable per record ("History" drawer on every screen). |
| 15 | Changing today's menu never changes yesterday's reports | Reports read `sale_items` (actual price) and effective-dated cost data, never "current menu". |
| 16 | Changing today's recipe cost never rewrites past costs | Costs are resolved **as of the transaction date** from effective-dated rows. |
| 17 | Reports recalculate on valid historical changes | Reports are computed from current ledger state (not stored totals), plus a change-tracking job that records "what moved" (see §8.4). |

---

## 3. Architecture

### 3.1 Style: modular monolith

One deployable Spring Boot application, internally split into **modules with enforced boundaries** (Spring Modulith `ApplicationModules.verify()` in the build fails if a module reaches into another's internals). Modules talk through public service interfaces and **domain events**.

Why not microservices: one café, one small team, strong transactional needs (a sale must update stock, cost and payment consistently). A monolith gives ACID transactions, simple deployment (one container), and easy debugging. The module boundaries leave clean seams to extract the WhatsApp gateway or the OCR worker into separate services later if load ever demands it.

### 3.2 System context

```
                                  ┌──────────────────────────────┐
   Owner / Manager / Staff        │      React PWA (Android,     │
   (Android phone, desktop)  ───► │      desktop, installable)   │
                                  └──────────────┬───────────────┘
                                                 │ HTTPS  REST/JSON  (JWT)
   Customers (WhatsApp) ──► Meta WhatsApp        │
                            Cloud API ──webhook──┤
                                                 ▼
 ┌───────────────────────────────────────────────────────────────────────────┐
 │                     Spring Boot modular monolith                          │
 │                                                                           │
 │  API layer (controllers, DTOs, validation, authz, problem+json errors)    │
 │  ───────────────────────────────────────────────────────────────────────  │
 │  CORE LEDGER (P1)            COSTING & STOCK (P2)    CHANNELS & DOCS (P3) │
 │   identity   catalog          recipes   costing       whatsapp  customers │
 │   menu       sales/orders     inventory containers    payments  documents │
 │   expenses   purchases        wastage  reconciliation ocr-adapter notif.  │
 │   imports    reporting        prep batches                                │
 │   dataquality audit                                                       │
 │                                COMPLIANCE & PEOPLE (P4)                   │
 │  SHARED KERNEL: money · units · dates · data-status · estimates · events  │
 │   tax-rules  gst  tds  payroll  hr  hygiene  compliance-calendar         │
 │  ───────────────────────────────────────────────────────────────────────  │
 │  Async: Modulith event registry (persistent) · job queue (DB-backed)      │
 └──────────────┬───────────────────────────────┬────────────────────┬───────┘
                ▼                               ▼                    ▼
        PostgreSQL 16+                  Object storage          External adapters
   (ledger, audit, queue)             (MinIO / S3: images,     OCR/LLM · UPI payment
                                       invoices, Excel)        gateway · e-mail/SMS
```

### 3.3 Technology choices

| Layer | Choice | Reason |
|---|---|---|
| Language/runtime | Java 21 LTS (25 LTS acceptable) | Records, virtual threads, long support. |
| Framework | Spring Boot (latest GA at project start), Spring Web, Validation, Security, Data JPA | Your stated stack; mature. |
| Module boundaries | Spring Modulith | Enforces module rules, persistent domain-event registry (gives us a transactional outbox for free). |
| Persistence | JPA/Hibernate for aggregates; **jOOQ or plain SQL** for reports | Reports are SQL-heavy (window functions, date ranges); an ORM is the wrong tool there. |
| Migrations | Flyway | Versioned, reviewable, testable schema. |
| Database | PostgreSQL 16+ | `NUMERIC` money, `daterange` + exclusion constraints, JSONB, partial indexes, RLS for later multi-tenancy. |
| Auth | Spring Security, JWT access token (15 min) + rotating refresh token | Simple and stateless. Argon2 or bcrypt password hashes. |
| Files | S3-compatible storage (MinIO in Docker) | Invoice/bill images, Excel uploads, OCR originals. |
| Excel/CSV | Apache POI (streaming) + a CSV parser | Import module. |
| Jobs | DB-backed queue (`SELECT … FOR UPDATE SKIP LOCKED`) or JobRunr | No Redis/Kafka to operate for a one-café system. |
| API docs | springdoc OpenAPI 3 | TypeScript client generated from it, so frontend and backend cannot drift. |
| Frontend | React 18+, TypeScript, Vite, React Router, TanStack Query, React Hook Form + Zod, Tailwind CSS + Radix/shadcn UI, Recharts, `vite-plugin-pwa` | Fast, small, mobile-first. |
| Testing | JUnit 5, Testcontainers (real Postgres), ArchUnit/Modulith verify, Playwright (E2E), Vitest | Maps directly to your 20 test scenarios. |
| Ops | Docker Compose, Caddy or Nginx (TLS), pg_dump + WAL archiving, Actuator metrics | Runs on one VPS; portable to cloud. |

### 3.4 Module map

| Module | Phase | Responsibility |
|---|---|---|
| `platform` (shared kernel) | 0 | Money, units, dates (IST, business-day rollover, FY), data-status, estimates, sequences, events, attachments, notes |
| `identity` | 1 | Users, roles, configurable permissions, login, PIN for shared staff devices |
| `audit` | 1 | Append-only audit log, "history" queries, void/restore records |
| `catalog` | 1 | Products, categories, variants (permanent identities), aliases |
| `menu` | 1 | Menu versions, effective dates, menu items (snapshots), price history, comparison, copy |
| `sales` | 1 | Sales, sale items, quick daily summaries, backdating, edit/void, channel and payment split |
| `orders` | 1 | Orders, order items, order lifecycle, sources, types |
| `customers` | 1 (light) / 3 | Customer records, order history, addresses |
| `expenses` | 1 | Expense categories, expenses, recurring schedules |
| `purchases` | 1 (basic) / 2 | Suppliers, purchases, items, payment status, attachments |
| `imports` | 1 | Excel/CSV import wizard, mapping templates, dedupe, history |
| `reporting` | 1 | Dashboard, P&L, 3-month review, drill-down, restatement tracking |
| `dataquality` | 1 | Completeness, gaps, flags, "Data Health" |
| `recipes` | 2 | Recipes, versions, sub-recipes, units, portions |
| `costing` | 2 | Ingredient cost history, cost resolution, COGS, estimation |
| `inventory` | 2 | Ingredients, lots, containers (tubs), movements, counts, variance |
| `wastage` | 2 | Wastage records and financial impact |
| `reconciliation` | 2 | Cash and payment reconciliation |
| `whatsapp` | 3 | Webhook, conversation state machine, templates, staff takeover |
| `payments` | 3 | Payment links, gateway webhooks, payment status |
| `documents` | 3 | Uploaded documents, OCR extraction, drafts, review/confirm |
| `notifications` | 3 | In-app, web push, WhatsApp/e-mail alerts |
| `tax` / `gst` / `tds` | 4 | Effective-dated tax rules, GST registers, TDS/TCS ledgers |
| `payroll` / `hr` | 4 | Employees, attendance, salary, payslips |
| `hygiene` | 4 | Checklists, hygiene and temperature logs |
| `compliance` | 4 | Compliance dashboard and calendar |

### 3.5 Deployment (Docker Compose)

```
 caddy/nginx ─► api (Spring Boot)      ─► postgres
 (TLS, static   │                       ├─ nightly pg_dump + WAL archive ─► object storage
  React build)  ├─► minio (files)       └─ restore drill documented
                └─► ocr-worker (P3, optional separate container)
```

Environments: `dev` (with a demo business and sample data), `staging`, `prod`. Configuration through environment variables (12-factor). A single small VPS is enough for the first years; the API is stateless so it can scale horizontally later.

---

## 4. Cross-cutting foundations

These are the decisions everything else depends on. Getting them right in Phase 0 is the cheapest insurance in the project.

### 4.1 Money, quantities and units

- **Amounts:** `NUMERIC(14,2)` in rupees. **Unit costs and rates:** `NUMERIC(14,4)` (cost per gram can be ₹0.0357). **Quantities:** `NUMERIC(16,4)` stored in the ingredient's **base unit** (g, ml, piece).
- Java uses `BigDecimal` with explicit rounding (HALF_UP) at defined points only: line totals round to 2 dp; everything upstream keeps 4 dp. The API sends decimals as **strings** so JavaScript never loses precision.
- **Units have a dimension:** MASS (g, kg), VOLUME (ml, l), COUNT (piece, packet, box, bottle, scoop, portion, unit). Conversion inside a dimension is automatic (1 kg = 1000 g). **Conversion across dimensions (litre ↔ kg) is never automatic** (your section 98). It requires an explicit, per-ingredient `pack_spec` or density (e.g., "4 L tub ≈ 2.8 kg"), which carries a basis flag: EXACT / ESTIMATED / SUPPLIER_SPECIFICATION.
- Indian formatting (₹ 2,40,000; `dd MMM yyyy`) is a **presentation** concern handled in the frontend with `Intl` (`en-IN`). Storage and API stay locale-neutral.

### 4.2 Dates and time

- `txn_date` is a `DATE` in business time (Asia/Kolkata). `entry_at` is `timestamptz` set by the server.
- **Business-day rollover:** a configurable hour (default 04:00) so a sale rung up at 12:30 AM belongs to the previous trading day if the café stays open late.
- **Financial year:** April–March, used by reports now and GST/TDS later.
- **BACKDATED** is derived: `entry_at::date > txn_date`. The UI shows "Backdated — entered 73 days later".

### 4.3 The data-status model

Your eight statuses are not one dimension. I split them so each is derivable and consistent:

| Status | How it is determined |
|---|---|
| CONFIRMED | `confirmation_state = CONFIRMED` (owner/staff verified). Default for manual entries made by an authorised user; **never** automatic for OCR/AI/import drafts. |
| IMPORTED | `source IN (EXCEL, CSV, IMPORT)` |
| MANUALLY ENTERED | `source = MANUAL` (or COUNTER/WHATSAPP/OCR as the channel of origin; see below) |
| ESTIMATED | `is_estimated = true` with a row in `estimates` (method + inputs) |
| PARTIAL | `is_partial = true` (record or period is missing required parts, e.g. sale total with no line items, or cost coverage < 100%) |
| MISSING | **Derived by absence**: a trading day with no sales data, an expense without a category, a purchase without a supplier. Missing is a gap report, not a row. |
| BACKDATED | Derived: `entry_at::date > txn_date` |
| CORRECTED | Derived: `revision > 0` (record was changed after creation; audit log holds the original) |

- **Source** (your section 54) is a separate column: `MANUAL, EXCEL, CSV, OCR, WHATSAPP, COUNTER, IMPORT, SYSTEM, OTHER`.
- Multiple statuses can apply at once. A DB view `v_record_status` exposes the computed set so reports and the UI use identical logic.
- **Aggregates carry a status mix.** Every number the API returns is a `Quantified` object:

```
{
  "amount": "482000.00",
  "currency": "INR",
  "statusMix": { "CONFIRMED": "380000.00", "IMPORTED": "70000.00", "ESTIMATED": "32000.00" },
  "completeness": { "basis": "days", "have": 24, "expected": 30, "missing": 6 },
  "flags": ["ESTIMATED", "PARTIAL", "HAS_BACKDATED", "HAS_CORRECTIONS"],
  "formula": "NetSales = GrossSales - Discounts",
  "inputs": ["metric:grossSales", "metric:discounts"],
  "drill": { "type": "salesByDay", "params": { "from": "2026-07-01", "to": "2026-07-31" } }
}
```

  This gives you three of your key requirements for free: **transparent calculations** (tap a number → "How is this calculated?"), **clickable drill-down** (§27 in your doc: month → day → transaction), and **never hide uncertainty**.

### 4.4 Estimation

Estimation is allowed only through **named methods** (a code-level registry), never ad hoc. Each use writes an `estimates` row: `entity/metric scope, value, method, input_data (JSON), calculated_at, confidence/status`. Examples of defined methods: `RECIPE_COST_STANDARD` (cost from recipe × latest known ingredient price), `AVG_PURCHASE_PRICE_LAST_N`, `OWNER_ASSUMPTION` (the owner types a value and a reason), `PURCHASES_AS_COGS_PROXY` (off by default). The UI always prints the reason, e.g. "Estimated COGS ₹85,000 — recipe costing available for 82% of sales value."

### 4.5 Audit, void and restatement (the "no silent change" machinery)

1. **Change** a financial record → service requires a `reason` (mandatory when the record is CONFIRMED, optional for drafts) → row updated, `revision++`, `audit_log` row written in the same transaction (`entity, action, txn_date, before JSONB, after JSONB, changed_fields, reason, source, user, at`).
2. **Void** instead of delete → `void_state = VOIDED` + reason/user/time; voided rows are excluded from totals but visible in history.
3. **Restate** → because reports are computed from the current ledger, a corrected July sale automatically changes July figures. A **change-tracking job** records what moved (before/after totals per affected month) so you can see "July 2026 P&L, last updated 26 Sep: Sales +₹18,000, COGS +₹7,200, Gross profit +₹10,800" (§8.4).
4. **Optimistic locking** (`version` column) prevents two devices silently overwriting each other (HTTP 409 with a clear message).
5. **Optional later:** period locks for CA filing. The model allows it (`period_locks`) but it is off by default because you asked that history stay editable.

### 4.6 Effective-dated data

Menu versions, prices, recipe versions, ingredient costs, costing policy and tax rules all use the same pattern: `effective_from` (date) + the next row's `effective_from` defines the end, with **PostgreSQL exclusion constraints** (`btree_gist` + `daterange`) preventing overlap. A helper `AsOf<T>.resolve(date)` is the only way code reads such data, so "as of the transaction date" is applied uniformly.

### 4.7 Human-readable numbers

`document_sequences` (per type, per year/FY, row-locked for gap-free numbers): `WA-1024`, `PUR-000245`, `PISTA-2026-0098` (container IDs), order numbers, invoice numbers. Primary keys are internal `bigint`/UUID; the human code is a separate unique column.

### 4.8 Multi-tenant readiness (kept cheap)

Every table has `business_id`. Hibernate 6 `@TenantId` (or a repository base filter) scopes queries to the current business; unique constraints include `business_id`. PostgreSQL Row-Level Security can be switched on later for SaaS. `locations` (freezer, display, kitchen, branch) is optional in the MVP. This costs almost nothing now and avoids a painful retrofit.


---

## 5. Data model

A diagram of the core entities is in the companion file **`core-erd.mermaid`**. Table groups below; every table also has `business_id`, `created_at/by`, `updated_at/by`, and (for financial tables) `version`, `revision`, `void_state`, `source`, `confirmation_state`, `is_estimated`, `is_partial`.

Your document says *"Do not combine PRODUCT, MENU VERSION, PRICE, RECIPE, COST, ORDER, SALE, PAYMENT into one table."* The model respects that: each is its own entity with its own lifecycle.

### 5.1 Identity and settings (P1)

| Table | Key columns / notes |
|---|---|
| `business` | name, address, phone, WhatsApp number, GSTIN (nullable), opening/closing hours, business-day rollover hour, data-start date, currency, timezone |
| `users` | name, mobile/email, password hash, status. **Every person has an individual login**; the same account works on phone and desktop web |
| `roles`, `permissions`, `role_permissions` | OWNER, MANAGER, STAFF, KITCHEN as defaults; **permission matrix is data, not code**, so "financial data permissions must be configurable" is satisfied |
| `settings` | key/value per business (costing method, allocation method, shelf-life reminders, variance thresholds) with change history |
| `business_calendar` | per date: OPEN / CLOSED / HOLIDAY — needed so a closed day is not reported as "missing sales" |
| `notes` | polymorphic notes on day, sale, purchase, expense, product, supplier, order, customer, inventory (your "notes on day/sale/purchase/…" requirement) |
| `attachments` | file metadata + storage key + SHA-256 (duplicate detection), linked polymorphically |

### 5.2 Catalog and menu (P1)

| Table | Key columns / notes |
|---|---|
| `categories` | name, parent (subcategory), sort order, active — fully editable list |
| `products` | **permanent identity**: code, canonical name (latest), category, subcategory, lifecycle status (DRAFT / ACTIVE / TEMPORARILY_UNAVAILABLE / DISCONTINUED / ARCHIVED), unit, created/discontinued dates. Never duplicated when price changes. |
| `product_variants` | product, name (Single/Double/Regular/Large…), size, unit, status. Also permanent. |
| `product_aliases` | alternate names ("Pista scoop", old spellings) used by the importer and historical rename tracking |
| `menu_versions` | name, `effective_from`, created date, reason, status (DRAFT/SCHEDULED/ACTIVE/ARCHIVED), `based_on_version_id` (copy source), created by |
| `menu_items` | menu_version, product, variant, **price**, **snapshot** of display name / category / description / image / sort order, availability, add-on flag. This is the frozen "what was offered and at what price". |
| `price_history` | product, variant, old price, new price, effective date, old/new menu version, reason, changed by, changed at. Append-only event log. |
| `addons` / `addon_groups` | toppings and add-ons attached to products or categories via menu items |

### 5.3 Orders, sales, payments, customers (P1; payments gateway and customer depth in P3)

| Table | Key columns / notes |
|---|---|
| `orders` | order_no, order date/time, customer, source (COUNTER/WHATSAPP/PHONE/OTHER), type (PICKUP/DELIVERY/DINE_IN/TAKEAWAY), status (NEW/CONFIRMED/PREPARING/READY/OUT_FOR_DELIVERY/COMPLETED/CANCELLED), payment status (PENDING/INITIATED/PAID/PARTIALLY_PAID/FAILED/REFUNDED), delivery charge, special instructions |
| `order_items` | order, menu_item, product, variant, qty, unit price, discount, tax, add-ons |
| `sales` | **the accounting fact**: txn_date, entry_at, channel/source, customer, gross, discount, tax, net, optional `order_id`, granularity = ITEMIZED, status flags |
| `sale_items` | product, variant, `menu_item_id` (nullable reference), qty, **`list_price_at_sale`** (reference), **`unit_price`** (actual), discount, net amount, tax fields, payment method |
| `daily_sales_summaries` | **Quick Daily Entry**: date, total sales, tender split, channel split, orders count, discounts, refunds, wastage, other income, notes; granularity = SUMMARY |
| `payments` | sale/order, method (CASH/UPI/CARD/ONLINE/OTHER), amount, status, reference, platform (Swiggy/Zomato/…), received date, commission, settlement id |
| `customers` | name, WhatsApp/mobile (unique), addresses, notes; aggregates (orders, spend, last order, preferred type) are computed |

**Order vs Sale (important):** an *order* is operational (it moves through NEW → COMPLETED). A *sale* is the recognised revenue fact. When an order is completed, a sale is created automatically (your "completed WhatsApp order → sales transaction" requirement: "Do NOT require staff to manually enter the same order again"). A counter sale is an order that is created and completed immediately. Historical entry (Excel import, handwritten records, quick entry) creates sales without orders. See §6.3 for how Quick Entry and item-wise entry coexist without double counting.

### 5.4 Expenses and purchases (P1; supplier ledger in P2)

| Table | Key columns / notes |
|---|---|
| `expense_categories` | your 19 categories, editable, with parent |
| `expenses` | txn_date, category, amount, payment method, paid to, recurring flag, notes, bill attachment |
| `expense_schedules` | recurring templates (rent, salary, software, internet). They generate **expected** entries (reminders), which are **not** posted until the owner confirms the real amount — so a recurring ₹X never fabricates a number. |
| `suppliers` | name, contact, category, payment terms, GSTIN/PAN (P4), notes. Outstanding and last purchase are computed. |
| `purchases` | txn_date, supplier, invoice no/date, payment status, totals, attachment, notes |
| `purchase_items` | ingredient or free-text item, qty, unit, unit price, total, **treatment** (INVENTORY / EXPENSE / FIXED_ASSET), batch no, expiry |

**Key accounting principle (§119):** an ingredient purchase is an *inventory asset*, not an expense. It reaches the P&L only as COGS when consumed. Until Phase 2 inventory exists, Phase 1 shows purchases in a **Purchase Register** and does not label them as COGS (§6.5).

### 5.5 Recipes, costing, inventory (P2)

| Table | Key columns / notes |
|---|---|
| `ingredients` | name, kind (RAW / PACKAGING / SEMI_FINISHED / OTHER), dimension, base unit, container-tracked flag, shelf-life policy (optional), low-stock threshold |
| `units`, `unit_conversions` | standard and custom conversions (owner-definable) |
| `ingredient_pack_specs` | ingredient, pack ("4 L tub"), nominal volume, expected net weight, basis (EXACT / ESTIMATED / SUPPLIER_SPEC) |
| `recipes` | one per sellable product variant (or per sub-recipe/preparation) |
| `recipe_versions` | recipe, version no, `effective_from`, status |
| `recipe_lines` | recipe_version, ingredient **or** sub-recipe, quantity, unit, yield/loss % |
| `ingredient_cost_history` | ingredient, effective_from, cost per base unit, basis (ACTUAL_PURCHASE / MANUAL_ESTIMATE), source purchase_item |
| `inventory_lots` | ingredient, purchase_item, batch no, supplier, mfg/expiry dates, received qty, unit cost |
| `stock_containers` | container ID (e.g., PISTA-2026-0098), ingredient, lot, nominal size, expected net weight, **quantity basis**, status (SEALED/OPEN/LOW_STOCK/EMPTY/WASTED/EXPIRED/DISPOSED), opened_at, opening qty, location |
| `inventory_movements` | **append-only ledger**: type (OPENING_BALANCE, PURCHASE_RECEIPT, SALE_CONSUMPTION, MANUAL_CONSUMPTION, WASTAGE, TRANSFER_OUT/IN, ADJUSTMENT, PREP_CONSUME, PREP_OUTPUT), signed qty in base unit, lot, container, location, value, txn_date, entry_at, reason, ref |
| `stock_counts`, `stock_count_lines` | physical checks: system qty, actual qty, variance, reason |
| `preparation_batches` | semi-finished batches: prepared qty, expected yield, actual yield, cost, cost per unit, remaining, wastage |
| `cogs_entries` | per sale line and ingredient: qty, unit cost, amount, lot/container, **cost basis**, calc revision |
| `wastage` | txn_date, item/ingredient/container, qty, estimated cost, reason (Expired/Damaged/Overproduction/Preparation error/Customer return/Dropped/Unknown) |
| `costing_policy` | effective-dated: costing method (FIFO / WEIGHTED_AVERAGE), allocation method (FIFO / FEFO / MANUAL). **Never silently changed** — a change is a new effective-dated row with a reason (§117). |

### 5.6 Reconciliation (P2)

| Table | Key columns / notes |
|---|---|
| `cash_days` | date, opening cash (default = prior day's actual closing, editable), expected closing (computed), actual counted, difference, status |
| `cash_difference_explanations` | cash_day, type (Personal withdrawal / Expense not recorded / Change float / Error / Other), amount, note. **An unexplained difference is never booked as profit or loss.** |
| `cash_withdrawals` | owner drawings and similar — **not** expenses |
| `payment_settlements` | method/platform, period, sales recorded, amount received, commission, settlement amount, difference; later importable from statements |

### 5.7 Documents, import, quality, audit (P1 import/quality/audit; P3 OCR)

| Table | Key columns / notes |
|---|---|
| `import_batches`, `import_rows`, `import_mappings` | file hash, status, mapping template, per-row result and errors, committed records list |
| `uploaded_documents` | type (purchase invoice, kaccha bill, receipt, handwritten record, …), file, hash, status (UPLOADED → EXTRACTING → EXTRACTED → IN_REVIEW → CONFIRMED / REJECTED) |
| `ocr_extractions` | provider + model version, raw text, structured JSON, **per-field confidence** |
| `document_drafts` | proposed record (purchase/expense/sales) with fields the user edits; `confirmed_at/by`, link to the real record created |
| `estimates` | see §4.4 |
| `data_quality_flags` | detected issues (uncategorised expense, purchase without supplier, missing opening stock …) with resolution state |
| `report_snapshots` | per period + report type + generated_at + key figures (JSON) — basis for the "what changed" view |
| `audit_log` | see §4.5; **append-only** (no UPDATE/DELETE grant); optional hash chain for tamper evidence |

### 5.8 Channels (P3)

`whatsapp_conversations` (customer, state, cart JSON, mode BOT/HUMAN), `whatsapp_messages` (direction, wamid, type, payload, status), `whatsapp_orders` (link to `orders`), `notifications`, `message_templates`.

### 5.9 Compliance and people (P4)

`tax_rules` (effective-dated: type, rate, thresholds, form/section, notes), `tax_profile`, `gst_*` registers, `tds_*` ledgers and challans, `employees`, `attendance`, `salary_structures`, `payroll_runs`, `payslips`, `hygiene_checklists`, `hygiene_logs`, `temperature_logs`, `compliance_items`, `licences`. Detailed in §10.

---

## 6. The hard parts — core mechanisms

### 6.1 Menu versioning, effective dates and price history

**Lifecycle**

```
 DRAFT ──schedule(effective_from)──► SCHEDULED ──(date arrives)──► ACTIVE ──(next version arrives)──► ARCHIVED
   ▲                                    │ editable until it becomes ACTIVE
   └────────── edit freely ─────────────┘            ACTIVE/ARCHIVED = frozen (read-only)
```

- **Copy Existing Menu** clones a version's `menu_items` into a new DRAFT (`based_on_version_id` set). **Create New Menu Version** does the same from the active menu with a "reason for change".
- Whenever a version is created or its content changed, the UI asks **"From which date should this menu be effective?"** (§11). That date is mandatory to schedule it.
- **Which menu applies on date D** = the non-draft version with the greatest `effective_from ≤ D`. Status is therefore *resolved by date* at read time; the nightly job (00:00 IST) only tidies the stored status, so a missed cron run can never leave the wrong menu live. Example: created 26 Sep, effective 1 Oct → the current menu stays ACTIVE through 30 Sep and the new one becomes ACTIVE on 1 Oct automatically.
- **Frozen history:** once ACTIVE or ARCHIVED, a version cannot be edited. A mistake in an old menu is corrected by creating a **backdated** version (allowed, §30) or a clearly flagged correction, never by editing the frozen one.
- **Products are permanent.** A price change is only a new `menu_items.price` in a new version for the same `product_id`/`variant_id`. A rename is a new `display_name` snapshot in the new version (and an alias row). A removal is the product being absent from the new version. Discontinued products stay in `products` (status DISCONTINUED) so historical reports still show them (§16).
- **Price history** rows are written when a version is saved against its predecessor (who, when, why, old → new, both version IDs). A nightly consistency check verifies `price_history` equals the diff of consecutive versions, so the two can never silently diverge.
- **Menu comparison** is a pure diff of two versions' `menu_items` keyed by `(product_id, variant_id)`: added, removed, renamed, price up/down/unchanged (with ₹ and %), category change, variant change. Example: Belgian Dark Waffwich ₹120 → ₹140 = +₹20 (+16.67%).
- **Prices are never hard-coded** anywhere (your "do NOT hard-code prices" rule). The Valentina menu is loaded through the same import path as any other data (§17).

### 6.2 Historical transaction pricing

`sale_items.unit_price` is what the customer actually paid and is the only price used in reports. `list_price_at_sale` is copied from the menu version in force on the transaction date, purely as a reference (for "sold below list price" insight). If a backdated menu version is added later, existing sale lines are **not** repriced; an optional "re-link to menu" tool may only update `menu_item_id`, never the amounts.

### 6.3 Quick Daily Entry vs item-wise entry (avoiding double counting)

Your §7 has two modes. They can both exist for the same date, so the design defines the rule explicitly:

- Each trading day has a **day basis**: `ITEMIZED` (sales rows exist), `SUMMARY_ONLY` (only a quick entry), `BOTH`, or `NONE`.
- **BOTH:** totals are **never summed**. The system shows the itemised total, the quick-entry total and the **difference**, and the day enters an **approval step** (decided by the owner): the day gets status `PENDING_APPROVAL`, and an authorised approver (permission `sales.approve_day_variance`, default OWNER role) must choose one outcome — *accept itemised*, *accept quick total* (itemised lines then cover only part of it, so product-level metrics for that day are PARTIAL), or *accept with explained difference* (reason required). The decision, approver, time and reason go to the audit log. Until approved, reports use the itemised figures, show the day as **UNRECONCILED**, and list the unapproved variance separately in Data Health.
- **SUMMARY_ONLY** days count in sales/channel/tender totals but contribute nothing to product, category or COGS reports (flagged PARTIAL, not guessed).
- A day with no data is **MISSING** unless the owner marked it CLOSED in `business_calendar`.

### 6.4 Payment method vs channel

**Decision (confirmed by you):** WhatsApp, Swiggy and Zomato amounts in Quick Entry are **"paid via"**, i.e. payment methods. So `payments.method` is one list: `CASH, UPI, CARD, WHATSAPP, SWIGGY, ZOMATO, OTHER_ONLINE`. Platform methods (Swiggy/Zomato) settle later and carry commission, so they feed payment reconciliation (§6.7). Order **source** (Counter / WhatsApp / Phone / Other) remains a separate field on itemised orders and sales. In Quick Entry there is no source split, so "WhatsApp business share" on days with only a quick entry is the *paid-via-WhatsApp* amount and is labelled as such (PARTIAL for source reporting), never presented as the full WhatsApp order share.

### 6.5 Recipe costing and COGS

**Cost resolution for a product on date D** (`CostResolver`):

1. Find the `recipe_version` in force on D (effective-dated). If none → cost status **MISSING**.
2. For each line, expand sub-recipes recursively (e.g., Chocolate Sauce inside Belgian Waffwich), converting units (within dimension automatically; across dimension only via a pack spec/density).
3. For each ingredient find its unit cost **as of D**: the actual purchase cost per the costing method if lots exist (**ACTUAL**), else the latest known price (**ESTIMATED**, method recorded), else MISSING.
4. Sum into ingredient cost + packaging cost = total cost; gross profit and margin follow.

Each `cogs_entries` row stores its **cost basis**: `ACTUAL_LOTS`, `RECIPE_ACTUAL_PRICE`, `RECIPE_ESTIMATED_PRICE`, or `MISSING`.

**Coverage and labelling.** Period COGS reports "cost coverage = sales value of lines with a cost ÷ total sales value". If < 100% or any line is estimated, the P&L shows exactly your sentence: *"Gross profit is estimated because cost data is incomplete."* Coverage is computed, never invented (your §5 data-quality rule).

**Rule 16 (no rewriting past costs)** is satisfied by effective dating: changing today's ingredient price creates a new row effective today; June sales keep resolving to June's rows. A **backdated** correction (e.g., you later find the real June chocolate price) legitimately changes June's COGS; that is a restatement and is recorded by the change-tracking job.

**Phase 1 behaviour (no recipes yet):** COGS shows **"Not available — recipes/costs not set up"**, gross profit is shown as MISSING, not as 100% margin. The owner may opt in to the clearly labelled estimation method `PURCHASES_AS_COGS_PROXY` if they want an interim approximation.

### 6.6 Inventory, opened containers and the stock ledger

**Ledger approach.** Stock is never a stored number that gets overwritten. It is the **sum of append-only `inventory_movements`**. Remaining quantity in a container = opening quantity + Σ(its movements). "Estimated remaining" is labelled *estimated* until a physical stock check pins it.

**Tub lifecycle (your example):**

```
 PURCHASE            OPEN                     SALES                    CHECK                 VARIANCE
 4 L tub ≈ 2.8 kg →  26 Sep 11:30, weighed → 20 scoops × 70 g        →  physical 1.30 kg  →  -60 g
 SEALED              2.76 kg (EXACT)          = 1.40 kg theoretical     expected 1.36 kg       reason requested
 container ID auto   status OPEN              consumption → container   adjustment movement   audit entry
```

- **Basic entry is two taps** ("Pista 4L tub opened today" → Save). Container ID, expected weight (from the pack spec) and timestamp are filled automatically; the weighed quantity and notes are optional extras. Quantity basis defaults to SUPPLIER_SPECIFICATION and becomes EXACT when weighed (§97).
- **Consumption from sales.** When a sale is confirmed (event `SaleRecorded`), the `ConsumptionService` expands the recipe, then allocates each container-tracked ingredient to open containers using the configured allocation method: **FIFO** (oldest opened), **FEFO** (earliest expiry), or **MANUAL** (staff picks). If the current container runs out mid-sale the remainder spills to the next open one; if no container is open the consumption is recorded against the ingredient's general stock with a PARTIAL flag.
- **Deterministic replay.** Backdated sales and edits are handled by *replaying* the ingredient's movement stream in `txn` order and rewriting only the derived allocations (not the manual ones). Manual overrides are pinned and survive replays. The replay is idempotent and keyed by `(sale_item_id, revision)`.
- **Only consumed quantity becomes COGS** (§120). Opening a tub does not expense it; the unconsumed rest stays inventory. Purchases increase inventory value; consumption moves value to COGS using FIFO or weighted average (the policy is effective-dated and never changed silently).
- **Multiple open containers** of one product are always shown separately (§104). No anonymous merged number.
- **Open-stock age:** days since opened is computed. If the owner configured an internal shelf-life/use-by policy for the product, it appears as an *operational reminder*; the app never declares food safe or unsafe on age alone (§101).
- **Theoretical vs actual:** Expected = Opening + Purchases − Theoretical consumption − Recorded wastage. Variance = Physical − Expected. Over-serving analysis compares recipe-based consumption to actual stock reduction and shows the neutral sentence "Actual consumption is 1 kg higher than recipe-based consumption." — the **reason is chosen by the owner**, never auto-classified (§100).
- **Scoop costing:** owner-defined standard scoop weight (e.g., 70 g); cost per gram = bulk cost ÷ usable grams; cost for 1/2/3 scoops derived.

### 6.7 Cash and payment reconciliation

- **Cash:** Expected closing = Opening + Cash sales + Other cash income − Cash expenses − Cash withdrawals. Owner enters the counted amount; the app shows Expected / Actual / Difference and lets the owner attach explanations. The difference is reported as its own line and is **not** posted into profit or loss.
- **Payments:** for each method/platform the app compares Sales recorded vs Amount received vs Commission vs Settlement, with the difference. Statement import (UPI/bank/Swiggy/Zomato CSV) is a later extension using the same import wizard.

### 6.8 Valentina menu mapping (from your two menu images)

The current menu has been transcribed into **`valentina-menu-seed-draft.csv`**: **16 categories, 137 products, 160 price rows** (a product with two sizes/forms has two variants). It is a *draft for your review*, not final data; it will be loaded through the normal import path as a menu version (no prices in code).

How it maps onto the model:

- **Product vs variant:** *Waffwich/Waffstick* and *Single/Double cookie*, *Regular/Large Mastani* are **variants** of one product, each with its own price. Ice-cream flavours are products with a *Single Scoop* variant.
- **Same name, different product:** "Belgian Dark" exists as a Waffwich, a Waffstick and a Hot Chocolate. Product identity is category + name, so these stay separate.
- **Ice-cream flavours link to tubs:** each flavour product will later link to a bulk-stock ingredient (e.g., Pista), which is what drives scoop consumption and tub tracking in Phase 2.
- **Faloodas** appear as two sub-groups (Signature ₹180, Royal Rabdi Kulfi ₹200) including a *Make Your Own — any 2 scoops* item; recipe-wise this is a variable combination, so it will be costed from the chosen scoops (Phase 2).
- **Add-ons** are priced extras (₹10–₹40) attached to menu items; **Shots** are 60 ml, drinks carry sizes (300/350/390/600 ml).
- **Confirmed by owner:** all prices are correct; *Anti Inflammatory* shot is ₹70; the Waffwich/Waffstick column order is as transcribed. Spellings such as "Jamoon" and "Kardant" are kept as printed; 2- and 3-scoop pricing is not on the menu (add variants if you sell them).
- **Effective date (confirmed):** the menu has been the same since the café opened in **May 2026**, so it loads as one version, **"May 2026 Menu", ACTIVE, effective 1 May 2026**, with no price-history rows yet. The first real price change you make will create the first history entry.

---

## 7. Key flows

### 7.1 Sale to P&L (counter or WhatsApp)

```
 Counter / WhatsApp / Phone
        │  create order (source, type, items, customer)
        ▼
   ORDERS ── status NEW → CONFIRMED → PREPARING → READY → (OUT_FOR_DELIVERY) → COMPLETED
        │                         price = current ACTIVE menu item (at order time)
        ▼  on COMPLETED  (event OrderCompleted)
   SALES + SALE_ITEMS  (txn_date, actual unit_price, list_price_at_sale, discount, tax)
        │                         + PAYMENTS (method, status; "paid" only when confirmed)
        ├──► (P2) ConsumptionService → inventory_movements → container/lot allocation
        ├──► (P2) CostResolver → cogs_entries (as-of txn_date)
        └──► Reporting (reads ledger live) → Dashboard / P&L / 3-Month Review
```

Payment is **never** marked paid because a customer says "I have paid". It moves to PAID through the payment gateway callback, bank/UPI reconciliation, or an authorised staff confirmation (your payment-confirmation rule).

### 7.2 Backdated sale and later correction

```
 Today 26 Sep ── user enters sale with txn_date = 15 Jul ──► saved, entry_at = 26 Sep
                  flags: BACKDATED ("entered 73 days later") + source/MANUAL
 Report July ◄── includes it automatically (groups by txn_date)

 Later: July sales ₹8,500 → ₹9,200
   service requires reason + source ("Found additional handwritten bill / Handwritten register")
   ├─ audit_log: before ₹8,500, after ₹9,200, reason, source, user, time
   ├─ revision++ → record shows CORRECTED
   ├─ event SaleChanged → P2: replay stock + rebuild cogs for that date
   └─ change-tracking job: July P&L before/after snapshot → "Sales +₹700, COGS +₹…"
```

No duplicate rows are created: the correction updates the same record, and history lives in the audit log.

### 7.3 Menu change with a future effective date

```
 26 Sep: "Create New Menu Version" (copy of September Menu) → DRAFT
         edit prices / add items / rename → "Effective from?" = 1 Oct → SCHEDULED
 26–30 Sep: September Menu remains ACTIVE; owner can still edit the SCHEDULED one
 1 Oct 00:00 IST (and on read): October Menu becomes ACTIVE, September ARCHIVED (frozen)
 price_history rows already exist (who, when, why, old → new, both versions)
 WhatsApp & POS read menuVersionFor(today): new price appears everywhere immediately
 Historical sales: unchanged
```

### 7.4 Open a tub

`Open New Ice Cream` → choose product → choose container size (defaults from pack spec) → optional weighed quantity → date/time defaults to now → **Save**. The system creates the `stock_containers` row (OPEN), the opening movement, and starts allocating sales consumption to it.

### 7.5 Document → confirmed entry (OCR)

```
 📷 SCAN DOCUMENT (type chosen or AUTO-DETECT)
   → upload (client-side image compression) → object storage → uploaded_documents (UPLOADED)
   → async job → OCR/LLM adapter → ocr_extractions (fields + confidence)
   → document_drafts (unverified)   ← shown as "AI extracted data — please verify."
   → user edits/confirms ──► real record (purchase/expense/sale) with source=OCR, CONFIRMED,
                              link to original image, extracted vs confirmed values stored,
                              confirmation date/user
   Unreadable fields stay empty: "GST: could not determine · Invoice no.: missing · Confidence: Medium"
```

### 7.6 Excel / CSV import

```
 Upload file → parse (streamed) → choose/save column mapping
   (Date, Product, Category, Quantity, Rate, Discount, Net sales, Payment method)
 → PREVIEW: records to import · potential duplicates · unmapped columns · errors · missing values
            · unknown products (map to existing / create as DRAFT product / skip)
 → explicit CONFIRM → commit in one transaction under an import_batch_id (source=IMPORT/EXCEL/CSV, flag IMPORTED)
 → import history; a bad batch can be VOIDED as a whole (rows are voided, never deleted)
```

Duplicate detection uses a deterministic row signature (date + product + qty + rate + amount [+ source row hash]) against existing sales and previous batches.

---

## 8. Reporting and data quality

### 8.1 How reports are computed

- **Source of truth is the ledger.** Reports are SQL over `sales`, `sale_items`, `payments`, `expenses`, `cogs_entries`, `inventory_movements`, `wastage`, etc., filtered by `txn_date` and excluding `VOIDED`. This is what makes restatement automatic (Rule 17).
- **Performance:** at one café's volume, indexed live queries are fast. A rebuildable rollup table `daily_metrics (business_id, date, metric, dimension, value, status_mix)` is updated by domain events to keep the dashboard instant on a phone; it can be fully regenerated from the ledger at any time, so it can never become a second source of truth.
- **Metric registry.** Each metric (Gross Sales, Discounts, Net Sales, COGS, Gross Profit, Gross Margin %, Operating Expenses, Operating/Estimated Net Profit, Net Margin %, AOV, …) is defined once in code with its formula, inputs and completeness rule. The API returns that definition with the number — this powers "How is this calculated?" and keeps dashboard, P&L and 3-month review consistent.

### 8.2 P&L (simplified, as in your §24)

```
 Gross Sales
 − Discounts
 = Net Sales
 − COGS                     (basis + coverage shown; "Gross profit is estimated because cost data is incomplete.")
 = Gross Profit             (Gross Margin %)
 − Operating Expenses
 = Operating Profit / Estimated Net Profit   (Operating Margin %, Net Margin %)
```

- **Menu prices are GST-inclusive and the café is GST-registered (confirmed).** Every sale line stores the tax-inclusive amount actually charged; tax is **extracted** as `taxable = inclusive ÷ (1 + rate)` and `tax = inclusive − taxable`, using the rate effective on the transaction date from `tax_rules`. Net Sales and all margins are computed on the **taxable (ex-GST) value**; GST collected is shown separately and is never revenue. Until a rate is configured for a category, those lines are flagged PARTIAL ("GST rate not configured — Net Sales shown tax-inclusive"). Rates are **not** hard-coded: `tax_rules` is seeded with **GST 5% effective 1 May 2026** (your confirmed rate, to be verified by your CA) and can be changed or end-dated as rules change (§17-C). Because GST is now in scope from day one, Phase 1 captures the tax-ready fields (rate, taxable value, tax, HSN/SAC reference) even though GST returns come in Phase 4.
- "Estimated Net Profit" is by definition **before** items not recorded (depreciation, owner drawings, interest, income tax). The label states this.
- Unexplained cash differences, owner withdrawals and stock-purchase cash outflows do **not** enter operating expenses.

### 8.3 Dashboards and the 3-Month Review

- **Owner dashboard (§5):** Today (sales, orders, AOV, cash/UPI/card, WhatsApp, other, expenses, wastage) · This month (gross sales … net margin) · Trend vs previous month · Top metrics · **Data quality**. Every tile is a `Quantified` value with a status chip and is tappable.
- **3-Month Business Review (§6):** Month 1/2/3 columns with all listed measures, a free date-range picker, five trend charts, and drill-down **month → day → transaction**. Month boundaries follow `txn_date`.
- **Drill-down contract:** every aggregate returns a `drill` descriptor; one generic endpoint family (`/reports/drill/...`) returns the next level (days, then transactions, then full record with audit history).
- **Product/category performance:** quantity, revenue, average selling price, estimated COGS, gross profit, margin, discounts, wastage; sortable by revenue/quantity/gross profit/margin. **No single "best product" score** — only the actual metrics.
- **Before/after price-change analysis:** compares units sold, revenue, average price, gross profit and margin across the periods either side of a price change, **without concluding** whether it "worked".
- **Channel reports:** sales, orders, AOV, gross profit by Counter / WhatsApp / Phone / Other (+ online platforms).
- **The 18 listed reports** are built on the same metric registry and exportable to Excel/CSV/PDF.

### 8.4 Restatement tracking ("what changed in my history")

When a valid historical change lands, a debounced job recomputes the affected months, stores a `report_snapshots` row, and diffs it with the previous one. Each historical month page shows **"Report last updated"** and **"Changes: Sales +₹18,000 · COGS +₹7,200 · Gross profit +₹10,800"**, linking to the audit entries that caused it. The "Which historical records were corrected?" question from your closing section is answered by the same data.

### 8.5 Data Health (completeness without invention)

Completeness is always **counted from actual records**, never assumed:

| Area | Definition (proposed — please confirm in §17) |
|---|---|
| Sales | Trading days (non-CLOSED days since the data-start date) that have ITEMIZED or SUMMARY data ÷ trading days. Plus ITEMIZED share of SUMMARY totals. |
| Purchases | Purchases with supplier, amount, and (if stock) item lines ÷ all purchases; plus count of purchase records lacking an attachment/invoice no. |
| Expenses | Expenses with category and payment method ÷ all expenses; amounts uncategorised in ₹. |
| Inventory | Ingredients with opening stock set, with recent stock check, with a recipe-linked consumption; containers with weighed opening quantity. |
| Costing | Sales value with a resolvable recipe cost ÷ total sales value. |

Messages are factual, e.g. *"12 days have no recorded sales." "₹18,500 of expenses have no category." "Opening stock is missing." "25 purchase records have no supplier."* Each is a link to the exact records to fix. Percentages appear only when the denominator is knowable; otherwise the UI shows counts or "cannot be calculated".

---

## 9. Integrations

### 9.1 WhatsApp ordering (Phase 3)

- **Provider:** WhatsApp Business Platform / Cloud API behind an adapter interface (`MessagingGateway`), so Meta-direct vs a BSP can be swapped without touching ordering logic.
- **Webhook:** verification challenge handling, **signature verification** (`X-Hub-Signature-256`), idempotent processing by message ID, then async handling. Everything returns 200 quickly; work is queued.
- **Conversation state machine** (persisted in `whatsapp_conversations`): `WELCOME → MENU_BROWSE → CART → CHECKOUT_DETAILS → CONFIRMATION → TRACKING`, with a `HUMAN` mode for **Talk to Staff** (bot paused during takeover). Cart lives in the conversation; **prices are re-resolved from the current menu at checkout**, so a stale cart can never carry an old price.
- **Menu shown** = `menuVersionFor(today)` + availability (Available / Out of stock / Temporarily unavailable are not orderable; products are never deleted). Categories → products → variants/size → add-ons, using WhatsApp interactive list/reply-button messages (WhatsApp limits list size and button counts, so categories and long lists are **paginated**), with images/descriptions from the menu snapshot.
- **Checkout:** pickup (name, mobile, time, instructions) or delivery (name, mobile, address, landmark, time, instructions) with a configurable delivery charge; suggested pickup times from opening/closing hours, order cut-off and preparation time.
- **Order creation:** the **same `OrderService`** as the counter, with `source = WHATSAPP`, order number `WA-1024` from `document_sequences`. Staff see it on the **Order dashboard** (NEW → … → COMPLETED/CANCELLED). Completed orders flow to `sales` automatically.
- **Customers:** identified by WhatsApp number — no separate login for customers. Profile aggregates (orders, spend, last order, preferred type, saved addresses) are computed from orders. **Reorder previous order** copies the items but re-prices at the **current** menu; the historical order is untouched.
- **Templates and the 24-hour window:** free-form replies are allowed inside the customer-service window; outside it only pre-approved **templates** (order confirmation, payment confirmation, accepted, preparing, ready, dispatched, delivered, cancelled, refund) can be sent. Templates are managed in `message_templates` with their approval status.
- **Payments:** payment-link generation through a UPI-capable gateway (adapter), status PENDING → INITIATED → PAID / PARTIALLY_PAID / FAILED / REFUNDED. Gateway webhooks and bank/UPI reconciliation confirm payment.
- **Resilience:** an outbox and retry queue; if the API is down the dashboard shows **connection status**, staff can enter the order manually (`source = WHATSAPP` or `PHONE` as appropriate) and orders are never lost; failed outbound messages are retried.
- **Cost/compliance notes:** Meta charges per conversation/message category and requires a verified business and template approval — these are operational set-up items, not code, but they affect the Phase 3 timeline.

### 9.2 Documents, OCR and the "Scan Everything" button (Phase 3)

- **Adapter:** `DocumentExtractor` interface with pluggable engines (local Tesseract for printed text; a cloud OCR or multimodal LLM for handwriting and messy bills). The engine and model version are stored with every extraction.
- **Output is always a draft.** Fields: supplier name, GSTIN, PAN, invoice no/date, due date, items, qty, unit, rate, discount, taxable value, CGST/SGST/IGST/cess, total, payment status — each with a **confidence score**. Anything not readable stays **blank**; the system never invents GSTIN, tax rates, invoice numbers or amounts.
- **Validation helpers:** GSTIN structure/check-digit validation, arithmetic checks (qty × rate − discount + tax = total), duplicate invoice detection (supplier + invoice no + amount, plus image hash).
- **Auto-classification (suggestion only):** inventory/ingredient/packaging purchase, fixed asset, repair, electricity, rent, salary, professional fee, marketing, delivery, miscellaneous, supplier credit note, other. A confirmed ingredient purchase drives: Purchase → Supplier → **Inventory (+stock)** → Purchase register → ITC (if applicable) → Supplier payable → later consumption → COGS (the chain in your §8).
- **Document management (§9):** each transaction links to its image, OCR data, items, GST, payment, inventory receipt, TDS and audit history. Saved searches answer: *"all purchases from ABC Foods"*, *"all electricity bills in FY 2026-27"*, *"expenses without bills"*, *"GST invoices where GSTIN is missing."*
- **Handwritten sales registers (§29):** same pipeline, producing draft sales (date, product, quantity, amount, payment method, notes). Stored: original image, extracted data, confirmed data, confirmation date.

### 9.3 Excel / CSV / Google Sheets import (Phase 1)

Covered in §7.6. Google Sheets works through "Download as .xlsx/.csv". Mapping templates are saved per source file layout so a recurring monthly Excel imports in two taps. Because your real 3-month records are still to come, the importer is built **mapping-driven and forgiving**, and the data model is finalised against your real files before Phase 1 is frozen (§17).

---

## 10. Compliance and people modules (Phase 4 — design level)

These are real accounting/compliance features and tax rules keep changing, so the design principle is: **configurable, effective-dated rules + CA review workflow, never hard-coded rates**, and the app presents itself as record-keeping support, not tax advice.

- **Tax rule master (`tax_rules`):** effective-dated rows (type, rate, thresholds, section/form, notes). A `tax_profile` records registration type, turnover category, and applicable obligations. The system **does not assume** a form or return applies; applicability is derived from the profile and flagged "needs CA confirmation" when uncertain.
- **GST filing profile (confirmed): regular scheme, 5%, QRMP.** Under QRMP the business files returns quarterly and pays tax monthly, so the compliance calendar holds *quarterly return* items and *monthly payment* items; the due dates are configurable data, not code, and are verified by your CA. Sales are recorded tax-inclusive and the tax is extracted per §8.2.
- **GST:** GST-ready transaction data from day one (tax rate, taxable value, CGST/SGST/IGST/cess, HSN/SAC, place of supply, B2B/B2C, GSTIN validation, nil/exempt/non-GST, reverse-charge flag, credit/debit notes). Registers: sales, purchase, ITC, output GST, HSN-wise, rate-wise. Return workpapers for GSTR-1, GSTR-3B and annual return (GSTR-9) plus books-vs-GST-data reconciliation (importing portal data where available), challan and filing-status tracking. E-invoice/e-way-bill readiness is a flag, not an assumption.
- **TDS/TCS:** vendor/payee, PAN, TAN, nature of payment, section, threshold, rate (from the effective-dated master), deduction/credit/deposit dates, challans, deductee ledger, return data, corrections, certificates, pending actions. Salary TDS handled separately from vendor TDS. Provision-transition rules between the older and newer Income-tax Acts are handled as **effective-dated rules**, selected by transaction/credit/payment date.
- **Payroll/HR:** employee master (ID, contact, joining date, role, employment type, salary structure, bank/payment, PAN, PF/ESIC/PT/TDS info, emergency contact, documents, active flag — Aadhaar-related fields only where legally necessary and encrypted), attendance (present/absent/weekly off/half day/leave/late/early/overtime/shift), salary calculation (basic, HRA, allowances, incentives, overtime, bonus, advances/recoveries, deductions, TDS/PF/ESIC/PT), outputs: monthly payroll register, payslips, salary payable and payment records, salary history, payroll journal/expense, TDS payroll data, year-end employee tax information. Payroll posts into `expenses` (staff salary) so the P&L stays one system.
- **Hygiene and food-safety logs:** configurable opening/closing checklists and hygiene log (date → area → task → frequency → staff → time → status → photo → remarks → supervisor approval; statuses Completed/Pending/Failed/Corrective action required). Temperature logs for freezers, fridges, cold storage, prep area, hot holding with **owner-configured acceptable ranges** (the app does not invent regulatory limits).
- **Compliance dashboard and calendar:** items generated from the business profile (GST, TDS/TCS, payroll, licences such as shop & establishment/FSSAI-related records, insurance, fire/safety, renewals) with traffic-light status and **configurable due dates**.

---

## 11. Security and data protection

- **Authentication:** JWT access (short) + rotating refresh tokens (httpOnly, `SameSite`); rate limiting and lockout on login; **one personal account per person (confirmed)**, usable on Android and on the desktop web UI, so the audit trail always names the real person. No shared logins.
- **Authorization:** roles → permissions (data-driven) enforced with method security. Field-level rules: e.g., KITCHEN sees only order preparation; STAFF sees orders/sales entry but **not** cost, margin or profit unless the owner grants `finance.view_*`.
- **Sensitive data:** employee Aadhaar/PAN/bank fields encrypted at column level (P4); secrets in environment/secret store; HTTPS everywhere; uploaded-file type/size validation and re-encoding of images; WhatsApp and gateway webhooks verified by signature.
- **Privacy:** customer data (names, mobile numbers, addresses) is personal data under India's Digital Personal Data Protection Act, 2023 — design includes consent for WhatsApp messaging, data minimisation, retention settings and export/erase workflows (**erasing never breaks financial records**: financial rows keep an anonymised reference). Please confirm applicability with your advisor.
- **Backup and recovery (§77):** nightly logical dump + continuous WAL archiving for point-in-time recovery, object-storage versioning for documents, an alert if a backup fails, and a documented restore drill. Day-to-day "accidental change" recovery uses audit history and void/restore, not backups.
- **Observability:** structured logs with request IDs, Actuator health/metrics, error alerts to owner/admin.

---

### 11.1 Suggested staff roles

Roles are data, so you can rename them or change any permission later. Suggested starting set for a café of your size:

| Role | Typical person | Can do | Cannot see/do by default |
|---|---|---|---|
| **Owner** | You | Everything; approves day variances; changes menus, prices, users, settings; sees all finance | — |
| **Manager** | Shift/store manager | Sales, orders, purchases, expenses, inventory, wastage, stock checks, cash count, reports (incl. cost and profit if granted), menu *draft* edits | Approve day variances, change users/permissions, publish menu prices (Owner decides), audit-log settings |
| **Cashier / Counter** | Counter staff | New sale/order, quick entry, payments, wastage entry, open-tub entry, view menu | Cost, margin, profit, purchases' prices, reports beyond today's own sales, editing past-dated records |
| **Kitchen** | Prep/kitchen staff | Order board (preparing/ready), open tub, wastage, stock check | Any money figures, customers' contact details |
| **Accountant / CA (read-only)** | Your CA or bookkeeper | View P&L, GST/TDS data, purchases, expenses, audit trail, exports; add notes | Create or edit sales/orders, change menu or users |

Rules that apply to every role: every action is attributed to the logged-in person; edits to past records require a reason; only the Owner (or a role the Owner grants `sales.approve_day_variance`) approves day variances. Add a **Delivery** role later if you hire riders.

## 12. API design

REST/JSON under `/api/v1`, described by OpenAPI; a typed TypeScript client is generated from it.

**Conventions**

- **Money/quantities as strings**; dates `YYYY-MM-DD`; timestamps ISO-8601 with offset.
- **Create/update financial records** accept `txnDate`, `source`, `notes`, and `reason` (required when changing a CONFIRMED record). Server sets `entryAt`.
- **Idempotency-Key** header on POSTs so a flaky mobile connection cannot create duplicates.
- **Optimistic locking** via `version` (HTTP 409 on conflict).
- **Errors** as RFC 7807 `application/problem+json` with field-level details.
- **Lists:** cursor pagination, filters (`from`, `to`, `status`, `source`, `q`), `fields` for light mobile payloads.
- **No DELETE on financial resources.** `POST /{id}/void` with a reason.

| Area | Representative endpoints |
|---|---|
| Auth | `POST /auth/login`, `/auth/refresh`; `GET /me` |
| Settings/users | `GET/PUT /settings/*`, `/users`, `/roles`, `/permissions` |
| Catalog | `/categories`, `/products`, `/products/{id}/variants`, `/products/{id}/lifecycle` |
| Menu | `/menus`, `POST /menus/{id}/copy`, `POST /menus/{id}/schedule {effectiveFrom, reason}`, `GET /menus/active?date=`, `GET /menus/compare?a=&b=`, `GET /price-history?productId=` |
| Sales | `POST /sales` (itemised), `POST /daily-summaries` (quick), `GET /sales?from&to&channel`, `PATCH /sales/{id}`, `POST /sales/{id}/void`, `GET /days/{date}` (basis + variance) |
| Orders | `POST /orders`, `PATCH /orders/{id}/status`, `GET /orders?status=` |
| Expenses | `/expense-categories`, `/expenses`, `/expense-schedules`, `POST /expense-schedules/{id}/confirm` |
| Purchases | `/suppliers`, `/purchases` (with attachments), `POST /purchases/{id}/receive` |
| Imports | `POST /imports` (upload), `PUT /imports/{id}/mapping`, `GET /imports/{id}/preview`, `POST /imports/{id}/commit`, `POST /imports/{id}/void` |
| Reports | `GET /reports/dashboard`, `/reports/pnl?from&to`, `/reports/three-month`, `/reports/products`, `/reports/categories`, `/reports/channels`, `/reports/price-change-impact`, `/reports/data-health`, `/reports/drill/...`, `/reports/{type}/export?format=` |
| Audit | `GET /audit?entityType&entityId`, `GET /history/{type}/{id}` |
| Recipes/costing (P2) | `/recipes`, `/recipes/{id}/versions`, `/costing/product/{id}?date=`, `/ingredients`, `/ingredients/{id}/cost-history` |
| Inventory (P2) | `/stock`, `/containers`, `POST /containers/{id}/open`, `POST /stock-counts`, `/wastage`, `/stock/variance`, `/stock/adjustments` |
| Reconciliation (P2) | `/cash-days/{date}`, `/payment-reconciliation` |
| WhatsApp (P3) | `GET/POST /webhooks/whatsapp`, `/whatsapp/conversations`, `POST /whatsapp/conversations/{id}/takeover` |
| Documents (P3) | `POST /documents`, `GET /documents/{id}/draft`, `POST /documents/{id}/confirm` |

---

## 13. Frontend design

### 13.1 Principles (from your §3)

Mobile-first, large touch targets (min 48 px), minimal typing (pickers, steppers, last-used defaults, search-as-you-type), one-handed use, fast first load (route-level code splitting, small bundle, cached app shell), plain language, ₹ and Indian digit grouping, and "a modern café app, not enterprise accounting software".

### 13.2 Navigation

- **Mobile:** bottom bar — **Home · Sales · Orders · Stock · More**; **More** is a grid with Menu, Products, Purchases, Expenses, Reports, Customers, Settings. A floating **＋** opens the shortcut sheet: *New Sale · New Order · Expense · Purchase · Wastage · Stock Adjustment · Upload Excel · Upload Handwritten Record · 📷 Scan Document*.
- **Desktop:** left sidebar with all 11 items (HOME, SALES, ORDERS, MENU, PRODUCTS, PURCHASES, INVENTORY, EXPENSES, REPORTS, CUSTOMERS, SETTINGS), a top search, and wider tables/charts.
- Global search (products, customers, suppliers, bills) and per-list quick filters.

### 13.3 Screen inventory

| Area | Screens |
|---|---|
| Home | Owner dashboard (Today / This month / Trend / Top metrics / Data quality), shortcut sheet |
| Sales | Quick Daily Entry, Item-wise Entry (multi-line cart), Day view (basis, variance), Sales list/detail with History drawer |
| Orders | Order board (staff dashboard by status), New Order, Order detail |
| Menu | Menu versions list, version editor (draft/scheduled), **effective-date prompt**, copy menu, historical menu viewer, **menu comparison**, price history per product |
| Products | Product list/detail, variants, lifecycle status, recipe builder entry (P2) |
| Purchases | Supplier list, purchase entry (with photo), purchase register |
| Inventory (P2) | Current stock, **Open Stock** (tubs), Open New Tub, End-of-day stock check, Wastage, Adjustment, Variance, Batches |
| Expenses | Expense entry, categories, recurring schedules (expected → confirm) |
| Reports | Report hub, **P&L**, **3-Month Business Review**, Product/Category/Channel performance, Price-change impact, Data Health, Cash & Payment reconciliation (P2), exports |
| Customers | List/profile (P1 light, P3 full) |
| Settings | Business profile, hours, users/roles/permissions, payment methods, delivery charge, tax profile, costing/allocation policy, imports history, backups status |

### 13.4 Reusable UI components (the "trust layer")

`<Money>` (₹, lakh/crore grouping) · `<StatusChips>` (CONFIRMED / IMPORTED / MANUAL / ESTIMATED / PARTIAL / MISSING / BACKDATED / CORRECTED) · `<MetricCard drill>` (value + status + tap to drill) · `<HowCalculated>` bottom sheet (formula + inputs) · `<CompletenessMeter>` · `<DateRangePicker>` (presets: Today, This month, Last 3 months, FY) · `<BackdatedBanner>` ("Backdated — entered 73 days later") · `<HistoryDrawer>` (audit trail for any record) · `<ReasonDialog>` (mandatory reason on edits/voids) · `<EffectiveDatePrompt>` · `<EmptyState>` that explains *missing* vs *zero*.

**Design rule:** a number is never shown without its status; a blank is never shown as ₹0.

### 13.5 Wireframe sketches (mobile)

```
 HOME                              QUICK DAILY ENTRY                  NEW MENU VERSION
┌──────────────────────────┐      ┌──────────────────────────┐      ┌──────────────────────────┐
│ Valentina    4 Oct 2026  │      │ ‹ Quick entry            │      │ ‹ Create Menu Version    │
│ TODAY                    │      │ Date  [ 26 Sep 2026 ▾ ]  │      │ Name  [October 2026 Menu]│
│ Sales ₹12,450  CONFIRMED │      │ Total sales  [ ₹       ] │      │ Based on [Sept 2026 ▾]   │
│ 41 orders · AOV ₹304     │      │ Received by              │      │ Reason  [Festive revision]│
│ Cash 5,200 UPI 6,100 …   │      │  Cash[ ] UPI[ ] Card[ ]  │      │ From which date should   │
│ THIS MONTH               │      │ Order came from          │      │ this menu be effective?  │
│ Net sales ₹3.8L  ●ESTIM. │      │  WhatsApp[ ] Online[ ]   │      │  [ 1 Oct 2026 ▾ ]        │
│ GP ₹1.9L (est.)  tap ▸   │      │ Orders [ ] Discounts [ ] │      │ Current menu stays ACTIVE│
│ DATA QUALITY             │      │ Refunds[ ] Wastage[ ]    │      │ until 30 Sep.            │
│ Sales 24/30 days ▓▓▓▓░░  │      │ Notes [                ] │      │ [ Save as Scheduled ]    │
│ 12 days no sales ▸       │      │ [        Save          ] │      └──────────────────────────┘
│ [＋]  Home Sales Orders… │      └──────────────────────────┘
└──────────────────────────┘
 OPEN STOCK (P2)
┌──────────────────────────────────────────┐
│ OPEN ICE CREAM                           │
│ Pista        opened 26 Sep · 3 days      │
│ Opening 2.76 kg (weighed)                │
│ Est. consumed 1.05 kg · Est. left 1.71 kg│
│ [OPEN]  [Stock check] [Dispose]          │
│ Chocolate    opened 25 Sep               │
│ Est. left 0.60 kg  [LOW STOCK]           │
└──────────────────────────────────────────┘
```

### 13.6 PWA behaviour

Installable on the Android home screen; app shell and static assets cached by a service worker for fast loads; data always fetched live (**online-first**, per your choice). Image uploads are compressed on-device. Offline queueing of entries is a possible later enhancement (see 17-D); the API's idempotency keys already make it feasible.

---

## 14. Project structure and engineering practice

```
cafe-pos/
├─ backend/                        Spring Boot (Maven or Gradle)
│  └─ src/main/java/com/cafepos/
│     ├─ platform/                 money, units, dates, status, estimates, sequences, events
│     ├─ identity/  audit/  catalog/  menu/  sales/  orders/  customers/
│     ├─ expenses/  purchases/  imports/  reporting/  dataquality/
│     ├─ recipes/  costing/  inventory/  wastage/  reconciliation/        (P2)
│     ├─ whatsapp/  payments/  documents/  notifications/                  (P3)
│     └─ tax/  gst/  tds/  payroll/  hr/  hygiene/  compliance/            (P4)
│        each module:  <Module>Api (public)  ·  internal/{web, application, domain, persistence}
│  └─ src/main/resources/db/migration/   Flyway V001__*.sql …
├─ frontend/                       React + TS + Vite
│  └─ src/{app, features/<module>, shared/{ui,format,api,hooks}, i18n}
├─ deploy/                         docker-compose.yml, Caddyfile, backup scripts, .env.example
└─ docs/                           this HLD, ADRs (architecture decision records), runbooks
```

- **Architecture guard rails:** Modulith `verify()` and ArchUnit in CI; no module touches another's tables directly.
- **Database discipline:** every schema change is a Flyway migration; constraints (exclusion constraints, `CHECK`s, foreign keys, non-negative amounts) live in the DB, not only in Java; DB role for the app has no DELETE/UPDATE on `audit_log` and no DELETE on financial tables.
- **CI:** build + unit + Testcontainers integration tests + migration test (empty DB → latest) + OpenAPI diff check + frontend typecheck/lint/Vitest + Playwright smoke.
- **Environments and data:** `dev` seeds a **separate demo business** (uses `business_id`) with realistic sample data, so sample data can never mix with your real business data.
- **Documentation:** ADRs for each major decision in this HLD, kept in `docs/`.

---

## 15. Test strategy and your 20 acceptance scenarios

- **Unit tests:** money/rounding, unit conversion (incl. blocked litre↔kg), cost resolver (as-of dates, sub-recipes), stock replay determinism, menu effective-date resolution, status derivation.
- **Integration tests (real PostgreSQL via Testcontainers):** audit written on every change, void instead of delete, exclusion constraints reject overlapping menu dates, restatement changes reports, no double count in Quick vs Item-wise.
- **Golden datasets:** small hand-checkable data (e.g., your ₹8,500 → ₹9,200 example, ₹1,400 tub / 2,800 g → ₹35 per 70 g scoop) with expected P&L figures.
- **Property tests:** replaying the same stock events in any insertion order yields identical balances.
- **E2E (Playwright):** the scenarios below on a mobile viewport.

| # | Your test scenario | Phase proven |
|---|---|---|
| 1 | Create product | P1 |
| 2 | Create menu version | P1 |
| 3 | Change product price | P1 |
| 4 | Create future menu | P1 |
| 5 | Enter historical sale | P1 |
| 6 | Edit historical sale | P1 |
| 7 | Verify historical report changes correctly | P1 |
| 8 | Create WhatsApp order | P1 (manual entry with source=WHATSAPP) → P3 (real webhook) |
| 9 | Change menu price | P1 |
| 10 | Old WhatsApp order retains old price | P1 (manual) → P3 (real) |
| 11 | Discontinue product | P1 |
| 12 | Historical sales still show discontinued product | P1 |
| 13 | Upload Excel | P1 |
| 14 | Upload handwritten record | P3 |
| 15 | Verify OCR data before confirmation | P3 |
| 16 | Create expense | P1 |
| 17 | Calculate P&L | P1 (COGS shown as unavailable/estimated) → P2 (full) |
| 18 | Record wastage | P2 |
| 19 | Reconcile cash | P2 |
| 20 | Review audit log | P1 |

Note: your document says the MVP should be tested against all 20, but the same document's build order puts WhatsApp/OCR after the core. I propose the phased proof above (17-Q).

---

## 16. Phased roadmap

Sizes are relative (S/M/L/XL), not calendar estimates — I would give dates after you confirm scope and the data shape.

| Phase | Scope | Size |
|---|---|---|
| **0 — Foundations** | Repo, Docker Compose, CI, Flyway baseline, shared kernel (money/units/dates/status/estimates/sequences/events), identity & roles, audit infrastructure, app shell + design system + PWA, demo business seed | M |
| **1 — Core ledger** (your "First build" list) | Login · Product master · Menu manager · **Menu versions + effective dates** · **Price history + comparison** · Daily sales (quick + item-wise) · Orders · Expenses · Purchases (basic) · **Basic P&L** · **3-Month Review** · Dashboard + Data Health · **Excel/CSV import** · **Backdated entries** · **Audit trail** · void/correct · notes · recurring expenses · users/permissions | XL |
| **2 — Costing and inventory** | Recipes + versions + sub-recipes · units/conversions/pack specs · ingredient cost history · cost resolver + COGS · inventory ledger · lots · **opened containers** · stock counts · variance/over-serving · wastage · prep batches/semi-finished · supplier ledger · **cash reconciliation** · **payment reconciliation** · cookie management · inventory reports | XL |
| **3 — Channels and documents** | WhatsApp ordering end-to-end · customer profiles · payment links/status · order notifications · bill scanner · handwritten-record OCR · document management · notifications | XL |
| **4 — Compliance and people** | Tax rule master · GST registers/workpapers · TDS/TCS · payroll/HR · hygiene & temperature logs · compliance dashboard/calendar | XL |
| **5 — Growth** | Advanced analytics, loyalty, promotions, multi-branch, accounting/bank/delivery-platform integrations, advanced AI assistance | L+ |

**Why this order is safe:** Phase 1 already records `txn_date`, source, status flags, audit, tax-ready fields, and `business_id`/`location_id` placeholders, so Phases 2–5 add modules and tables without reshaping the ledger.

---

## 17. Assumptions and open questions

Defaults are what I will build unless you say otherwise.

| ID | Topic | My default / question |
|---|---|---|
| A | Quick vs item-wise on the same day | **Decided:** show both and the difference; the day needs **approval** by an authorised approver (default Owner) before it counts as reconciled (§6.3). |
| B | WhatsApp/Swiggy/Zomato in Quick Entry | **Decided:** "paid via" → payment methods (§6.4). |
| C | GST | **Decided:** regular scheme, **5%**, **QRMP** (quarterly returns, monthly payment), prices GST-inclusive (§8.2, §10). Seeded as an effective-dated rule from 1 May 2026 for all categories. **For your CA to confirm:** (1) 5% applies to every item incl. add-ons and drinks, (2) whether input tax credit can be claimed at this rate — the design has an ITC-eligible switch so the purchase/ITC registers follow the CA's answer. |
| D | Connectivity | Assumed reliable internet at the counter (online-first PWA). If not, offline entry queue is a Medium–Large addition. |
| E | Language | English UI first, i18n-ready (Hindi/Marathi later). Needed sooner? |
| F | Staff and roles | **Confirmed** roles in §11.1 (Owner, Manager, Cashier, Kitchen, Accountant). Individual login for everyone, phone and desktop web. Users will be added later by the Owner. |
| G | Data-start date | **Decided:** 1 May 2026 (café opened). Days from 1 May that have no data are reported as missing unless marked CLOSED. Opening stock/cash balances at 1 May are not assumed; they stay "not set" until you enter them. |
| H | Costing/allocation | Default costing = **Weighted Average**; tub allocation = **FIFO (oldest opened)**; FEFO for expiry-tracked items. Policy changes are effective-dated and logged. |
| I | "Backdated" flag | Flagged when entry date is later than transaction date, with a business-day rollover (default 04:00) so late-night entries don't all show as backdated. |
| J | Mandatory reason | Required when editing/voiding CONFIRMED financial records; optional on drafts. |
| K | Completeness definitions | As proposed in §8.5. |
| L | Real data | **Received:** current menu → `valentina-menu-seed-draft.csv` (§6.8). **Decided:** menu unchanged since 1 May 2026, so there is one menu version and no price changes yet. **Pending (later):** Excel/handwritten samples; the model is built to accept them when they arrive. |
| M | Sample data | Lives in a separate demo business, never in your real business. |
| N | Providers | WhatsApp: Meta Cloud API directly vs a BSP? Payment gateway for UPI links? Meta business verification and template approval take lead time — start early. |
| O | OCR engine & privacy | Handwriting needs a cloud vision/LLM model. Is it acceptable to send bill images to such a vendor? (cost and privacy) |
| P | Period lock | Optional "lock month for CA filing"? Off by default (you want history editable). |
| Q | Test scope | Phased proof of the 20 scenarios (§15). |
| R | Hosting | Single VPS with Docker Compose; India-region hosting preferred? |
| S | Profit definition | "Estimated Net Profit" excludes depreciation, owner drawings, interest and income tax unless recorded. |

---

## 18. Requirements traceability

| Your section(s) | Topic | Module | Phase |
|---|---|---|---|
| Preamble, §37, source/partial/estimation sections | Data statuses, source, partial/missing/estimated | `platform`, `dataquality`, `reporting` | 0–1 |
| §1, §§5–6, report/analytics/drill-down screenshot sections | Objectives, dashboard, 3-month review, reports, drill-down | `reporting` | 1 |
| §2, §§16–17 | Categories, product master, lifecycle | `catalog` | 1 |
| §3–4, shortcut/navigation sections | Mobile-first UX, navigation, shortcuts | frontend | 0–1 |
| §7 | Quick daily + item-wise entry | `sales` | 1 |
| §8, screenshot order sections | Order management, order dashboard | `orders` | 1 |
| §§9–15, menu-integrity and Valentina-menu sections | Menu mgmt, versions, effective date, history, price history, historical pricing, comparison, import of Valentina menu | `menu`, `catalog` | 1 |
| §22, §23, recurring expenses | Purchases, suppliers, expenses | `purchases`, `expenses` | 1 (supplier ledger 2) |
| §24 | P&L | `reporting`, `costing` | 1 → 2 |
| §28 | Excel/CSV import | `imports` | 1 |
| §§30–36, notes/audit/change-tracking sections | Backdating, entry vs transaction date, edits, restatement, audit, void, notes | `audit`, `platform`, all | 0–1 |
| §68, Users | Roles & permissions | `identity` | 1 |
| Settings | Business settings | `platform` | 1 |
| §§18–19, 87–89, 99, 106–112 | Recipes, units, portions, versions, costing, sub-recipes, prep batches, semi-finished | `recipes`, `costing`, `inventory` | 2 |
| §§20–21, 90–105, 113–123 | Inventory, containers/opened stock, batches, counts, variance, FIFO/FEFO, over-serving | `inventory` | 2 |
| §27, 103 | Wastage | `wastage` | 2 |
| §§25–26 | Cash and payment reconciliation | `reconciliation` | 2 |
| Cookie management | Cookie types, dough, stuffed, scoopable | `catalog`, `recipes` | 2 |
| §§38–44 and WhatsApp screenshot sections (menu, flow, checkout, confirmation, staff dashboard, payments, customer profile, reorder, availability, hours/templates, takeover, outage handling, analytics) | WhatsApp ordering | `whatsapp`, `payments`, `customers`, `notifications` | 3 |
| §29, Scanner, Kaccha bill, Auto-classification, Document management, Scan Everything | OCR and documents | `documents` | 3 |
| §77, future-ready list | Backup, extensibility | ops, architecture | 0 (backup), 5 (features) |
| Addendum 1–2, 10–11 | GST, TDS/TCS, compliance dashboard and calendar | `tax`, `gst`, `tds`, `compliance` | 4 |
| Addendum 3 | Payroll/HR | `payroll`, `hr` | 4 |
| Addendum 4–5 | Hygiene, temperature logs | `hygiene` | 4 |
| Build order, 17 rules, 20 tests, "final objective" | Governance of the build | all | all |

---

## 19. What I need from you to proceed

1. Review this HLD and tell me what to change.
2. Confirm the proposed staff roles (§11.1) and give me the list of people who need logins.
3. Ask your CA to confirm: 5% on every item, whether input tax credit is claimable, and the QRMP due dates to configure.
4. Confirm that I may start **Phase 0 + Phase 1** (the repo, foundations and core ledger).

No code has been written yet.
