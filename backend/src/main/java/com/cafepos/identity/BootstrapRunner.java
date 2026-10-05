package com.cafepos.identity;

import com.cafepos.platform.CafeProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** On the very first start (no users yet) creates the Owner account. */
@Component
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final CafeProps props;

    public BootstrapRunner(JdbcClient jdbc, PasswordEncoder encoder, CafeProps props) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        int count = jdbc.sql("select count(*) from users where business_id = :b")
                .param("b", props.businessId()).query(Integer.class).single();
        if (count > 0) {
            return;
        }
        String password = props.bootstrap().ownerPassword();
        boolean generated = password == null || password.isBlank();
        if (generated) {
            SecureRandom rnd = new SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
            password = sb.toString();
        }
        long roleId = jdbc.sql("select id from roles where business_id = :b and code = 'OWNER'")
                .param("b", props.businessId()).query(Long.class).single();
        jdbc.sql("""
                insert into users (business_id, name, email, password_hash, role_id)
                values (:b, :n, :e, :p, :r)
                """)
                .param("b", props.businessId()).param("n", props.bootstrap().ownerName())
                .param("e", props.bootstrap().ownerEmail()).param("p", encoder.encode(password))
                .param("r", roleId).update();
        log.warn("==================================================================");
        log.warn(" Owner account created: {}", props.bootstrap().ownerEmail());
        if (generated) {
            log.warn(" Generated password (shown ONCE, change it after login): {}", password);
        } else {
            log.warn(" Password taken from CAFE_OWNER_PASSWORD");
        }
        log.warn("==================================================================");
    }
}
