package com.cafepos.catalog;

import com.cafepos.audit.AuditService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Service
public class CatalogService {

    public record CategoryDto(long id, String name, Long parentId, int sortOrder, boolean active) {}

    public record ProductDto(long id, String name, long categoryId, String categoryName, String subcategory,
                             String unit, String description, String imageUrl, String lifecycleStatus,
                             LocalDate discontinuedOn) {}

    public record VariantDto(long id, long productId, String name, String size, String status, int sortOrder) {}

    public record ProductDetail(ProductDto product, List<VariantDto> variants) {}

    public record VariantInput(String name, String size) {}

    private static final Set<String> LIFECYCLE =
            Set.of("DRAFT", "ACTIVE", "TEMPORARILY_UNAVAILABLE", "DISCONTINUED", "ARCHIVED");

    private static final String PRODUCT_SELECT = """
            select p.id, p.name, p.category_id, c.name as category_name, p.subcategory, p.unit, p.description,
                   p.image_url, p.lifecycle_status, p.discontinued_on
            from products p join categories c on c.id = p.category_id
            """;

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final AuditService audit;
    private final BusinessClock clock;

    public CatalogService(JdbcClient jdbc, CafeProps props, AuditService audit, BusinessClock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- categories ----

    public List<CategoryDto> categories() {
        return jdbc.sql("""
                select id, name, parent_id, sort_order, active from categories
                where business_id = :b order by sort_order, name
                """).param("b", props.businessId()).query(CategoryDto.class).list();
    }

    @Transactional
    public CategoryDto createCategory(String name, Long parentId) {
        long id = jdbc.sql("""
                insert into categories (business_id, name, parent_id, sort_order)
                values (:b, :n, :p, coalesce((select max(sort_order) from categories where business_id = :b), 0) + 1)
                returning id
                """).param("b", props.businessId()).param("n", name.trim()).param("p", parentId)
                .query(Long.class).single();
        return categories().stream().filter(c -> c.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public CategoryDto updateCategory(long id, String name, Boolean active, Integer sortOrder) {
        int n = jdbc.sql("""
                update categories set name = coalesce(:n, name), active = coalesce(:a, active),
                       sort_order = coalesce(:s, sort_order)
                where id = :id and business_id = :b
                """).param("n", name == null ? null : name.trim()).param("a", active).param("s", sortOrder)
                .param("id", id).param("b", props.businessId()).update();
        if (n == 0) throw ApiException.notFound("Category");
        return categories().stream().filter(c -> c.id() == id).findFirst().orElseThrow();
    }

    // ---- products ----

    public List<ProductDto> products(String q, Long categoryId, String status) {
        return jdbc.sql(PRODUCT_SELECT + """
                where p.business_id = :b
                  and (cast(:q as text) is null or lower(p.name) like lower('%' || :q || '%')
                       or exists (select 1 from product_aliases a where a.product_id = p.id
                                  and lower(a.alias) like lower('%' || :q || '%')))
                  and (cast(:cat as bigint) is null or p.category_id = :cat)
                  and (cast(:st as text) is null or p.lifecycle_status = :st)
                order by c.sort_order, p.name
                """)
                .param("b", props.businessId()).param("q", blankToNull(q)).param("cat", categoryId)
                .param("st", blankToNull(status)).query(ProductDto.class).list();
    }

    public ProductDetail product(long id) {
        ProductDto p = jdbc.sql(PRODUCT_SELECT + " where p.id = :id and p.business_id = :b")
                .param("id", id).param("b", props.businessId()).query(ProductDto.class).optional()
                .orElseThrow(() -> ApiException.notFound("Product"));
        return new ProductDetail(p, variants(id));
    }

    public List<VariantDto> variants(long productId) {
        return jdbc.sql("""
                select id, product_id, name, size, status, sort_order from product_variants
                where product_id = :p order by sort_order, id
                """).param("p", productId).query(VariantDto.class).list();
    }

    @Transactional
    public ProductDetail createProduct(String name, long categoryId, String subcategory, String unit,
                                       String description, String lifecycleStatus, List<VariantInput> variants) {
        String status = lifecycleStatus == null ? "ACTIVE" : lifecycleStatus;
        if (!LIFECYCLE.contains(status)) throw ApiException.badRequest("Unknown lifecycle status: " + status);
        long id = jdbc.sql("""
                insert into products (business_id, name, category_id, subcategory, unit, description, lifecycle_status)
                values (:b, :n, :c, :s, coalesce(:u, 'unit'), :d, :st) returning id
                """)
                .param("b", props.businessId()).param("n", name.trim()).param("c", categoryId)
                .param("s", blankToNull(subcategory)).param("u", blankToNull(unit)).param("d", description)
                .param("st", status).query(Long.class).single();
        List<VariantInput> vs = (variants == null || variants.isEmpty())
                ? List.of(new VariantInput("Regular", null)) : variants;
        int order = 0;
        for (VariantInput v : vs) {
            jdbc.sql("insert into product_variants (product_id, name, size, sort_order) values (:p, :n, :s, :o)")
                    .param("p", id).param("n", v.name().trim()).param("s", blankToNull(v.size()))
                    .param("o", order++).update();
        }
        audit.record("PRODUCT", id, "CREATE", null, null,
                "{\"name\":\"" + name.replace("\"", "'") + "\"}", null, "MANUAL");
        return product(id);
    }

    @Transactional
    public ProductDetail updateProduct(long id, String name, Long categoryId, String subcategory,
                                       String description, String imageUrl) {
        ProductDto before = product(id).product();
        jdbc.sql("""
                update products set name = coalesce(:n, name), category_id = coalesce(:c, category_id),
                       subcategory = coalesce(:s, subcategory), description = coalesce(:d, description),
                       image_url = coalesce(:i, image_url), updated_at = now()
                where id = :id and business_id = :b
                """)
                .param("n", name == null ? null : name.trim()).param("c", categoryId).param("s", subcategory)
                .param("d", description).param("i", imageUrl).param("id", id).param("b", props.businessId())
                .update();
        if (name != null && !name.trim().equalsIgnoreCase(before.name())) {
            // keep the old name searchable and importable
            jdbc.sql("insert into product_aliases (product_id, alias) values (:p, :a) on conflict do nothing")
                    .param("p", id).param("a", before.name()).update();
        }
        audit.record("PRODUCT", id, "UPDATE", null, "{\"name\":\"" + before.name().replace("\"", "'") + "\"}",
                "{\"name\":\"" + (name == null ? before.name() : name).replace("\"", "'") + "\"}", null, "MANUAL");
        return product(id);
    }

    @Transactional
    public ProductDetail setLifecycle(long id, String status, LocalDate effectiveDate, String reason) {
        if (!LIFECYCLE.contains(status)) throw ApiException.badRequest("Unknown lifecycle status: " + status);
        ProductDto before = product(id).product();
        LocalDate disc = "DISCONTINUED".equals(status) ? (effectiveDate == null ? clock.today() : effectiveDate) : null;
        jdbc.sql("""
                update products set lifecycle_status = :s, discontinued_on = :d, updated_at = now()
                where id = :id and business_id = :b
                """).param("s", status).param("d", disc).param("id", id).param("b", props.businessId()).update();
        audit.record("PRODUCT", id, "LIFECYCLE", effectiveDate,
                "{\"status\":\"" + before.lifecycleStatus() + "\"}", "{\"status\":\"" + status + "\"}", reason, "MANUAL");
        return product(id);
    }

    @Transactional
    public VariantDto addVariant(long productId, String name, String size) {
        product(productId);
        long id = jdbc.sql("""
                insert into product_variants (product_id, name, size, sort_order)
                values (:p, :n, :s, coalesce((select max(sort_order) from product_variants where product_id = :p), 0) + 1)
                returning id
                """).param("p", productId).param("n", name.trim()).param("s", blankToNull(size))
                .query(Long.class).single();
        return variants(productId).stream().filter(v -> v.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public VariantDto updateVariant(long productId, long variantId, String name, String size, String status) {
        if (status != null && !Set.of("ACTIVE", "INACTIVE").contains(status)) {
            throw ApiException.badRequest("Unknown variant status: " + status);
        }
        int n = jdbc.sql("""
                update product_variants set name = coalesce(:n, name), size = coalesce(:s, size),
                       status = coalesce(:st, status)
                where id = :id and product_id = :p
                """).param("n", name == null ? null : name.trim()).param("s", size).param("st", status)
                .param("id", variantId).param("p", productId).update();
        if (n == 0) throw ApiException.notFound("Variant");
        return variants(productId).stream().filter(v -> v.id() == variantId).findFirst().orElseThrow();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
