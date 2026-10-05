package com.cafepos.orders;

import com.cafepos.audit.AuditService;
import com.cafepos.customers.CustomerService;
import com.cafepos.menu.MenuService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.platform.SequenceService;
import com.cafepos.platform.TaxMath;
import com.cafepos.sales.SalesService;
import com.cafepos.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orders are operational (NEW -> ... -> COMPLETED). On completion a SALE is created automatically,
 * so staff never enter the same order twice. WhatsApp, phone and counter orders share this one model.
 */
@Service
public class OrderService {

    public record OrderLine(@NotNull Long productId, @NotNull Long variantId,
                            @NotNull @DecimalMin("0.001") BigDecimal quantity, BigDecimal discount) {}

    public record CreateOrder(String source, String orderType, Long customerId, String customerName,
                              String customerMobile, String specialInstructions,
                              @NotEmpty @Valid List<OrderLine> items) {}

    public record CompleteOrder(@NotNull String paymentMethod, String reference) {}

    public record OrderRow(long id, String orderNo, LocalDate orderDate, LocalTime orderTime, String source,
                           String orderType, String status, String paymentStatus, BigDecimal totalAmount,
                           String customerName, String customerMobile, Long saleId, String specialInstructions,
                           int version) {}

    public record OrderItemRow(long id, long productId, long variantId, String itemName, BigDecimal quantity,
                               BigDecimal unitPrice, BigDecimal discount, BigDecimal lineTotal) {}

    public record OrderDetail(OrderRow order, List<OrderItemRow> items) {}

    private static final Set<String> SOURCES = Set.of("COUNTER", "WHATSAPP", "PHONE", "OTHER");
    private static final Set<String> TYPES = Set.of("PICKUP", "DELIVERY", "DINE_IN", "TAKEAWAY");
    private static final Map<String, Set<String>> NEXT = Map.of(
            "NEW", Set.of("CONFIRMED", "PREPARING", "CANCELLED"),
            "CONFIRMED", Set.of("PREPARING", "CANCELLED"),
            "PREPARING", Set.of("READY", "CANCELLED"),
            "READY", Set.of("OUT_FOR_DELIVERY", "COMPLETED", "CANCELLED"),
            "OUT_FOR_DELIVERY", Set.of("COMPLETED", "CANCELLED"));

    private static final String SELECT = """
            select o.id, o.order_no, o.order_date, o.order_time, o.source, o.order_type, o.status, o.payment_status,
                   o.total_amount, c.name as customer_name, c.mobile as customer_mobile, o.sale_id,
                   o.special_instructions, o.version
            from orders o left join customers c on c.id = o.customer_id
            """;

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final AuditService audit;
    private final BusinessClock clock;
    private final SequenceService sequences;
    private final MenuService menus;
    private final SalesService sales;
    private final CustomerService customers;

    public OrderService(JdbcClient jdbc, CafeProps props, AuditService audit, BusinessClock clock,
                        SequenceService sequences, MenuService menus, SalesService sales, CustomerService customers) {
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
        this.sequences = sequences;
        this.menus = menus;
        this.sales = sales;
        this.customers = customers;
    }

    public List<OrderRow> list(String status, LocalDate from, LocalDate to) {
        return jdbc.sql(SELECT + """
                where o.business_id = :b and o.order_date between :f and :t
                  and (cast(:st as text) is null or o.status = :st)
                order by o.id desc limit 500
                """).param("b", props.businessId()).param("f", from).param("t", to)
                .param("st", status == null || status.isBlank() ? null : status).query(OrderRow.class).list();
    }

    public OrderDetail get(long id) {
        OrderRow o = jdbc.sql(SELECT + " where o.id = :id and o.business_id = :b").param("id", id)
                .param("b", props.businessId()).query(OrderRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Order"));
        List<OrderItemRow> items = jdbc.sql("""
                select id, product_id, variant_id, item_name, quantity, unit_price, discount, line_total
                from order_items where order_id = :id order by id
                """).param("id", id).query(OrderItemRow.class).list();
        return new OrderDetail(o, items);
    }

    @Transactional
    public OrderDetail create(CreateOrder r) {
        CurrentUser.require("orders.manage");
        String source = r.source() == null ? "COUNTER" : r.source();
        String type = r.orderType() == null ? "TAKEAWAY" : r.orderType();
        if (!SOURCES.contains(source)) throw ApiException.badRequest("Unknown order source: " + source);
        if (!TYPES.contains(type)) throw ApiException.badRequest("Unknown order type: " + type);
        LocalDate today = clock.today();
        Long customerId = r.customerId();
        if (customerId == null && r.customerMobile() != null && !r.customerMobile().isBlank()) {
            customerId = customers.findOrCreate(r.customerName(), r.customerMobile());
        }
        BigDecimal total = BigDecimal.ZERO;
        List<Object[]> rows = new ArrayList<>();
        for (OrderLine l : r.items()) {
            var mi = menus.priceFor(l.variantId(), today).orElseThrow(() ->
                    ApiException.badRequest("This item is not on today's menu (variant " + l.variantId() + ")"));
            if (!"AVAILABLE".equals(mi.availability())) {
                throw ApiException.badRequest(mi.displayName() + " is " + mi.availability().toLowerCase().replace('_', ' '));
            }
            BigDecimal disc = l.discount() == null ? BigDecimal.ZERO : l.discount();
            BigDecimal gross = TaxMath.scale2(l.quantity().multiply(mi.price()));
            if (disc.signum() < 0 || disc.compareTo(gross) > 0) throw ApiException.badRequest("Invalid discount");
            BigDecimal lineTotal = gross.subtract(disc);
            total = total.add(lineTotal);
            rows.add(new Object[]{mi, l, lineTotal, disc});
        }
        String prefix = "WHATSAPP".equals(source) ? "WA" : "ORD";
        long id = jdbc.sql("""
                insert into orders (business_id, order_no, order_date, customer_id, source, order_type, total_amount,
                                    special_instructions, created_by)
                values (:b, :no, :d, :c, :s, :t, :tot, :si, :u) returning id
                """)
                .param("b", props.businessId()).param("no", sequences.next(prefix, "")).param("d", today)
                .param("c", customerId).param("s", source).param("t", type).param("tot", total)
                .param("si", r.specialInstructions()).param("u", CurrentUser.id()).query(Long.class).single();
        for (Object[] row : rows) {
            MenuService.MenuItemDto mi = (MenuService.MenuItemDto) row[0];
            OrderLine l = (OrderLine) row[1];
            jdbc.sql("""
                    insert into order_items (order_id, product_id, variant_id, menu_item_id, item_name, quantity,
                                             unit_price, discount, line_total)
                    values (:o, :p, :v, :mi, :n, :q, :up, :d, :lt)
                    """)
                    .param("o", id).param("p", mi.productId()).param("v", mi.variantId()).param("mi", mi.id())
                    .param("n", mi.displayName() + (mi.variantName().equals("Regular") ? "" : " (" + mi.variantName() + ")"))
                    .param("q", l.quantity()).param("up", mi.price()).param("d", (BigDecimal) row[3])
                    .param("lt", (BigDecimal) row[2]).update();
        }
        audit.record("ORDER", id, "CREATE", today, null,
                "{\"source\":\"" + source + "\",\"total\":\"" + total + "\"}", null, source);
        return get(id);
    }

    @Transactional
    public OrderDetail setStatus(long id, String status) {
        OrderRow o = get(id).order();
        Set<String> allowed = NEXT.getOrDefault(o.status(), Set.of());
        if (!allowed.contains(status) || "COMPLETED".equals(status)) {
            throw ApiException.conflict("Cannot move an order from " + o.status() + " to " + status
                    + ("COMPLETED".equals(status) ? " here. Use the complete action (it takes payment)." : ""));
        }
        if (Set.of("PREPARING", "READY").contains(status)) {
            if (!CurrentUser.has("orders.prepare") && !CurrentUser.has("orders.manage")) {
                throw ApiException.forbidden("You do not have permission to change order status");
            }
        } else {
            CurrentUser.require("orders.manage");
        }
        jdbc.sql("update orders set status = :s, version = version + 1, updated_at = now() where id = :id")
                .param("s", status).param("id", id).update();
        audit.record("ORDER", id, "STATUS", o.orderDate(), "{\"status\":\"" + o.status() + "\"}",
                "{\"status\":\"" + status + "\"}", null, "MANUAL");
        return get(id);
    }

    /** Completing an order takes payment and creates the sale automatically. */
    @Transactional
    public OrderDetail complete(long id, CompleteOrder r) {
        CurrentUser.require("orders.manage");
        CurrentUser.require("sales.create");
        OrderDetail d = get(id);
        OrderRow o = d.order();
        if (!Set.of("NEW", "CONFIRMED", "PREPARING", "READY", "OUT_FOR_DELIVERY").contains(o.status())) {
            throw ApiException.conflict("Order is already " + o.status());
        }
        if (!SalesService.METHODS.contains(r.paymentMethod())) {
            throw ApiException.badRequest("Unknown payment method: " + r.paymentMethod());
        }
        List<SalesService.Line> lines = new ArrayList<>();
        for (OrderItemRow i : d.items()) {
            lines.add(new SalesService.Line(i.productId(), i.variantId(), i.quantity(), i.unitPrice(), i.discount()));
        }
        String source = "WHATSAPP".equals(o.source()) ? "WHATSAPP" : "COUNTER";
        SalesService.SaleRequest req = new SalesService.SaleRequest(o.orderDate(), o.source(), source, null,
                "From order " + o.orderNo(), lines,
                List.of(new SalesService.Pay(r.paymentMethod(), o.totalAmount(), r.reference())),
                null, null, null);
        SalesService.SaleDetail sale = sales.createFromOrder(req, id);
        jdbc.sql("""
                update orders set status = 'COMPLETED', payment_status = 'PAID', sale_id = :s,
                       version = version + 1, updated_at = now() where id = :id
                """).param("s", sale.sale().id()).param("id", id).update();
        audit.record("ORDER", id, "COMPLETE", o.orderDate(), "{\"status\":\"" + o.status() + "\"}",
                "{\"status\":\"COMPLETED\",\"saleId\":" + sale.sale().id() + "}", null, o.source());
        return get(id);
    }

    @Transactional
    public OrderDetail cancel(long id, String reason) {
        CurrentUser.require("orders.manage");
        OrderRow o = get(id).order();
        if (!NEXT.getOrDefault(o.status(), Set.of()).contains("CANCELLED")) {
            throw ApiException.conflict("Order is already " + o.status());
        }
        jdbc.sql("update orders set status = 'CANCELLED', version = version + 1, updated_at = now() where id = :id")
                .param("id", id).update();
        audit.record("ORDER", id, "CANCEL", o.orderDate(), "{\"status\":\"" + o.status() + "\"}",
                "{\"status\":\"CANCELLED\"}", reason, "MANUAL");
        return get(id);
    }
}
