package com.cafepos.imports;

import com.cafepos.audit.AuditService;
import com.cafepos.ocr.FileSniffer;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.purchases.PurchaseService;
import com.cafepos.expenses.ExpenseService;
import com.cafepos.sales.DailySummaryService;
import com.cafepos.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Excel / CSV import wizard: upload -> map columns -> preview (nothing written) -> commit (existing services, one
 * transaction per batch) -> undo (void every created record with a reason). Nothing is deleted.
 */
@Service
public class ImportService {

    public static final int MAX_ROWS = 5000;
    private static final Set<String> FILE_TYPES = Set.of("csv", "txt", "xlsx", "xls");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Integer>> INT_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJ_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STR_MAP = new TypeReference<>() {};

    public record BatchDto(long id, String fileName, String targetType, String status, String statusReason,
                           int totalRows, int validRows, int errorRows, int duplicateRows, String createdByName,
                           OffsetDateTime createdAt, OffsetDateTime committedAt) {}

    public record BatchRow(long id, String fileName, String targetType, String status, String statusReason,
                           int totalRows, int validRows, int errorRows, int duplicateRows, String mappingJson,
                           String optionsJson, String createdByName, OffsetDateTime createdAt,
                           OffsetDateTime committedAt) {}

    public record SampleRow(int rowNo, List<String> cells) {}

    public record RawRow(int rowNo, String rawJson) {}

    public record RowRec(long id, int rowNo, String rawJson, String mappedJson, String status, String error,
                         String createdRecordType, Long createdRecordId) {}

    public record ExistingBatch(long id, String status) {}

    public record ExistingRow(LocalDate date, BigDecimal amount, String party, String ref) {}

    public record NameId(String name, long id) {}

    public record ImportDetail(BatchDto batch, List<String> headers, boolean hasHeader, List<SampleRow> sampleRows,
                               List<HeaderMatcher.FieldSpec> fields, Map<String, Integer> suggestedMapping,
                               String suggestedDateFormat, Map<String, Integer> mapping, String dateFormat,
                               List<String> dateFormats) {}

    public record MappingRequest(String targetType, Map<String, Integer> mapping, String dateFormat) {}

    public record RowDto(int rowNo, List<String> cells, Map<String, String> mapped, String status, String error,
                         String createdRecordType, Long createdRecordId) {}

    public record PreviewResult(BatchDto batch, List<RowDto> rows, boolean truncated, List<String> unknownCategories,
                                List<String> unknownSuppliers) {}

    public record CommitResult(BatchDto batch, int created, int skippedErrors, int skippedDuplicates,
                               List<String> unknownCategories, List<String> unknownSuppliers) {}

    private static final String BATCH_SELECT = """
            select b.id, b.file_name, b.target_type, b.status, b.status_reason, b.total_rows, b.valid_rows,
                   b.error_rows, b.duplicate_rows, b.mapping::text as mapping_json, b.options::text as options_json,
                   u.name as created_by_name, b.created_at, b.committed_at
            from import_batches b left join users u on u.id = b.created_by
            """;

    private static final String ROW_SELECT = """
            select id, row_no, raw::text as raw_json, mapped::text as mapped_json, status, error,
                   created_record_type, created_record_id
            from import_rows
            """;

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final ExpenseService expenses;
    private final DailySummaryService summaries;
    private final PurchaseService purchases;

    public ImportService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit, ObjectMapper mapper,
                         ExpenseService expenses, DailySummaryService summaries, PurchaseService purchases) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
        this.mapper = mapper;
        this.expenses = expenses;
        this.summaries = summaries;
        this.purchases = purchases;
    }

    // ------------------------------------------------------------------ list / get

    public List<BatchDto> list() {
        CurrentUser.require("imports.manage");
        return jdbc.sql(BATCH_SELECT + " where b.business_id = :b order by b.id desc limit 100")
                .param("b", props.businessId()).query(BatchRow.class).list().stream().map(this::dto).toList();
    }

    public ImportDetail detail(long id, String targetOverride) {
        CurrentUser.require("imports.manage");
        return buildDetail(batch(id), targetOverride);
    }

    public List<RowDto> rows(long id, String status) {
        CurrentUser.require("imports.manage");
        batch(id);
        return jdbc.sql(ROW_SELECT + """
                where batch_id = :id and (cast(:st as text) is null or status = :st) order by row_no limit 2000
                """).param("id", id).param("st", status == null || status.isBlank() ? null : status)
                .query(RowRec.class).list().stream().map(this::rowDto).toList();
    }

    // ------------------------------------------------------------------ 1. upload

    @Transactional
    public ImportDetail upload(String originalName, byte[] data, String targetType, boolean hasHeader) {
        CurrentUser.require("imports.manage");
        if (targetType == null || !HeaderMatcher.isTarget(targetType)) {
            throw ApiException.badRequest("Choose what you are importing: expenses, daily sales or purchases");
        }
        if (data == null || data.length == 0) throw ApiException.badRequest("The file is empty");
        String name = FileSniffer.sanitiseName(originalName);
        String ext = FileSniffer.extensionOf(name);
        if (!FILE_TYPES.contains(ext)) throw ApiException.badRequest("Only .xlsx, .xls and .csv files can be imported");
        String sha = FileSniffer.sha256(data);
        var existing = jdbc.sql("""
                select id, status from import_batches
                where business_id = :b and file_sha256 = :h and status not in ('REJECTED','UNDONE')
                """).param("b", props.businessId()).param("h", sha).query(ExistingBatch.class).optional();
        if (existing.isPresent()) {
            throw ApiException.conflict("This exact file was already uploaded as import #" + existing.get().id()
                    + " (" + existing.get().status().toLowerCase(Locale.ROOT) + "). Open it from the list, or reject / undo it first to import it again.");
        }

        List<List<String>> all = readTable(ext, data);
        int headerIdx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (!TabularParser.isBlank(all.get(i))) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) throw ApiException.badRequest("The file has no data");
        int firstData = hasHeader ? headerIdx + 1 : headerIdx;
        List<Integer> dataIdx = new ArrayList<>();
        for (int i = firstData; i < all.size(); i++) if (!TabularParser.isBlank(all.get(i))) dataIdx.add(i);
        if (dataIdx.isEmpty()) throw ApiException.badRequest("The file has a header but no data rows");
        if (dataIdx.size() > MAX_ROWS) {
            throw ApiException.badRequest("The file has " + dataIdx.size() + " rows; the limit is " + MAX_ROWS + ". Split it into smaller files.");
        }
        int width = hasHeader ? all.get(headerIdx).size() : 0;
        for (int i : dataIdx) width = Math.max(width, all.get(i).size());
        List<String> headers = new ArrayList<>();
        for (int c = 0; c < width; c++) {
            String h = hasHeader && c < all.get(headerIdx).size() ? all.get(headerIdx).get(c).trim() : "";
            headers.add(h.isEmpty() ? "Column " + columnLetter(c) : h);
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("hasHeader", hasHeader);
        options.put("headers", headers);
        long id;
        try {
            id = jdbc.sql("""
                    insert into import_batches (business_id, file_name, file_sha256, target_type, total_rows, options, created_by)
                    values (:b, :n, :h, :t, :tr, cast(:o as jsonb), :u) returning id
                    """).param("b", props.businessId()).param("n", name).param("h", sha).param("t", targetType)
                    .param("tr", dataIdx.size()).param("o", json(options)).param("u", CurrentUser.id())
                    .query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("This exact file was just uploaded by someone else. Open it from the list.");
        }
        for (int i : dataIdx) {
            jdbc.sql("insert into import_rows (batch_id, row_no, raw) values (:b, :r, cast(:raw as jsonb))")
                    .param("b", id).param("r", i + 1).param("raw", json(all.get(i))).update();
        }
        audit.record("IMPORT_BATCH", id, "UPLOAD", null, null,
                json(Map.of("fileName", name, "targetType", targetType, "rows", dataIdx.size())), null, "IMPORT");
        return buildDetail(batch(id), null);
    }

    private List<List<String>> readTable(String ext, byte[] data) {
        if (ext.equals("xlsx") || ext.equals("xls")) {
            try {
                return XlsxReader.read(data);
            } catch (IOException | RuntimeException e) {
                throw ApiException.badRequest("Could not read this spreadsheet (" + e.getMessage()
                        + "). Open it in Excel and save it again as .xlsx or .csv.");
            }
        }
        for (byte b : data) {
            if (b == 0) throw ApiException.badRequest("This does not look like a CSV text file");
        }
        return TabularParser.parseCsv(data);
    }

    // ------------------------------------------------------------------ 2. mapping

    @Transactional
    public ImportDetail saveMapping(long id, MappingRequest r) {
        CurrentUser.require("imports.manage");
        BatchRow b = batch(id);
        if (!Set.of("UPLOADED", "MAPPED", "PREVIEWED").contains(b.status())) {
            throw ApiException.conflict("This import is " + b.status().toLowerCase(Locale.ROOT) + " and can no longer be changed");
        }
        String target = r.targetType() == null || r.targetType().isBlank() ? b.targetType() : r.targetType();
        if (!HeaderMatcher.isTarget(target)) throw ApiException.badRequest("Unknown import type: " + target);
        String format = r.dateFormat() == null || r.dateFormat().isBlank() ? "dd/MM/yyyy" : r.dateFormat();
        if (!ImportDates.FORMATS.contains(format)) throw ApiException.badRequest("Unknown date format: " + format);

        int cols = headers(b).size();
        Set<String> keys = HeaderMatcher.fieldKeys(target);
        Map<String, Integer> mapping = new LinkedHashMap<>();
        if (r.mapping() != null) {
            for (Map.Entry<String, Integer> e : r.mapping().entrySet()) {
                if (e.getValue() == null) continue;
                if (!keys.contains(e.getKey())) throw ApiException.badRequest("Unknown field: " + e.getKey());
                if (e.getValue() < 0 || e.getValue() >= cols) throw ApiException.badRequest("Column " + (e.getValue() + 1) + " does not exist in this file");
                mapping.put(e.getKey(), e.getValue());
            }
        }
        for (HeaderMatcher.FieldSpec f : HeaderMatcher.fields(target)) {
            if (f.required() && !mapping.containsKey(f.key())) {
                throw ApiException.badRequest("Choose which column holds: " + f.label());
            }
        }
        Map<Integer, String> used = new HashMap<>();
        for (Map.Entry<String, Integer> e : mapping.entrySet()) {
            String other = used.put(e.getValue(), e.getKey());
            boolean allowed = other != null && Set.of("date", "invoiceDate").containsAll(Set.of(other, e.getKey()));
            if (other != null && !allowed) {
                throw ApiException.badRequest("One column cannot be used for two different fields (" + other + " and " + e.getKey() + ")");
            }
        }
        jdbc.sql("""
                update import_batches set target_type = :t, mapping = cast(:m as jsonb),
                       options = coalesce(options, '{}'::jsonb) || cast(:o as jsonb), status = 'MAPPED',
                       valid_rows = 0, error_rows = 0, duplicate_rows = 0
                where id = :id and business_id = :b
                """).param("t", target).param("m", json(mapping)).param("o", json(Map.of("dateFormat", format)))
                .param("id", id).param("b", props.businessId()).update();
        jdbc.sql("update import_rows set status = 'PENDING', mapped = null, error = null where batch_id = :id")
                .param("id", id).update();
        return buildDetail(batch(id), null);
    }

    // ------------------------------------------------------------------ 3. preview (writes nothing to business tables)

    @Transactional
    public PreviewResult preview(long id) {
        CurrentUser.require("imports.manage");
        BatchRow b = batch(id);
        if (!Set.of("MAPPED", "PREVIEWED").contains(b.status())) {
            throw ApiException.conflict(b.status().equals("UPLOADED")
                    ? "Choose the column mapping first"
                    : "This import is " + b.status().toLowerCase(Locale.ROOT) + " and cannot be previewed");
        }
        Evaluation ev = evaluate(b);
        List<RowRec> rows = jdbc.sql(ROW_SELECT + " where batch_id = :id order by row_no limit 1001")
                .param("id", id).query(RowRec.class).list();
        boolean truncated = rows.size() > 1000;
        List<RowDto> out = rows.stream().limit(1000).map(this::rowDto).toList();
        return new PreviewResult(dto(batch(id)), out, truncated, ev.unknownCategories, ev.unknownSuppliers);
    }

    private static final class Evaluation {
        final List<String> unknownCategories = new ArrayList<>();
        final List<String> unknownSuppliers = new ArrayList<>();
    }

    /** Validates every row again and stores the result on import_rows. Nothing else is written. */
    private Evaluation evaluate(BatchRow b) {
        Map<String, Integer> mapping = readInts(b.mappingJson());
        String format = String.valueOf(readObj(b.optionsJson()).getOrDefault("dateFormat", "dd/MM/yyyy"));
        LocalDate today = clock.today();
        LocalDate start = clock.dataStartDate();
        List<RowRec> rows = jdbc.sql(ROW_SELECT + " where batch_id = :id and status not in ('COMMITTED','UNDONE') order by row_no")
                .param("id", b.id()).query(RowRec.class).list();

        int n = rows.size();
        String[] status = new String[n];
        String[] error = new String[n];
        Map<String, String>[] mapped = newArray(n);
        Map<String, Integer> firstSeen = new HashMap<>();
        LocalDate min = null, max = null;
        for (int i = 0; i < n; i++) {
            RowRec r = rows.get(i);
            RowValidator.Result res = RowValidator.validate(b.targetType(), readList(r.rawJson()), mapping, format, start, today);
            mapped[i] = res.mapped();
            if (!res.ok()) {
                status[i] = "ERROR";
                error[i] = res.error();
                continue;
            }
            status[i] = "VALID";
            LocalDate d = LocalDate.parse(res.mapped().get("date"));
            if (min == null || d.isBefore(min)) min = d;
            if (max == null || d.isAfter(max)) max = d;
            String key = RowValidator.dedupeKey(b.targetType(), res.mapped());
            Integer earlier = firstSeen.putIfAbsent(key, r.rowNo());
            if (earlier != null) {
                status[i] = "DUPLICATE";
                error[i] = "Same as row " + earlier + " earlier in this file";
            }
        }
        if (min != null) {
            List<RowValidator.Existing> existing = loadExisting(b.targetType(), min, max);
            for (int i = 0; i < n; i++) {
                if (!"VALID".equals(status[i])) continue;
                String why = RowValidator.duplicateOf(b.targetType(), mapped[i], existing);
                if (why != null) {
                    status[i] = "DUPLICATE";
                    error[i] = why;
                }
            }
        }
        int valid = 0, err = 0, dup = 0;
        Evaluation ev = new Evaluation();
        Set<String> unknownCats = new LinkedHashSet<>(), unknownSups = new LinkedHashSet<>();
        Map<String, Long> cats = "EXPENSES".equals(b.targetType()) ? categoryIds() : Map.of();
        Map<String, Long> sups = "PURCHASES".equals(b.targetType()) ? supplierIds() : Map.of();
        for (int i = 0; i < n; i++) {
            switch (status[i]) {
                case "VALID" -> valid++;
                case "DUPLICATE" -> dup++;
                default -> err++;
            }
            if (!"ERROR".equals(status[i])) {
                String c = mapped[i].get("category");
                if (c != null && !cats.containsKey(c.toLowerCase(Locale.ROOT))) unknownCats.add(c);
                String s = mapped[i].get("supplier");
                if (s != null && !sups.containsKey(s.toLowerCase(Locale.ROOT))) unknownSups.add(s);
            }
            jdbc.sql("update import_rows set status = :s, error = :e, mapped = cast(:m as jsonb) where id = :id")
                    .param("s", status[i]).param("e", error[i]).param("m", json(mapped[i])).param("id", rows.get(i).id()).update();
        }
        jdbc.sql("""
                update import_batches set status = 'PREVIEWED', valid_rows = :v, error_rows = :e, duplicate_rows = :d
                where id = :id and business_id = :b
                """).param("v", valid).param("e", err).param("d", dup).param("id", b.id()).param("b", props.businessId()).update();
        ev.unknownCategories.addAll(unknownCats);
        ev.unknownSuppliers.addAll(unknownSups);
        return ev;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String>[] newArray(int n) {
        return (Map<String, String>[]) new Map[n];
    }

    private List<RowValidator.Existing> loadExisting(String target, LocalDate from, LocalDate to) {
        String sql = switch (target) {
            case "EXPENSES" -> """
                    select e.txn_date as date, e.amount as amount, coalesce(e.paid_to, '') as party, coalesce(c.name, '') as ref
                    from expenses e left join expense_categories c on c.id = e.category_id
                    where e.business_id = :b and e.void_state = 'ACTIVE' and e.txn_date between :f and :t
                    """;
            case "PURCHASES" -> """
                    select p.txn_date as date, p.total_amount as amount, coalesce(s.name, '') as party, coalesce(p.invoice_no, '') as ref
                    from purchases p left join suppliers s on s.id = p.supplier_id
                    where p.business_id = :b and p.void_state = 'ACTIVE' and p.txn_date between :f and :t
                    """;
            default -> """
                    select d.txn_date as date, d.total_sales as amount, '' as party, '' as ref
                    from daily_sales_summaries d
                    where d.business_id = :b and d.void_state = 'ACTIVE' and d.txn_date between :f and :t
                    """;
        };
        return jdbc.sql(sql).param("b", props.businessId()).param("f", from).param("t", to)
                .query(ExistingRow.class).list().stream()
                .map(e -> new RowValidator.Existing(e.date(), e.amount(), e.party(), e.ref())).toList();
    }

    // ------------------------------------------------------------------ 4. commit

    @Transactional
    public CommitResult commit(long id, boolean includeDuplicates) {
        CurrentUser.require("imports.manage");
        BatchRow b = batch(id);
        if (!"PREVIEWED".equals(b.status())) {
            throw ApiException.conflict("Preview the import first, then commit it (this one is " + b.status().toLowerCase(Locale.ROOT) + ")");
        }
        Evaluation ev = evaluate(b); // re-check: records may have been added since the preview
        boolean duplicatesAllowed = includeDuplicates && !"DAILY_SALES".equals(b.targetType());
        List<RowRec> todo = jdbc.sql(ROW_SELECT + """
                where batch_id = :id and (status = 'VALID' or (status = 'DUPLICATE' and :dup)) order by row_no
                """).param("id", id).param("dup", duplicatesAllowed).query(RowRec.class).list();
        if (todo.isEmpty()) throw ApiException.badRequest("There are no valid rows to import");
        checkPermissions(b.targetType(), todo);

        String source = b.fileName().toLowerCase(Locale.ROOT).endsWith(".csv") || b.fileName().toLowerCase(Locale.ROOT).endsWith(".txt") ? "CSV" : "EXCEL";
        Map<String, Long> cats = categoryIds();
        Map<String, Long> sups = supplierIds();
        int created = 0;
        for (RowRec r : todo) {
            Map<String, String> m = readStrings(r.mappedJson());
            String trace = "Import #" + id + " row " + r.rowNo();
            try {
                String type;
                long recordId;
                switch (b.targetType()) {
                    case "EXPENSES" -> {
                        type = "EXPENSE";
                        recordId = createExpense(m, cats, source, trace);
                    }
                    case "PURCHASES" -> {
                        type = "PURCHASE";
                        recordId = createPurchase(m, sups, source, trace);
                    }
                    default -> {
                        type = "DAILY_SUMMARY";
                        recordId = createDaily(m, source, id);
                    }
                }
                jdbc.sql("update import_rows set status = 'COMMITTED', created_record_type = :t, created_record_id = :rid where id = :id")
                        .param("t", type).param("rid", recordId).param("id", r.id()).update();
                created++;
            } catch (ApiException e) {
                throw new ApiException(e.status(), "Row " + r.rowNo() + ": " + e.getMessage() + " Nothing was imported.");
            }
        }
        jdbc.sql("""
                update import_batches set status = 'COMMITTED', committed_at = now(),
                       options = coalesce(options, '{}'::jsonb) || cast(:o as jsonb)
                where id = :id and business_id = :b
                """).param("o", json(Map.of("includeDuplicates", duplicatesAllowed))).param("id", id).param("b", props.businessId()).update();
        BatchRow done = batch(id);
        audit.record("IMPORT_BATCH", id, "COMMIT", null, null,
                json(Map.of("created", created, "target", b.targetType(), "file", b.fileName())), null, source);
        return new CommitResult(dto(done), created, done.errorRows(),
                duplicatesAllowed ? 0 : done.duplicateRows(), ev.unknownCategories, ev.unknownSuppliers);
    }

    private void need(String permission, String what) {
        if (!CurrentUser.has(permission)) {
            throw ApiException.forbidden("Importing " + what + " also needs the '" + permission + "' permission, which your role does not have");
        }
    }

    private void checkPermissions(String target, List<RowRec> rows) {
        switch (target) {
            case "EXPENSES" -> need("expenses.create", "expenses");
            case "PURCHASES" -> need("purchases.create", "purchases");
            default -> {
                need("sales.create", "daily sales");
                LocalDate today = clock.today();
                for (RowRec r : rows) {
                    String d = readStrings(r.mappedJson()).get("date");
                    if (d != null && LocalDate.parse(d).isBefore(today)) {
                        need("sales.backdate", "daily sales for past dates");
                        break;
                    }
                }
            }
        }
    }

    private long createExpense(Map<String, String> m, Map<String, Long> cats, String source, String trace) {
        String catText = m.get("category");
        Long catId = catText == null ? null : cats.get(catText.toLowerCase(Locale.ROOT));
        List<String> notes = new ArrayList<>();
        if (m.get("notes") != null) notes.add(m.get("notes"));
        if (catText != null && catId == null) notes.add("Category in file: " + catText);
        notes.add(trace);
        ExpenseService.ExpenseRequest req = new ExpenseService.ExpenseRequest(
                LocalDate.parse(m.get("date")), catId, new BigDecimal(m.get("amount")), m.get("method"), m.get("paidTo"),
                false, String.join(" | ", notes), source, false, false, null, null, null);
        return expenses.create(req).id();
    }

    private long createPurchase(Map<String, String> m, Map<String, Long> sups, String source, String trace) {
        String supText = m.get("supplier");
        Long supId = supText == null ? null : sups.get(supText.toLowerCase(Locale.ROOT));
        List<String> notes = new ArrayList<>();
        if (m.get("notes") != null) notes.add(m.get("notes"));
        if (supText != null && supId == null) notes.add("Supplier in file: " + supText);
        notes.add(trace);
        LocalDate invDate = m.get("invoiceDate") == null ? null : LocalDate.parse(m.get("invoiceDate"));
        PurchaseService.PurchaseRequest req = new PurchaseService.PurchaseRequest(
                LocalDate.parse(m.get("date")), supId, m.get("invoiceNo"), invDate, m.get("paymentStatus"),
                new BigDecimal(m.get("amount")), String.join(" | ", notes), source, false, false, null, null, null);
        return purchases.create(req).id();
    }

    private long createDaily(Map<String, String> m, String source, long batchId) {
        DailySummaryService.SummaryRequest req = new DailySummaryService.SummaryRequest(
                LocalDate.parse(m.get("date")), new BigDecimal(m.get("totalSales")),
                dec(m, "cash"), dec(m, "upi"), dec(m, "card"), dec(m, "whatsapp"), dec(m, "swiggy"), dec(m, "zomato"),
                null, m.get("ordersCount") == null ? null : Integer.valueOf(m.get("ordersCount")),
                dec(m, "discounts"), dec(m, "refunds"), null, null, m.get("notes"),
                "Imported from batch #" + batchId, null, null);
        long id = summaries.create(req).summary().id();
        // The daily-entry service has no "source" input; label the record so StatusFlags shows IMPORTED.
        jdbc.sql("update daily_sales_summaries set source = :s where id = :id and business_id = :b")
                .param("s", source).param("id", id).param("b", props.businessId()).update();
        audit.record("DAILY_SUMMARY", id, "SOURCE", LocalDate.parse(m.get("date")), null,
                json(Map.of("source", source, "importBatch", batchId)), "Imported from batch #" + batchId, source);
        return id;
    }

    private static BigDecimal dec(Map<String, String> m, String k) {
        return m.get(k) == null ? null : new BigDecimal(m.get(k));
    }

    // ------------------------------------------------------------------ 5. undo / reject

    @Transactional
    public BatchDto undo(long id, String reason) {
        CurrentUser.require("imports.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to undo an import");
        BatchRow b = batch(id);
        if (!"COMMITTED".equals(b.status())) throw ApiException.conflict("Only a committed import can be undone (this one is " + b.status().toLowerCase(Locale.ROOT) + ")");
        switch (b.targetType()) {
            case "EXPENSES" -> need("expenses.edit", "undo of expenses");
            case "PURCHASES" -> need("purchases.edit", "undo of purchases");
            default -> need("sales.void", "undo of daily sales");
        }
        String why = "Import undone: batch #" + id + " (" + reason.trim() + ")";
        List<RowRec> rows = jdbc.sql(ROW_SELECT + " where batch_id = :id and status = 'COMMITTED' order by row_no")
                .param("id", id).query(RowRec.class).list();
        int voided = 0;
        for (RowRec r : rows) {
            long rid = r.createdRecordId();
            // Check first: a failed service call inside this transaction would poison it.
            String table = switch (b.targetType()) {
                case "EXPENSES" -> "expenses";
                case "PURCHASES" -> "purchases";
                default -> "daily_sales_summaries";
            };
            String state = jdbc.sql("select void_state from " + table + " where id = :id").param("id", rid)
                    .query(String.class).optional().orElse("VOIDED");
            if ("ACTIVE".equals(state)) {
                switch (b.targetType()) {
                    case "EXPENSES" -> expenses.voidExpense(rid, why);
                    case "PURCHASES" -> purchases.voidPurchase(rid, why);
                    default -> summaries.voidEntry(rid, why);
                }
                voided++;
            }
            jdbc.sql("update import_rows set status = 'UNDONE' where id = :id").param("id", r.id()).update();
        }
        jdbc.sql("update import_batches set status = 'UNDONE', status_reason = :r where id = :id and business_id = :b")
                .param("r", reason.trim()).param("id", id).param("b", props.businessId()).update();
        audit.record("IMPORT_BATCH", id, "UNDO", null, null, json(Map.of("voided", voided, "rows", rows.size())), reason.trim(), "IMPORT");
        return dto(batch(id));
    }

    @Transactional
    public BatchDto reject(long id, String reason) {
        CurrentUser.require("imports.manage");
        BatchRow b = batch(id);
        if (!Set.of("UPLOADED", "MAPPED", "PREVIEWED").contains(b.status())) {
            throw ApiException.conflict("Only an import that has not been committed can be rejected (this one is " + b.status().toLowerCase(Locale.ROOT) + ")");
        }
        jdbc.sql("update import_batches set status = 'REJECTED', status_reason = :r where id = :id and business_id = :b")
                .param("r", reason == null || reason.isBlank() ? "Discarded" : reason.trim()).param("id", id)
                .param("b", props.businessId()).update();
        audit.record("IMPORT_BATCH", id, "REJECT", null, null, null, reason, "IMPORT");
        return dto(batch(id));
    }

    // ------------------------------------------------------------------ internals

    private BatchRow batch(long id) {
        return jdbc.sql(BATCH_SELECT + " where b.id = :id and b.business_id = :b").param("id", id)
                .param("b", props.businessId()).query(BatchRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Import"));
    }

    private BatchDto dto(BatchRow b) {
        return new BatchDto(b.id(), b.fileName(), b.targetType(), b.status(), b.statusReason(), b.totalRows(),
                b.validRows(), b.errorRows(), b.duplicateRows(), b.createdByName(), b.createdAt(), b.committedAt());
    }

    private List<String> headers(BatchRow b) {
        Object h = readObj(b.optionsJson()).get("headers");
        List<String> out = new ArrayList<>();
        if (h instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
        return out;
    }

    private ImportDetail buildDetail(BatchRow b, String targetOverride) {
        String target = targetOverride == null || targetOverride.isBlank() ? b.targetType() : targetOverride;
        if (!HeaderMatcher.isTarget(target)) throw ApiException.badRequest("Unknown import type: " + target);
        Map<String, Object> opts = readObj(b.optionsJson());
        List<String> headers = headers(b);
        Map<String, Integer> suggested = HeaderMatcher.suggest(target, headers);
        Map<String, Integer> saved = readInts(b.mappingJson());
        List<RawRow> raw = jdbc.sql("select row_no, raw::text as raw_json from import_rows where batch_id = :id order by row_no limit 200")
                .param("id", b.id()).query(RawRow.class).list();
        List<SampleRow> sample = raw.stream().limit(20).map(r -> new SampleRow(r.rowNo(), readList(r.rawJson()))).toList();
        Integer dateCol = saved.containsKey("date") ? saved.get("date") : suggested.get("date");
        String suggestedFormat = "dd/MM/yyyy";
        if (dateCol != null) {
            List<String> values = new ArrayList<>();
            for (RawRow r : raw) {
                List<String> cells = readList(r.rawJson());
                if (dateCol < cells.size()) values.add(cells.get(dateCol));
            }
            suggestedFormat = ImportDates.detect(values);
        }
        String format = opts.get("dateFormat") == null ? suggestedFormat : String.valueOf(opts.get("dateFormat"));
        return new ImportDetail(dto(b), headers, !Boolean.FALSE.equals(opts.get("hasHeader")), sample,
                HeaderMatcher.fields(target), suggested, suggestedFormat, saved, format, ImportDates.FORMATS);
    }

    private Map<String, Long> categoryIds() {
        Map<String, Long> m = new HashMap<>();
        for (NameId n : jdbc.sql("select name, id from expense_categories where business_id = :b and active")
                .param("b", props.businessId()).query(NameId.class).list()) {
            m.put(n.name().toLowerCase(Locale.ROOT).trim(), n.id());
        }
        return m;
    }

    private Map<String, Long> supplierIds() {
        Map<String, Long> m = new HashMap<>();
        for (NameId n : jdbc.sql("select name, id from suppliers where business_id = :b and active")
                .param("b", props.businessId()).query(NameId.class).list()) {
            m.put(n.name().toLowerCase(Locale.ROOT).trim(), n.id());
        }
        return m;
    }

    private RowDto rowDto(RowRec r) {
        return new RowDto(r.rowNo(), readList(r.rawJson()), r.mappedJson() == null ? Map.of() : readStrings(r.mappedJson()),
                r.status(), r.error(), r.createdRecordType(), r.createdRecordId());
    }

    private static String columnLetter(int c) {
        StringBuilder sb = new StringBuilder();
        int n = c;
        do {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> readList(String s) {
        try {
            return s == null ? List.of() : mapper.readValue(s, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Integer> readInts(String s) {
        try {
            return s == null ? new LinkedHashMap<>() : mapper.readValue(s, INT_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> readObj(String s) {
        try {
            return s == null ? new LinkedHashMap<>() : mapper.readValue(s, OBJ_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> readStrings(String s) {
        try {
            return s == null ? new LinkedHashMap<>() : mapper.readValue(s, STR_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
