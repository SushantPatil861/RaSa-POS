package com.cafepos.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Default sender while WhatsApp is not connected (cafe.whatsapp.enabled=false): only writes the message to the log. */
@Component
public class LoggingSender implements WhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSender.class);

    @Override
    public void send(String toNumber, String text) {
        log.info("[WhatsApp not connected - message NOT sent] to={} text={}", toNumber, text);
    }
}
