package com.cafepos.whatsapp;

import com.cafepos.audit.AuditService;
import com.cafepos.customers.CustomerService;
import com.cafepos.notifications.NotificationService;
import com.cafepos.orders.OrderService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
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
 * WhatsApp ordering. Inbound messages are stored and turned into DRAFT orders (never real orders): staff review the
 * parsed lines, fix them, and CONFIRM, which calls the normal OrderService (prices come from today's menu).
 * Outbound messages go through a WhatsAppSender: log-only until cafe.whatsapp.enabled=true.
 */
@Service
public class WhatsAppService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppService.class);
    private static final TypeReference<List<Map<String, Object>>> ITEM_LIST = new TypeReference<>() {};
    private static final Set<String> ORDER_TYPES = Set.of("PICKUP", "DELIVERY", "DINE_IN", "TAKEAWAY");

    private static final String MENU_VERSION_IN_FORCE = """
            (select id from menu_versions where business_id = :b and status = 'SCHEDULED' and effective_from <= :d
             order by effective_from desc limit 1)
            """;

    public record MenuRow(long productId, long variantId, String name, String variantName, BigDecimal price,
                          String availability, String aliases) {}

    public record InboxRow(long messageId, String waNumber, String customerName, String body, String messageStatus,
                           OffsetDateTime createdAt, Long draftId, String parsedItemsJson, String unmatchedText,
                           String draftStatus, Long orderId, String orderNo, String rejectReason, Long customerId) {}

    public record InboxDto(long messageId, String waNumber, String customerName, String body, String messageStatus,
                           OffsetDateTime createdAt, Long draftId, List<Map<String, Object>> parsedItems,
                           String unmatchedText, String draftStatus, Long orderId, String orderNo, String rejectReason,
                           Long customerId) {}

    public record ThreadRow(long id, String direction, String body, String status, OffsetDateTime createdAt) {}

    public record DraftItemInput(Long productId, Long variantId, BigDecimal qty) {}

    public record ItemsRequest(List<DraftItemInput> items, String unmatchedText) {}

    public record ConfirmRequest(String orderType, String specialInstructions, String customerName) {}

    public record ConfirmResult(long orderId, String orderNo, BigDecimal total) {}

    public record SendRequest(String to, String text) {}

    public record SentResult(long messageId, String status, String to, String text) {}

    public record StatusDto(boolean enabled, boolean upiConfigured, boolean webhookSecured, boolean verifyTokenSet) {}

    public record OrderMsgRow(String number, String name, String orderNo, BigDecimal totalAmount, String status,
                              String paymentStatus) {}

    public record IdRow(long id) {}

    public record DraftState(long id, long messageId, String status, String parsedItemsJson, Long customerId,
                             String waNumber, String customerName) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final NotificationService notifications;
    private final OrderService orders;
    private final CustomerService customers;
    private final LoggingSender loggingSender;
    private final MetaCloudSender metaSender;
    private final boolean enabled;
    private final String verifyToken;
    private final String appSecret;
    private final String upiVpa;
    private final boolean allowUnsigned;

    public WhatsAppService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit, ObjectMapper mapper,
                           NotificationService notifications, OrderService orders, CustomerService customers,
                           LoggingSender loggingSender, MetaCloudSender metaSender,
                           @Value("${cafe.whatsapp.enabled:false}") boolean enabled,
                           @Value("${cafe.whatsapp.verify-token:}") String verifyToken,
                           @Value("${cafe.whatsapp.app-secret:}") String appSecret,
                           @Value("${cafe.whatsapp.upi-vpa:}") String upiVpa,
                           @Value("${cafe.whatsapp.allow-unsigned:false}") boolean allowUnsigned) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
        this.mapper = mapper;
        this.notifications = notifications;
        this.orders = orders;
        this.customers = customers;
        this.loggingSender = loggingSender;
        this.metaSender = metaSender;
        this.enabled = enabled;
        this.verifyToken = verifyToken;
        this.appSecret = appSecret;
        this.upiVpa = upiVpa;
        this.allowUnsigned = allowUnsigned;
    }

    private WhatsAppSender sender() {
        return enabled ? metaSender : loggingSender;
    }

    public StatusDto status() {
        CurrentUser.require("whatsapp.view");
        return new StatusDto(enabled, UpiLink.validVpa(upiVpa), !appSecret.isBlank(), !verifyToken.isBlank());
    }

    // ------------------------------------------------------------------ webhook (no logged-in user here)

    /** Meta's GET verification: hub.mode=subscribe and the token we configured. */
    public boolean verifyTokenOk(String mode, String token) {
        return "subscribe".equals(mode) && !verifyToken.isBlank() && SignatureVerifier.constantTimeEquals(verifyToken, token);
    }

    /** Signature check for POSTs. Without an app secret, posts are refused unless allow-unsigned is set (local testing). */
    public boolean signatureAcceptable(byte[] body, String header) {
        if (appSecret.isBlank()) {
            if (!allowUnsigned) log.warn("WhatsApp webhook POST refused: set cafe.whatsapp.app-secret (CAFE_WA_APP_SECRET)");
            return allowUnsigned;
        }
        return SignatureVerifier.verify(appSecret, body, header);
    }

    @Transactional
    public int handleWebhook(byte[] body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            throw ApiException.badRequest("Invalid JSON");
        }
        int stored = 0;
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                Map<String, String> names = new HashMap<>();
                for (JsonNode c : value.path("contacts")) {
                    names.put(c.path("wa_id").asText(""), c.path("profile").path("name").asText(""));
                }
                for (JsonNode msg : value.path("messages")) {
                    if (storeInbound(msg, names)) stored++;
                }
            }
        }
        return stored;
    }

    private boolean storeInbound(JsonNode msg, Map<String, String> names) {
        String waId = msg.path("id").asText(null);
        String from = PhoneNumbers.digits(msg.path("from").asText(""));
        if (from.isEmpty()) return false;
        String type = msg.path("type").asText("text");
        String text = switch (type) {
            case "text" -> msg.path("text").path("body").asText("");
            case "button" -> msg.path("button").path("text").asText("");
            case "interactive" -> {
                JsonNode i = msg.path("interactive");
                String t = i.path("button_reply").path("title").asText("");
                yield t.isEmpty() ? i.path("list_reply").path("title").asText("") : t;
            }
            default -> "";
        };
        boolean readable = !text.isBlank();
        String body = readable ? text.trim() : "[" + type + " message - open WhatsApp to read it]";
        String customerName = blankToNull(names.get(from));
        Long id = jdbc.sql("""
                insert into whatsapp_messages (business_id, direction, wa_message_id, wa_number, customer_name, body, payload, status)
                values (:b, 'IN', :w, :n, :cn, :body, cast(:p as jsonb), 'RECEIVED')
                on conflict (business_id, wa_message_id) where wa_message_id is not null do nothing
                returning id
                """).param("b", props.businessId()).param("w", waId).param("n", from).param("cn", customerName)
                .param("body", body).param("p", msg.toString()).query(Long.class).optional().orElse(null);
        if (id == null) return false; // already received (Meta retries): nothing to do

        String who = customerName != null ? customerName : "+" + from;
        if (!readable) {
            notifications.publish("WHATSAPP_MESSAGE", "WhatsApp " + type + " from " + who, "Open WhatsApp on the phone to read it.", "/whatsapp", "whatsapp.view");
            return true;
        }
        OrderTextParser.Result parsed = OrderTextParser.parse(body, loadMenu(clock.today()));
        boolean looksLikeOrder = !parsed.items().isEmpty() || !parsed.unmatched().isEmpty();
        if (!looksLikeOrder) {
            jdbc.sql("update whatsapp_messages set status = 'PARSED' where id = :id").param("id", id).update();
            notifications.publish("WHATSAPP_MESSAGE", "WhatsApp message from " + who, shorten(body), "/whatsapp", "whatsapp.view");
            return true;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (OrderTextParser.Item i : parsed.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", i.text());
            m.put("productId", i.productId());
            m.put("variantId", i.variantId());
            m.put("name", i.name());
            m.put("qty", i.qty().toPlainString());
            m.put("unitPrice", i.unitPrice().toPlainString());
            m.put("confidence", i.confidence());
            items.add(m);
        }
        jdbc.sql("""
                insert into whatsapp_order_drafts (message_id, parsed_items, unmatched_text, customer_id)
                values (:m, cast(:items as jsonb), :un, :c)
                """).param("m", id).param("items", json(items))
                .param("un", parsed.unmatched().isEmpty() ? null : String.join("\n", parsed.unmatched()))
                .param("c", findCustomerId(from)).update();
        jdbc.sql("update whatsapp_messages set status = 'DRAFTED' where id = :id").param("id", id).update();
        notifications.publish("WHATSAPP_ORDER", "WhatsApp order from " + who, shorten(body), "/whatsapp", "whatsapp.view");
        return true;
    }

    // ------------------------------------------------------------------ inbox

    public List<InboxDto> inbox(String status, int limit) {
        CurrentUser.require("whatsapp.view");
        return jdbc.sql(INBOX_SELECT + """
                where m.business_id = :b and m.direction = 'IN'
                  and (cast(:st as text) is null or d.status = :st)
                order by m.id desc limit :lim
                """).param("b", props.businessId()).param("st", blankToNull(status))
                .param("lim", Math.min(Math.max(limit, 1), 300)).query(InboxRow.class).list().stream().map(this::inboxDto).toList();
    }

    public List<ThreadRow> thread(String number) {
        CurrentUser.require("whatsapp.view");
        String n = PhoneNumbers.digits(number);
        if (n.isEmpty()) throw ApiException.badRequest("A number is required");
        List<ThreadRow> rows = jdbc.sql("""
                select id, direction, body, status, created_at from whatsapp_messages
                where business_id = :b and wa_number = :n order by id desc limit 50
                """).param("b", props.businessId()).param("n", n).query(ThreadRow.class).list();
        List<ThreadRow> asc = new ArrayList<>(rows);
        java.util.Collections.reverse(asc);
        return asc;
    }

    public List<MenuRow> menu(String q) {
        CurrentUser.require("whatsapp.view");
        return jdbc.sql("""
                select mi.product_id, mi.variant_id, mi.display_name as name, mi.variant_name, mi.price, mi.availability,
                       '' as aliases
                from menu_items mi
                where mi.menu_version_id = """ + MENU_VERSION_IN_FORCE + """
                  and (cast(:q as text) is null or lower(mi.display_name) like '%' || lower(:q) || '%'
                       or exists (select 1 from product_aliases a where a.product_id = mi.product_id
                                  and lower(a.alias) like '%' || lower(:q) || '%'))
                order by mi.sort_order, mi.id limit 30
                """).param("b", props.businessId()).param("d", clock.today()).param("q", blankToNull(q))
                .query(MenuRow.class).list();
    }

    // ------------------------------------------------------------------ draft review

    @Transactional
    public InboxDto updateItems(long draftId, ItemsRequest r) {
        CurrentUser.require("whatsapp.manage");
        DraftState d = draft(draftId);
        if (!"PENDING_REVIEW".equals(d.status())) throw ApiException.conflict("This draft is already " + d.status().toLowerCase(Locale.ROOT).replace('_', ' '));
        LocalDate today = clock.today();
        List<Map<String, Object>> items = new ArrayList<>();
        if (r.items() != null) {
            for (DraftItemInput in : r.items()) {
                if (in.variantId() == null || in.qty() == null) throw ApiException.badRequest("Every line needs an item and a quantity");
                if (in.qty().signum() <= 0) throw ApiException.badRequest("Quantity must be above zero");
                MenuRow m = jdbc.sql("""
                        select mi.product_id, mi.variant_id, mi.display_name as name, mi.variant_name, mi.price,
                               mi.availability, '' as aliases
                        from menu_items mi
                        where mi.variant_id = :v and mi.menu_version_id = """ + MENU_VERSION_IN_FORCE + """
                        """).param("v", in.variantId()).param("b", props.businessId()).param("d", today)
                        .query(MenuRow.class).optional()
                        .orElseThrow(() -> ApiException.badRequest("One of the items is not on today's menu"));
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("text", "(set by staff)");
                o.put("productId", m.productId());
                o.put("variantId", m.variantId());
                o.put("name", m.name() + ("Regular".equalsIgnoreCase(m.variantName()) ? "" : " (" + m.variantName() + ")"));
                o.put("qty", in.qty().stripTrailingZeros().toPlainString());
                o.put("unitPrice", m.price().toPlainString());
                o.put("confidence", 1.0);
                items.add(o);
            }
        }
        jdbc.sql("update whatsapp_order_drafts set parsed_items = cast(:i as jsonb), unmatched_text = :u where id = :id")
                .param("i", json(items)).param("u", blankToNull(r.unmatchedText())).param("id", draftId).update();
        return inboxDto(inboxRow(draftId));
    }

    @Transactional
    public ConfirmResult confirm(long draftId, ConfirmRequest r) {
        CurrentUser.require("whatsapp.manage");
        DraftState d = draft(draftId);
        if (!"PENDING_REVIEW".equals(d.status())) throw ApiException.conflict("This draft is already " + d.status().toLowerCase(Locale.ROOT).replace('_', ' '));
        List<Map<String, Object>> items = readItems(d.parsedItemsJson());
        if (items.isEmpty()) throw ApiException.badRequest("Add at least one item before confirming the order");
        String type = r.orderType() == null || r.orderType().isBlank() ? "PICKUP" : r.orderType();
        if (!ORDER_TYPES.contains(type)) throw ApiException.badRequest("Unknown order type: " + type);

        Long customerId = d.customerId();
        if (customerId == null) customerId = findCustomerId(d.waNumber());
        String name = firstNonBlank(r.customerName(), d.customerName());
        if (customerId == null) customerId = customers.findOrCreate(name, PhoneNumbers.storeForm(d.waNumber()));

        List<OrderService.OrderLine> lines = new ArrayList<>();
        for (Map<String, Object> i : items) {
            lines.add(new OrderService.OrderLine(((Number) i.get("productId")).longValue(), ((Number) i.get("variantId")).longValue(),
                    new BigDecimal(String.valueOf(i.get("qty"))), null));
        }
        OrderService.OrderDetail created = orders.create(new OrderService.CreateOrder("WHATSAPP", type, customerId, name,
                null, blankToNull(r.specialInstructions()), lines));
        long orderId = created.order().id();
        jdbc.sql("""
                update whatsapp_order_drafts set status = 'CONFIRMED', order_id = :o, customer_id = :c,
                       reviewed_by = :u, reviewed_at = now() where id = :id
                """).param("o", orderId).param("c", customerId).param("u", CurrentUser.id()).param("id", draftId).update();
        audit.record("WHATSAPP_DRAFT", draftId, "CONFIRM", clock.today(), null,
                json(Map.of("orderId", orderId, "orderNo", created.order().orderNo())), null, "WHATSAPP");
        return new ConfirmResult(orderId, created.order().orderNo(), created.order().totalAmount());
    }

    @Transactional
    public InboxDto reject(long draftId, String reason) {
        CurrentUser.require("whatsapp.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to reject a draft");
        DraftState d = draft(draftId);
        if (!"PENDING_REVIEW".equals(d.status())) throw ApiException.conflict("This draft is already " + d.status().toLowerCase(Locale.ROOT).replace('_', ' '));
        jdbc.sql("""
                update whatsapp_order_drafts set status = 'REJECTED', reject_reason = :r, reviewed_by = :u, reviewed_at = now()
                where id = :id
                """).param("r", reason.trim()).param("u", CurrentUser.id()).param("id", draftId).update();
        audit.record("WHATSAPP_DRAFT", draftId, "REJECT", clock.today(), null, null, reason.trim(), "WHATSAPP");
        return inboxDto(inboxRow(draftId));
    }

    // ------------------------------------------------------------------ outbound

    public SentResult send(String to, String text) {
        CurrentUser.require("whatsapp.manage");
        String number = PhoneNumbers.forWhatsApp(to);
        if (number == null) throw ApiException.badRequest("That does not look like a valid phone number");
        if (text == null || text.isBlank()) throw ApiException.badRequest("Type a message first");
        if (text.length() > 4000) throw ApiException.badRequest("The message is too long (limit 4000 characters)");
        return deliver(number, text.trim());
    }

    public SentResult notifyOrder(long orderId, String template) {
        CurrentUser.require("whatsapp.manage");
        OrderMsgRow o = orderRecipient(orderId);
        String number = PhoneNumbers.forWhatsApp(o.number());
        if (number == null) throw ApiException.badRequest("This order has no valid phone number on its customer");
        String cafe = businessName();
        String text = switch (template == null ? "" : template) {
            case "CONFIRMED" -> MessageTemplates.orderConfirmed(o.name(), o.orderNo(), o.totalAmount(), cafe);
            case "READY" -> MessageTemplates.orderReady(o.name(), o.orderNo(), o.totalAmount(), cafe);
            default -> throw ApiException.badRequest("Unknown message template (use CONFIRMED or READY)");
        };
        return deliver(number, text);
    }

    public SentResult paymentRequest(long orderId) {
        CurrentUser.require("whatsapp.manage");
        if (upiVpa.isBlank()) throw ApiException.badRequest("The cafe UPI id is not set. Ask the owner to set CAFE_WA_UPI_VPA.");
        OrderMsgRow o = orderRecipient(orderId);
        if ("PAID".equals(o.paymentStatus())) throw ApiException.conflict("This order is already marked paid");
        if ("CANCELLED".equals(o.status())) throw ApiException.conflict("This order is cancelled");
        String number = PhoneNumbers.forWhatsApp(o.number());
        if (number == null) throw ApiException.badRequest("This order has no valid phone number on its customer");
        String cafe = businessName();
        String link;
        try {
            link = UpiLink.build(upiVpa, cafe, o.totalAmount(), o.orderNo());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        return deliver(number, MessageTemplates.paymentRequest(o.name(), o.orderNo(), o.totalAmount(), upiVpa, link, cafe));
    }

    /** Sends and stores the OUT message. Not transactional: a FAILED row must survive the error. */
    private SentResult deliver(String number, String text) {
        String status = "SENT";
        String error = null;
        try {
            sender().send(number, text);
        } catch (RuntimeException e) {
            status = "FAILED";
            error = e.getMessage();
            log.warn("WhatsApp send failed: {}", error);
        }
        long id = jdbc.sql("""
                insert into whatsapp_messages (business_id, direction, wa_number, body, payload, status)
                values (:b, 'OUT', :n, :body, cast(:p as jsonb), :s) returning id
                """).param("b", props.businessId()).param("n", number).param("body", text)
                .param("p", json(Map.of("sentBy", CurrentUser.id(), "live", enabled))).param("s", status)
                .query(Long.class).single();
        if ("FAILED".equals(status)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The WhatsApp message could not be sent: " + error);
        }
        return new SentResult(id, status, number, text);
    }

    // ------------------------------------------------------------------ internals

    private static final String INBOX_SELECT = """
            select m.id as message_id, m.wa_number, m.customer_name, m.body, m.status as message_status, m.created_at,
                   d.id as draft_id, d.parsed_items::text as parsed_items_json, d.unmatched_text,
                   d.status as draft_status, d.order_id, o.order_no, d.reject_reason, d.customer_id
            from whatsapp_messages m
            left join whatsapp_order_drafts d on d.message_id = m.id
            left join orders o on o.id = d.order_id
            """;

    private InboxRow inboxRow(long draftId) {
        return jdbc.sql(INBOX_SELECT + " where d.id = :id and m.business_id = :b").param("id", draftId)
                .param("b", props.businessId()).query(InboxRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Order draft"));
    }

    private InboxDto inboxDto(InboxRow r) {
        return new InboxDto(r.messageId(), r.waNumber(), r.customerName(), r.body(), r.messageStatus(), r.createdAt(),
                r.draftId(), r.parsedItemsJson() == null ? List.of() : readItems(r.parsedItemsJson()), r.unmatchedText(),
                r.draftStatus(), r.orderId(), r.orderNo(), r.rejectReason(), r.customerId());
    }

    private DraftState draft(long id) {
        return jdbc.sql("""
                select d.id, d.message_id, d.status, d.parsed_items::text as parsed_items_json, d.customer_id,
                       m.wa_number, m.customer_name
                from whatsapp_order_drafts d join whatsapp_messages m on m.id = d.message_id
                where d.id = :id and m.business_id = :b
                """).param("id", id).param("b", props.businessId()).query(DraftState.class).optional()
                .orElseThrow(() -> ApiException.notFound("Order draft"));
    }

    private OrderMsgRow orderRecipient(long orderId) {
        return jdbc.sql("""
                select coalesce((select m.wa_number from whatsapp_order_drafts d join whatsapp_messages m on m.id = d.message_id
                                 where d.order_id = o.id limit 1), c.mobile) as number,
                       c.name as name, o.order_no, o.total_amount, o.status, o.payment_status
                from orders o left join customers c on c.id = o.customer_id
                where o.id = :id and o.business_id = :b
                """).param("id", orderId).param("b", props.businessId()).query(OrderMsgRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Order"));
    }

    private String businessName() {
        return jdbc.sql("select name from business where id = :b").param("b", props.businessId())
                .query(String.class).optional().orElse("Cafe");
    }

    private List<OrderTextParser.MenuEntry> loadMenu(LocalDate today) {
        List<OrderTextParser.MenuEntry> out = new ArrayList<>();
        List<MenuRow> rows = jdbc.sql("""
                select mi.product_id, mi.variant_id, mi.display_name as name, mi.variant_name, mi.price, mi.availability,
                       coalesce((select string_agg(a.alias, '|') from product_aliases a where a.product_id = mi.product_id), '') as aliases
                from menu_items mi
                where mi.menu_version_id = """ + MENU_VERSION_IN_FORCE + """
                order by mi.sort_order, mi.id
                """).param("b", props.businessId()).param("d", today).query(MenuRow.class).list();
        for (MenuRow r : rows) {
            List<String> aliases = r.aliases() == null || r.aliases().isBlank() ? List.of() : List.of(r.aliases().split("\\|"));
            out.add(new OrderTextParser.MenuEntry(r.productId(), r.variantId(), r.name(), r.variantName(), r.price(), aliases));
        }
        return out;
    }

    /** Finds a customer whose stored mobile ends with the same 10 digits (stored forms vary: 98..., +91-98...). */
    private Long findCustomerId(String number) {
        String local = PhoneNumbers.local10(number);
        String exact = PhoneNumbers.digits(number);
        return jdbc.sql("""
                select id from customers
                where business_id = :b and mobile is not null
                  and (right(regexp_replace(mobile, '\\D', '', 'g'), 10) = :l or regexp_replace(mobile, '\\D', '', 'g') = :e)
                order by id limit 1
                """).param("b", props.businessId()).param("l", local == null ? "-" : local).param("e", exact)
                .query(Long.class).optional().orElse(null);
    }

    private List<Map<String, Object>> readItems(String s) {
        try {
            return s == null ? new ArrayList<>() : mapper.readValue(s, ITEM_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String shorten(String s) {
        return s.length() > 140 ? s.substring(0, 137) + "..." : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonBlank(String a, String b) {
        return blankToNull(a) != null ? a.trim() : blankToNull(b);
    }
}
