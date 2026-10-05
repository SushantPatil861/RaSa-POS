package com.cafepos.ocr;

import java.nio.file.Path;

/** Pluggable OCR engine. Implementations must never throw for "engine missing / unreadable": return an unavailable result. */
public interface OcrProvider {

    OcrResult extract(Path file, String docType);
}
