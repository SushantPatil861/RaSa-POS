package com.cafepos.ocr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Local Tesseract through its command line: {@code tesseract <file> stdout -l eng --psm 6}.
 * Printed text only; handwriting is mostly manual transcription (the review screen shows the image next to the form).
 * If the binary is missing, OCR is switched off, or it takes longer than the timeout, the result is "unavailable".
 */
@Component
public class TesseractCliOcr implements OcrProvider {

    private static final Logger log = LoggerFactory.getLogger(TesseractCliOcr.class);
    public static final String ENGINE = "tesseract-cli";

    private final boolean enabled;
    private final String binary;
    private final String lang;
    private final int timeoutSeconds;

    public TesseractCliOcr(@Value("${cafe.ocr.enabled:true}") boolean enabled,
                           @Value("${cafe.ocr.tesseract-path:tesseract}") String binary,
                           @Value("${cafe.ocr.lang:eng}") String lang,
                           @Value("${cafe.ocr.timeout-seconds:60}") int timeoutSeconds) {
        this.enabled = enabled;
        this.binary = binary;
        this.lang = lang;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public OcrResult extract(Path file, String docType) {
        if (!enabled) return OcrResult.unavailable(ENGINE, "OCR is switched off (cafe.ocr.enabled=false)");
        ProcessBuilder pb = new ProcessBuilder(List.of(binary, file.toAbsolutePath().toString(), "stdout",
                "-l", lang, "--psm", "6"));
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            log.warn("Tesseract could not be started ({}): {}", binary, e.getMessage());
            return OcrResult.unavailable(ENGINE, "Tesseract is not installed on this server");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                in.transferTo(out);
            } catch (IOException ignored) {
                // process was killed
            }
        }, "tesseract-reader");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return OcrResult.unavailable(ENGINE, "Tesseract took longer than " + timeoutSeconds + " seconds");
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            return OcrResult.unavailable(ENGINE, "OCR was interrupted");
        }
        if (p.exitValue() != 0) {
            return OcrResult.unavailable(ENGINE, "Tesseract could not read this file (exit code " + p.exitValue() + ")");
        }
        String text = out.toString(StandardCharsets.UTF_8).strip();
        return OcrResult.of(ENGINE, text, null);
    }
}
