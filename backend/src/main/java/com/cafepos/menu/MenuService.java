package com.cafepos.menu;

import com.cafepos.audit.AuditService;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class MenuService {

    public record VersionRow(long id, String name, LocalDate effectiveFrom, String status, String reason,
                              Long basedOnVersionId, OffsetDateTime createdAt, int itemCount) {}

    public record MenuVersionDto(long id, String name, LocalDate effectiveFrom, String status, String reason,
                                 Long basedOnVersionId, OffsetDateTime createdAt, int itemCount, boolean editable) {}

    public record MenuItemDto(long id, long menuVersionId, long productId, long variantId, BigDecimal price,
                              String displayName, String categoryName, String subcategory, String variantName,
                              String sizeLabel, String description, String imageUrl, String availability,
                              boolean addon, int sortOrder) {}

    public record MenuDetail(MenuVersionDto version, List<MenuItemDto> items) {}

    public record ItemInput(Long productId, Long variantId, BigDecimal price, String displayName,
                            String availability, String description) {}

    public record PriceHistoryDto(long id, long productId, String productName, long variantId, String variantName,
                                  BigDecimal oldPrice, BigDecimal newPrice, LocalDate effectiveDate,
                                  Long oldMenuVersionId, long newMenuVersionId, String newMenuVersionName,
                                  String reason, Long changedBy, OffsetDateTime changedAt) {}

    public record ComparisonRow(long productId, long variantId, String name, String variantName,
                                BigDecimal oldPrice, BigDecimal newPrice, BigDecimal delta, BigDecimal pct,
                                String oldName, String newName, String oldCategory, String newCategory,
                                String oldAvailability, String newAvailability, List<String> changes) {}

    public record Comparison(MenuVersionDto a, MenuVersionDto b, List<ComparisonRow> rows,
                             int added, int removed, int renamed, int priceUp, int priceDown, int unchanged,
                             int categoryChanged, int availabilityChanged) {}

    public record CmpRaw(long productId, long variantId, BigDecimal oldPrice, BigDecimal newPrice,
                          String oldName, String newName, String oldCategory, String newCategory,
                          String oldVariant, String newVariant, String oldAvailability, String newAvailability) {}

    private static final Set<String> AVAILABILITY = Set.of("AVAILABLE", "OUT_OF_STOCK", "TEMPORARILY_UNAVAILABLE");

    private static final String ITEM_SELECT = """
            select id, menu_version_id, product_id, variant_id, price, display_name, category_name, subcategory,
                   variant_name, size_label, description, image_url, availability, is_addon as addon, sort_order
            from menu_items
            """;

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final AuditService audit;
    private final BusinessClock clock;

    public MenuService(JdbcClient jdbc, CafeProps props, AuditService audit, BusinessClock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------ reading

    public List<MenuVersionDto> versions() {
        List<VersionRow> rows = jdbc.sql("""
                select v.id, v.name, v.effective_from, v.status, v.reason, v.based_on_version_id, v.created_at,
                       (select count(*) from menu_items i where i.menu_version_id = v.id) as item_count
                from menu_versions v where v.business_id = :b
                order by v.effective_from desc nulls first, v.id desc
                """).param("b", props.businessId()).query(VersionRow.class).list();
        LocalDate today = clock.today();
        List<MenuVersionDto> out = new ArrayList<>();
        for (VersionRow r : rows) {
            out.add(toDto(r, today, nextEffective(rows, r)));
        }
        return out;
    }

    public MenuVersionDto version(long id) {
        return versions().stream().filter(v -> v.id() == id).findFirst()
                .orElseThrow(() -> ApiException.notFound("Menu version"));
    }

    public MenuDetail detail(long id) {
        return new MenuDetail(version(id), items(id));
    }

    /** The menu version in force on a date (latest scheduled version whose effective date is on/before it). */
    public Optional<MenuVersionDto> inForce(LocalDate date) {
        Optional<Long> id = jdbc.sql("""
                select id from menu_versions
                where business_id = :b and status = 'SCHEDULED' and effective_from <= :d
                order by effective_from desc limit 1
                """).param("b", props.businessId()).param("d", date).query(Long.class).optional();
        return id.map(this::version);
    }

    public MenuDetail activeOn(LocalDate date) {
        LocalDate d = date == null ? clock.today() : date;
        MenuVersionDto v = inForce(d).orElseThrow(() -> ApiException.notFound("Menu in force on " + d));
        return new MenuDetail(v, items(v.id()));
    }

    public List<MenuItemDto> items(long versionId) {
        return jdbc.sql(ITEM_SELECT + " where menu_version_id = :v order by sort_order, id")
                .param("v", versionId).query(MenuItemDto.class).list();
    }

    // ------------------------------------------------------------ writing

    /** "Create New Menu Version" (copy of the menu in force today) / "Copy Existing Menu" (copyFromId given). */
    @Transactional
    public MenuDetail create(String name, String reason, Long copyFromId, boolean blank) {
        Long source = copyFromId;
        if (source == null && !blank) {
            source = inForce(clock.today()).map(MenuVersionDto::id).orElse(null);
        }
        if (source != null) version(source); // must exist
        long id = jdbc.sql("""
                insert into menu_versions (business_id, name, status, reason, based_on_version_id, created_by)
                values (:b, :n, 'DRAFT', :r, :src, :u) returning id
                """)
                .param("b", props.businessId()).param("n", name.trim()).param("r", reason).param("src", source)
                .param("u", CurrentUser.id()).query(Long.class).single();
        if (source != null) {
            jdbc.sql("""
                    insert into menu_items (menu_version_id, product_id, variant_id, price, display_name, category_name,
                                            subcategory, variant_name, size_label, description, image_url,
                                            availability, is_addon, sort_order)
                    select :n, product_id, variant_id, price, display_name, category_name, subcategory, variant_name,
                           size_label, description, image_url, availability, is_addon, sort_order
                    from menu_items where menu_version_id = :s
                    """).param("n", id).param("s", source).update();
        }
        audit.record("MENU_VERSION", id, "CREATE", null, null,
                "{\"name\":\"" + esc(name) + "\",\"copiedFrom\":" + source + "}", reason, "MANUAL");
        return detail(id);
    }

    @Transactional
    public MenuVersionDto rename(long id, String name, String reason) {
        MenuVersionDto v = requireEditable(id);
        jdbc.sql("""
                update menu_versions set name = coalesce(:n, name), reason = coalesce(:r, reason), updated_at = now()
                where id = :id
                """).param("n", name == null ? null : name.trim()).param("r", reason).param("id", id).update();
        audit.record("MENU_VERSION", id, "UPDATE", v.effectiveFrom(), "{\"name\":\"" + esc(v.name()) + "\"}",
                "{\"name\":\"" + esc(name == null ? v.name() : name) + "\"}", reason, "MANUAL");
        return version(id);
    }

    @Transactional
    public MenuItemDto addItem(long versionId, ItemInput in) {
        MenuVersionDto v = requireEditable(versionId);
        if (in.productId() == null || in.variantId() == null || in.price() == null) {
            throw ApiException.badRequest("productId, variantId and price are required");
        }
        checkPrice(in.price());
        String avail = in.availability() == null ? "AVAILABLE" : in.availability();
        checkAvailability(avail);
        long id = jdbc.sql("""
                insert into menu_items (menu_version_id, product_id, variant_id, price, display_name, category_name,
                                        subcategory, variant_name, size_label, description, image_url, availability,
                                        is_addon, sort_order)
                select :v, p.id, pv.id, :price, coalesce(:dn, p.name), c.name, p.subcategory, pv.name, pv.size,
                       coalesce(:desc, p.description), p.image_url, :av, (c.name = 'Add-ons'),
                       coalesce((select max(sort_order) from menu_items where menu_version_id = :v), 0) + 1
                from products p join categories c on c.id = p.category_id
                join product_variants pv on pv.product_id = p.id
                where p.id = :pid and pv.id = :vid and p.business_id = :b
                returning id
                """)
                .param("v", versionId).param("price", in.price()).param("dn", blankToNull(in.displayName()))
                .param("desc", blankToNull(in.description())).param("av", avail).param("pid", in.productId())
                .param("vid", in.variantId()).param("b", props.businessId())
                .query(Long.class).optional()
                .orElseThrow(() -> ApiException.badRequest("Unknown product/variant"));
        audit.record("MENU_ITEM", id, "CREATE", v.effectiveFrom(), null,
                "{\"versionId\":" + versionId + ",\"price\":\"" + in.price() + "\"}", null, "MANUAL");
        if ("SCHEDULED".equals(v.status())) recordPriceEvents(versionId, "Item added to scheduled menu");
        return item(id);
    }

    @Transactional
    public MenuItemDto updateItem(long versionId, long itemId, ItemInput in) {
        MenuVersionDto v = requireEditable(versionId);
        MenuItemDto before = item(itemId);
        if (before.menuVersionId() != versionId) throw ApiException.notFound("Menu item");
        if (in.price() != null) checkPrice(in.price());
        if (in.availability() != null) checkAvailability(in.availability());
        jdbc.sql("""
                update menu_items set price = coalesce(:p, price), display_name = coalesce(:dn, display_name),
                       availability = coalesce(:av, availability), description = coalesce(:d, description)
                where id = :id and menu_version_id = :v
                """)
                .param("p", in.price()).param("dn", blankToNull(in.displayName())).param("av", in.availability())
                .param("d", in.description()).param("id", itemId).param("v", versionId).update();
        MenuItemDto after = item(itemId);
        audit.record("MENU_ITEM", itemId, "UPDATE", v.effectiveFrom(),
                "{\"price\":\"" + before.price() + "\",\"name\":\"" + esc(before.displayName()) + "\",\"availability\":\""
                        + before.availability() + "\"}",
                "{\"price\":\"" + after.price() + "\",\"name\":\"" + esc(after.displayName()) + "\",\"availability\":\""
                        + after.availability() + "\"}", null, "MANUAL");
        if ("SCHEDULED".equals(v.status()) && before.price().compareTo(after.price()) != 0) {
            recordPriceEvents(versionId, "Price edited in scheduled menu");
        }
        return after;
    }

    @Transactional
    public void removeItem(long versionId, long itemId) {
        MenuVersionDto v = requireEditable(versionId);
        MenuItemDto before = item(itemId);
        if (before.menuVersionId() != versionId) throw ApiException.notFound("Menu item");
        jdbc.sql("delete from menu_items where id = :id and menu_version_id = :v").param("id", itemId)
                .param("v", versionId).update();
        audit.record("MENU_ITEM", itemId, "REMOVE", v.effectiveFrom(),
                "{\"versionId\":" + versionId + ",\"name\":\"" + esc(before.displayName()) + "\"}", null, null, "MANUAL");
    }

    /**
     * Schedules a version: "From which date should this menu be effective?".
     * The menu in force until then stays live; this one takes over automatically on that date.
     */
    @Transactional
    public MenuDetail schedule(long id, LocalDate effectiveFrom, String reason) {
        if (effectiveFrom == null) throw ApiException.badRequest("Effective date is required");
        MenuVersionDto v = requireEditable(id);
        Long clash = jdbc.sql("""
                select id from menu_versions
                where business_id = :b and status = 'SCHEDULED' and effective_from = :d and id <> :id
                """).param("b", props.businessId()).param("d", effectiveFrom).param("id", id)
                .query(Long.class).optional().orElse(null);
        if (clash != null) {
            throw ApiException.conflict("Another menu version is already effective on " + effectiveFrom);
        }
        jdbc.sql("""
                update menu_versions set status = 'SCHEDULED', effective_from = :d,
                       reason = coalesce(:r, reason), updated_at = now() where id = :id
                """).param("d", effectiveFrom).param("r", reason).param("id", id).update();
        audit.record("MENU_VERSION", id, "SCHEDULE", effectiveFrom,
                "{\"status\":\"" + v.status() + "\",\"effectiveFrom\":" + (v.effectiveFrom() == null ? "null"
                        : "\"" + v.effectiveFrom() + "\"") + "}",
                "{\"status\":\"SCHEDULED\",\"effectiveFrom\":\"" + effectiveFrom + "\"}", reason, "MANUAL");
        recordPriceEvents(id, reason);
        // a version inserted in the middle changes the predecessor of the next one
        jdbc.sql("""
                select id from menu_versions where business_id = :b and status = 'SCHEDULED'
                  and effective_from > :d order by effective_from limit 1
                """).param("b", props.businessId()).param("d", effectiveFrom).query(Long.class).optional()
                .ifPresent(next -> recordPriceEvents(next, "Predecessor menu changed"));
        return detail(id);
    }

    /** Back to DRAFT. Only possible while the version is still in the future. */
    @Transactional
    public MenuDetail unschedule(long id) {
        MenuVersionDto v = requireEditable(id);
        if (!"SCHEDULED".equals(v.status())) throw ApiException.conflict("Menu is not scheduled");
        jdbc.sql("update menu_versions set status = 'DRAFT', effective_from = null, updated_at = now() where id = :id")
                .param("id", id).update();
        audit.record("MENU_VERSION", id, "UNSCHEDULE", v.effectiveFrom(),
                "{\"effectiveFrom\":\"" + v.effectiveFrom() + "\"}", "{\"status\":\"DRAFT\"}", null, "MANUAL");
        return detail(id);
    }

    // ------------------------------------------------------------ price history & comparison

    public List<PriceHistoryDto> priceHistory(Long productId, Long variantId) {
        return jdbc.sql("""
                select * from (
                  select distinct on (h.variant_id, h.new_menu_version_id)
                         h.id, h.product_id, p.name as product_name, h.variant_id, pv.name as variant_name,
                         h.old_price, h.new_price, h.effective_date, h.old_menu_version_id, h.new_menu_version_id,
                         nv.name as new_menu_version_name, h.reason, h.changed_by, h.changed_at
                  from price_history h
                  join products p on p.id = h.product_id
                  join product_variants pv on pv.id = h.variant_id
                  join menu_versions nv on nv.id = h.new_menu_version_id
                  where h.business_id = :b
                    and (cast(:p as bigint) is null or h.product_id = :p)
                    and (cast(:v as bigint) is null or h.variant_id = :v)
                  order by h.variant_id, h.new_menu_version_id, h.changed_at desc, h.id desc
                ) latest
                order by effective_date desc, product_name, variant_name
                """).param("b", props.businessId()).param("p", productId).param("v", variantId)
                .query(PriceHistoryDto.class).list();
    }

    public Comparison compare(long aId, long bId) {
        MenuVersionDto a = version(aId);
        MenuVersionDto b = version(bId);
        List<CmpRaw> raw = jdbc.sql("""
                select coalesce(a.product_id, b.product_id) as product_id,
                       coalesce(a.variant_id, b.variant_id) as variant_id,
                       a.price as old_price, b.price as new_price,
                       a.display_name as old_name, b.display_name as new_name,
                       a.category_name as old_category, b.category_name as new_category,
                       a.variant_name as old_variant, b.variant_name as new_variant,
                       a.availability as old_availability, b.availability as new_availability
                from (select * from menu_items where menu_version_id = :a) a
                full outer join (select * from menu_items where menu_version_id = :b) b
                  on a.variant_id = b.variant_id
                order by coalesce(b.category_name, a.category_name), coalesce(b.sort_order, a.sort_order)
                """).param("a", aId).param("b", bId).query(CmpRaw.class).list();
        List<ComparisonRow> rows = new ArrayList<>();
        int added = 0, removed = 0, renamed = 0, up = 0, down = 0, same = 0, cat = 0, avail = 0;
        for (CmpRaw r : raw) {
            List<String> ch = new ArrayList<>();
            BigDecimal delta = null, pct = null;
            if (r.oldPrice() == null) {
                ch.add("ADDED");
                added++;
            } else if (r.newPrice() == null) {
                ch.add("REMOVED");
                removed++;
            } else {
                int c = r.newPrice().compareTo(r.oldPrice());
                delta = r.newPrice().subtract(r.oldPrice());
                if (r.oldPrice().signum() != 0) {
                    pct = delta.multiply(BigDecimal.valueOf(100)).divide(r.oldPrice(), 2, RoundingMode.HALF_UP);
                }
                if (c > 0) {
                    ch.add("PRICE_UP");
                    up++;
                } else if (c < 0) {
                    ch.add("PRICE_DOWN");
                    down++;
                } else {
                    same++;
                }
                if (!r.oldName().equals(r.newName())) {
                    ch.add("RENAMED");
                    renamed++;
                }
                if (!r.oldCategory().equals(r.newCategory())) {
                    ch.add("CATEGORY_CHANGED");
                    cat++;
                }
                if (!r.oldAvailability().equals(r.newAvailability())) {
                    ch.add("AVAILABILITY_CHANGED");
                    avail++;
                }
                if (ch.isEmpty()) ch.add("UNCHANGED");
            }
            rows.add(new ComparisonRow(r.productId(), r.variantId(),
                    r.newName() != null ? r.newName() : r.oldName(),
                    r.newVariant() != null ? r.newVariant() : r.oldVariant(),
                    r.oldPrice(), r.newPrice(), delta, pct, r.oldName(), r.newName(), r.oldCategory(),
                    r.newCategory(), r.oldAvailability(), r.newAvailability(), ch));
        }
        return new Comparison(a, b, rows, added, removed, renamed, up, down, same, cat, avail);
    }

    // ------------------------------------------------------------ internals

    /** The menu item (price list entry) in force for a variant on a date, if any. Used by sales. */
    public Optional<MenuItemDto> priceFor(long variantId, LocalDate date) {
        return jdbc.sql(ITEM_SELECT + """
                where variant_id = :v and menu_version_id = (
                  select id from menu_versions where business_id = :b and status = 'SCHEDULED' and effective_from <= :d
                  order by effective_from desc limit 1)
                """).param("v", variantId).param("b", props.businessId()).param("d", date)
                .query(MenuItemDto.class).optional();
    }

    /** Writes price-history events for a scheduled version versus its predecessor (append-only). */
    private void recordPriceEvents(long versionId, String reason) {
        jdbc.sql("""
                with cur as (select id, effective_from from menu_versions where id = :v and status = 'SCHEDULED'),
                prev as (
                  select mv.id from menu_versions mv, cur
                  where mv.business_id = :b and mv.status = 'SCHEDULED' and mv.effective_from < cur.effective_from
                  order by mv.effective_from desc limit 1
                )
                insert into price_history (business_id, product_id, variant_id, old_price, new_price, effective_date,
                                           old_menu_version_id, new_menu_version_id, reason, changed_by)
                select :b, ni.product_id, ni.variant_id, oi.price, ni.price, cur.effective_from,
                       (select id from prev), ni.menu_version_id, :reason, :u
                from menu_items ni
                cross join cur
                left join menu_items oi on oi.menu_version_id = (select id from prev) and oi.variant_id = ni.variant_id
                where ni.menu_version_id = :v
                  and (oi.id is null or oi.price <> ni.price)
                  and (select id from prev) is not null
                """)
                .param("v", versionId).param("b", props.businessId()).param("reason", reason)
                .param("u", CurrentUser.id()).update();
    }

    private MenuVersionDto requireEditable(long id) {
        MenuVersionDto v = version(id);
        if (!MenuStatus.valueOf(v.status()).editable()) {
            throw ApiException.conflict("This menu is " + v.status()
                    + " and is frozen history. Create a new menu version instead (history is never edited).");
        }
        return v;
    }

    private MenuItemDto item(long id) {
        return jdbc.sql(ITEM_SELECT + " where id = :id").param("id", id).query(MenuItemDto.class).optional()
                .orElseThrow(() -> ApiException.notFound("Menu item"));
    }

    private static MenuVersionDto toDto(VersionRow r, LocalDate today, LocalDate next) {
        MenuStatus st = MenuStatus.resolve(r.status(), r.effectiveFrom(), today, next);
        return new MenuVersionDto(r.id(), r.name(), r.effectiveFrom(), st.name(), r.reason(), r.basedOnVersionId(),
                r.createdAt(), r.itemCount(), st.editable());
    }

    private static LocalDate nextEffective(List<VersionRow> all, VersionRow r) {
        if (r.effectiveFrom() == null || !"SCHEDULED".equals(r.status())) return null;
        LocalDate best = null;
        for (VersionRow o : all) {
            if ("SCHEDULED".equals(o.status()) && o.effectiveFrom() != null && o.effectiveFrom().isAfter(r.effectiveFrom())
                    && (best == null || o.effectiveFrom().isBefore(best))) {
                best = o.effectiveFrom();
            }
        }
        return best;
    }

    private static void checkPrice(BigDecimal p) {
        if (p.signum() < 0) throw ApiException.badRequest("Price cannot be negative");
    }

    private static void checkAvailability(String a) {
        if (!AVAILABILITY.contains(a)) throw ApiException.badRequest("Unknown availability: " + a);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
