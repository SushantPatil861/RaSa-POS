package com.cafepos.ocr;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BillTextParserTest {

    /** A printed GST tax invoice from a wholesale supplier. */
    private static final String WHOLESALE = """
            SHREE GANESH TRADERS
            Wholesale Dairy & Grocery, Shivaji Nagar, Pune 411005
            Ph: 020-25531234  Mob: 98221 12345
            GSTIN: 27AAPFU0939F1ZV
            TAX INVOICE
            Invoice No: GT/2026-27/0456        Date: 12/05/2026
            Customer: Valentina Ice Cream & Cafe
            Sr Particulars         Qty   Rate     Amount
            1  Amul Butter 500g    10    280.00   2800.00
            2  Fresh Cream 1 ltr   6     245.50   1473.00
            3  Sugar               25 kg 44.00    1100.00
            Sub Total                             5373.00
            CGST 2.5%                             134.33
            SGST 2.5%                             134.33
            Round off                             0.34
            GRAND TOTAL                           5642.00
            Amount in words: Five thousand six hundred forty two only
            """;

    /** A kaccha bill: no GSTIN, hand-printed cash memo, quantity x rate. */
    private static final String KACCHA = """
            Cash Memo
            Sharma Dudh Bhandar
            No. 245                      Date 3-5-26
            Milk 5 ltr x 56 = 280.00
            Paneer 2 kg x 320 = 640.00
            Dahi 3 x 40 = 120.00
            Total 1040/-
            Thank you
            """;

    /** A restaurant-style thermal receipt with GST lines and month-name date. */
    private static final String RECEIPT = """
            *** URBAN BAKES & CAFE ***
            GST No 29AABCT1332L1ZG
            Bill No: 8821
            Date: 05-May-2026  Time 18:42
            2 Choco Waffle 120.00 240.00
            1 Mango Shake 150.00
            1 Cold Coffee 90.00
            Total Qty: 4
            Sub Total 480.00
            CGST @2.5% 12.00
            SGST @2.5% 12.00
            Grand Total \u20B9 504.00
            Paid by UPI
            """;

    /** A utility bill: supplier is long, date is ISO, total is labelled "Amount Payable". */
    private static final String ELECTRICITY = """
            MAHARASHTRA STATE ELECTRICITY DISTRIBUTION CO LTD
            Consumer No 170012345678
            Bill Date: 2026-05-09        Due Date: 2026-05-24
            Units consumed 812
            Current charges 7,980.00
            Electricity duty 476.00
            Amount Payable Rs. 8,456.00/-
            Net Amount after due date 8,556.00
            """;

    @Test
    void wholesaleInvoiceFields() {
        BillTextParser.Parsed p = BillTextParser.parse(WHOLESALE);
        assertThat(p.supplierName().value()).isEqualTo("SHREE GANESH TRADERS");
        assertThat(p.supplierName().confidence()).isEqualTo("MEDIUM");
        assertThat(p.invoiceNo().value()).isEqualTo("GT/2026-27/0456");
        assertThat(p.invoiceNo().confidence()).isEqualTo("HIGH");
        assertThat(p.invoiceDate().value()).isEqualTo(LocalDate.of(2026, 5, 12));
        assertThat(p.invoiceDate().confidence()).isEqualTo("HIGH");
        assertThat(p.gstin().value()).isEqualTo("27AAPFU0939F1ZV");
        assertThat(p.total().value()).isEqualByComparingTo("5642.00");
        assertThat(p.total().confidence()).isEqualTo("HIGH");
    }

    @Test
    void wholesaleLineItems() {
        List<BillTextParser.Item> items = BillTextParser.parse(WHOLESALE).items();
        assertThat(items).hasSize(3);
        assertThat(items.get(0).name()).isEqualTo("Amul Butter 500g");
        assertThat(items.get(0).quantity()).isEqualByComparingTo("10");
        assertThat(items.get(0).unitPrice()).isEqualByComparingTo("280.00");
        assertThat(items.get(0).amount()).isEqualByComparingTo("2800.00");
        assertThat(items.get(0).confidence()).isEqualTo("HIGH");
        assertThat(items.get(2).name()).isEqualTo("Sugar");
        assertThat(items.get(2).amount()).isEqualByComparingTo("1100.00");
    }

    @Test
    void kacchaBillQuantityTimesRate() {
        BillTextParser.Parsed p = BillTextParser.parse(KACCHA);
        assertThat(p.gstin()).isNull();
        assertThat(p.supplierName().value()).isEqualTo("Sharma Dudh Bhandar");
        assertThat(p.invoiceNo().value()).isEqualTo("245");
        assertThat(p.invoiceNo().confidence()).isEqualTo("MEDIUM");
        assertThat(p.invoiceDate().value()).isEqualTo(LocalDate.of(2026, 5, 3));
        assertThat(p.items()).hasSize(3);
        assertThat(p.items().get(1).name()).isEqualTo("Paneer");
        assertThat(p.items().get(1).quantity()).isEqualByComparingTo("2");
        assertThat(p.items().get(1).amount()).isEqualByComparingTo("640.00");
        assertThat(p.total().value()).isEqualByComparingTo("1040");
        assertThat(p.total().confidence()).isEqualTo("HIGH"); // items add up to the total
    }

    @Test
    void receiptWithMonthNameDateAndGrandTotalSymbol() {
        BillTextParser.Parsed p = BillTextParser.parse(RECEIPT);
        assertThat(p.supplierName().value()).isEqualTo("URBAN BAKES & CAFE");
        assertThat(p.gstin().value()).isEqualTo("29AABCT1332L1ZG");
        assertThat(p.invoiceNo().value()).isEqualTo("8821");
        assertThat(p.invoiceDate().value()).isEqualTo(LocalDate.of(2026, 5, 5));
        assertThat(p.total().value()).isEqualByComparingTo("504.00");
        assertThat(p.items()).hasSize(3);
        assertThat(p.items().get(0).name()).isEqualTo("Choco Waffle");
        assertThat(p.items().get(0).quantity()).isEqualByComparingTo("2");
        assertThat(p.items().get(0).confidence()).isEqualTo("HIGH");
        assertThat(p.items().get(1).confidence()).isEqualTo("LOW"); // amount only, no quantity
    }

    @Test
    void utilityBillPrefersAmountPayableAndSkipsDueDate() {
        BillTextParser.Parsed p = BillTextParser.parse(ELECTRICITY);
        assertThat(p.supplierName().value()).isEqualTo("MAHARASHTRA STATE ELECTRICITY DISTRIBUTION CO LTD");
        assertThat(p.invoiceDate().value()).isEqualTo(LocalDate.of(2026, 5, 9));
        // "Amount Payable" (8,456.00) and "Net Amount" (8,556.00) are both top-priority; the largest plausible wins
        assertThat(p.total().value()).isEqualByComparingTo("8556.00");
        assertThat(p.total().confidence()).isEqualTo("HIGH");
        assertThat(p.invoiceNo()).isNull();
    }

    @Test
    void nothingFoundIsNullNotZero() {
        BillTextParser.Parsed p = BillTextParser.parse("");
        assertThat(p.supplierName()).isNull();
        assertThat(p.invoiceNo()).isNull();
        assertThat(p.invoiceDate()).isNull();
        assertThat(p.gstin()).isNull();
        assertThat(p.total()).isNull();
        assertThat(p.items()).isEmpty();
        assertThat(BillTextParser.parse(null).total()).isNull();
    }

    @Test
    void totalWithoutLabelIsLowConfidence() {
        BillTextParser.Parsed p = BillTextParser.parse("Some Shop\nTea 10.00\nSnack 25.50\n");
        assertThat(p.total().value()).isEqualByComparingTo("25.50");
        assertThat(p.total().confidence()).isEqualTo("LOW");
    }

    @Test
    void totalOnTheNextLine() {
        BillTextParser.Parsed p = BillTextParser.parse("My Shop\nGRAND TOTAL\n1,234.00\n");
        assertThat(p.total().value()).isEqualByComparingTo("1234.00");
    }

    @Test
    void inconsistentItemIsLowConfidence() {
        List<BillTextParser.Item> items = BillTextParser.parse("Shop\nRice 10 x 50.00 = 900.00\n").items();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).confidence()).isEqualTo("LOW");
    }

    @Test
    void gstinChecksumAndRejectsBadStateCode() {
        assertThat(BillTextParser.validGstinChecksum("27AAPFU0939F1ZV")).isTrue();
        assertThat(BillTextParser.validGstinChecksum("27AAPFU0939F1ZX")).isFalse();
        // regex-shaped but state code 99 is not a state: ignored
        assertThat(BillTextParser.parse("Shop\nGSTIN 99AAPFU0939F1ZV\n").gstin()).isNull();
        // checksum fails (OCR misread) -> still proposed, but only MEDIUM
        assertThat(BillTextParser.parse("Shop\nGSTIN 27AAPFU0939F1ZX\n").gstin().confidence()).isEqualTo("MEDIUM");
    }

    @Test
    void phoneNumbersAreNotAmounts() {
        assertThat(BillTextParser.lastAmount("Total 9822112345")).isNull();
        assertThat(BillTextParser.lastAmount("Total Rs.1,234.50/-")).isEqualByComparingTo("1234.50");
        assertThat(BillTextParser.lastAmount("Total 12/05/2026")).isNull();
    }

    // ---------------------------------------------------------------- draft review rule

    @Test
    void lowConfidenceKeyFieldSubmittedUnchangedMeansEstimated() {
        Map<String, String> proposed = Map.of("totalAmount", "25.50", "supplierName", "Some Shop");
        Map<String, String> conf = Map.of("totalAmount", "LOW", "supplierName", "MEDIUM");
        assertThat(DraftReview.anyLowUnedited(proposed, conf, Map.of("totalAmount", "25.5", "supplierName", "Some Shop"))).isTrue();
        // edited -> reviewer vouched for it
        assertThat(DraftReview.anyLowUnedited(proposed, conf, Map.of("totalAmount", "30", "supplierName", "Some Shop"))).isFalse();
        // nothing was proposed (manual transcription) -> never estimated by this rule
        assertThat(DraftReview.anyLowUnedited(Map.of(), Map.of(), Map.of("totalAmount", "30"))).isFalse();
        // MEDIUM/HIGH proposals do not trigger it
        assertThat(DraftReview.anyLowUnedited(proposed, Map.of("totalAmount", "HIGH"), Map.of("totalAmount", "25.50"))).isFalse();
    }

    @Test
    void sniffsFileTypesByMagicBytesAndSanitisesNames() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] pdf = FileSniffer.ascii("%PDF-1.7\n1 0 obj");
        byte[] webp = FileSniffer.ascii("RIFF\0\0\0\0WEBPVP8 ");
        assertThat(FileSniffer.sniff(png).extension()).isEqualTo("png");
        assertThat(FileSniffer.sniff(jpg).contentType()).isEqualTo("image/jpeg");
        assertThat(FileSniffer.sniff(pdf).image()).isFalse();
        assertThat(FileSniffer.sniff(webp).extension()).isEqualTo("webp");
        assertThat(FileSniffer.sniff(FileSniffer.ascii("<html><script>alert(1)</script>"))).isNull();
        assertThat(FileSniffer.sniff(FileSniffer.ascii("MZ this is an exe renamed .jpg"))).isNull();
        assertThat(FileSniffer.sanitiseName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FileSniffer.sanitiseName("C:\\Users\\x\\bill 1.jpg")).isEqualTo("bill 1.jpg");
        assertThat(FileSniffer.sanitiseName("a<b>|c.jpg")).isEqualTo("a_b__c.jpg");
        assertThat(FileSniffer.sanitiseName("")).isEqualTo("file");
        assertThat(FileSniffer.sanitiseName("..hidden")).isEqualTo("hidden");
        assertThat(FileSniffer.sha256(FileSniffer.ascii("abc"))).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
