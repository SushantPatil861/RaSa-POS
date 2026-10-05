package com.cafepos.expenses;

import com.cafepos.audit.AuditService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.platform.StatusFlags;
import com.cafepos.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Expenses: every row has a transaction date (when it happened) and an entry time (when typed in).
 * Category is optional so that "what is known" can be recorded and classified later.
 * Nothing is deleted: edits need a reason (revision++), voids need a reason.
 */
@Service
public class ExpenseService {

    private static final Set<String> METHODS = Set.of("CASH", "UPI", "CARD", "BANK", "OTHER");
    private static final Set<String> SOURCES = Set.of("MANUAL", "EXCEL", "CSV", "OCR", "WHATSAPP", "COUNTER", "IMPORT", "SYSTEM", "OTHER");

    public record CategoryDto(long id, String name, Long parentId, int sortOrder, boolean active) {}

    public record ExpenseRow(long id, LocalDate txnDate, OffsetDateTime entryAt, Long categoryId, String categoryName,
                             BigDecimal amount, String paymentMethod, String paidTo, boolean recurring, String notes,
                             String source, String confirmationState, boolean estimated, boolean partial, int revision,
                             String voidState, String voidReason, int version) {}

    public record ExpenseDto(long id, LocalDate txnDate, OffsetDateTime entryAt, Long categoryId, String categoryName,
                             BigDecimal amount, String paymentMethod, String paidTo, boolean recurring, String notes,
                             String source, String confirmationState, boolean estimated, boolean partial, int revision,
                             String voidState, String voidReason, int version, List<String> flags) {}

    public record ExpenseRequest(LocalDate txnDate, Long categoryId, BigDecimal amount, String paymentMethod,
                                 String paidTo, Boolean recurring, String notes, String source,
                                 Boolean estimated, Boolean partial, String confirmationState,
                                 String reason, Integer version) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;

    public ExpenseService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
    }

    // ------------------------------------------------------------ categories

    public List<CategoryDto> categories() {
        return jdbc.sql("""
                select id, name, parent_id, sort_order, active from expense_categories
                where business_id = :b order by sort_order, name
                """).param("b", props.businessId()).query(CategoryDto.class).list();
    }

    @Transactional
    public CategoryDto createCategory(String name, Long parentId) {
        CurrentUser.require("expenses.create");
        if (name == null || name.isBlank()) throw ApiException.badRequest("Category name is required");
        long id;
        try {
            id = jdbc.sql("""
                    insert into expense_categories (business_id, name, parent_id, sort_order)
                    values (:b, :n, :p, coalesce((select max(sort_order) + 1 from expense_categories where business_id = :b), 0))
                    returning id
                    """).param("b", props.businessId()).param("n", name.trim()).param("p", parentId)
                    .query(Long.class).single();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("A category named '" + name.trim() + "' already exists");
        }
        return jdbc.sql("select id, name, parent_id, sort_order, active from expense_categories where id = :id")
                .param("id", id).query(CategoryDto.class).single();
    }

    // ------------------------------------------------------------ expenses

    public List<ExpenseDto> list(LocalDate from, LocalDate to, Long categoryId, boolean includeVoided, int limit) {
        return jdbc.sql(baseSelect() + """
                where e.business_id = :b
                  and (cast(:f as date) is null or e.txn_date >= :f)
                  and (cast(:t as date) is null or e.txn_date <= :t)
                  and (cast(:c as bigint) is null or e.category_id = :c)
                  and (:inc or e.void_state = 'ACTIVE')
                order by e.txn_date desc, e.id desc limit :lim
                """).param("b", props.businessId()).param("f", from).param("t", to).param("c", categoryId)
                .param("inc", includeVoided).param("lim", Math.min(Math.max(limit, 1), 500))
                .query(ExpenseRow.class).list().stream().map(this::toDto).toList();
    }

    public ExpenseDto get(long id) {
        return toDto(row(id));
    }

    @Transactional
    public ExpenseDto create(ExpenseRequest r) {
        CurrentUser.require("expenses.create");
        validate(r);
        String source = r.source() == null || r.source().isBlank() ? "MANUAL" : r.source();
        if (!SOURCES.contains(source)) throw ApiException.badRequest("Unknown source: " + source);
        String state = r.confirmationState() == null ? "CONFIRMED" : r.confirmationState();
        if (!Set.of("DRAFT", "CONFIRMED").contains(state)) throw ApiException.badRequest("Unknown confirmation state");
        long id = jdbc.sql("""
                insert into expenses (business_id, txn_date, category_id, amount, payment_method, paid_to, recurring,
                                      notes, source, confirmation_state, is_estimated, is_partial, created_by, updated_by)
                values (:b, :d, :c, :a, :pm, :pt, :rec, :n, :src, :st, :est, :part, :u, :u)
                returning id
                """)
                .param("b", props.businessId()).param("d", r.txnDate()).param("c", r.categoryId())
                .param("a", r.amount()).param("pm", r.paymentMethod()).param("pt", r.paidTo())
                .param("rec", Boolean.TRUE.equals(r.recurring())).param("n", r.notes()).param("src", source)
                .param("st", state).param("est", Boolean.TRUE.equals(r.estimated()))
                .param("part", Boolean.TRUE.equals(r.partial())).param("u", CurrentUser.id())
                .query(Long.class).single();
        audit.record("EXPENSE", id, "CREATE", r.txnDate(), null, snapshot(id), null, source);
        return get(id);
    }

    @Transactional
    public ExpenseDto update(long id, ExpenseRequest r) {
        CurrentUser.require("expenses.edit");
        if (r.reason() == null || r.reason().isBlank()) {
            throw ApiException.badRequest("A reason is required to change a recorded expense");
        }
        ExpenseRow cur = row(id);
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("This expense is voided and cannot be edited");
        if (r.version() != null && r.version() != cur.version()) {
            throw ApiException.conflict("This expense was changed by someone else. Reload it and try again.");
        }
        validate(r);
        String before = snapshot(id);
        jdbc.sql("""
                update expenses set txn_date = :d, category_id = :c, amount = :a, payment_method = :pm, paid_to = :pt,
                       recurring = :rec, notes = :n, is_estimated = :est, is_partial = :part,
                       confirmation_state = coalesce(:st, confirmation_state),
                       revision = revision + 1, version = version + 1, updated_by = :u, updated_at = now()
                where id = :id and business_id = :b
                """)
                .param("d", r.txnDate()).param("c", r.categoryId()).param("a", r.amount())
                .param("pm", r.paymentMethod()).param("pt", r.paidTo()).param("rec", Boolean.TRUE.equals(r.recurring()))
                .param("n", r.notes()).param("est", Boolean.TRUE.equals(r.estimated()))
                .param("part", Boolean.TRUE.equals(r.partial())).param("st", r.confirmationState())
                .param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("EXPENSE", id, "UPDATE", r.txnDate(), before, snapshot(id), r.reason(), "MANUAL");
        return get(id);
    }

    @Transactional
    public ExpenseDto voidExpense(long id, String reason) {
        CurrentUser.require("expenses.edit");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void an expense");
        ExpenseRow cur = row(id);
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("Already voided");
        String before = snapshot(id);
        jdbc.sql("""
                update expenses set void_state = 'VOIDED', void_reason = :r, voided_by = :u, voided_at = now(),
                       version = version + 1, updated_at = now()
                where id = :id and business_id = :b
                """).param("r", reason).param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("EXPENSE", id, "VOID", cur.txnDate(), before, snapshot(id), reason, "MANUAL");
        return get(id);
    }

    // ------------------------------------------------------------ internals

    private void validate(ExpenseRequest r) {
        if (r.txnDate() == null) throw ApiException.badRequest("Date is required");
        if (r.amount() == null || r.amount().signum() < 0) throw ApiException.badRequest("Amount must be zero or more");
        if (r.txnDate().isAfter(clock.today())) throw ApiException.badRequest("Transaction date cannot be in the future");
        if (r.txnDate().isBefore(clock.dataStartDate())) {
            throw ApiException.badRequest("Date is before the business data-start date " + clock.dataStartDate());
        }
        if (r.paymentMethod() != null && !r.paymentMethod().isBlank() && !METHODS.contains(r.paymentMethod())) {
            throw ApiException.badRequest("Unknown payment method: " + r.paymentMethod());
        }
    }

    private String baseSelect() {
        return """
                select e.id, e.txn_date, e.entry_at, e.category_id, c.name as category_name, e.amount,
                       e.payment_method, e.paid_to, e.recurring, e.notes, e.source, e.confirmation_state,
                       e.is_estimated as estimated, e.is_partial as partial, e.revision, e.void_state, e.void_reason,
                       e.version
                from expenses e left join expense_categories c on c.id = e.category_id
                """;
    }

    private ExpenseRow row(long id) {
        return jdbc.sql(baseSelect() + " where e.id = :id and e.business_id = :b")
                .param("id", id).param("b", props.businessId()).query(ExpenseRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Expense"));
    }

    private ExpenseDto toDto(ExpenseRow r) {
        return new ExpenseDto(r.id(), r.txnDate(), r.entryAt(), r.categoryId(), r.categoryName(), r.amount(),
                r.paymentMethod(), r.paidTo(), r.recurring(), r.notes(), r.source(), r.confirmationState(),
                r.estimated(), r.partial(), r.revision(), r.voidState(), r.voidReason(), r.version(),
                StatusFlags.of(r.source(), r.confirmationState(), r.estimated(), r.partial(), r.revision(),
                        r.txnDate(), r.entryAt(), clock.zone(), r.voidState()));
    }

    private String snapshot(long id) {
        return jdbc.sql("select to_jsonb(e)::text from expenses e where e.id = :id").param("id", id)
                .query(String.class).single();
    }
}
