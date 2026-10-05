package com.cafepos.customerprofile;

import com.cafepos.platform.ApiException;
import com.cafepos.platform.CafeProps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Read-only customer profile built from orders and sales. Spend counts only COMPLETED orders whose sale is still active. */
@Service
public class CustomerProfileService {

    public record CustomerInfo(long id, String name, String mobile, String notes, OffsetDateTime createdAt) {}

    public record StatsRow(long ordersCount, long completedOrders, long spendOrders, BigDecimal totalSpend,
                           LocalDate firstOrderDate, LocalDate lastOrderDate) {}

    public record FavouriteItem(long productId, String name, BigDecimal quantity, long orders) {}

    public record OrderBrief(long id, String orderNo, LocalDate orderDate, String source, String status,
                             String paymentStatus, BigDecimal totalAmount, String items) {}

    public record Profile(CustomerInfo customer, long ordersCount, long completedOrders, BigDecimal totalSpend,
                          BigDecimal averageOrder, LocalDate firstOrderDate, LocalDate lastOrderDate,
                          List<FavouriteItem> favouriteItems, List<OrderBrief> recentOrders, String whatsappNumber) {}

    private static final String ORDER_SELECT = """
            select o.id, o.order_no, o.order_date, o.source, o.status, o.payment_status, o.total_amount,
                   (select string_agg(i.item_name || ' x' || trim_scale(i.quantity)::text, ', ' order by i.id)
                    from order_items i where i.order_id = o.id) as items
            from orders o
            where o.business_id = :b and o.customer_id = :c
            order by o.order_date desc, o.id desc
            """;

    private final JdbcClient jdbc;
    private final CafeProps props;

    public CustomerProfileService(JdbcClient jdbc, CafeProps props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public Profile profile(long customerId) {
        CustomerInfo c = jdbc.sql("select id, name, mobile, notes, created_at from customers where id = :c and business_id = :b")
                .param("c", customerId).param("b", props.businessId()).query(CustomerInfo.class).optional()
                .orElseThrow(() -> ApiException.notFound("Customer"));
        StatsRow s = jdbc.sql("""
                select count(*) filter (where o.status <> 'CANCELLED') as orders_count,
                       count(*) filter (where o.status = 'COMPLETED') as completed_orders,
                       count(*) filter (where o.status = 'COMPLETED' and s.void_state = 'ACTIVE') as spend_orders,
                       coalesce(sum(s.total_inclusive) filter (where o.status = 'COMPLETED' and s.void_state = 'ACTIVE'), 0) as total_spend,
                       min(o.order_date) filter (where o.status <> 'CANCELLED') as first_order_date,
                       max(o.order_date) filter (where o.status <> 'CANCELLED') as last_order_date
                from orders o left join sales s on s.id = o.sale_id
                where o.business_id = :b and o.customer_id = :c
                """).param("b", props.businessId()).param("c", customerId).query(StatsRow.class).single();
        BigDecimal avg = s.spendOrders() == 0 ? null
                : s.totalSpend().divide(BigDecimal.valueOf(s.spendOrders()), 2, RoundingMode.HALF_UP);
        List<FavouriteItem> fav = jdbc.sql("""
                select oi.product_id, p.name as name, sum(oi.quantity) as quantity, count(distinct oi.order_id) as orders
                from order_items oi
                join orders o on o.id = oi.order_id
                join products p on p.id = oi.product_id
                where o.business_id = :b and o.customer_id = :c and o.status <> 'CANCELLED'
                group by oi.product_id, p.name
                order by sum(oi.quantity) desc, p.name
                limit 5
                """).param("b", props.businessId()).param("c", customerId).query(FavouriteItem.class).list();
        List<OrderBrief> recent = jdbc.sql(ORDER_SELECT + " limit 10").param("b", props.businessId())
                .param("c", customerId).query(OrderBrief.class).list();
        String wa = jdbc.sql("""
                select m.wa_number from whatsapp_order_drafts d join whatsapp_messages m on m.id = d.message_id
                where d.customer_id = :c order by m.id desc limit 1
                """).param("c", customerId).query(String.class).optional().orElse(null);
        return new Profile(c, s.ordersCount(), s.completedOrders(), s.totalSpend(), avg, s.firstOrderDate(),
                s.lastOrderDate(), fav, recent, wa);
    }

    public List<OrderBrief> orders(long customerId, int limit) {
        jdbc.sql("select id from customers where id = :c and business_id = :b").param("c", customerId)
                .param("b", props.businessId()).query(Long.class).optional().orElseThrow(() -> ApiException.notFound("Customer"));
        return jdbc.sql(ORDER_SELECT + " limit :lim").param("b", props.businessId()).param("c", customerId)
                .param("lim", Math.min(Math.max(limit, 1), 500)).query(OrderBrief.class).list();
    }
}
