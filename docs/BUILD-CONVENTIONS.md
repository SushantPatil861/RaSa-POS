# Build conventions for Phase 2-4 modules (read fully before writing code)

Project root: /home/claude/cafe-pos  (backend/ = Spring Boot 3.5.6, Java 21, Maven; frontend/ = Vite+React+TS+Tailwind).
The Phase 0+1 code ALREADY EXISTS and RUNS on the owner's machine. Copy its patterns EXACTLY. Study before writing:
 - backend/src/main/java/com/cafepos/expenses/ExpenseService.java + ExpenseController.java   (canonical CRUD+void+audit module)
 - backend/src/main/java/com/cafepos/purchases/PurchaseService.java                          (header + child rows)
 - backend/src/main/java/com/cafepos/reporting/ReportService.java                            (read-only SQL reports)
 - backend/src/main/java/com/cafepos/platform/*  (ApiException, BusinessClock, SequenceService, StatusFlags, CafeProps, TaxMath)
 - backend/src/main/java/com/cafepos/audit/AuditService.java, security/CurrentUser.java
 - backend/src/main/resources/db/migration/V1..V7 (tables, triggers forbid_modification(), seed style, permission/role grant style in V5)
 - frontend/src/api.ts, types.ts, components/ui.tsx, components/Layout.tsx, pages/Expenses.tsx (canonical page)

## Principles (non-negotiable, from the owner's requirements)
Never fabricate or overwrite history. txn_date (when it happened) is separate from entry_at (when typed). Every figure that is
estimated/partial/imported/backdated/corrected must be labelled (StatusFlags). Void with reason instead of delete (no-delete triggers on
financial headers). Edits need a reason and bump revision/version. Every financial change writes audit_log in the SAME transaction
(AuditService.record). Effective-dated rules never edited in place - add a new row. Money = numeric in DB, BigDecimal in Java, String in JSON.
Indian formats (INR, dd-MMM-yyyy). Mobile-first UI. Unknown stays unknown (NULL / "not available"), never zero-filled.
business_id column (default 1, CafeProps.businessId()) on every business table.

## Backend rules
 - Package per module under com.cafepos.<module>: XxxService (@Service), XxxController (@RestController, @RequestMapping("/api/v1")).
 - Spring JDBC `JdbcClient` + Java `record`s mapped by `.query(Rec.class)` (SimplePropertyRowMapper: snake_case column -> camelCase component,
   so alias SQL columns to match record components). EVERY record that JdbcClient maps MUST be `public` (nested public records inside the service are fine).
   Record component types: String, BigDecimal, Long/long, Integer/int, boolean/Boolean, LocalDate, LocalTime, OffsetDateTime. Use boxed types for nullable columns.
 - Named params only (:name). For nullable params in comparisons write `cast(:x as date) is null or ...` (see ExpenseService.list). For
   inserts with possibly-null params it is fine to pass .param("x", null) ONLY when the column type is unambiguous in the statement; otherwise cast(:x as type).
 - jsonb: pass JSON text param and `cast(:p as jsonb)`; read with `to_jsonb(row)::text`.
 - @PreAuthorize("hasAuthority('perm.code')") on controller methods + CurrentUser.require("perm.code") inside services for writes.
 - @Transactional on every mutating service method (org.springframework.transaction.annotation.Transactional).
 - Errors: throw ApiException.notFound/badRequest/conflict/forbidden (messages are shown to users; make them plain English).
 - Dates in query params are plain LocalDate (iso configured globally). Never use @DateTimeFormat.
 - Validate enums with Set.of(...) checks that mirror the SQL CHECK constraints.
 - Do NOT use JPA, Lombok, MapStruct, or any new library unless your brief says so. Java 21 features OK (records, switch expressions, text blocks, var).
 - Imports: write every import explicitly; no wildcard imports except org.springframework.web.bind.annotation.*.
 - Do not modify files outside your ownership list. If you need a change in shared code (e.g. a helper), copy/inline it into your module instead.

## Migration rules
 - Your migration file(s) are named exactly as in your brief (Flyway, immutable once shipped). Reference ONLY tables from V1..V7 (and your own).
   Never edit V1..V7. Include your permissions + role grants in your own migration, following V5 style:
   `insert into permissions (code, description) values ...;` then `insert into role_permissions (role_id, permission_code) select r.id, 'x' from roles r where r.code in ('OWNER',...)`.
   (OWNER gets everything; MANAGER operational; ACCOUNTANT read-only on money data; CASHIER/KITCHEN minimal.) Verify the roles table column names in V1/V5.
 - Triggers: append-only / no-delete triggers using forbid_modification() like V3/V4 for financial tables.
 - Use `create table`, partial unique indexes, CHECK constraints for enums, `numeric(14,2)` money, `numeric(14,4)` quantities where needed.
 - Seed data (units, hygiene templates, compliance tasks etc.) goes in the same migration; label seed values clearly and keep them conservative.

## Frontend rules
 - Own files only: src/pages/<YourPhase...>.tsx and src/routes/<yourphase>.tsx. The routes file MUST export:
     export const navItems: { to: string; label: string; icon: string; perm?: string }[]
     export const routeElements: ReactNode   // a fragment of <Route .../> elements, paths relative (no leading slash)
   The integrator wires these into App.tsx/Layout.tsx. Do NOT edit App.tsx, Layout.tsx, types.ts, ui.tsx, api.ts, index.css, package.json.
   Put your TypeScript interfaces in your own src/types.<phase>.ts file.
 - Reuse: `get/post/put/patch/qs` from '../api', `useQuery/useMutation/useQueryClient` from '@tanstack/react-query', components from '../components/ui'
   (PageTitle, Chip, Chips, ErrorNote, Loading, Field, Modal, ReasonDialog, HistoryDrawer, DateRange, Stat), formatters from '../format' (inr, dmy, todayIso, monthStartIso, toNumber...), `useAuth().can(perm)`.
 - Tailwind classes already defined in index.css: card, btn, btn-primary, btn-ghost, btn-danger, input, label, th, td. Mobile-first, big touch targets.
 - Every list/form must show loading and error states; money via inr(); dates via dmy().

## Verification you MUST do (the Java cannot be compiled here because Maven Central is blocked, so be extra careful)
1. SQL: a PostgreSQL 16 server is running with unix socket in /tmp on port 5433 (user postgres, no password): `export PGHOST=/tmp PGPORT=5433 PGUSER=postgres`.
   Create YOUR OWN database (name given in your brief), apply V1..V7 with `psql -d <db> -q -1 -v ON_ERROR_STOP=1 -f <file>` (note -1: V6 needs a transaction), then your migration(s).
   Then insert realistic sample rows and run every non-trivial SQL statement you wrote in Java (copy the SQL, replace :params) to prove it executes and returns sensible numbers.
   If server is not up: `rm -f /tmp/pgdata/postmaster.pid` is NOT needed unless `pg_isready -h /tmp -p 5433` fails; ask nothing, just report.
2. Java syntax/cross-reference check: from backend/ run
     javac -proc:none -Xmaxerrs 10000 -d /tmp/chk_<phase> $(find src/main/java -name '*.java') 2>&1 | grep "error:" | grep -v "cannot find symbol\|does not exist"
   must print nothing except the two known supertypes errors in BootstrapRunner/JwtAuthFilter ("method does not override"). Then list `symbol:` lines:
     ... | grep -A3 "cannot find symbol" | grep "symbol:" | sort | uniq -c
   and confirm every missing symbol is a third-party Spring/Jakarta/JWT/Jackson/slf4j class or annotation - NEVER one of our own classes or methods.
   Pure-Java logic (calculators, parsers) must live in framework-free classes and be run standalone with javac+java and asserted in a throwaway main() (also write them as JUnit 5 + AssertJ tests under backend/src/test/java/...; JUnit/AssertJ come with spring-boot-starter-test).
3. Frontend: `cd frontend && npx tsc --noEmit` - fix every error in YOUR files (ignore errors in other phases' files that are still being written). Add vitest tests only for pure functions.
4. Re-read your own Java once, line by line, hunting: missing imports, record component names vs SQL aliases, wrong generic types, unhandled nulls, unclosed text blocks, calling methods that do not exist.

## Final reply format (keep under 400 words)
List: files created, endpoints (method path perm), permissions added, tables, what you verified and how, known limitations/assumptions, anything the integrator must do (e.g. pom dependency, application.yml keys).
