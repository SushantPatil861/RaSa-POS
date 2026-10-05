package com.cafepos.whatsapp;

/** WhatsApp numbers arrive as digits with country code (919272467796); customers are usually stored as 10 digits. */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    public static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    /** International digits for the WhatsApp API (10-digit Indian numbers get 91). Null when it cannot be a phone number. */
    public static String forWhatsApp(String s) {
        String d = digits(s);
        if (d.length() == 10) d = "91" + d;
        else if (d.length() == 11 && d.startsWith("0")) d = "91" + d.substring(1);
        return d.length() < 11 || d.length() > 15 ? null : d;
    }

    /** The 10-digit Indian mobile, or null for anything else. */
    public static String local10(String s) {
        String d = digits(s);
        if (d.length() == 12 && d.startsWith("91")) return d.substring(2);
        if (d.length() == 11 && d.startsWith("0")) return d.substring(1);
        return d.length() == 10 ? d : null;
    }

    /** How the number is stored on a customer: 10 digits for Indian numbers, all digits otherwise. */
    public static String storeForm(String s) {
        String l = local10(s);
        return l != null ? l : digits(s);
    }
}
