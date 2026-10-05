package com.cafepos.imports;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the FIRST sheet of an .xlsx (or .xls) workbook as text cells. Real Excel dates become ISO text (yyyy-MM-dd) so
 * the importer never has to guess a locale; everything else is the text Excel shows (DataFormatter), formulas are
 * evaluated. List index + 1 = the Excel row number (blank rows are kept as empty lists).
 */
public final class XlsxReader {

    public static final int MAX_ROWS = 20000;

    private XlsxReader() {}

    public static List<List<String>> read(byte[] data) throws IOException {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            if (wb.getNumberOfSheets() == 0) return new ArrayList<>();
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.ENGLISH);
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            int last = sheet.getLastRowNum();
            if (last > MAX_ROWS) throw new IOException("The sheet has more than " + MAX_ROWS + " rows");
            List<List<String>> rows = new ArrayList<>();
            for (int i = 0; i <= last; i++) {
                Row row = sheet.getRow(i);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    int lastCell = row.getLastCellNum(); // -1 for an empty row
                    for (int c = 0; c < lastCell; c++) cells.add(text(row.getCell(c), fmt, ev));
                }
                rows.add(cells);
            }
            return rows;
        }
    }

    private static String text(Cell cell, DataFormatter fmt, FormulaEvaluator ev) {
        if (cell == null) return "";
        CellType t = cell.getCellType();
        if (t == CellType.FORMULA) t = cell.getCachedFormulaResultType();
        if (t == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString();
        }
        return fmt.formatCellValue(cell, ev).trim();
    }
}
