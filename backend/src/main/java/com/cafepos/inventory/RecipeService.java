package com.cafepos.inventory;

import com.cafepos.audit.AuditService;
import com.cafepos.costing.CostResolver;
import com.cafepos.costing.CostingService;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Recipes per product variant or per PREPARED ingredient. Versions are immutable and effective-dated:
 * a new version supersedes the previous one FROM its effective date; past versions are never edited.
 */
@Service
public class RecipeService {

    // ------------------------------------------------------------ DTOs
    public record VariantRow(long variantId, long productId, String productName, String variantName, BigDecimal menuPrice,
                             boolean hasRecipe) {}

    public record VariantCostDto(long variantId, long productId, String productName, String variantName,
                                 BigDecimal menuPriceInclusive, boolean hasRecipe, BigDecimal costPerPortion,
                                 String status, boolean estimated, BigDecimal marginPct) {}

    public record VersionHead(long id, int versionNo, LocalDate effectiveFrom, BigDecimal outputQty, String reason,
                              String notes, OffsetDateTime createdAt) {}

    public record LineRowDto(long versionId, long ingredientId, String ingredientName, String kind, String baseUnit,
                             BigDecimal quantityBase, BigDecimal wastePct, String notes) {}

    public record VersionDto(long id, int versionNo, LocalDate effectiveFrom, BigDecimal outputQty, String reason,
                             String notes, OffsetDateTime createdAt, List<LineRowDto> lines) {}

    public record RecipeDetail(Long recipeId, Long variantId, Long ingredientId, String title, String outputUnit,
                               List<VersionDto> versions) {}

    public record LineInput(Long ingredientId, BigDecimal quantity, String unit, BigDecimal wastePct, String notes) {}

    public record VersionRequest(Long variantId, Long ingredientId, LocalDate effectiveFrom, BigDecimal outputQty,
                                 String reason, String notes, List<LineInput> lines) {}

    public record LineCost(long ingredientId, String name, String kind, String baseUnit, BigDecimal quantityBase,
                           BigDecimal wastePct, BigDecimal costPerBase, BigDecimal lineCost, String status,
                           boolean estimated, List<String> missing) {}

    public record RecipeCost(LocalDate asOf, Long versionId, Integer versionNo, BigDecimal outputQty, String status,
                             BigDecimal totalCost, BigDecimal costPerOutput, boolean estimated, List<String> missing,
                             BigDecimal menuPriceInclusive, BigDecimal menuPriceExGst, BigDecimal marginAmount,
                             BigDecimal marginPct, List<LineCost> lines, List<String> notes) {}

    public record RecipeOwner(long id, Long variantId, Long ingredientId) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;
    private final CostingService costing;

    public RecipeService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit, CostingService costing) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
        this.costing = costing;
    }

    // ------------------------------------------------------------ lists

    /** Every sellable variant with recipe status; cost columns only for users allowed to see costs. */
    public List<VariantCostDto> variants(LocalDate asOf) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        List<VariantRow> rows = jdbc.sql("""
                select v.id as variant_id, p.id as product_id, p.name as product_name, v.name as variant_name,
                       (select mi.price from menu_items mi join menu_versions mv on mv.id = mi.menu_version_id
                         where mi.variant_id = v.id and mv.business_id = :b and mv.status = 'SCHEDULED'
                           and mv.effective_from <= :d
                         order by mv.effective_from desc limit 1) as menu_price,
                       exists (select 1 from recipes r where r.variant_id = v.id) as has_recipe
                from product_variants v join products p on p.id = v.product_id
                where p.business_id = :b and v.status = 'ACTIVE' and p.lifecycle_status in ('ACTIVE','TEMPORARILY_UNAVAILABLE','DRAFT')
                order by lower(p.name), v.sort_order, v.name
                """).param("b", props.businessId()).param("d", d).query(VariantRow.class).list();
        boolean costs = CostingService.canSeeCosts();
        CostingService.CostBook book = costs ? costing.loadBook() : null;
        CostResolver res = costs ? book.resolver() : null;
        BigDecimal gst = gstRate(d);
        List<VariantCostDto> out = new ArrayList<>();
        for (VariantRow r : rows) {
            BigDecimal cost = null;
            String status = r.hasRecipe() ? "RECIPE" : "NO_RECIPE";
            boolean est = false;
            BigDecimal margin = null;
            if (costs && r.hasRecipe()) {
                CostResolver.Result c = CostingService.portion(res, r.variantId(), d);
                if (c.noRecipe()) {
                    status = "NO_RECIPE";   // recipe exists but none in force on this date
                } else if (c.cost() == null) {
                    status = "MISSING";
                } else {
                    status = c.status();
                    cost = CostingService.money(c.cost());
                    est = c.estimated();
                    BigDecimal ex = exGst(r.menuPrice(), gst);
                    if (ex != null && ex.signum() > 0) {
                        margin = ex.subtract(c.cost()).multiply(new BigDecimal("100")).divide(ex, 1, RoundingMode.HALF_UP);
                    }
                }
            }
            out.add(new VariantCostDto(r.variantId(), r.productId(), r.productName(), r.variantName(), r.menuPrice(),
                    r.hasRecipe(), cost, status, est, margin));
        }
        return out;
    }

    // ------------------------------------------------------------ detail

    public RecipeDetail detail(Long variantId, Long ingredientId) {
        CurrentUser.require("recipes.view");
        checkOwner(variantId, ingredientId);
        RecipeOwner owner = findOwner(variantId, ingredientId);
        String title;
        String outputUnit;
        if (variantId != null) {
            title = jdbc.sql("""
                    select p.name || ' - ' || v.name from product_variants v join products p on p.id = v.product_id where v.id = :id
                    """).param("id", variantId).query(String.class).optional().orElseThrow(() -> ApiException.notFound("Product variant"));
            outputUnit = "portion";
        } else {
            var ing = jdbc.sql("select name, base_unit from ingredients where id = :id and business_id = :b")
                    .param("id", ingredientId).param("b", props.businessId()).query(IngName.class).optional()
                    .orElseThrow(() -> ApiException.notFound("Ingredient"));
            title = ing.name();
            outputUnit = ing.baseUnit();
        }
        if (owner == null) return new RecipeDetail(null, variantId, ingredientId, title, outputUnit, List.of());
        List<VersionHead> heads = jdbc.sql("""
                select id, version_no, effective_from, output_qty, reason, notes, created_at
                from recipe_versions where recipe_id = :r order by effective_from desc
                """).param("r", owner.id()).query(VersionHead.class).list();
        List<LineRowDto> lines = jdbc.sql("""
                select l.version_id, l.ingredient_id, i.name as ingredient_name, i.kind, i.base_unit, l.quantity_base,
                       l.waste_pct, l.notes
                from recipe_lines l join ingredients i on i.id = l.ingredient_id
                where l.version_id in (select id from recipe_versions where recipe_id = :r) order by l.id
                """).param("r", owner.id()).query(LineRowDto.class).list();
        List<VersionDto> versions = new ArrayList<>();
        for (VersionHead h : heads) {
            versions.add(new VersionDto(h.id(), h.versionNo(), h.effectiveFrom(), h.outputQty(), h.reason(), h.notes(),
                    h.createdAt(), lines.stream().filter(l -> l.versionId() == h.id()).toList()));
        }
        return new RecipeDetail(owner.id(), variantId, ingredientId, title, outputUnit, versions);
    }

    public record IngName(String name, String baseUnit) {}

    // ------------------------------------------------------------ create a version

    @Transactional
    public RecipeDetail addVersion(VersionRequest r) {
        CurrentUser.require("recipes.edit");
        checkOwner(r.variantId(), r.ingredientId());
        if (r.effectiveFrom() == null) throw ApiException.badRequest("Effective-from date is required");
        BigDecimal output = r.outputQty() == null ? BigDecimal.ONE : r.outputQty();
        if (output.signum() <= 0) throw ApiException.badRequest("Output quantity must be more than zero");
        if (r.lines() == null || r.lines().isEmpty()) throw ApiException.badRequest("A recipe needs at least one ingredient line");

        if (r.variantId() != null) {
            boolean ok = jdbc.sql("""
                    select exists (select 1 from product_variants v join products p on p.id = v.product_id
                                   where v.id = :id and p.business_id = :b)
                    """).param("id", r.variantId()).param("b", props.businessId()).query(Boolean.class).single();
            if (!ok) throw ApiException.notFound("Product variant");
        } else {
            String kind = jdbc.sql("select kind from ingredients where id = :id and business_id = :b")
                    .param("id", r.ingredientId()).param("b", props.businessId()).query(String.class).optional()
                    .orElseThrow(() -> ApiException.notFound("Ingredient"));
            if (!"PREPARED".equals(kind)) throw ApiException.badRequest("Only PREPARED ingredients can have a recipe");
        }

        // lines: validate, convert to base units
        List<CostResolver.Line> parsed = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (LineInput li : r.lines()) {
            if (li.ingredientId() == null) throw ApiException.badRequest("Choose an ingredient for every line");
            if (!seen.add(li.ingredientId())) throw ApiException.badRequest("An ingredient can appear only once in a recipe version");
            if (li.quantity() == null || li.quantity().signum() <= 0) throw ApiException.badRequest("Every line needs a quantity above zero");
            BigDecimal waste = li.wastePct() == null ? BigDecimal.ZERO : li.wastePct();
            if (waste.signum() < 0 || waste.compareTo(new BigDecimal("100")) >= 0) {
                throw ApiException.badRequest("Waste % must be between 0 and 99.99");
            }
            String baseUnit = jdbc.sql("select base_unit from ingredients where id = :id and business_id = :b and active")
                    .param("id", li.ingredientId()).param("b", props.businessId()).query(String.class).optional()
                    .orElseThrow(() -> ApiException.badRequest("An ingredient on this recipe does not exist or is inactive"));
            BigDecimal qty;
            try {
                qty = UnitConverter.toIngredientBase(li.quantity(), li.unit(), baseUnit);
            } catch (UnitConverter.UnitConversionException e) {
                throw ApiException.badRequest(e.getMessage());
            }
            parsed.add(new CostResolver.Line(li.ingredientId(), qty, waste));
            notes.add(li.notes());
        }

        // loops: A needs B needs A
        if (r.ingredientId() != null) {
            CostingService.CostBook book = costing.loadBook();
            Set<Long> ids = new LinkedHashSet<>();
            parsed.forEach(l -> ids.add(l.ingredientId()));
            List<Long> loop = CostResolver.findCycle(r.ingredientId(), ids, book::currentChildren);
            if (!loop.isEmpty()) {
                List<String> names = new ArrayList<>();
                loop.forEach(id -> names.add(book.ingredientName(id)));
                throw ApiException.badRequest("This would create a recipe loop: " + String.join(" -> ", names));
            }
        }

        RecipeOwner owner = findOwner(r.variantId(), r.ingredientId());
        long recipeId;
        int versionNo = 1;
        if (owner == null) {
            recipeId = jdbc.sql("""
                    insert into recipes (business_id, variant_id, ingredient_id, created_by) values (:b, :v, :i, :u) returning id
                    """).param("b", props.businessId()).param("v", r.variantId()).param("i", r.ingredientId())
                    .param("u", CurrentUser.id()).query(Long.class).single();
        } else {
            recipeId = owner.id();
            var latest = jdbc.sql("""
                    select max(effective_from) as latest_from, max(version_no) as latest_no from recipe_versions where recipe_id = :r
                    """).param("r", recipeId).query(Latest.class).single();
            if (latest.latestFrom() != null && !r.effectiveFrom().isAfter(latest.latestFrom())) {
                throw ApiException.conflict("A new version must start after the latest version (" + latest.latestFrom()
                        + "). Past versions are never changed, so history stays exact.");
            }
            versionNo = (latest.latestNo() == null ? 0 : latest.latestNo()) + 1;
        }
        long versionId = jdbc.sql("""
                insert into recipe_versions (recipe_id, version_no, effective_from, output_qty, reason, notes, created_by)
                values (:r, :n, :d, :o, :reason, :notes, :u) returning id
                """).param("r", recipeId).param("n", versionNo).param("d", r.effectiveFrom()).param("o", output)
                .param("reason", blank(r.reason())).param("notes", blank(r.notes())).param("u", CurrentUser.id())
                .query(Long.class).single();
        for (int i = 0; i < parsed.size(); i++) {
            CostResolver.Line l = parsed.get(i);
            jdbc.sql("""
                    insert into recipe_lines (version_id, ingredient_id, quantity_base, waste_pct, notes)
                    values (:v, :i, :q, :w, :n)
                    """).param("v", versionId).param("i", l.ingredientId()).param("q", l.quantityBase())
                    .param("w", l.wastePct()).param("n", blank(notes.get(i))).update();
        }
        String after = jdbc.sql("""
                select jsonb_build_object('version', to_jsonb(v), 'lines',
                       (select coalesce(jsonb_agg(to_jsonb(l) order by l.id), '[]'::jsonb) from recipe_lines l where l.version_id = v.id))::text
                from recipe_versions v where v.id = :id
                """).param("id", versionId).query(String.class).single();
        audit.record("RECIPE", recipeId, owner == null ? "CREATE" : "NEW_VERSION", r.effectiveFrom(), null, after,
                blank(r.reason()), "MANUAL");
        return detail(r.variantId(), r.ingredientId());
    }

    public record Latest(LocalDate latestFrom, Integer latestNo) {}

    // ------------------------------------------------------------ cost of a recipe as of a date

    public RecipeCost cost(Long variantId, Long ingredientId, LocalDate asOf) {
        CostingService.requireCosts();
        CurrentUser.require("recipes.view");
        checkOwner(variantId, ingredientId);
        LocalDate d = asOf == null ? clock.today() : asOf;
        CostingService.CostBook book = costing.loadBook();
        CostResolver res = book.resolver();
        CostResolver.Version v = variantId != null ? book.recipeForVariant(variantId, d) : book.recipeForIngredient(ingredientId, d);
        List<String> notes = new ArrayList<>();
        if (v == null) {
            notes.add("No recipe version is in force on " + d + ".");
            return new RecipeCost(d, null, null, null, "NO_RECIPE", null, null, false, List.of(), null, null, null, null,
                    List.of(), notes);
        }
        Long versionId = null;
        Integer versionNo = null;
        RecipeOwner owner = findOwner(variantId, ingredientId);
        if (owner != null) {
            var head = jdbc.sql("""
                    select id, version_no, effective_from, output_qty, reason, notes, created_at from recipe_versions
                    where recipe_id = :r and effective_from <= :d order by effective_from desc limit 1
                    """).param("r", owner.id()).param("d", d).query(VersionHead.class).optional().orElse(null);
            if (head != null) {
                versionId = head.id();
                versionNo = head.versionNo();
            }
        }
        List<LineCost> lines = new ArrayList<>();
        for (CostResolver.Line l : v.lines()) {
            CostResolver.Result u = CostingService.unit(res, l.ingredientId(), d);
            CostingService.IngRow ing = book.ingredients().get(l.ingredientId());
            BigDecimal lineCost = u.cost() == null ? null
                    : CostingService.money(CostResolver.grossQty(l).multiply(u.cost()));
            lines.add(new LineCost(l.ingredientId(), book.ingredientName(l.ingredientId()), ing == null ? null : ing.kind(),
                    ing == null ? null : ing.baseUnit(), l.quantityBase(), l.wastePct(), u.cost(),
                    lineCost, u.cost() == null ? "MISSING" : u.status(), u.estimated(), u.missing()));
        }
        CostResolver.Result total;
        if (variantId != null) {
            total = CostingService.portion(res, variantId, d);
        } else {
            total = CostingService.unit(res, ingredientId, d);
        }
        BigDecimal perOutput = total.cost();
        BigDecimal totalCost = perOutput == null ? null : CostingService.money(perOutput.multiply(v.outputQty()));
        String status = perOutput == null ? "MISSING" : total.status();
        BigDecimal price = null;
        BigDecimal exGst = null;
        BigDecimal marginAmount = null;
        BigDecimal marginPct = null;
        if (variantId != null) {
            price = menuPrice(variantId, d);
            exGst = exGst(price, gstRate(d));
            if (perOutput != null && exGst != null && exGst.signum() > 0) {
                marginAmount = CostingService.money(exGst.subtract(perOutput));
                marginPct = exGst.subtract(perOutput).multiply(new BigDecimal("100")).divide(exGst, 1, RoundingMode.HALF_UP);
            }
            if (price == null) notes.add("No menu price found for this variant on " + d + ".");
            else notes.add("Margin is against the menu price ex-GST (GST-inclusive price divided by 1 + GST rate).");
        }
        if ("PARTIAL".equals(status)) notes.add("Cost is PARTIAL: missing ingredient cost(s) are not counted, so the real cost is higher.");
        return new RecipeCost(d, versionId, versionNo, v.outputQty(), status,
                totalCost, perOutput == null ? null : CostingService.money(perOutput), total.estimated(), total.missing(),
                price, exGst == null ? null : exGst.setScale(2, RoundingMode.HALF_UP), marginAmount, marginPct, lines, notes);
    }

    // ------------------------------------------------------------ helpers

    private void checkOwner(Long variantId, Long ingredientId) {
        if ((variantId == null) == (ingredientId == null)) {
            throw ApiException.badRequest("Give either a product variant or a prepared ingredient (not both)");
        }
    }

    private RecipeOwner findOwner(Long variantId, Long ingredientId) {
        return jdbc.sql("""
                select id, variant_id, ingredient_id from recipes
                where business_id = :b and ((cast(:v as bigint) is not null and variant_id = :v)
                                         or (cast(:i as bigint) is not null and ingredient_id = :i))
                """).param("b", props.businessId()).param("v", variantId).param("i", ingredientId)
                .query(RecipeOwner.class).optional().orElse(null);
    }

    private BigDecimal menuPrice(long variantId, LocalDate d) {
        return jdbc.sql("""
                select mi.price from menu_items mi join menu_versions mv on mv.id = mi.menu_version_id
                where mi.variant_id = :v and mv.business_id = :b and mv.status = 'SCHEDULED' and mv.effective_from <= :d
                order by mv.effective_from desc limit 1
                """).param("v", variantId).param("b", props.businessId()).param("d", d).query(BigDecimal.class)
                .optional().orElse(null);
    }

    private BigDecimal gstRate(LocalDate d) {
        return jdbc.sql("""
                select rate from tax_rules where business_id = :b and tax_type = 'GST' and scope = 'ALL' and effective_from <= :d
                order by effective_from desc limit 1
                """).param("b", props.businessId()).param("d", d).query(BigDecimal.class).optional().orElse(null);
    }

    private static BigDecimal exGst(BigDecimal inclusive, BigDecimal rate) {
        if (inclusive == null || rate == null) return null;
        return inclusive.divide(BigDecimal.ONE.add(rate.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
