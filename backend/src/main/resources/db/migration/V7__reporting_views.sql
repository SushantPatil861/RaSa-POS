-- V7: one row per trading day with the figures that REPORTS use.
-- Rules (see docs/HLD): itemised and quick-entry totals are never summed.
--   only itemised      -> itemised figures
--   only quick entry   -> quick-entry figures (product-level metrics stay unavailable for that day)
--   both               -> itemised, unless an approver accepted the quick total (and the approval is not stale)
create or replace view v_daily_sales as
with it as (
  select business_id, txn_date,
         count(*)                        as orders_count,
         sum(gross_taxable)              as gross_taxable,
         sum(discount_taxable)           as discount_taxable,
         sum(net_taxable)                as net_taxable,
         sum(tax_amount)                 as tax_amount,
         sum(total_inclusive)            as total_inclusive
  from sales where void_state = 'ACTIVE'
  group by business_id, txn_date
), pay as (
  select s.business_id, s.txn_date,
         sum(p.amount) filter (where p.method = 'CASH')         as cash,
         sum(p.amount) filter (where p.method = 'UPI')          as upi,
         sum(p.amount) filter (where p.method = 'CARD')         as card,
         sum(p.amount) filter (where p.method = 'WHATSAPP')     as whatsapp,
         sum(p.amount) filter (where p.method = 'SWIGGY')       as swiggy,
         sum(p.amount) filter (where p.method = 'ZOMATO')       as zomato,
         sum(p.amount) filter (where p.method = 'OTHER_ONLINE') as other_online
  from payments p join sales s on s.id = p.sale_id
  where s.void_state = 'ACTIVE' and p.status = 'PAID'
  group by s.business_id, s.txn_date
), sm as (
  select business_id, txn_date, orders_count, gross_taxable, discount_taxable, refund_taxable, net_taxable, tax_amount,
         (total_sales - discounts - refunds) as net_total_inclusive,
         cash, upi, card, whatsapp, swiggy, zomato, other_online, wastage, other_income
  from daily_sales_summaries where void_state = 'ACTIVE'
), days as (
  select business_id, txn_date from it
  union
  select business_id, txn_date from sm
), j as (
  select d.business_id, d.txn_date,
         (it.txn_date is not null) as has_itemised,
         (sm.txn_date is not null) as has_summary,
         rv.decision, rv.itemised_total as rv_it, rv.summary_total as rv_sm,
         it.total_inclusive as it_total, sm.net_total_inclusive as sm_total,
         it.orders_count as it_orders, it.gross_taxable as it_gross, it.discount_taxable as it_disc,
         it.net_taxable as it_net, it.tax_amount as it_tax,
         sm.orders_count as sm_orders, sm.gross_taxable as sm_gross, sm.discount_taxable as sm_disc,
         sm.refund_taxable as sm_refund, sm.net_taxable as sm_net, sm.tax_amount as sm_tax,
         pay.cash as p_cash, pay.upi as p_upi, pay.card as p_card, pay.whatsapp as p_wa,
         pay.swiggy as p_sw, pay.zomato as p_zo, pay.other_online as p_oo,
         sm.cash as s_cash, sm.upi as s_upi, sm.card as s_card, sm.whatsapp as s_wa,
         sm.swiggy as s_sw, sm.zomato as s_zo, sm.other_online as s_oo,
         sm.wastage, sm.other_income
  from days d
  left join it  on it.business_id  = d.business_id and it.txn_date  = d.txn_date
  left join sm  on sm.business_id  = d.business_id and sm.txn_date  = d.txn_date
  left join pay on pay.business_id = d.business_id and pay.txn_date = d.txn_date
  left join day_reviews rv on rv.business_id = d.business_id and rv.txn_date = d.txn_date
), k as (
  select j.*,
         case when has_itemised and has_summary then 'BOTH'
              when has_itemised then 'ITEMISED' else 'SUMMARY' end as basis,
         (rv_it is not null and (rv_it <> coalesce(it_total,0) or rv_sm <> coalesce(sm_total,0))) as stale
  from j
)
select k.business_id, k.txn_date, k.basis,
       case when k.basis <> 'BOTH' then 'NOT_APPLICABLE'
            when k.decision is null then 'PENDING_APPROVAL'
            when k.stale then 'STALE'
            else 'APPROVED' end as review_status,
       k.decision,
       case when k.basis = 'SUMMARY' or (k.basis = 'BOTH' and k.decision = 'ACCEPT_SUMMARY' and not k.stale)
            then 'SUMMARY' else 'ITEMISED' end as chosen,
       coalesce(k.it_total, 0) as itemised_total,
       coalesce(k.sm_total, 0) as summary_total,
       case when k.basis = 'BOTH' then k.it_total - k.sm_total end as difference,
       c.total_inclusive, c.orders_count, c.gross_taxable, c.discount_taxable, c.refund_taxable, c.net_taxable, c.tax_amount,
       c.cash, c.upi, c.card, c.whatsapp, c.swiggy, c.zomato, c.other_online,
       k.wastage, k.other_income
from k
cross join lateral (
  select
    case when k.basis = 'SUMMARY' or (k.basis = 'BOTH' and k.decision = 'ACCEPT_SUMMARY' and not k.stale)
         then k.sm_total else k.it_total end as total_inclusive,
    case when k.basis = 'SUMMARY' or (k.basis = 'BOTH' and k.decision = 'ACCEPT_SUMMARY' and not k.stale)
         then k.sm_orders else k.it_orders end as orders_count,
    case when k.basis = 'SUMMARY' or (k.basis = 'BOTH' and k.decision = 'ACCEPT_SUMMARY' and not k.stale)
         then k.sm_gross else k.it_gross end as gross_taxable,
    case when k.basis = 'SUMMARY' or (k.basis = 'BOTH' and k.decision = 'ACCEPT_SUMMARY' and not k.stale)
         then k.sm_disc else k.it_disc end as discount_taxable,
    case when k.basis = 'SUMMARY' or (k.basis = 'BOTH' and k.decision = 'ACCEPT_SUMMARY' and not k.stale)
         then k.sm_refund else 0 end as refund_taxable,
    case when k.basis = 'SUMMARY' or (k.basis = 'BOTH' and k.decision = 'ACCEPT_SUMMARY' and not k.stale)
         then k.sm_net else k.it_net end as net_taxable,
    case when k.basis = 'SUMMARY' or (k.basis = 'BOTH' and k.decision = 'ACCEPT_SUMMARY' and not k.stale)
         then k.sm_tax else k.it_tax end as tax_amount,
    case when k.basis = 'SUMMARY' or (k.basis = 'BOTH' and k.decision = 'ACCEPT_SUMMARY' and not k.stale)
         then k.s_cash else k.p_cash end as cash,
    case when k.basis = 'SUMMARY' or (k.basis = 'BOTH' and k.decision = 'ACCEPT_SUMMARY' and not k.stale)
         then k.s_upi else k.p_upi end as upi,
    case when k.basis = 'SUMMARY' or (k.basis = 'BOTH' and k.decision = 'ACCEPT_SUMMARY' and not k.stale)
         then k.s_card else k.p_card end as card,
    case when k.basis = 'SUMMARY' or (k.basis = 'BOTH' and k.decision = 'ACCEPT_SUMMARY' and not k.stale)
         then k.s_wa else k.p_wa end as whatsapp,
    case when k.basis = 'SUMMARY' or (k.basis = 'BOTH' and k.decision = 'ACCEPT_SUMMARY' and not k.stale)
         then k.s_sw else k.p_sw end as swiggy,
    case when k.basis = 'SUMMARY' or (k.basis = 'BOTH' and k.decision = 'ACCEPT_SUMMARY' and not k.stale)
         then k.s_zo else k.p_zo end as zomato,
    case when k.basis = 'SUMMARY' or (k.basis = 'BOTH' and k.decision = 'ACCEPT_SUMMARY' and not k.stale)
         then k.s_oo else k.p_oo end as other_online
) c;
