package com.cafepos.whatsapp;

/** Outbound text messages. Implementations throw a RuntimeException when the message could not be sent. */
public interface WhatsAppSender {

    void send(String toNumber, String text);
}
