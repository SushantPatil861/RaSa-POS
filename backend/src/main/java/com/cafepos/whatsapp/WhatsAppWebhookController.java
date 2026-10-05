package com.cafepos.whatsapp;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Meta WhatsApp Cloud API webhook. Public (no login): protected by the verify token (GET) and the HMAC signature (POST).
 * The URL must be reachable over public HTTPS: https://<your-domain>/api/v1/webhooks/whatsapp
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WhatsAppWebhookController {

    private final WhatsAppService whatsapp;

    public WhatsAppWebhookController(WhatsAppService whatsapp) {
        this.whatsapp = whatsapp;
    }

    @GetMapping("/whatsapp")
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                         @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if (whatsapp.verifyTokenOk(mode, token) && challenge != null) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
    }

    @PostMapping("/whatsapp")
    public ResponseEntity<String> receive(@RequestBody byte[] body,
                                          @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        if (!whatsapp.signatureAcceptable(body, signature)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Bad signature");
        }
        whatsapp.handleWebhook(body);
        return ResponseEntity.ok("ok");
    }
}
