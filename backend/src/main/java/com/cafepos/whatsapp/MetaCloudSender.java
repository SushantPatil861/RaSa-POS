package com.cafepos.whatsapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Sends through the Meta WhatsApp Cloud API. Used when cafe.whatsapp.enabled=true. */
@Component
public class MetaCloudSender implements WhatsAppSender {

    private final String token;
    private final String phoneNumberId;
    private final String apiVersion;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public MetaCloudSender(@Value("${cafe.whatsapp.token:}") String token,
                           @Value("${cafe.whatsapp.phone-number-id:}") String phoneNumberId,
                           @Value("${cafe.whatsapp.api-version:v20.0}") String apiVersion) {
        this.token = token;
        this.phoneNumberId = phoneNumberId;
        this.apiVersion = apiVersion;
    }

    @Override
    public void send(String toNumber, String text) {
        if (token == null || token.isBlank() || phoneNumberId == null || phoneNumberId.isBlank()) {
            throw new IllegalStateException("WhatsApp is switched on but CAFE_WA_TOKEN or CAFE_WA_PHONE_ID is not set");
        }
        String body = "{\"messaging_product\":\"whatsapp\",\"recipient_type\":\"individual\",\"to\":" + JsonText.quote(toNumber)
                + ",\"type\":\"text\",\"text\":{\"preview_url\":false,\"body\":" + JsonText.quote(text) + "}}";
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://graph.facebook.com/" + apiVersion + "/" + phoneNumberId + "/messages"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                String b = res.body() == null ? "" : res.body();
                throw new IllegalStateException("WhatsApp API answered " + res.statusCode() + ": " + (b.length() > 300 ? b.substring(0, 300) : b));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not reach the WhatsApp API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sending was interrupted", e);
        }
    }
}
