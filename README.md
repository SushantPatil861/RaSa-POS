# Valentina Café POS — Phases 0–4

Business-management app for the café — core ledger (Phase 1), costing & inventory (Phase 2), importer / OCR / WhatsApp (Phase 3), GST / TDS / payroll / hygiene / compliance (Phase 4): item-wise billing, quick daily totals (with Owner approval when the two disagree), orders (counter / WhatsApp / phone), versioned menus with effective dates, expenses, purchases, GST-aware reports, audit trail, individual logins with roles.

Design: see `docs/Cafe-POS-High-Level-Design.md`. Stack: Java 21, Spring Boot 3.5, PostgreSQL 16 (Flyway migrations), React + TypeScript + Tailwind (installable PWA).

```
backend/    Spring Boot API   (Maven)      -> http://localhost:8080
frontend/   React PWA         (Vite)       -> http://localhost:5173  (dev)
docs/       HLD, ERD, menu seed CSV
docker-compose.yml   database (and optionally the whole stack)
```

> **Status — please read.** The SQL migrations, the pure business logic (GST extraction, menu status resolution, business-day rollover, status flags), the report queries and the whole frontend were built and tested while preparing this. The Spring Boot code could **not be compiled** where it was written (Maven Central was unreachable), so **the first `mvn clean verify` on your machine is the real compile check**. If it prints errors, copy them to me and I will fix them straight away — see "If the build fails" below.

---

## 1. Prerequisites

| Tool | Version | Check |
|---|---|---|
| JDK | 21 | `java -version` |
| Maven | 3.9+ | `mvn -v` (must say Java 21) |
| Node.js | 20 or 22 | `node -v` |
| Docker Desktop (easiest for the DB) **or** PostgreSQL 16 | — | `docker --version` |

Maven must be able to download from Maven Central (directly, or through your company Nexus/Artifactory if that proxies Central). If your office `settings.xml` only points to a private repository that does not mirror Central, either add a proxy of Central to it or build from a network that can reach `repo.maven.apache.org`.

## 2. Start the database

With Docker (recommended):

```bash
docker compose up -d db
```

This creates database `cafepos`, user `cafepos`, password `cafepos` on `localhost:5432`.

Without Docker — in `psql` as a superuser:

```sql
create user cafepos with password 'cafepos';
create database cafepos owner cafepos;
```

(Flyway (migrations V1–V11) creates all tables, roles, GST rule and the 160-item "May 2026 Menu" on first start. The `btree_gist` extension is needed — it ships with standard PostgreSQL; if your DB user is not a superuser, run `create extension if not exists btree_gist;` once as a superuser inside `cafepos`.)

## 3. Build and run the backend

```bash
cd backend
mvn clean verify          # compiles, runs the unit tests, builds the jar
mvn spring-boot:run       # starts the API on :8080
```

Or run the jar: `java -jar target/cafe-pos-backend-0.1.0-SNAPSHOT.jar`.

On the **first start** the app creates the Owner login:

* email: `owner@cafe.local` (change with `CAFE_OWNER_EMAIL`)
* password: if you did not set `CAFE_OWNER_PASSWORD`, a random one is **printed once in the log** — look for `Generated password (shown ONCE`. Copy it, then change it in Settings → My account.

Quick check: <http://localhost:8080/actuator/health> should show `{"status":"UP"}`.

Useful environment variables (all optional for local development):

| Variable | Default | Meaning |
|---|---|---|
| `CAFE_DB_URL` | `jdbc:postgresql://localhost:5432/cafepos` | database |
| `CAFE_DB_USER` / `CAFE_DB_PASSWORD` | `cafepos` / `cafepos` | |
| `CAFE_JWT_SECRET` | dev value | **set a random 32+ char value in production** (`openssl rand -base64 48`) |
| `CAFE_OWNER_EMAIL` / `CAFE_OWNER_NAME` / `CAFE_OWNER_PASSWORD` | `owner@cafe.local` / `Owner` / random | first-start Owner |
| `CAFE_COOKIE_SECURE` | `false` | set `true` when served over HTTPS |
| `CAFE_CORS_ORIGINS` | `http://localhost:5173` | allowed browser origin(s) |

## 4. Run the frontend

In a second terminal:

```bash
cd frontend
npm ci
npm run dev               # http://localhost:5173  (proxies /api to :8080)
```

Open <http://localhost:5173>, sign in as the Owner. On a phone on the same Wi-Fi, open `http://<your-PC-IP>:5173` (start with `npm run dev -- --host`). Tests/production build: `npm test`, `npm run build`.

## 5. Everything in containers (optional)

```bash
cp .env.example .env      # then edit: set CAFE_JWT_SECRET (32+ chars) and passwords
docker compose up --build
```

App at <http://localhost:8081>. (`docker compose` builds the backend inside Docker, so it also needs Maven Central access.)

## 6. First steps after login

1. **Settings → Users**: add the manager, cashiers, kitchen staff, accountant (roles: Owner, Manager, Cashier, Kitchen, Accountant). Everyone gets their own login; the same login works on phone and desktop.
2. **Settings → GST**: the 5 % GST-inclusive rule effective 01-May-2026 is pre-loaded. ITC eligibility is "to be confirmed by CA" — nothing is claimed.
3. **Menu**: "May 2026 Menu" (160 prices you confirmed) is active from 01-May-2026. To change prices: Menu → New version → copy → edit → *Schedule…* → choose the effective date. Old bills keep old prices.
4. Enter sales: **New sale** (item-wise) or **Daily entry** (just the day's total + "paid via" split). If both exist for a day and differ, the Owner sees *Pending approval* on that day and chooses which figure reports use; nothing is added together.
5. Past data: enter with the real transaction date. Entries are labelled BACKDATED/ESTIMATED/PARTIAL/CORRECTED automatically; days with nothing recorded show up under Reports → Data health instead of being filled with zeros.

## 7. If the build fails

* Run `mvn clean verify 2>&1 | tee build.log` and send me the **first ~30 error lines** (the first error usually causes the rest).
* `release version 21 not supported` → Maven is using an older JDK; set `JAVA_HOME` to JDK 21.
* Dependency download errors / `403` / `Could not transfer artifact` → network or `settings.xml` repository issue (see Prerequisites), not a code problem.
* Flyway error on start → send the message; check the DB user can create tables and the extension.
* Port 8080 busy → `PORT=8090 mvn spring-boot:run` (and change the proxy target in `frontend/vite.config.ts`).

## 8. What is in this build

**Phase 1 – core ledger:** auth + roles + permissions, audit trail with mandatory reasons, catalog + versioned menus + price history + compare, item-wise sales, quick daily totals + Owner variance approval, orders board (creates the sale on completion), expenses, suppliers & purchases, reports and data-health.

**Phase 2 – costing & inventory** (menu: *Stock & costing*): ingredients with pack specs and cost history; recipes with effective-dated versions and sub-recipes (prepared items); real COGS / gross profit with an explicit coverage banner (quick-entry days have no product detail, so no COGS); inventory ledger (append-only, corrections by reversal), purchase-to-ingredient linking, prep batches, stock counts with variance, wastage, opened containers with over-serving check, supplier ledger & payments, daily cash close with explained differences, payment-settlement reconciliation.

**Phase 3 – channels & documents:** Excel/CSV importer (upload → map columns → preview → commit → undo; re-imports of the same file are refused; unknown categories are never invented); bill / handwritten-record upload with OCR *drafts* (nothing is saved until staff confirm); WhatsApp ordering (webhook → parsed draft → staff confirm into an order; replies, order notifications, UPI payment-request text); in-app notifications; customer profiles.

**Phase 4 – compliance & people:** GST workpapers for QRMP quarters (GSTR-1 / GSTR-3B style figures, ITC only where marked eligible, CSV export, filings log); TDS/TCS register with thresholds and due dates; employees, attendance, advances, payroll runs and printable payslips; hygiene checklists and temperature logs (out-of-range needs a corrective action); compliance calendar and licence-expiry reminders.

> **Everything in Phase 4 is a workpaper/register for your CA.** Nothing is filed anywhere. Seeded rates, thresholds and due dates are defaults marked "confirm with your CA". The CGST/SGST 50:50 split and ITC month placement are stated assumptions.

### Extra setup for Phase 3
| Variable | Default | Meaning |
|---|---|---|
| `CAFE_STORAGE_DIR` | `./data/uploads` | where uploaded bills/photos are stored (must be writable; back it up) |
| `CAFE_OCR_ENABLED` | `true` | uses the `tesseract` command if installed; if not installed uploads still work and you type the data in |
| `CAFE_OCR_TESSERACT` / `CAFE_OCR_LANG` | `tesseract` / `eng` | OCR binary / language |
| `CAFE_WA_ENABLED` | `false` | `true` sends real WhatsApp messages; otherwise replies are only logged |
| `CAFE_WA_TOKEN`, `CAFE_WA_PHONE_ID` | – | Meta WhatsApp Cloud API token and phone-number ID |
| `CAFE_WA_VERIFY_TOKEN`, `CAFE_WA_APP_SECRET` | – | webhook verification string you choose; app secret (webhook calls are refused without a valid signature) |
| `CAFE_WA_UPI_VPA` | – | your UPI ID, used in payment-request messages |

WhatsApp setup: create a Meta app with the WhatsApp product, put the values above in the environment, set the callback URL to `https://<your-public-host>/api/v1/webhooks/whatsapp` (must be public HTTPS — use a tunnel such as Cloudflare Tunnel while testing) with your verify token, and subscribe to the `messages` field. Install Tesseract for OCR (Windows: UB-Mannheim installer, then add it to PATH; Linux: `apt install tesseract-ocr`). OCR of handwriting is weak — treat it as a typing aid.

### Roles added by Phases 2–4
New permissions are granted in the migrations (Owner everything; Manager operations; Accountant read-only on money data; Kitchen/Cashier minimal). Payroll is visible to Owner and Accountant only. Adjust under Settings → Users (roles) if you want different access.

## Dates and time zones
Timestamps are stored and transmitted as UTC (`timestamptz`; the JVM runs in UTC). The browser shows times in each user's own time zone, and "today" in the app is the user's local calendar date. Calendar dates such as a sale's date are plain dates chosen by the user. The business time zone (`cafe.timezone`, default Asia/Kolkata) is used only by the server for rules such as "no future dates" and the day-rollover (now midnight, migration V12).

## 9. Not built (by design)
Phase 5 (loyalty, promotions, multi-branch, bank / delivery-platform integrations, advanced analytics), payment gateway, offline-first mode, cookie-batch management (the requirements did not define it concretely), PF/ESI/professional-tax auto-calculation (enter as "other deductions"), automatic GST/TDS filing.

**Decisions to know about:** Spring JDBC with plain SQL instead of JPA; refresh token in an httpOnly cookie, access token in memory only; money is `numeric` in the DB and a string in the API; every financial change writes the audit log in the same transaction; history is never deleted — voids and reversals only.

## 10. Build note
The Spring code for Phases 1–4 was checked against the real database and with stub-based type checks, but never compiled under Maven (network restriction where it was written). If `mvn clean verify` reports a **test** failure, you can still build with `mvn clean verify -DskipTests` and send me the failing test name; for **compile** errors send the first ~30 lines.
