package com.nxr.platform.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class SimpleXlsxWriterTest {
    private static final String XLSX_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    @Test
    void firstRowMatchesPandasHeaderStyleWithoutColoringDataRows() throws Exception {
        Map<String, Document> parts = workbook(new SimpleXlsxWriter.Sheet("Cards", List.of(
            List.of("Grade", "Card", "Count"), List.of(new BigDecimal("9.55"), "Charizard", 3)
        ), Set.of("Grade")));
        Document styles = parts.get("xl/styles.xml"), sheet = parts.get("xl/worksheets/sheet1.xml");
        for (String reference : List.of("A1", "B1", "C1")) {
            Element xf = cellStyle(styles, cell(sheet, reference));
            Element font = child(element(styles, "fonts"), "font", integerAttribute(xf, "fontId"));
            Element alignment = child(xf, "alignment", 0);
            Element border = child(element(styles, "borders"), "border", integerAttribute(xf, "borderId"));
            Element fill = child(element(styles, "fills"), "fill", integerAttribute(xf, "fillId"));
            assertNotNull(child(font, "b", 0));
            assertEquals("center", alignment.getAttribute("horizontal"));
            assertEquals("top", alignment.getAttribute("vertical"));
            for (String side : List.of("left", "right", "top", "bottom")) {
                assertEquals("thin", child(border, side, 0).getAttribute("style"));
            }
            assertEquals("none", child(fill, "patternFill", 0).getAttribute("patternType"));
            assertEquals(0, fill.getElementsByTagNameNS(XLSX_NS, "fgColor").getLength());
            assertEquals("General", numberFormat(styles, cell(sheet, reference)));
        }
        Element dataFont = child(element(styles, "fonts"), "font", integerAttribute(cellStyle(styles, cell(sheet, "A2")), "fontId"));
        assertEquals(0, dataFont.getElementsByTagNameNS(XLSX_NS, "b").getLength());
    }

    @Test
    void configuredNumericColumnsUseOneDecimalDisplayWithoutChangingStoredValues() throws Exception {
        Map<String, Document> parts = workbook(new SimpleXlsxWriter.Sheet("Grades", List.of(
            List.of("final_grade_text", "ai_centering", "Count", "Text"),
            List.of(new BigDecimal("9.55"), 10, 7, "10.0"),
            List.of("Pristine 10", new BigDecimal("8.0"), 2, "=1+1")
        ), Set.of("final_grade_text", "ai_centering")));
        Document styles = parts.get("xl/styles.xml"), sheet = parts.get("xl/worksheets/sheet1.xml");
        for (String reference : List.of("A2", "B2", "B3")) {
            assertEquals("0.0", numberFormat(styles, cell(sheet, reference)));
            assertFalse(cell(sheet, reference).hasAttribute("t"));
        }
        assertEquals("9.55", cell(sheet, "A2").getTextContent());
        assertEquals("10", cell(sheet, "B2").getTextContent());
        assertEquals("8.0", cell(sheet, "B3").getTextContent());
        for (String reference : List.of("C2", "C3", "A3", "D2", "D3")) {
            assertEquals("General", numberFormat(styles, cell(sheet, reference)));
        }
        assertEquals("inlineStr", cell(sheet, "A3").getAttribute("t"));
        assertEquals("Pristine 10", cell(sheet, "A3").getTextContent());
        assertEquals("inlineStr", cell(sheet, "D2").getAttribute("t"));
        assertEquals("10.0", cell(sheet, "D2").getTextContent());
        assertEquals("=1+1", cell(sheet, "D3").getTextContent());
        assertEquals(0, sheet.getElementsByTagNameNS(XLSX_NS, "f").getLength());
    }

    @Test
    void twoArgumentApiKeepsDataGeneralAndConfigurationDoesNotLeakBetweenSheets() throws Exception {
        List<List<?>> rows = List.of(List.of("Grade", "Name"), List.of(new BigDecimal("9.50"), "Card"));
        SimpleXlsxWriter.Sheet legacy = new SimpleXlsxWriter.Sheet("Legacy", rows);
        Set<String> selected = new HashSet<>(Set.of("Grade"));
        SimpleXlsxWriter.Sheet formatted = new SimpleXlsxWriter.Sheet("Formatted", rows, selected);
        selected.clear();
        assertTrue(legacy.decimalColumns().isEmpty());
        Map<String, Document> parts = workbook(legacy, formatted);
        Document styles = parts.get("xl/styles.xml");
        assertEquals("General", numberFormat(styles, cell(parts.get("xl/worksheets/sheet1.xml"), "A2")));
        assertEquals("0.0", numberFormat(styles, cell(parts.get("xl/worksheets/sheet2.xml"), "A2")));
        assertEquals("9.50", cell(parts.get("xl/worksheets/sheet1.xml"), "A2").getTextContent());
        assertEquals(2, element(parts.get("xl/workbook.xml"), "sheets").getElementsByTagNameNS(XLSX_NS, "sheet").getLength());
    }

    @Test
    void textAndSheetNamesRoundTripXmlSpecialCharactersAndUnicode() throws Exception {
        String name = "Cards & \"Quoted\"", text = "<Card & token> \"quote\" 'apostrophe' 中文";
        Map<String, Document> parts = workbook(new SimpleXlsxWriter.Sheet(name, List.of(
            List.of("Name & <Details>"), List.of(text)
        )));
        Document sheet = parts.get("xl/worksheets/sheet1.xml");
        assertEquals(name, element(parts.get("xl/workbook.xml"), "sheet").getAttribute("name"));
        assertEquals("Name & <Details>", cell(sheet, "A1").getTextContent());
        assertEquals(text, cell(sheet, "A2").getTextContent());
        assertEquals("inlineStr", cell(sheet, "A2").getAttribute("t"));
    }

    @Test
    void formattingUsesTheColumnHeaderBeyondZAndLeavesUnmatchedAndExtraColumnsGeneral() throws Exception {
        List<Object> headers = new ArrayList<>(), values = new ArrayList<>();
        for (int index = 1; index <= 44; index++) {
            headers.add("Column " + index);
            values.add(index);
        }
        values.add(45);
        Map<String, Document> parts = workbook(new SimpleXlsxWriter.Sheet("Wide", List.of(headers, values), Set.of("Column 44", "Missing")));
        Document styles = parts.get("xl/styles.xml"), sheet = parts.get("xl/worksheets/sheet1.xml");
        assertEquals("0.0", numberFormat(styles, cell(sheet, "AR2")));
        assertEquals("44", cell(sheet, "AR2").getTextContent());
        assertEquals("General", numberFormat(styles, cell(sheet, "Z2")));
        assertEquals("General", numberFormat(styles, cell(sheet, "AS2")));
    }

    @Test
    void emptyAndHeaderOnlySheetsAndNullDataRemainReadable() throws Exception {
        Map<String, Document> parts = workbook(
            new SimpleXlsxWriter.Sheet("Empty", List.of()),
            new SimpleXlsxWriter.Sheet("Headers", List.of(List.of("Grade")), Set.of("Grade")),
            new SimpleXlsxWriter.Sheet("Nulls", List.of(List.of("Grade", "Text"), Arrays.asList(null, null)), Set.of("Grade")),
            new SimpleXlsxWriter.Sheet("Unnamed", List.of(Arrays.asList(null, "Text"), List.of(5, "Card")))
        );
        Document styles = parts.get("xl/styles.xml");
        assertEquals(0, parts.get("xl/worksheets/sheet1.xml").getElementsByTagNameNS(XLSX_NS, "row").getLength());
        assertEquals("General", numberFormat(styles, cell(parts.get("xl/worksheets/sheet2.xml"), "A1")));
        Element blank = cell(parts.get("xl/worksheets/sheet3.xml"), "A2");
        assertEquals("General", numberFormat(styles, blank));
        assertEquals("inlineStr", blank.getAttribute("t"));
        assertEquals("", blank.getTextContent());
        assertEquals("General", numberFormat(styles, cell(parts.get("xl/worksheets/sheet4.xml"), "A2")));
        assertEquals("5", cell(parts.get("xl/worksheets/sheet4.xml"), "A2").getTextContent());
    }

    private static Map<String, Document> workbook(SimpleXlsxWriter.Sheet... sheets) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SimpleXlsxWriter.writeWorkbook(output, List.of(sheets));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Map<String, Document> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                parts.put(entry.getName(), factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes())));
            }
        }
        assertTrue(parts.containsKey("[Content_Types].xml"));
        assertTrue(parts.containsKey("xl/_rels/workbook.xml.rels"));
        return parts;
    }

    private static Element element(Document document, String name) {
        Element result = (Element) document.getElementsByTagNameNS(XLSX_NS, name).item(0);
        assertNotNull(result, name);
        return result;
    }

    private static Element child(Element parent, String name, int index) {
        return (Element) parent.getElementsByTagNameNS(XLSX_NS, name).item(index);
    }

    private static Element cell(Document document, String reference) {
        NodeList cells = document.getElementsByTagNameNS(XLSX_NS, "c");
        for (int index = 0; index < cells.getLength(); index++) {
            Element candidate = (Element) cells.item(index);
            if (reference.equals(candidate.getAttribute("r"))) return candidate;
        }
        throw new AssertionError("Missing cell " + reference);
    }

    private static Element cellStyle(Document styles, Element cell) {
        int style = cell.hasAttribute("s") ? integerAttribute(cell, "s") : 0;
        Element result = child(element(styles, "cellXfs"), "xf", style);
        assertNotNull(result, "Referenced cell style must exist");
        return result;
    }

    private static int integerAttribute(Element element, String name) {
        return Integer.parseInt(element.getAttribute(name));
    }

    private static String numberFormat(Document styles, Element cell) {
        String formatId = cellStyle(styles, cell).getAttribute("numFmtId");
        if ("0".equals(formatId)) return "General";
        NodeList formats = styles.getElementsByTagNameNS(XLSX_NS, "numFmt");
        for (int index = 0; index < formats.getLength(); index++) {
            Element format = (Element) formats.item(index);
            if (formatId.equals(format.getAttribute("numFmtId"))) return format.getAttribute("formatCode");
        }
        throw new AssertionError("Missing number format " + formatId);
    }
}
