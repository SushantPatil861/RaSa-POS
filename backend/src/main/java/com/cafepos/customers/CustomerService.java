package com.cafepos.customers;

import com.cafepos.platform.ApiException;
import com.cafepos.platform.CafeProps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

@Service
public class CustomerService {

    public record CustomerDto(long id, String name, String mobile, String notes) {}

    private final JdbcClient jdbc;
    private final CafeProps props;

    public CustomerService(JdbcClient jdbc, CafeProps props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public List<CustomerDto> list(String q) {
        return jdbc.sql("""
                select id, name, mobile, notes from customers
                where business_id = :b
                  and (cast(:q as text) is null or lower(name) like lower('%' || :q || '%') or mobile like '%' || :q || '%')
                order by name limit 200
                """).param("b", props.businessId()).param("q", q == null || q.isBlank() ? null : q.trim())
                .query(CustomerDto.class).list();
    }

    /** Finds a customer by mobile, or creates one. Used by order entry. */
    @Transactional
    public long findOrCreate(String name, String mobile) {
        if (mobile != null && !mobile.isBlank()) {
            var existing = jdbc.sql("select id from customers where business_id = :b and mobile = :m")
                    .param("b", props.businessId()).param("m", mobile.trim()).query(Long.class).optional();
            if (existing.isPresent()) return existing.get();
        }
        return jdbc.sql("insert into customers (business_id, name, mobile) values (:b, :n, :m) returning id")
                .param("b", props.businessId()).param("n", name == null || name.isBlank() ? "Customer" : name.trim())
                .param("m", mobile == null || mobile.isBlank() ? null : mobile.trim()).query(Long.class).single();
    }

    @Transactional
    public CustomerDto create(String name, String mobile, String notes) {
        long id = jdbc.sql("insert into customers (business_id, name, mobile, notes) values (:b, :n, :m, :t) returning id")
                .param("b", props.businessId()).param("n", name.trim())
                .param("m", mobile == null || mobile.isBlank() ? null : mobile.trim()).param("t", notes)
                .query(Long.class).single();
        return jdbc.sql("select id, name, mobile, notes from customers where id = :id").param("id", id)
                .query(CustomerDto.class).optional().orElseThrow(() -> ApiException.notFound("Customer"));
    }
}
