package com.cafepos.documents;

import com.cafepos.audit.AuditService;
import com.cafepos.expenses.ExpenseService;
import com.cafepos.notifications.NotificationService;
import com.cafepos.ocr.BillTextParser;
import com.cafepos.ocr.DraftReview;
import com.cafepos.ocr.FileSniffer;
import com.cafepos.ocr.OcrProvider;
import com.cafepos.ocr.OcrResult;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.CafeProps;
import com.cafepos.purchases.PurchaseService;
import com.cafepos.sales.DailySummaryService;
import com.cafepos.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Uploaded bills / receipts / handwritten records. Files live on local disk (named by their SHA-256 + a type chosen from
 * the file's real contents). OCR only ever produces a DRAFT; a person reviews the image next to an editable form and
 * CONFIRM then calls the normal expense / purchase / daily-entry services with source OCR.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final Set<String> DOC_TYPES = Set.of("RECEIPT", "BILL", "HANDWRITTEN", "OTHER");
    private static final Set<String> RECORD_TYPES = Set.of("EXPENSE", "PURCHASE", "DAILY_SALES");
    private static final long MAX_BYTES = 15L * 1024 * 1024;
    private static final TypeReference<Map<String, Object>> OBJ_MAP = new TypeReference<>() {};

    public record DocRow(long id, String originalName, String contentType, long sizeBytes, String storagePath,
                         String docType, String status, String statusReason, String uploadedByName,
                         OffsetDateTime uploadedAt, String entityType, Long entityId) {}

    public record DocumentDto(long id, String originalName, String contentType, long sizeBytes, String docType,
                              String status, String statusReason, String uploadedByName, OffsetDateTime uploadedAt,
                              String entityType, Long entityId, boolean image) {}

    public record DraftRow(long id, String proposedType, String proposedJson, String status,
                           String confirmedRecordType, Long confirmedRecordId, OffsetDateTime confirmedAt,
                           String rejectReason) {}

    public record DraftDto(long id, String proposedType, Map<String, Object> proposed, String status,
                           String confirmedRecordType, Long confirmedRecordId, OffsetDateTime confirmedAt,
                           String rejectReason) {}

    public record ExtractionDto(String engine, String rawText, BigDecimal avgConfidence, OffsetDateTime createdAt) {}

    public record DocumentDetail(DocumentDto document, DraftDto draft, ExtractionDto extraction) {}

    public record FileData(byte[] bytes, String contentType, String name) {}

    public record ConfirmItem(String itemName, BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal total) {}

    public record ConfirmRequest(String type, LocalDate txnDate, BigDecimal totalAmount, Long categoryId,
                                 String paymentMethod, String paidTo, String notes, Long supplierId,
                                 String supplierName, String invoiceNo, LocalDate invoiceDate, String gstin,
                                 String paymentStatus, List<ConfirmItem> items, BigDecimal cash, BigDecimal upi,
                                 BigDecimal card, BigDecimal whatsapp, BigDecimal swiggy, BigDecimal zomato,
                                 Integer ordersCount, BigDecimal discounts, BigDecimal refunds, Boolean estimated) {}

    public record IdRow(long id, String status) {}

    private static final String DOC_SELECT = """
            select d.id, d.original_name, d.content_type, d.size_bytes, d.storage_path, d.doc_type, d.status,
                   d.status_reason, u.name as uploaded_by_name, d.uploaded_at, d.entity_type, d.entity_id
            from uploaded_documents d left join users u on u.id = d.uploaded_by
            """;

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final OcrProvider ocr;
    private final NotificationService notifications;
    private final ExpenseService expenses;
    private final PurchaseService purchases;
    private final DailySummaryService summaries;
    private final String storageDir;

    public DocumentService(JdbcClient jdbc, CafeProps props, AuditService audit, ObjectMapper mapper, OcrProvider ocr,
                           NotificationService notifications, ExpenseService expenses, PurchaseService purchases,
                           DailySummaryService summaries, @Value("${cafe.storage.dir:./data/uploads}") String storageDir) {
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
        this.mapper = mapper;
        this.ocr = ocr;
        this.notifications = notifications;
        this.expenses = expenses;
        this.purchases = purchases;
        this.summaries = summaries;
        this.storageDir = storageDir;
    }

    // ------------------------------------------------------------------ read

    public List<DocumentDto> list(String status, String docType, int limit) {
        CurrentUser.require("documents.view");
        return jdbc.sql(DOC_SELECT + """
                where d.business_id = :b
                  and (cast(:st as text) is null or d.status = :st)
                  and (cast(:dt as text) is null or d.doc_type = :dt)
                order by d.id desc limit :lim
                """).param("b", props.businessId()).param("st", blankToNull(status)).param("dt", blankToNull(docType))
                .param("lim", Math.min(Math.max(limit, 1), 500)).query(DocRow.class).list().stream().map(this::dto).toList();
    }

    public DocumentDetail get(long id) {
        CurrentUser.require("documents.view");
        return detail(row(id));
    }

    public FileData file(long id) {
        CurrentUser.require("documents.view");
        DocRow d = row(id);
        Path dir = dir();
        Path p = dir.resolve(d.storagePath()).normalize();
        if (!p.startsWith(dir) || !Files.isRegularFile(p)) throw ApiException.notFound("The stored file for this document");
        try {
            return new FileData(Files.readAllBytes(p), d.contentType(), d.originalName());
        } catch (IOException e) {
            throw ApiException.notFound("The stored file for this document");
        }
    }

    // ------------------------------------------------------------------ upload (+ OCR, outside any DB transaction)

    public DocumentDetail upload(String originalName, byte[] data, String docType) {
        CurrentUser.require("documents.upload");
        String type = docType == null || docType.isBlank() ? "OTHER" : docType.toUpperCase(Locale.ROOT);
        if (!DOC_TYPES.contains(type)) throw ApiException.badRequest("Unknown document type: " + docType);
        if (data == null || data.length == 0) throw ApiException.badRequest("The file is empty");
        if (data.length > MAX_BYTES) throw ApiException.badRequest("The file is larger than 15 MB");
        FileSniffer.Detected det = FileSniffer.sniff(data);
        if (det == null) {
            throw ApiException.badRequest("Only JPG, PNG, WEBP or PDF files are accepted (the contents of this file are not one of those)");
        }
        String sha = FileSniffer.sha256(data);
        var existing = jdbc.sql("select id, status from uploaded_documents where business_id = :b and sha256 = :h")
                .param("b", props.businessId()).param("h", sha).query(IdRow.class).optional();
        if (existing.isPresent()) {
            throw ApiException.conflict("This exact file was already uploaded as document #" + existing.get().id()
                    + " (" + existing.get().status().toLowerCase(Locale.ROOT) + ")");
        }
        String stored = sha + "." + det.extension();
        Path dir = dir();
        Path target = dir.resolve(stored).normalize();
        if (!target.startsWith(dir)) throw ApiException.badRequest("Invalid file name");
        try {
            Files.createDirectories(dir);
            if (!Files.exists(target)) {
                Path tmp = Files.createTempFile(dir, "upload-", ".tmp");
                Files.write(tmp, data);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Could not store uploaded document", e);
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "The file could not be saved on the server");
        }
        long id;
        try {
            id = jdbc.sql("""
                    insert into uploaded_documents (business_id, original_name, content_type, size_bytes, sha256,
                                                    storage_path, doc_type, uploaded_by)
                    values (:b, :n, :ct, :sz, :h, :sp, :dt, :u) returning id
                    """).param("b", props.businessId()).param("n", FileSniffer.sanitiseName(originalName))
                    .param("ct", det.contentType()).param("sz", (long) data.length).param("h", sha).param("sp", stored)
                    .param("dt", type).param("u", CurrentUser.id()).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("This exact file was just uploaded by someone else");
        }
        process(id, target, det.image(), type);
        notifications.publish("DOCUMENT_REVIEW", "New document to review",
                FileSniffer.sanitiseName(originalName) + " (" + type.toLowerCase(Locale.ROOT) + ")", "/documents/" + id,
                "documents.confirm");
        return detail(row(id));
    }

    /** Re-runs OCR and rebuilds the pending draft (e.g. after Tesseract was installed). Never touches business records. */
    public DocumentDetail reprocess(long id) {
        CurrentUser.require("documents.confirm");
        DocRow d = row(id);
        if (Set.of("CONFIRMED", "REJECTED").contains(d.status())) {
            throw ApiException.conflict("This document is already " + d.status().toLowerCase(Locale.ROOT));
        }
        Path p = dir().resolve(d.storagePath()).normalize();
        process(id, p, d.contentType().startsWith("image/"), d.docType());
        return detail(row(id));
    }

    private void process(long id, Path file, boolean image, String docType) {
        String notice = null;
        BillTextParser.Parsed parsed = null;
        String status = "UPLOADED";
        if (!image) {
            notice = "PDF files are not read automatically. Type the details in while looking at the document (needs manual entry).";
        } else {
            OcrResult res = ocr.extract(file, docType);
            jdbc.sql("insert into ocr_extractions (document_id, engine, raw_text, avg_confidence) values (:d, :e, :t, :c)")
                    .param("d", id).param("e", res.engine()).param("t", res.text()).param("c", res.avgConfidence()).update();
            if (!res.available()) {
                notice = res.message() + ". Type the details in while looking at the image (needs manual entry).";
            } else if (res.text().isBlank()) {
                notice = "No text could be read from this image. Type the details in by hand.";
                status = "PROCESSED";
            } else if ("HANDWRITTEN".equals(docType)) {
                notice = "Handwritten records are best typed in by hand; the text the OCR guessed is shown only as a hint.";
                status = "PROCESSED";
            } else {
                parsed = BillTextParser.parse(res.text());
                status = "DRAFTED";
            }
        }
        String proposedType = switch (docType) {
            case "BILL" -> "PURCHASE";
            case "HANDWRITTEN" -> "DAILY_SALES";
            default -> "EXPENSE";
        };
        String proposal = json(proposal(proposedType, parsed, notice));
        var draftId = jdbc.sql("select id from document_drafts where document_id = :d and status = 'PENDING' order by id desc limit 1")
                .param("d", id).query(Long.class).optional();
        if (draftId.isPresent()) {
            jdbc.sql("update document_drafts set proposed_type = :t, proposed = cast(:p as jsonb) where id = :id")
                    .param("t", proposedType).param("p", proposal).param("id", draftId.get()).update();
        } else {
            jdbc.sql("insert into document_drafts (document_id, proposed_type, proposed) values (:d, :t, cast(:p as jsonb))")
                    .param("d", id).param("t", proposedType).param("p", proposal).update();
        }
        jdbc.sql("update uploaded_documents set status = :s where id = :id and status in ('UPLOADED','PROCESSED','DRAFTED')")
                .param("s", status).param("id", id).update();
    }

    /** Proposal JSON: fields + per-field confidence + items. Empty (manual entry) when nothing was parsed. */
    static Map<String, Object> proposal(String type, BillTextParser.Parsed p, String notice) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> conf = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        if (p != null) {
            if (p.supplierName() != null) {
                fields.put("supplierName", p.supplierName().value());
                conf.put("supplierName", p.supplierName().confidence());
            }
            if (p.invoiceNo() != null) {
                fields.put("invoiceNo", p.invoiceNo().value());
                conf.put("invoiceNo", p.invoiceNo().confidence());
            }
            if (p.invoiceDate() != null) {
                fields.put("invoiceDate", p.invoiceDate().value().toString());
                conf.put("invoiceDate", p.invoiceDate().confidence());
                fields.put("txnDate", p.invoiceDate().value().toString());
                conf.put("txnDate", p.invoiceDate().confidence());
            }
            if (p.gstin() != null) {
                fields.put("gstin", p.gstin().value());
                conf.put("gstin", p.gstin().confidence());
            }
            if (p.total() != null) {
                fields.put("totalAmount", p.total().value().toPlainString());
                conf.put("totalAmount", p.total().confidence());
            }
            for (BillTextParser.Item it : p.items()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("itemName", it.name());
                m.put("quantity", it.quantity() == null ? null : it.quantity().toPlainString());
                m.put("unitPrice", it.unitPrice() == null ? null : it.unitPrice().toPlainString());
                m.put("total", it.amount() == null ? null : it.amount().toPlainString());
                m.put("confidence", it.confidence());
                items.add(m);
            }
        }
        out.put("type", type);
        out.put("needsManualEntry", p == null || fields.isEmpty());
        out.put("notice", notice);
        out.put("fields", fields);
        out.put("confidence", conf);
        out.put("items", items);
        return out;
    }

    // ------------------------------------------------------------------ confirm / reject

    @Transactional
    public DocumentDetail confirm(long id, ConfirmRequest r) {
        CurrentUser.require("documents.confirm");
        DocRow d = row(id);
        if (Set.of("CONFIRMED", "REJECTED").contains(d.status())) {
            throw ApiException.conflict("This document is already " + d.status().toLowerCase(Locale.ROOT));
        }
        if (r.type() == null || !RECORD_TYPES.contains(r.type())) throw ApiException.badRequest("Choose what this document records: expense, purchase or daily sales");
        if (r.txnDate() == null) throw ApiException.badRequest("Enter the date of the transaction");
        if (r.totalAmount() == null) throw ApiException.badRequest("Enter the total amount");
        DraftRow draft = pendingDraft(d);
        Map<String, Object> proposed = draft.proposedJson() == null ? Map.of() : read(draft.proposedJson());
        Map<String, String> pf = stringMap(proposed.get("fields"));
        Map<String, String> pc = stringMap(proposed.get("confidence"));
        Map<String, String> submitted = new HashMap<>();
        submitted.put("totalAmount", r.totalAmount().toPlainString());
        submitted.put("txnDate", r.txnDate().toString());
        if (r.supplierName() != null) submitted.put("supplierName", r.supplierName());
        boolean estimated = Boolean.TRUE.equals(r.estimated()) || DraftReview.anyLowUnedited(pf, pc, submitted);

        List<String> notes = new ArrayList<>();
        if (r.notes() != null && !r.notes().isBlank()) notes.add(r.notes().trim());
        if (r.gstin() != null && !r.gstin().isBlank()) notes.add("GSTIN on bill: " + r.gstin().trim());
        notes.add("From document #" + id + " (" + d.originalName() + ")");

        String recordType;
        long recordId;
        switch (r.type()) {
            case "EXPENSE" -> {
                recordType = "EXPENSE";
                String payee = firstNonBlank(r.paidTo(), r.supplierName());
                recordId = expenses.create(new ExpenseService.ExpenseRequest(r.txnDate(), r.categoryId(), r.totalAmount(),
                        blankToNull(r.paymentMethod()), payee, false, String.join(" | ", notes), "OCR", estimated, false,
                        null, null, null)).id();
            }
            case "PURCHASE" -> {
                recordType = "PURCHASE";
                Long supplierId = r.supplierId();
                if (supplierId == null && r.supplierName() != null && !r.supplierName().isBlank()) {
                    supplierId = jdbc.sql("select id from suppliers where business_id = :b and lower(name) = lower(:n) and active")
                            .param("b", props.businessId()).param("n", r.supplierName().trim()).query(Long.class).optional().orElse(null);
                    if (supplierId == null) notes.add(0, "Supplier on bill: " + r.supplierName().trim());
                }
                recordId = purchases.create(new PurchaseService.PurchaseRequest(r.txnDate(), supplierId,
                        blankToNull(r.invoiceNo()), r.invoiceDate(), blankToNull(r.paymentStatus()), r.totalAmount(),
                        String.join(" | ", notes), "OCR", estimated, false, purchaseItems(r.items()), null, null)).id();
            }
            default -> {
                recordType = "DAILY_SUMMARY";
                recordId = summaries.create(new DailySummaryService.SummaryRequest(r.txnDate(), r.totalAmount(), r.cash(),
                        r.upi(), r.card(), r.whatsapp(), r.swiggy(), r.zomato(), null, r.ordersCount(), r.discounts(),
                        r.refunds(), null, null, String.join(" | ", notes), "Confirmed from scanned document #" + id,
                        null, null)).summary().id();
                // the daily-entry service has no "source" input; label the record so it shows as OCR-sourced
                jdbc.sql("update daily_sales_summaries set source = 'OCR', is_estimated = :e where id = :id and business_id = :b")
                        .param("e", estimated).param("id", recordId).param("b", props.businessId()).update();
                audit.record("DAILY_SUMMARY", recordId, "SOURCE", r.txnDate(), null, "{\"source\":\"OCR\",\"document\":" + id + "}",
                        "Confirmed from scanned document #" + id, "OCR");
            }
        }
        jdbc.sql("""
                update uploaded_documents set status = 'CONFIRMED', entity_type = :t, entity_id = :eid
                where id = :id and business_id = :b
                """).param("t", recordType).param("eid", recordId).param("id", id).param("b", props.businessId()).update();
        jdbc.sql("""
                update document_drafts set status = 'CONFIRMED', confirmed_record_type = :t, confirmed_record_id = :eid,
                       confirmed_by = :u, confirmed_at = now() where id = :id
                """).param("t", recordType).param("eid", recordId).param("u", CurrentUser.id()).param("id", draft.id()).update();
        audit.record("DOCUMENT", id, "CONFIRM", r.txnDate(), draft.proposedJson(),
                jdbc.sql("select to_jsonb(d)::text from uploaded_documents d where d.id = :id").param("id", id)
                        .query(String.class).single(), null, "OCR");
        return detail(row(id));
    }

    @Transactional
    public DocumentDetail reject(long id, String reason) {
        CurrentUser.require("documents.confirm");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to reject a document");
        DocRow d = row(id);
        if (Set.of("CONFIRMED", "REJECTED").contains(d.status())) {
            throw ApiException.conflict("This document is already " + d.status().toLowerCase(Locale.ROOT));
        }
        jdbc.sql("update uploaded_documents set status = 'REJECTED', status_reason = :r where id = :id and business_id = :b")
                .param("r", reason.trim()).param("id", id).param("b", props.businessId()).update();
        jdbc.sql("update document_drafts set status = 'REJECTED', reject_reason = :r where document_id = :id and status = 'PENDING'")
                .param("r", reason.trim()).param("id", id).update();
        audit.record("DOCUMENT", id, "REJECT", null, null, null, reason.trim(), "OCR");
        return detail(row(id));
    }

    // ------------------------------------------------------------------ internals

    private List<PurchaseService.ItemRequest> purchaseItems(List<ConfirmItem> items) {
        List<PurchaseService.ItemRequest> out = new ArrayList<>();
        if (items == null) return out;
        int n = 0;
        for (ConfirmItem i : items) {
            n++;
            if (i.itemName() == null || i.itemName().isBlank()) continue; // an empty line on the form is simply dropped
            if (i.quantity() == null) throw ApiException.badRequest("Enter a quantity for line " + n + " (" + i.itemName().trim() + ") or remove the line");
            BigDecimal total = i.total();
            if (total == null && i.unitPrice() != null) total = i.quantity().multiply(i.unitPrice());
            if (total == null) throw ApiException.badRequest("Enter an amount for line " + n + " (" + i.itemName().trim() + ") or remove the line");
            out.add(new PurchaseService.ItemRequest(i.itemName().trim(), i.quantity(), blankToNull(i.unit()), i.unitPrice(), total, null));
        }
        return out;
    }

    private DocRow row(long id) {
        return jdbc.sql(DOC_SELECT + " where d.id = :id and d.business_id = :b").param("id", id)
                .param("b", props.businessId()).query(DocRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Document"));
    }

    private DraftRow pendingDraft(DocRow d) {
        var existing = latestDraft(d.id());
        if (existing != null && "PENDING".equals(existing.status())) return existing;
        long id = jdbc.sql("""
                insert into document_drafts (document_id, proposed_type, proposed)
                values (:d, 'EXPENSE', cast(:p as jsonb)) returning id
                """).param("d", d.id()).param("p", json(proposal("EXPENSE", null, null))).query(Long.class).single();
        return new DraftRow(id, "EXPENSE", json(proposal("EXPENSE", null, null)), "PENDING", null, null, null, null);
    }

    private DraftRow latestDraft(long documentId) {
        return jdbc.sql("""
                select id, proposed_type, proposed::text as proposed_json, status, confirmed_record_type,
                       confirmed_record_id, confirmed_at, reject_reason
                from document_drafts where document_id = :d order by id desc limit 1
                """).param("d", documentId).query(DraftRow.class).optional().orElse(null);
    }

    private DocumentDetail detail(DocRow d) {
        DraftRow dr = latestDraft(d.id());
        DraftDto draft = dr == null ? null : new DraftDto(dr.id(), dr.proposedType(),
                dr.proposedJson() == null ? Map.of() : read(dr.proposedJson()), dr.status(), dr.confirmedRecordType(),
                dr.confirmedRecordId(), dr.confirmedAt(), dr.rejectReason());
        ExtractionDto ex = jdbc.sql("""
                select engine, raw_text, avg_confidence, created_at from ocr_extractions
                where document_id = :d order by id desc limit 1
                """).param("d", d.id()).query(ExtractionDto.class).optional().orElse(null);
        return new DocumentDetail(dto(d), draft, ex);
    }

    private DocumentDto dto(DocRow d) {
        return new DocumentDto(d.id(), d.originalName(), d.contentType(), d.sizeBytes(), d.docType(), d.status(),
                d.statusReason(), d.uploadedByName(), d.uploadedAt(), d.entityType(), d.entityId(),
                d.contentType().startsWith("image/"));
    }

    private Path dir() {
        return Path.of(storageDir).toAbsolutePath().normalize();
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> read(String s) {
        try {
            return mapper.readValue(s, OBJ_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> stringMap(Object o) {
        Map<String, String> out = new HashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) if (e.getValue() != null) out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonBlank(String a, String b) {
        return blankToNull(a) != null ? a.trim() : blankToNull(b);
    }
}
