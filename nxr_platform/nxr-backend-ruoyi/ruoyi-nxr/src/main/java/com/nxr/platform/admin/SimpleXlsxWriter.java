package com.nxr.platform.admin;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class SimpleXlsxWriter {

    private SimpleXlsxWriter() {
    }

    static void writeWorkbook(OutputStream outputStream, List<Sheet> sheets) throws IOException {
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            writeEntry(zipOutputStream, "[Content_Types].xml", contentTypes(sheets.size()));
            writeEntry(zipOutputStream, "_rels/.rels", rootRelationships());
            writeEntry(zipOutputStream, "xl/workbook.xml", workbookXml(sheets));
            writeEntry(zipOutputStream, "xl/_rels/workbook.xml.rels", workbookRelationships(sheets.size()));
            writeEntry(zipOutputStream, "xl/styles.xml", stylesXml());

            for (int index = 0; index < sheets.size(); index++) {
                writeEntry(zipOutputStream, "xl/worksheets/sheet" + (index + 1) + ".xml", sheetXml(sheets.get(index)));
            }
        }
    }

    private static void writeEntry(ZipOutputStream zipOutputStream, String name, String content) throws IOException {
        zipOutputStream.putNextEntry(new ZipEntry(name));
        zipOutputStream.write(content.getBytes(StandardCharsets.UTF_8));
        zipOutputStream.closeEntry();
    }

    private static String contentTypes(int sheetCount) {
        StringBuilder builder = new StringBuilder("""
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
              <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
            """);
        for (int index = 1; index <= sheetCount; index++) {
            builder.append("  <Override PartName=\"/xl/worksheets/sheet")
                .append(index)
                .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>\n");
        }
        builder.append("</Types>");
        return builder.toString();
    }

    private static String rootRelationships() {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>
            """;
    }

    private static String workbookXml(List<Sheet> sheets) {
        StringBuilder builder = new StringBuilder("""
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets>
            """);
        for (int index = 0; index < sheets.size(); index++) {
            builder.append("    <sheet name=\"")
                .append(escapeAttribute(sheets.get(index).name()))
                .append("\" sheetId=\"")
                .append(index + 1)
                .append("\" r:id=\"rId")
                .append(index + 1)
                .append("\"/>\n");
        }
        builder.append("""
              </sheets>
            </workbook>
            """);
        return builder.toString();
    }

    private static String workbookRelationships(int sheetCount) {
        StringBuilder builder = new StringBuilder("""
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            """);
        for (int index = 1; index <= sheetCount; index++) {
            builder.append("  <Relationship Id=\"rId")
                .append(index)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                .append(index)
                .append(".xml\"/>\n");
        }
        builder.append("  <Relationship Id=\"rId").append(sheetCount + 1)
            .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>\n");
        builder.append("</Relationships>");
        return builder.toString();
    }

    private static String stylesXml() {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <numFmts count="1"><numFmt numFmtId="164" formatCode="0.0"/></numFmts>
              <fonts count="2">
                <font><sz val="11"/><name val="Calibri"/></font>
                <font><b/><sz val="11"/><name val="Calibri"/></font>
              </fonts>
              <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
              <borders count="2">
                <border><left/><right/><top/><bottom/><diagonal/></border>
                <border><left style="thin"/><right style="thin"/><top style="thin"/><bottom style="thin"/><diagonal/></border>
              </borders>
              <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
              <cellXfs count="3">
                <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                <xf numFmtId="0" fontId="1" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="top"/></xf>
                <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
              </cellXfs>
              <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
            </styleSheet>
            """;
    }

    private static String sheetXml(Sheet sheet) {
        StringBuilder builder = new StringBuilder("""
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
            """);
        List<?> headers = sheet.rows().isEmpty() ? List.of() : sheet.rows().get(0);
        int rowNumber = 1;
        for (List<?> row : sheet.rows()) {
            builder.append("    <row r=\"").append(rowNumber).append("\">");
            for (int columnIndex = 0; columnIndex < row.size(); columnIndex++) {
                Object value = row.get(columnIndex);
                String cellRef = columnName(columnIndex + 1) + rowNumber;
                // Formatting changes display only; keep the original Number and text values.
                int style = rowNumber == 1 ? 1 : value instanceof Number
                    && columnIndex < headers.size() && headers.get(columnIndex) instanceof String header
                    && sheet.decimalColumns().contains(header) ? 2 : 0;
                builder.append("<c r=\"").append(cellRef).append("\"");
                if (style != 0) {
                    builder.append(" s=\"").append(style).append("\"");
                }
                if (value instanceof Number number) {
                    builder.append("><v>").append(number).append("</v></c>");
                } else {
                    builder.append(" t=\"inlineStr\"><is><t>")
                        .append(escapeText(value == null ? "" : String.valueOf(value)))
                        .append("</t></is></c>");
                }
            }
            builder.append("</row>\n");
            rowNumber++;
        }
        builder.append("""
              </sheetData>
            </worksheet>
            """);
        return builder.toString();
    }

    private static String columnName(int columnNumber) {
        StringBuilder builder = new StringBuilder();
        int value = columnNumber;
        while (value > 0) {
            int remainder = (value - 1) % 26;
            builder.insert(0, (char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return builder.toString();
    }

    private static String escapeText(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private static String escapeAttribute(String value) {
        return escapeText(value).replace("\"", "&quot;");
    }

    record Sheet(String name, List<List<?>> rows, Set<String> decimalColumns) {
        Sheet {
            decimalColumns = Set.copyOf(decimalColumns);
        }

        Sheet(String name, List<List<?>> rows) {
            this(name, rows, Set.of());
        }
    }
}
