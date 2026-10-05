package com.cafepos.inventory;

import com.cafepos.audit.AuditService;
import com.cafepos.costing.UnitConverter;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
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
 * The append-only stock ledger. Rows are never updated or deleted (DB triggers). A correction is a REVERSAL
 * movement with a reason. Sales usage is NOT written here: it is computed from recipes x sales (see InventoryService).
 */
@Service
public class LedgerService {

    public static final Set<String> TYPES = Set.of("OPENING", "PURCHASE", "WASTAGE", "ADJUSTMENT", "COUNT_ADJUSTMENT",
            "PREP_PRODUCE", "PREP_CONSUME", "REVERSAL");
    private static final Set<String> MANUAL_TYPES = Set.of("OPENING", "ADJUSTMENT");

    public record MovementDto(long id, long ingredientId, String ingredientName, String baseUnit, String movementType,
                              BigDecimal quantityBase, LocalDate txnDate, OffsetDateTime entryAt, String reason,
                              String refType, Long refId, String source, Long reversesId, boolean reversed) {}

    public record MovementRequest(Long ingredientId, String movementType, BigDecimal quantity, String unit,
                                  LocalDate txnDate, String reason) {}

    public record MovementRow(long id, long ingredientId, String movementType, BigDecimal quantityBase,
                              LocalDate txnDate, String refType, Long refId) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;

    public LedgerService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
    }

    // ------------------------------------------------------------ reads

    public List<MovementDto> list(Long ingredientId, LocalDate from, LocalDate to, int limit) {
        return jdbc.sql("""
                select m.id, m.ingredient_id, i.name as ingredient_name, i.base_unit, m.movement_type, m.quantity_base,
                       m.txn_date, m.entry_at, m.reason, m.ref_type, m.ref_id, m.source, m.reverses_id,
                       exists (select 1 from inventory_movements r where r.reverses_id = m.id) as reversed
                from inventory_movements m join ingredients i on i.id = m.ingredient_id
                where m.business_id = :b
                  and (cast(:ing as bigint) is null or m.ingredient_id = :ing)
                  and (cast(:f as date) is null or m.txn_date >= :f)
                  and (cast(:t as date) is null or m.txn_date <= :t)
                order by m.txn_date desc, m.id desc limit :lim
                """).param("b", props.businessId()).param("ing", ingredientId).param("f", from).param("t", to)
                .param("lim", Math.min(Math.max(limit, 1), 500)).query(MovementDto.class).list();
    }

    // ------------------------------------------------------------ writes used by other services

    /** Appends one movement (signed quantity in the ingredient's base unit). Also writes the audit row. */
    @Transactional
    public long post(long ingredientId, String type, BigDecimal qty, LocalDate txnDate, String reason, String refType,
                     Long refId, String source) {
        if (!TYPES.contains(type)) throw ApiException.badRequest("Unknown movement type: " + type);
        if (qty == null || qty.signum() == 0) throw ApiException.badRequest("Quantity cannot be zero");
        if (txnDate == null) throw ApiException.badRequest("Date is required");
        if (txnDate.isAfter(clock.today())) throw ApiException.badRequest("Date cannot be in the future");
        long id = jdbc.sql("""
                insert into inventory_movements (business_id, ingredient_id, movement_type, quantity_base, txn_date, reason,
                                                 ref_type, ref_id, source, created_by)
                values (:b, :ing, :ty, :q, :d, :r, :rt, :rid, :src, :u)
                returning id
                """)
                .param("b", props.businessId()).param("ing", ingredientId).param("ty", type).param("q", qty)
                .param("d", txnDate).param("r", reason).param("rt", refType).param("rid", refId)
                .param("src", source).param("u", CurrentUser.id()).query(Long.class).single();
        audit.record("INVENTORY_MOVEMENT", id, "CREATE", txnDate, null, snapshot(id), reason, source);
        return id;
    }

    /** Compensating movement for one movement (same date, opposite sign). Never deletes. */
    @Transactional
    public long reverse(long movementId, String reason, String source) {
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to reverse a stock movement");
        MovementRow m = jdbc.sql("""
                select id, ingredient_id, movement_type, quantity_base, txn_date, ref_type, ref_id
                from inventory_movements where id = :id and business_id = :b
                """).param("id", movementId).param("b", props.businessId()).query(MovementRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Stock movement"));
        if ("REVERSAL".equals(m.movementType())) throw ApiException.conflict("A reversal cannot be reversed");
        boolean already = jdbc.sql("select exists (select 1 from inventory_movements where reverses_id = :id)")
                .param("id", movementId).query(Boolean.class).single();
        if (already) throw ApiException.conflict("This movement has already been reversed");
        long id = jdbc.sql("""
                insert into inventory_movements (business_id, ingredient_id, movement_type, quantity_base, txn_date, reason,
                                                 ref_type, ref_id, source, reverses_id, created_by)
                values (:b, :ing, 'REVERSAL', :q, :d, :r, :rt, :rid, :src, :rev, :u)
                returning id
                """)
                .param("b", props.businessId()).param("ing", m.ingredientId()).param("q", m.quantityBase().negate())
                .param("d", m.txnDate()).param("r", reason).param("rt", m.refType()).param("rid", m.refId())
                .param("src", source).param("rev", movementId).param("u", CurrentUser.id())
                .query(Long.class).single();
        audit.record("INVENTORY_MOVEMENT", id, "REVERSAL", m.txnDate(), snapshot(movementId), snapshot(id), reason, source);
        return id;
    }

    /** Reverses every not-yet-reversed movement that belongs to a parent record (wastage entry, prep batch, count...). */
    @Transactional
    public int reverseAllFor(String refType, long refId, String reason, String source) {
        List<Long> ids = jdbc.sql("""
                select m.id from inventory_movements m
                where m.business_id = :b and m.ref_type = :rt and m.ref_id = :rid and m.movement_type <> 'REVERSAL'
                  and not exists (select 1 from inventory_movements r where r.reverses_id = m.id)
                order by m.id
                """).param("b", props.businessId()).param("rt", refType).param("rid", refId)
                .query(Long.class).list();
        for (Long id : ids) reverse(id, reason, source);
        return ids.size();
    }

    // ------------------------------------------------------------ manual movements (opening stock, adjustments)

    @Transactional
    public MovementDto createManual(MovementRequest r) {
        CurrentUser.require("inventory.manage");
        if (r.ingredientId() == null) throw ApiException.badRequest("Choose an ingredient");
        String type = r.movementType();
        if (type == null || !MANUAL_TYPES.contains(type)) {
            throw ApiException.badRequest("Manual movements can only be OPENING or ADJUSTMENT");
        }
        if (r.quantity() == null || r.quantity().signum() == 0) throw ApiException.badRequest("Enter a quantity");
        if ("OPENING".equals(type) && r.quantity().signum() < 0) throw ApiException.badRequest("Opening stock must be positive");
        if ("ADJUSTMENT".equals(type) && (r.reason() == null || r.reason().isBlank())) {
            throw ApiException.badRequest("A reason is required for a stock adjustment");
        }
        String baseUnit = jdbc.sql("select base_unit from ingredients where id = :id and business_id = :b")
                .param("id", r.ingredientId()).param("b", props.businessId()).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("Ingredient"));
        BigDecimal qty;
        try {
            qty = UnitConverter.toIngredientBase(r.quantity(), r.unit(), baseUnit);
        } catch (UnitConverter.UnitConversionException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        long id = post(r.ingredientId(), type, qty, r.txnDate(), r.reason(), null, null, "MANUAL");
        return list(r.ingredientId(), r.txnDate(), r.txnDate(), 500).stream().filter(x -> x.id() == id).findFirst()
                .orElseThrow(() -> ApiException.notFound("Stock movement"));
    }

    @Transactional
    public MovementDto reverseManual(long movementId, String reason) {
        CurrentUser.require("inventory.manage");
        MovementRow m = jdbc.sql("""
                select id, ingredient_id, movement_type, quantity_base, txn_date, ref_type, ref_id
                from inventory_movements where id = :id and business_id = :b
                """).param("id", movementId).param("b", props.businessId()).query(MovementRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Stock movement"));
        if (m.refType() != null) {
            throw ApiException.conflict("This movement belongs to a " + m.refType().toLowerCase().replace('_', ' ')
                    + ". Correct it there (void / unlink) so everything stays consistent.");
        }
        long id = reverse(movementId, reason, "MANUAL");
        return list(m.ingredientId(), m.txnDate(), m.txnDate(), 500).stream().filter(x -> x.id() == id).findFirst()
                .orElseThrow(() -> ApiException.notFound("Stock movement"));
    }

    private String snapshot(long id) {
        return jdbc.sql("select to_jsonb(m)::text from inventory_movements m where m.id = :id").param("id", id)
                .query(String.class).single();
    }
}
