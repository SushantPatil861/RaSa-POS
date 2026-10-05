package com.cafepos.ocr;

/** Output of one OCR attempt. {@code available=false} means "could not read" (engine missing, timed out): never an upload failure. */
public record OcrResult(boolean available, String engine, String text, Double avgConfidence, String message) {

    public static OcrResult unavailable(String engine, String message) {
        return new OcrResult(false, engine, "", null, message);
    }

    public static OcrResult of(String engine, String text, Double avgConfidence) {
        return new OcrResult(true, engine, text == null ? "" : text, avgConfidence, null);
    }
}
