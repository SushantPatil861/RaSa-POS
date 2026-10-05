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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Opened containers (tubs). Opening a tub does not change stock (it is already counted); finishing it compares what was
 * ACTUALLY used (opening - closing) with what the recipes say should have been used between the two dates.
 * The reason for any gap is chosen by the owner, never guessed here.
 */
@Service
public class ContainerService {

    /** Over-serving is flagged when actual use exceeds theoretical use by more than this percentage. */
    public static final BigDecimal OVER_SERVING_THRESHOLD_PCT = new BigDecimal("10");

    private static final Set<String> BASIS = Set.of("MEASURED", "ESTIMATED");

    public record ContainerRow(long id, String containerCode, long ingredientId, String ingredientName, String baseUnit,
                               Long packSpecId, String packSpecName, LocalDate openedDate, BigDecimal openingQty,
                               String quantityBasis, String status, LocalDate finishedDate, BigDecimal closingQty,
                               BigDecimal actualUsage, BigDecimal theoreticalUsage, BigDecimal wastageRecorded,
                               BigDecimal overServingPct, Boolean overServingFlag, String usageFlags, String notes) {}

    public record ContainerDto(long id, String containerCode, long ingredientId, String ingredientName, String baseUnit,
                               Long packSpecId, String packSpecName, LocalDate openedDate, BigDecimal openingQty,
                               String quantityBasis, String status, LocalDate finishedDate, BigDecimal closingQty,
                               BigDecimal actualUsage, BigDecimal theoreticalUsage, BigDecimal wastageRecorded,
                               BigDecimal overServingPct, Boolean overServingFlag, String notes, List<String> flags,
                               BigDecimal theoreticalUsageSoFar, BigDecimal expectedRemaining, BigDecimal thresholdPct,
                               String summary) {}

    public record OpenRequest(Long ingredientId, LocalDate openedDate, BigDecimal openingQty, String unit,
                              String quantityBasis, Long packSpecId, String notes) {}

    public record FinishRequest(LocalDate finishedDate, BigDecimal closingQty, String unit, String notes) {}

    public record IngInfo(long id, String name, String baseUnit) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;
    private final InventoryService inventory;
    private final IngredientService ingredients;

    public ContainerService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit,
                            InventoryService inventory, IngredientService ingredients) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
        this.inventory = inventory;
        this.ingredients = ingredients;
    }

    public List<ContainerDto> list(String status, Long ingredientId, int limit) {
        List<ContainerRow> rows = jdbc.sql(select() + """
                where c.business_id = :b
                  and (cast(:st as text) is null or c.status = :st)
                  and (cast(:ing as bigint) is null or c.ingredient_id = :ing)
                order by (c.status = 'OPEN') desc, c.opened_date desc, c.id desc limit :lim
                """).param("b", props.businessId()).param("st", status == null || status.isBlank() ? null : status)
                .param("ing", ingredientId).param("lim", Math.min(Math.max(limit, 1), 300))
                .query(ContainerRow.class).list();
        List<ContainerDto> out = new ArrayList<>();
        for (ContainerRow r : rows) out.add(toDto(r));
        return out;
    }

    private String select() {
        return """
                select c.id, c.container_code, c.ingredient_id, i.name as ingredient_name, i.base_unit, c.pack_spec_id,
                       ps.name as pack_spec_name, c.opened_date, c.opening_qty, c.quantity_basis, c.status, c.finished_date,
                       c.closing_qty, c.actual_usage, c.theoretical_usage, c.wastage_recorded, c.over_serving_pct,
                       c.over_serving_flag, c.usage_flags, c.notes
                from stock_containers c
                join ingredients i on i.id = c.ingredient_id
                left join ingredient_pack_specs ps on ps.id = c.pack_spec_id
                """;
    }

    private ContainerDto toDto(ContainerRow r) {
        List<String> flags = new ArrayList<>();
        if ("ESTIMATED".equals(r.quantityBasis())) flags.add("ESTIMATED");
        if (r.usageFlags() != null && !r.usageFlags().isBlank()) {
            for (String f : r.usageFlags().split(",")) flags.add(f.trim());
        }
        if (Boolean.TRUE.equals(r.overServingFlag())) flags.add("OVER_SERVING");
        BigDecimal soFar = null;
        BigDecimal remaining = null;
        String summary = null;
        if ("OPEN".equals(r.status())) {
            LocalDate today = clock.today();
            soFar = inventory.theoreticalUsage(r.ingredientId(), r.openedDate(), today.isBefore(r.openedDate()) ? r.openedDate() : today);
            remaining = r.openingQty().subtract(soFar);
            summary = "Recipe-based estimate: about " + remaining.max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP) + " "
                    + r.baseUnit() + " left (estimate, not weighed).";
        } else if (r.actualUsage() != null && r.theoreticalUsage() != null) {
            BigDecimal diff = r.actualUsage().subtract(r.wastageRecorded() == null ? BigDecimal.ZERO : r.wastageRecorded())
                    .subtract(r.theoreticalUsage());
            String word = diff.signum() >= 0 ? "higher" : "lower";
            summary = "Actual consumption is " + diff.abs().setScale(0, RoundingMode.HALF_UP) + " " + r.baseUnit() + " " + word
                    + " than recipe-based consumption.";
        }
        return new ContainerDto(r.id(), r.containerCode(), r.ingredientId(), r.ingredientName(), r.baseUnit(), r.packSpecId(),
                r.packSpecName(), r.openedDate(), r.openingQty(), r.quantityBasis(), r.status(), r.finishedDate(),
                r.closingQty(), r.actualUsage(), r.theoreticalUsage(), r.wastageRecorded(), r.overServingPct(),
                r.overServingFlag(), r.notes(), flags, soFar, remaining, OVER_SERVING_THRESHOLD_PCT, summary);
    }

    @Transactional
    public ContainerDto open(OpenRequest r) {
        CurrentUser.require("containers.manage");
        if (r.ingredientId() == null) throw ApiException.badRequest("Choose the ingredient");
        IngInfo ing = jdbc.sql("select id, name, base_unit from ingredients where id = :id and business_id = :b and active")
                .param("id", r.ingredientId()).param("b", props.businessId()).query(IngInfo.class).optional()
                .orElseThrow(() -> ApiException.notFound("Ingredient"));
        LocalDate opened = r.openedDate() == null ? clock.today() : r.openedDate();
        if (opened.isAfter(clock.today())) throw ApiException.badRequest("Opened date cannot be in the future");
        BigDecimal qty;
        String basis;
        if (r.openingQty() != null) {
            if (r.openingQty().signum() <= 0) throw ApiException.badRequest("Opening quantity must be more than zero");
            try {
                qty = UnitConverter.toIngredientBase(r.openingQty(), r.unit(), ing.baseUnit());
            } catch (UnitConverter.UnitConversionException e) {
                throw ApiException.badRequest(e.getMessage());
            }
            basis = r.quantityBasis() == null || r.quantityBasis().isBlank() ? "MEASURED" : r.quantityBasis();
        } else if (r.packSpecId() != null) {
            qty = ingredients.packSpecs(ing.id()).stream().filter(p -> p.id() == r.packSpecId()).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("That pack size does not belong to this ingredient")).quantityBase();
            basis = "ESTIMATED";
        } else {
            throw ApiException.badRequest("Enter the weighed quantity or choose a pack size");
        }
        if (!BASIS.contains(basis)) throw ApiException.badRequest("Quantity basis must be MEASURED or ESTIMATED");
        String code = nextCode(ing.name(), opened);
        long id = jdbc.sql("""
                insert into stock_containers (business_id, container_code, ingredient_id, pack_spec_id, opened_date,
                                              opening_qty, quantity_basis, notes, created_by)
                values (:b, :c, :i, :ps, :d, :q, :qb, :n, :u) returning id
                """).param("b", props.businessId()).param("c", code).param("i", ing.id()).param("ps", r.packSpecId())
                .param("d", opened).param("q", qty).param("qb", basis)
                .param("n", r.notes() == null || r.notes().isBlank() ? null : r.notes().trim())
                .param("u", CurrentUser.id()).query(Long.class).single();
        audit.record("CONTAINER", id, "OPEN", opened, null, snapshot(id), null, "MANUAL");
        return one(id);
    }

    @Transactional
    public ContainerDto finish(long id, FinishRequest r) {
        CurrentUser.require("containers.manage");
        ContainerRow c = jdbc.sql(select() + " where c.id = :id and c.business_id = :b").param("id", id)
                .param("b", props.businessId()).query(ContainerRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Container"));
        if (!"OPEN".equals(c.status())) throw ApiException.conflict("This container is already finished");
        LocalDate fin = r.finishedDate() == null ? clock.today() : r.finishedDate();
        if (fin.isAfter(clock.today())) throw ApiException.badRequest("Finished date cannot be in the future");
        if (fin.isBefore(c.openedDate())) throw ApiException.badRequest("Finished date cannot be before the opened date");
        BigDecimal closing = BigDecimal.ZERO;
        if (r.closingQty() != null) {
            if (r.closingQty().signum() < 0) throw ApiException.badRequest("Closing quantity cannot be negative");
            try {
                closing = UnitConverter.toIngredientBase(r.closingQty(), r.unit(), c.baseUnit());
            } catch (UnitConverter.UnitConversionException e) {
                throw ApiException.badRequest(e.getMessage());
            }
        }
        BigDecimal actual = c.openingQty().subtract(closing);
        if (actual.signum() < 0) throw ApiException.badRequest("Closing quantity is more than the opening quantity");

        BigDecimal theoretical = inventory.theoreticalUsage(c.ingredientId(), c.openedDate(), fin);
        BigDecimal wastage = jdbc.sql("""
                select coalesce(sum(quantity), 0) from wastage_entries
                where business_id = :b and ingredient_id = :i and void_state = 'ACTIVE' and txn_date between :f and :t
                """).param("b", props.businessId()).param("i", c.ingredientId()).param("f", c.openedDate()).param("t", fin)
                .query(BigDecimal.class).single();
        long overlaps = jdbc.sql("""
                select count(*) from stock_containers
                where business_id = :b and ingredient_id = :i and id <> :id and opened_date <= :t
                  and (finished_date is null or finished_date >= :f)
                """).param("b", props.businessId()).param("i", c.ingredientId()).param("id", id).param("f", c.openedDate())
                .param("t", fin).query(Long.class).single();
        InventoryService.UsageGaps gaps = inventory.gaps(c.openedDate(), fin);

        List<String> flags = new ArrayList<>();
        if (gaps.summaryOnlyDays() > 0) flags.add("SUMMARY_DAYS_EXCLUDED");
        if (overlaps > 0) flags.add("OVERLAPPING_CONTAINERS");
        BigDecimal pct = null;
        Boolean over = null;
        if (theoretical.signum() > 0) {
            BigDecimal gap = actual.subtract(wastage).subtract(theoretical);
            pct = gap.multiply(new BigDecimal("100")).divide(theoretical, 2, RoundingMode.HALF_UP);
            over = pct.compareTo(OVER_SERVING_THRESHOLD_PCT) > 0;
        } else {
            flags.add("NO_THEORETICAL_USAGE");
        }
        String before = snapshot(id);
        jdbc.sql("""
                update stock_containers set status = 'FINISHED', finished_date = :fd, finished_at = now(), closing_qty = :cq,
                       actual_usage = :au, theoretical_usage = :tu, wastage_recorded = :wr, over_serving_pct = :pct,
                       over_serving_flag = :flag, usage_flags = :uf, finished_by = :u,
                       notes = coalesce(:n, notes)
                where id = :id
                """).param("fd", fin).param("cq", closing).param("au", actual).param("tu", theoretical).param("wr", wastage)
                .param("pct", pct).param("flag", over).param("uf", flags.isEmpty() ? null : String.join(",", flags))
                .param("u", CurrentUser.id()).param("n", r.notes() == null || r.notes().isBlank() ? null : r.notes().trim())
                .param("id", id).update();
        audit.record("CONTAINER", id, "FINISH", fin, before, snapshot(id), null, "MANUAL");
        return one(id);
    }

    private ContainerDto one(long id) {
        return toDto(jdbc.sql(select() + " where c.id = :id").param("id", id).query(ContainerRow.class).single());
    }

    private String nextCode(String ingredientName, LocalDate opened) {
        String letters = ingredientName.toUpperCase().replaceAll("[^A-Z0-9]", "");
        String prefix = letters.isEmpty() ? "ING" : letters.substring(0, Math.min(5, letters.length()));
        String stem = prefix + "-" + opened.getYear() + "-";
        int next = jdbc.sql("""
                select coalesce(max(cast(substring(container_code from '[0-9]+$') as int)), 0) + 1
                from stock_containers where business_id = :b and container_code like :pat
                """).param("b", props.businessId()).param("pat", stem + "%").query(Integer.class).single();
        return stem + String.format("%04d", next);
    }

    private String snapshot(long id) {
        return jdbc.sql("select to_jsonb(c)::text from stock_containers c where c.id = :id").param("id", id)
                .query(String.class).single();
    }
}
