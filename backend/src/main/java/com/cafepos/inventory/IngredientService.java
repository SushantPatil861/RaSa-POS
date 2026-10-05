package com.cafepos.inventory;

import com.cafepos.audit.AuditService;
import com.cafepos.costing.CostResolver;
import com.cafepos.costing.CostingService;
import com.cafepos.costing.UnitConverter;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/** Ingredients (g / ml / pcs), pack specs, append-only cost history and purchase-item links. */
@Service
public class IngredientService {

    private static final Set<String> KINDS = Set.of("RAW", "PREPARED");
    private static final Set<String> BASE_UNITS = Set.of("g", "ml", "pcs");
    private static final Set<String> BASIS = Set.of("EXACT", "ESTIMATED", "SUPPLIER_SPEC");
    private static final Set<String> COST_SOURCES = Set.of("MANUAL", "ESTIMATE");

    public record UnitDto(String code, String name, String dimension, String baseUnit, BigDecimal toBase) {}

    public record IngredientDto(long id, String name, String kind, String baseUnit, String category, BigDecimal minLevel,
                                boolean active, String notes, boolean hasRecipe) {}

    public record IngredientRequest(String name, String kind, String baseUnit, String category, BigDecimal minLevel,
                                    Boolean active, String notes, String reason) {}

    public record PackSpecDto(long id, long ingredientId, String name, BigDecimal quantityBase, String basis, String notes,
                              boolean active) {}

    public record PackSpecRequest(String name, BigDecimal quantity, String unit, String basis, String notes) {}

    public record CostRequest(LocalDate effectiveDate, BigDecimal costPerBase, BigDecimal totalCost, BigDecimal quantity,
                              String unit, String source, String notes) {}

    public record CostHistoryDto(long id, long ingredientId, LocalDate effectiveDate, BigDecimal costPerBase, String source,
                                 Long purchaseItemId, Long purchaseId, String notes, OffsetDateTime entryAt,
                                 String createdByName, boolean voided, String voidReason) {}

    public record LinkRequest(Long ingredientId, BigDecimal quantity, String unit, Long packSpecId, BigDecimal packCount) {}

    public record LinkDto(long id, long purchaseItemId, long purchaseId, String purchaseNo, LocalDate txnDate,
                          long ingredientId, String ingredientName, String baseUnit, String itemName, BigDecimal itemTotal,
                          BigDecimal baseQuantity, Long packSpecId, String status, String attention,
                          OffsetDateTime linkedAt, String unlinkReason) {}

    public record UnlinkedItem(long purchaseItemId, long purchaseId, String purchaseNo, LocalDate txnDate,
                               String supplierName, String itemName, BigDecimal quantity, String unit, BigDecimal total) {}

    public record ReconcileResult(int unlinked) {}

    public record ItemInfo(long id, long purchaseId, String itemName, BigDecimal total, String treatment,
                           LocalDate txnDate, String voidState, String confirmationState, boolean estimated) {}

    public record LinkRow(long id, long purchaseItemId, long ingredientId, BigDecimal baseQuantity, Long costHistoryId,
                          Long movementId, String status) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;
    private final LedgerService ledger;

    public IngredientService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit, LedgerService ledger) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
        this.ledger = ledger;
    }

    // ------------------------------------------------------------ units

    public List<UnitDto> units() {
        return jdbc.sql("select code, name, dimension, base_unit, to_base from units order by dimension, to_base")
                .query(UnitDto.class).list();
    }

    // ------------------------------------------------------------ ingredients

    public List<IngredientDto> list(boolean includeInactive) {
        return jdbc.sql(baseSelect() + """
                where i.business_id = :b and (:inc or i.active)
                order by lower(i.name)
                """).param("b", props.businessId()).param("inc", includeInactive).query(IngredientDto.class).list();
    }

    public IngredientDto get(long id) {
        return jdbc.sql(baseSelect() + " where i.id = :id and i.business_id = :b")
                .param("id", id).param("b", props.businessId()).query(IngredientDto.class).optional()
                .orElseThrow(() -> ApiException.notFound("Ingredient"));
    }

    private String baseSelect() {
        return """
                select i.id, i.name, i.kind, i.base_unit, i.category, i.min_level, i.active, i.notes,
                       exists (select 1 from recipes r where r.ingredient_id = i.id) as has_recipe
                from ingredients i
                """;
    }

    @Transactional
    public IngredientDto create(IngredientRequest r) {
        CurrentUser.require("inventory.manage");
        validate(r);
        if (UnitConverter.normalise(r.baseUnit()) == null) throw ApiException.badRequest("Base unit is required: g, ml or pcs");
        String kind = r.kind() == null || r.kind().isBlank() ? "RAW" : r.kind();
        long id;
        try {
            id = jdbc.sql("""
                    insert into ingredients (business_id, name, kind, base_unit, category, min_level, active, notes, created_by)
                    values (:b, :n, :k, :u, :c, :m, true, :notes, :uid)
                    returning id
                    """)
                    .param("b", props.businessId()).param("n", r.name().trim()).param("k", kind)
                    .param("u", UnitConverter.normalise(r.baseUnit())).param("c", blankToNull(r.category()))
                    .param("m", r.minLevel()).param("notes", blankToNull(r.notes())).param("uid", CurrentUser.id())
                    .query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("An ingredient named '" + r.name().trim() + "' already exists");
        }
        audit.record("INGREDIENT", id, "CREATE", null, null, snapshot(id), null, "MANUAL");
        return get(id);
    }

    @Transactional
    public IngredientDto update(long id, IngredientRequest r) {
        CurrentUser.require("inventory.manage");
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.badRequest("A reason is required to change an ingredient");
        IngredientDto cur = get(id);
        validate(r);
        String newKind = r.kind() == null || r.kind().isBlank() ? cur.kind() : r.kind();
        String newUnit = UnitConverter.normalise(r.baseUnit() == null || r.baseUnit().isBlank() ? cur.baseUnit() : r.baseUnit());
        if (!newKind.equals(cur.kind()) || !newUnit.equals(cur.baseUnit())) {
            boolean used = jdbc.sql("""
                    select exists (select 1 from inventory_movements where ingredient_id = :id)
                        or exists (select 1 from recipe_lines where ingredient_id = :id)
                        or exists (select 1 from ingredient_cost_history where ingredient_id = :id)
                        or exists (select 1 from recipes where ingredient_id = :id)
                    """).param("id", id).query(Boolean.class).single();
            if (used) throw ApiException.conflict("Kind and base unit cannot change once the ingredient has stock, costs or recipes");
        }
        String before = snapshot(id);
        try {
            jdbc.sql("""
                    update ingredients set name = :n, kind = :k, base_unit = :u, category = :c, min_level = :m,
                           active = :a, notes = :notes, updated_at = now()
                    where id = :id and business_id = :b
                    """)
                    .param("n", r.name().trim()).param("k", newKind).param("u", newUnit)
                    .param("c", blankToNull(r.category())).param("m", r.minLevel())
                    .param("a", r.active() == null ? cur.active() : r.active()).param("notes", blankToNull(r.notes()))
                    .param("id", id).param("b", props.businessId()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("An ingredient named '" + r.name().trim() + "' already exists");
        }
        audit.record("INGREDIENT", id, "UPDATE", null, before, snapshot(id), r.reason(), "MANUAL");
        return get(id);
    }

    private void validate(IngredientRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Ingredient name is required");
        if (r.kind() != null && !r.kind().isBlank() && !KINDS.contains(r.kind())) throw ApiException.badRequest("Kind must be RAW or PREPARED");
        String u = UnitConverter.normalise(r.baseUnit());
        if (r.baseUnit() != null && !r.baseUnit().isBlank() && (u == null || !BASE_UNITS.contains(u))) {
            throw ApiException.badRequest("Base unit must be g, ml or pcs");
        }
        if (r.minLevel() != null && r.minLevel().signum() < 0) throw ApiException.badRequest("Par level cannot be negative");
    }

    // ------------------------------------------------------------ pack specs

    public List<PackSpecDto> packSpecs(long ingredientId) {
        return jdbc.sql("""
                select id, ingredient_id, name, quantity_base, basis, notes, active
                from ingredient_pack_specs where ingredient_id = :i order by active desc, lower(name)
                """).param("i", ingredientId).query(PackSpecDto.class).list();
    }

    @Transactional
    public PackSpecDto createPackSpec(long ingredientId, PackSpecRequest r) {
        CurrentUser.require("inventory.manage");
        IngredientDto ing = get(ingredientId);
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Pack name is required (for example '1.4 kg tub')");
        if (r.quantity() == null || r.quantity().signum() <= 0) throw ApiException.badRequest("Pack quantity must be more than zero");
        String basis = r.basis() == null || r.basis().isBlank() ? "SUPPLIER_SPEC" : r.basis();
        if (!BASIS.contains(basis)) throw ApiException.badRequest("Basis must be EXACT, ESTIMATED or SUPPLIER_SPEC");
        BigDecimal qty = toBase(r.quantity(), r.unit(), ing.baseUnit());
        long id;
        try {
            id = jdbc.sql("""
                    insert into ingredient_pack_specs (ingredient_id, name, quantity_base, basis, notes)
                    values (:i, :n, :q, :bs, :notes) returning id
                    """).param("i", ingredientId).param("n", r.name().trim()).param("q", qty).param("bs", basis)
                    .param("notes", blankToNull(r.notes())).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("This ingredient already has a pack named '" + r.name().trim() + "'");
        }
        audit.record("PACK_SPEC", id, "CREATE", null, null,
                jdbc.sql("select to_jsonb(p)::text from ingredient_pack_specs p where id = :id").param("id", id).query(String.class).single(),
                null, "MANUAL");
        return packSpecs(ingredientId).stream().filter(p -> p.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public PackSpecDto setPackSpecActive(long packSpecId, boolean active) {
        CurrentUser.require("inventory.manage");
        Long ingredientId = jdbc.sql("select ingredient_id from ingredient_pack_specs where id = :id")
                .param("id", packSpecId).query(Long.class).optional().orElseThrow(() -> ApiException.notFound("Pack spec"));
        jdbc.sql("update ingredient_pack_specs set active = :a where id = :id").param("a", active).param("id", packSpecId).update();
        audit.record("PACK_SPEC", packSpecId, active ? "ACTIVATE" : "DEACTIVATE", null, null, null, null, "MANUAL");
        return packSpecs(ingredientId).stream().filter(p -> p.id() == packSpecId).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------ cost history

    public List<CostHistoryDto> costHistory(long ingredientId) {
        CostingService.requireCosts();
        get(ingredientId);
        return jdbc.sql("""
                select h.id, h.ingredient_id, h.effective_date, h.cost_per_base, h.source, h.purchase_item_id, h.purchase_id,
                       h.notes, h.entry_at, u.name as created_by_name, (v.cost_id is not null) as voided, v.reason as void_reason
                from ingredient_cost_history h
                left join users u on u.id = h.created_by
                left join ingredient_cost_voids v on v.cost_id = h.id
                where h.ingredient_id = :i and h.business_id = :b
                order by h.effective_date desc, h.id desc
                """).param("i", ingredientId).param("b", props.businessId()).query(CostHistoryDto.class).list();
    }

    @Transactional
    public List<CostHistoryDto> addCost(long ingredientId, CostRequest r) {
        CurrentUser.require("inventory.manage");
        CostingService.requireCosts();
        IngredientDto ing = get(ingredientId);
        String source = r.source() == null || r.source().isBlank() ? "MANUAL" : r.source();
        if (!COST_SOURCES.contains(source)) throw ApiException.badRequest("Source must be MANUAL or ESTIMATE");
        if (r.effectiveDate() == null) throw ApiException.badRequest("Effective date is required");
        if (r.effectiveDate().isAfter(clock.today())) throw ApiException.badRequest("Effective date cannot be in the future");
        BigDecimal perBase;
        if (r.costPerBase() != null) {
            perBase = r.costPerBase();
        } else if (r.totalCost() != null && r.quantity() != null) {
            BigDecimal baseQty = toBase(r.quantity(), r.unit(), ing.baseUnit());
            if (baseQty.signum() <= 0) throw ApiException.badRequest("Quantity must be more than zero");
            perBase = CostResolver.costPerBase(r.totalCost(), baseQty);
        } else {
            throw ApiException.badRequest("Enter either a cost per " + ing.baseUnit() + " or a total cost with its quantity");
        }
        if (perBase.signum() < 0) throw ApiException.badRequest("Cost cannot be negative");
        long id = jdbc.sql("""
                insert into ingredient_cost_history (business_id, ingredient_id, effective_date, cost_per_base, source, notes, created_by)
                values (:b, :i, :d, :c, :s, :n, :u) returning id
                """).param("b", props.businessId()).param("i", ingredientId).param("d", r.effectiveDate())
                .param("c", perBase).param("s", source).param("n", blankToNull(r.notes())).param("u", CurrentUser.id())
                .query(Long.class).single();
        audit.record("INGREDIENT_COST", id, "CREATE", r.effectiveDate(), null, costSnapshot(id), r.notes(), "MANUAL");
        return costHistory(ingredientId);
    }

    @Transactional
    public List<CostHistoryDto> voidCost(long costId, String reason) {
        CurrentUser.require("inventory.manage");
        CostingService.requireCosts();
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void a cost entry");
        Long ingredientId = jdbc.sql("select ingredient_id from ingredient_cost_history where id = :id and business_id = :b")
                .param("id", costId).param("b", props.businessId()).query(Long.class).optional()
                .orElseThrow(() -> ApiException.notFound("Cost entry"));
        boolean voided = jdbc.sql("select exists (select 1 from ingredient_cost_voids where cost_id = :id)")
                .param("id", costId).query(Boolean.class).single();
        if (voided) throw ApiException.conflict("This cost entry is already voided");
        jdbc.sql("insert into ingredient_cost_voids (cost_id, reason, voided_by) values (:id, :r, :u)")
                .param("id", costId).param("r", reason).param("u", CurrentUser.id()).update();
        audit.record("INGREDIENT_COST", costId, "VOID", null, costSnapshot(costId), null, reason, "MANUAL");
        return costHistory(ingredientId);
    }

    // ------------------------------------------------------------ purchase links

    public List<UnlinkedItem> unlinkedItems(LocalDate from, LocalDate to) {
        CurrentUser.require("inventory.manage");
        return jdbc.sql("""
                select pi.id as purchase_item_id, pi.purchase_id, p.purchase_no, p.txn_date, s.name as supplier_name,
                       pi.item_name, pi.quantity, pi.unit, pi.total
                from purchase_items pi
                join purchases p on p.id = pi.purchase_id
                left join suppliers s on s.id = p.supplier_id
                where p.business_id = :b and p.void_state = 'ACTIVE' and p.confirmation_state = 'CONFIRMED'
                  and pi.treatment = 'INVENTORY'
                  and (cast(:f as date) is null or p.txn_date >= :f)
                  and (cast(:t as date) is null or p.txn_date <= :t)
                  and not exists (select 1 from purchase_item_links l where l.purchase_item_id = pi.id and l.status = 'ACTIVE')
                order by p.txn_date desc, pi.id desc limit 300
                """).param("b", props.businessId()).param("f", from).param("t", to).query(UnlinkedItem.class).list();
    }

    public List<LinkDto> links(Long ingredientId, String status) {
        CurrentUser.require("inventory.manage");
        return jdbc.sql(linkSelect() + """
                where l.business_id = :b
                  and (cast(:ing as bigint) is null or l.ingredient_id = :ing)
                  and (cast(:st as text) is null or l.status = :st)
                order by l.id desc limit 300
                """).param("b", props.businessId()).param("ing", ingredientId).param("st", blankToNull(status))
                .query(LinkDto.class).list();
    }

    private String linkSelect() {
        return """
                select l.id, l.purchase_item_id, l.purchase_id, p.purchase_no, p.txn_date, l.ingredient_id,
                       i.name as ingredient_name, i.base_unit, l.item_name, l.item_total, l.base_quantity, l.pack_spec_id,
                       l.status,
                       case when l.status <> 'ACTIVE' then null
                            when p.void_state = 'VOIDED' then 'PURCHASE_VOIDED'
                            when pi.id is null then 'ITEM_REMOVED'
                            when pi.total <> l.item_total then 'AMOUNT_CHANGED' end as attention,
                       l.linked_at, l.unlink_reason
                from purchase_item_links l
                join purchases p on p.id = l.purchase_id
                join ingredients i on i.id = l.ingredient_id
                left join purchase_items pi on pi.id = l.purchase_item_id
                """;
    }

    /** Idempotent per purchase item: linking again with the same values returns the existing link. */
    @Transactional
    public LinkDto link(long purchaseItemId, LinkRequest r) {
        CurrentUser.require("inventory.manage");
        if (r.ingredientId() == null) throw ApiException.badRequest("Choose the ingredient this purchase item is");
        IngredientDto ing = get(r.ingredientId());
        ItemInfo item = jdbc.sql("""
                select i.id, i.purchase_id, i.item_name, i.total, i.treatment, p.txn_date, p.void_state, p.confirmation_state,
                       p.is_estimated as estimated
                from purchase_items i join purchases p on p.id = i.purchase_id
                where i.id = :id and p.business_id = :b
                """).param("id", purchaseItemId).param("b", props.businessId()).query(ItemInfo.class).optional()
                .orElseThrow(() -> ApiException.notFound("Purchase item"));
        if ("VOIDED".equals(item.voidState())) throw ApiException.conflict("This purchase is voided");
        if (!"CONFIRMED".equals(item.confirmationState())) throw ApiException.conflict("This purchase is still a DRAFT. Confirm it first");
        if (!"INVENTORY".equals(item.treatment())) {
            throw ApiException.conflict("This item is treated as " + item.treatment() + ", not INVENTORY. Change its treatment in the purchase first");
        }
        BigDecimal baseQty = baseQuantity(r, ing);
        LinkRow existing = jdbc.sql("""
                select id, purchase_item_id, ingredient_id, base_quantity, cost_history_id, movement_id, status
                from purchase_item_links where purchase_item_id = :id
                """).param("id", purchaseItemId).query(LinkRow.class).optional().orElse(null);
        if (existing != null && "ACTIVE".equals(existing.status())) {
            if (existing.ingredientId() == ing.id() && existing.baseQuantity().compareTo(baseQty) == 0) {
                return linkDto(existing.id());
            }
            throw ApiException.conflict("This purchase item is already linked differently. Unlink it first (with a reason)");
        }
        Long costId = null;
        if (item.total().signum() > 0) {
            BigDecimal perBase = CostResolver.costPerBase(item.total(), baseQty);
            costId = jdbc.sql("""
                    insert into ingredient_cost_history (business_id, ingredient_id, effective_date, cost_per_base, source,
                                                         purchase_item_id, purchase_id, notes, created_by)
                    values (:b, :i, :d, :c, :s, :pi, :p, :n, :u) returning id
                    """).param("b", props.businessId()).param("i", ing.id()).param("d", item.txnDate()).param("c", perBase)
                    .param("s", item.estimated() ? "ESTIMATE" : "PURCHASE").param("pi", purchaseItemId)
                    .param("p", item.purchaseId()).param("n", "From purchase item: " + item.itemName())
                    .param("u", CurrentUser.id()).query(Long.class).single();
        }
        long movementId = ledger.post(ing.id(), "PURCHASE", baseQty, item.txnDate(), "Purchase item: " + item.itemName(),
                "PURCHASE_ITEM", purchaseItemId, "PURCHASE_LINK");
        long linkId;
        if (existing == null) {
            linkId = jdbc.sql("""
                    insert into purchase_item_links (business_id, purchase_item_id, purchase_id, ingredient_id, base_quantity,
                                                     pack_spec_id, item_name, item_total, cost_history_id, movement_id, linked_by)
                    values (:b, :pi, :p, :i, :q, :ps, :n, :t, :c, :m, :u) returning id
                    """).param("b", props.businessId()).param("pi", purchaseItemId).param("p", item.purchaseId())
                    .param("i", ing.id()).param("q", baseQty).param("ps", r.packSpecId()).param("n", item.itemName())
                    .param("t", item.total()).param("c", costId).param("m", movementId).param("u", CurrentUser.id())
                    .query(Long.class).single();
        } else {
            linkId = existing.id();
            jdbc.sql("""
                    update purchase_item_links set ingredient_id = :i, base_quantity = :q, pack_spec_id = :ps, item_name = :n,
                           item_total = :t, cost_history_id = :c, movement_id = :m, status = 'ACTIVE', linked_at = now(),
                           linked_by = :u, unlinked_at = null, unlinked_by = null, unlink_reason = null,
                           purchase_id = :p
                    where id = :id
                    """).param("i", ing.id()).param("q", baseQty).param("ps", r.packSpecId()).param("n", item.itemName())
                    .param("t", item.total()).param("c", costId).param("m", movementId).param("u", CurrentUser.id())
                    .param("p", item.purchaseId()).param("id", linkId).update();
        }
        audit.record("PURCHASE_LINK", linkId, "LINK", item.txnDate(), null, linkSnapshot(linkId), null, "MANUAL");
        return linkDto(linkId);
    }

    @Transactional
    public LinkDto unlink(long linkId, String reason) {
        CurrentUser.require("inventory.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to unlink a purchase item");
        return doUnlink(linkId, reason, "MANUAL");
    }

    /** Unlinks links whose purchase was voided or whose item was removed by a purchase edit (compensating movements). */
    @Transactional
    public ReconcileResult reconcile() {
        CurrentUser.require("inventory.manage");
        List<LinkDto> all = jdbc.sql(linkSelect() + " where l.business_id = :b and l.status = 'ACTIVE'")
                .param("b", props.businessId()).query(LinkDto.class).list();
        int n = 0;
        for (LinkDto l : all) {
            if ("PURCHASE_VOIDED".equals(l.attention())) {
                doUnlink(l.id(), "Purchase " + l.purchaseNo() + " was voided", "SYSTEM");
                n++;
            } else if ("ITEM_REMOVED".equals(l.attention())) {
                doUnlink(l.id(), "Purchase " + l.purchaseNo() + " was edited and this item no longer exists (re-link the new item)", "SYSTEM");
                n++;
            }
        }
        return new ReconcileResult(n);
    }

    private LinkDto doUnlink(long linkId, String reason, String source) {
        LinkRow l = jdbc.sql("""
                select id, purchase_item_id, ingredient_id, base_quantity, cost_history_id, movement_id, status
                from purchase_item_links where id = :id and business_id = :b
                """).param("id", linkId).param("b", props.businessId()).query(LinkRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Purchase link"));
        if (!"ACTIVE".equals(l.status())) throw ApiException.conflict("This link is already removed");
        String before = linkSnapshot(linkId);
        if (l.movementId() != null) ledger.reverse(l.movementId(), reason, source.equals("SYSTEM") ? "SYSTEM" : "PURCHASE_LINK");
        if (l.costHistoryId() != null) {
            boolean voided = jdbc.sql("select exists (select 1 from ingredient_cost_voids where cost_id = :id)")
                    .param("id", l.costHistoryId()).query(Boolean.class).single();
            if (!voided) {
                jdbc.sql("insert into ingredient_cost_voids (cost_id, reason, voided_by) values (:id, :r, :u)")
                        .param("id", l.costHistoryId()).param("r", reason).param("u", CurrentUser.id()).update();
            }
        }
        jdbc.sql("""
                update purchase_item_links set status = 'UNLINKED', unlinked_at = now(), unlinked_by = :u, unlink_reason = :r
                where id = :id
                """).param("u", CurrentUser.id()).param("r", reason).param("id", linkId).update();
        audit.record("PURCHASE_LINK", linkId, "UNLINK", null, before, linkSnapshot(linkId), reason, source);
        return linkDto(linkId);
    }

    private BigDecimal baseQuantity(LinkRequest r, IngredientDto ing) {
        if (r.packSpecId() != null) {
            PackSpecDto spec = packSpecs(ing.id()).stream().filter(p -> p.id() == r.packSpecId()).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("That pack size does not belong to this ingredient"));
            if (r.packCount() == null || r.packCount().signum() <= 0) throw ApiException.badRequest("Enter how many packs were bought");
            return spec.quantityBase().multiply(r.packCount());
        }
        if (r.quantity() == null || r.quantity().signum() <= 0) throw ApiException.badRequest("Enter the quantity bought");
        BigDecimal q = toBase(r.quantity(), r.unit(), ing.baseUnit());
        if (q.signum() <= 0) throw ApiException.badRequest("Quantity must be more than zero");
        return q;
    }

    private LinkDto linkDto(long id) {
        return jdbc.sql(linkSelect() + " where l.id = :id").param("id", id).query(LinkDto.class).single();
    }

    // ------------------------------------------------------------ helpers

    private static BigDecimal toBase(BigDecimal qty, String unit, String ingredientBase) {
        try {
            return UnitConverter.toIngredientBase(qty, unit, ingredientBase);
        } catch (UnitConverter.UnitConversionException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String snapshot(long id) {
        return jdbc.sql("select to_jsonb(i)::text from ingredients i where i.id = :id").param("id", id).query(String.class).single();
    }

    private String costSnapshot(long id) {
        return jdbc.sql("select to_jsonb(h)::text from ingredient_cost_history h where h.id = :id").param("id", id)
                .query(String.class).single();
    }

    private String linkSnapshot(long id) {
        return jdbc.sql("select to_jsonb(l)::text from purchase_item_links l where l.id = :id").param("id", id)
                .query(String.class).single();
    }
}
