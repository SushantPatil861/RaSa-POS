package com.cafepos.whatsapp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTextParserTest {

    private static OrderTextParser.MenuEntry e(long product, long variant, String name, String variantName, String price, String... aliases) {
        return new OrderTextParser.MenuEntry(product, variant, name, variantName, new BigDecimal(price), List.of(aliases));
    }

    private static final List<OrderTextParser.MenuEntry> MENU = List.of(
            e(1, 11, "Chocolate Waffle", "Regular", "120"),
            e(2, 21, "Mango Shake", "Regular", "150"),
            e(3, 31, "Cold Coffee", "Regular", "110", "cold cofee", "cc"),
            e(4, 41, "Brownie", "Regular", "90"),
            e(5, 51, "Brownie with Ice Cream", "Regular", "140"),
            e(6, 61, "Two Scoop Sundae", "Regular", "180"),
            e(7, 71, "Vanilla Scoop", "Regular", "60"),
            e(8, 81, "Sweet Lassi", "Small", "70"),
            e(8, 82, "Sweet Lassi", "Large", "100"),
            e(9, 91, "Strawberry Cookie", "Regular", "80"));

    private static OrderTextParser.Result parse(String text) {
        return OrderTextParser.parse(text, MENU);
    }

    @Test
    void parsesTheBriefExample() {
        OrderTextParser.Result r = parse("2 choco waffle, 1 mango shake pls");
        assertThat(r.unmatched()).isEmpty();
        assertThat(r.items()).hasSize(2);
        assertThat(r.items().get(0).productId()).isEqualTo(1L);
        assertThat(r.items().get(0).variantId()).isEqualTo(11L);
        assertThat(r.items().get(0).qty()).isEqualByComparingTo("2");
        assertThat(r.items().get(0).unitPrice()).isEqualByComparingTo("120");
        assertThat(r.items().get(0).confidence()).isGreaterThan(0.8);
        assertThat(r.items().get(1).name()).isEqualTo("Mango Shake");
        assertThat(r.items().get(1).qty()).isEqualByComparingTo("1");
    }

    @Test
    void understandsNumberWordsAndSplitsOnAnd() {
        OrderTextParser.Result r = parse("two cold coffee and one brownie");
        assertThat(r.unmatched()).isEmpty();
        assertThat(r.items()).hasSize(2);
        assertThat(r.items().get(0).variantId()).isEqualTo(31L);
        assertThat(r.items().get(0).qty()).isEqualByComparingTo("2");
        assertThat(r.items().get(1).variantId()).isEqualTo(41L);
    }

    @Test
    void understandsHindiEnglishMix() {
        OrderTextParser.Result r = parse("bhaiya do cold coffee aur ek brownie dena please");
        assertThat(r.unmatched()).isEmpty();
        assertThat(r.items()).hasSize(2);
        assertThat(r.items().get(0).qty()).isEqualByComparingTo("2");
        assertThat(r.items().get(1).qty()).isEqualByComparingTo("1");
        assertThat(r.items().get(1).variantId()).isEqualTo(41L);
    }

    @Test
    void quantityStyles() {
        assertThat(parse("3x vanilla scoop").items().get(0).qty()).isEqualByComparingTo("3");
        assertThat(parse("vanilla scoop x2").items().get(0).qty()).isEqualByComparingTo("2");
        assertThat(parse("vanilla scoop 4").items().get(0).qty()).isEqualByComparingTo("4");
        assertThat(parse("5 pcs vanilla scoop").items().get(0).qty()).isEqualByComparingTo("5");
        assertThat(parse("vanilla scoop").items().get(0).qty()).isEqualByComparingTo("1");
        assertThat(parse("3 brownies").items().get(0).qty()).isEqualByComparingTo("3");
    }

    @Test
    void aliasesMatchExactly() {
        OrderTextParser.Result r = parse("1 cc");
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).variantId()).isEqualTo(31L);
        assertThat(r.items().get(0).confidence()).isEqualTo(1.0);
        assertThat(parse("2 cold cofee").items().get(0).variantId()).isEqualTo(31L);
    }

    @Test
    void unknownItemsGoToUnmatchedNeverGuessed() {
        OrderTextParser.Result r = parse("2 pizza, 1 mango shake");
        assertThat(r.items()).hasSize(1);
        assertThat(r.unmatched()).containsExactly("2 pizza");
    }

    @Test
    void ambiguousSizeIsNotGuessed() {
        OrderTextParser.Result r = parse("1 sweet lassi");
        assertThat(r.items()).isEmpty();
        assertThat(r.unmatched()).hasSize(1);
        assertThat(r.unmatched().get(0)).contains("could be");
        assertThat(r.unmatched().get(0)).contains("Large");
    }

    @Test
    void sizeWordResolvesTheVariant() {
        OrderTextParser.Result r = parse("1 large sweet lassi");
        assertThat(r.unmatched()).isEmpty();
        assertThat(r.items().get(0).variantId()).isEqualTo(82L);
        assertThat(r.items().get(0).name()).isEqualTo("Sweet Lassi (Large)");
    }

    @Test
    void numberWordInsideAProductNameIsNotAQuantity() {
        OrderTextParser.Result r = parse("two scoop sundae");
        assertThat(r.unmatched()).isEmpty();
        assertThat(r.items().get(0).variantId()).isEqualTo(61L);
        assertThat(r.items().get(0).qty()).isEqualByComparingTo("1");
        assertThat(parse("2 two scoop sundae").items().get(0).qty()).isEqualByComparingTo("2");
    }

    @Test
    void greetingsAndNoiseCreateNothing() {
        OrderTextParser.Result r = parse("Hello bhai, thanks!");
        assertThat(r.items()).isEmpty();
        assertThat(r.unmatched()).isEmpty();
        assertThat(parse("").items()).isEmpty();
        assertThat(parse(null).items()).isEmpty();
    }

    @Test
    void sameItemTwiceIsMerged() {
        OrderTextParser.Result r = parse("1 brownie, 2 brownie");
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).qty()).isEqualByComparingTo("3");
    }

    @Test
    void newlinesAndPlusSeparateItems() {
        OrderTextParser.Result r = parse("2 mango shake\n1 brownie + 1 vanilla scoop");
        assertThat(r.items()).hasSize(3);
        assertThat(r.unmatched()).isEmpty();
    }

    @Test
    void pluralAndSpellingVariants() {
        assertThat(parse("2 strawberry cookies").items().get(0).variantId()).isEqualTo(91L);
        assertThat(parse("1 mango shakes").items().get(0).variantId()).isEqualTo(21L);
    }

    @Test
    void shortGenericWordIsAmbiguousOrUnmatched() {
        // "waffle" alone matches only the Chocolate Waffle at 0.67 -> accepted; "shake" matches one item too.
        assertThat(parse("1 waffle").items()).hasSize(1);
        // "scoop" matches Vanilla Scoop and Two Scoop Sundae about equally: must not guess
        OrderTextParser.Result r = parse("1 scoop");
        assertThat(r.items().size() + r.unmatched().size()).isEqualTo(1);
    }

    // ------------------------------------------------------------ other pure helpers

    @Test
    void upiLinkIsBuiltAndEncoded() {
        String link = UpiLink.build("valentina@okaxis", "Valentina Ice Cream & Caf\u00e9", new BigDecimal("450"), "WA-000012");
        assertThat(link).isEqualTo("upi://pay?pa=valentina@okaxis&pn=Valentina%20Ice%20Cream%20%26%20Caf%C3%A9&am=450.00&cu=INR&tn=WA-000012");
    }

    @Test
    void upiLinkRefusesBadInput() {
        for (Object[] c : new Object[][]{{"", "x", "10"}, {null, "x", "10"}, {"nobank", "x", "10"}, {"a b@bank", "x", "10"}, {"v@bank", "x", "0"}, {"v@bank", "x", "-5"}}) {
            boolean threw = false;
            try {
                UpiLink.build((String) c[0], (String) c[1], new BigDecimal((String) c[2]), "N1");
            } catch (IllegalArgumentException ex) {
                threw = true;
            }
            assertThat(threw).isTrue();
        }
        assertThat(UpiLink.validVpa("9272467796@ybl")).isTrue();
        assertThat(UpiLink.validVpa("name.surname-1@okhdfcbank")).isTrue();
    }

    @Test
    void webhookSignatureIsVerifiedInConstantTime() {
        byte[] body = "{\"object\":\"whatsapp_business_account\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sig = "sha256=" + SignatureVerifier.sign("topsecret", body);
        assertThat(SignatureVerifier.verify("topsecret", body, sig)).isTrue();
        assertThat(SignatureVerifier.verify("topsecret", body, sig.toUpperCase().replace("SHA256=", "sha256="))).isTrue();
        assertThat(SignatureVerifier.verify("wrong", body, sig)).isFalse();
        assertThat(SignatureVerifier.verify("topsecret", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), sig)).isFalse();
        assertThat(SignatureVerifier.verify("topsecret", body, null)).isFalse();
        assertThat(SignatureVerifier.verify("topsecret", body, "abc")).isFalse();
        assertThat(SignatureVerifier.verify("", body, sig)).isFalse();
        // published HMAC-SHA256 test vector
        assertThat(SignatureVerifier.sign("key", "The quick brown fox jumps over the lazy dog".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
        assertThat(SignatureVerifier.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(SignatureVerifier.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(SignatureVerifier.constantTimeEquals(null, "abd")).isFalse();
    }

    @Test
    void phoneNumberForms() {
        assertThat(PhoneNumbers.local10("919272467796")).isEqualTo("9272467796");
        assertThat(PhoneNumbers.local10("+91-92724 67796")).isEqualTo("9272467796");
        assertThat(PhoneNumbers.local10("09272467796")).isEqualTo("9272467796");
        assertThat(PhoneNumbers.local10("442071234567")).isNull();
        assertThat(PhoneNumbers.forWhatsApp("9272467796")).isEqualTo("919272467796");
        assertThat(PhoneNumbers.forWhatsApp("123")).isNull();
        assertThat(PhoneNumbers.storeForm("919272467796")).isEqualTo("9272467796");
        assertThat(PhoneNumbers.storeForm("442071234567")).isEqualTo("442071234567");
    }

    @Test
    void messageTemplatesUseIndianMoney() {
        assertThat(MessageTemplates.inr(new BigDecimal("450"))).isEqualTo("\u20B9450.00");
        assertThat(MessageTemplates.inr(new BigDecimal("1234567.5"))).isEqualTo("\u20B912,34,567.50");
        assertThat(MessageTemplates.inr(new BigDecimal("12345"))).isEqualTo("\u20B912,345.00");
        assertThat(MessageTemplates.inr(new BigDecimal("123456"))).isEqualTo("\u20B91,23,456.00");
        String m = MessageTemplates.orderConfirmed("Priya", "WA-000012", new BigDecimal("450"), "Valentina");
        assertThat(m).contains("WA-000012");
        assertThat(m).contains("\u20B9450.00");
        assertThat(MessageTemplates.orderReady("Customer", "WA-1", new BigDecimal("10"), "V")).startsWith("Hi, your order WA-1");
        String p = MessageTemplates.paymentRequest("Priya", "WA-000012", new BigDecimal("450"), "v@okaxis", "upi://pay?x", "V");
        assertThat(p).contains("upi://pay?x");
        assertThat(p).contains("v@okaxis");
    }

    @Test
    void jsonTextEscapes() {
        assertThat(JsonText.quote("a\"b\\c\nd")).isEqualTo("\"a\\\"b\\\\c\\nd\"");
        assertThat(JsonText.quote("caf\u00e9 \u20B9")).isEqualTo("\"caf\u00e9 \u20B9\"");
        assertThat(JsonText.quote("\u0001")).isEqualTo("\"\\u0001\"");
    }
}
