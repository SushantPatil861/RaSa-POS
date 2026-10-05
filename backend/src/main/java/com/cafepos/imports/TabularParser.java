package com.cafepos.imports;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Framework-free CSV reader: quoted cells, escaped quotes (""), commas / semicolons / tabs, BOM, CRLF / LF / CR,
 * newlines inside quotes. Every record is returned (blank records are kept as lists of empty strings so that the
 * record index still equals the row number the user sees); a final newline does not create an extra record.
 */
public final class TabularParser {

    private TabularParser() {}

    /** Decodes bytes as UTF-8 (BOM stripped); falls back to Windows-1252, which is what Excel "CSV" files often are. */
    public static String decode(byte[] data) {
        int off = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) off = 3;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, off, data.length - off)).toString();
        } catch (CharacterCodingException e) {
            return new String(data, off, data.length - off, java.nio.charset.Charset.forName("windows-1252"));
        }
    }

    public static List<List<String>> parseCsv(byte[] data) {
        return parseCsv(decode(data));
    }

    public static List<List<String>> parseCsv(String text) {
        if (text.startsWith("﻿")) text = text.substring(1);
        char delim = detectDelimiter(text);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        boolean cellWasQuoted = false;
        boolean any = false; // something was read since the last record end
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }
            if (c == '"' && cell.toString().isBlank() && !cellWasQuoted) {
                inQuotes = true;
                cellWasQuoted = true;
                cell.setLength(0);
                any = true;
            } else if (c == delim) {
                row.add(finishCell(cell, cellWasQuoted));
                cell.setLength(0);
                cellWasQuoted = false;
                any = true;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                row.add(finishCell(cell, cellWasQuoted));
                rows.add(row);
                row = new ArrayList<>();
                cell.setLength(0);
                cellWasQuoted = false;
                any = false;
            } else {
                cell.append(c);
                any = true;
            }
        }
        if (any || cell.length() > 0 || !row.isEmpty()) {
            row.add(finishCell(cell, cellWasQuoted));
            rows.add(row);
        }
        return rows;
    }

    private static String finishCell(StringBuilder cell, boolean quoted) {
        return cell.toString().strip();
    }

    /** Picks the delimiter (comma, semicolon or tab) that occurs most in the first non-empty line, outside quotes. */
    static char detectDelimiter(String text) {
        int commas = 0, semis = 0, tabs = 0;
        boolean inQ = false;
        boolean seenContent = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') inQ = !inQ;
            else if (!inQ) {
                if (c == '\n' || c == '\r') {
                    if (seenContent) break;
                } else {
                    if (!Character.isWhitespace(c)) seenContent = true;
                    if (c == ',') commas++;
                    else if (c == ';') semis++;
                    else if (c == '\t') tabs++;
                }
            }
        }
        if (tabs > commas && tabs >= semis) return '\t';
        if (semis > commas) return ';';
        return ',';
    }

    public static boolean isBlank(List<String> row) {
        for (String c : row) if (c != null && !c.isBlank()) return false;
        return true;
    }

    /** Widest row (the number of columns to offer in the mapping screen). */
    public static int width(List<List<String>> rows) {
        int w = 0;
        for (List<String> r : rows) w = Math.max(w, r.size());
        return w;
    }
}
