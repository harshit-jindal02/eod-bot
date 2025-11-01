package com.mybot.eod_bot.service;

import com.mybot.eod_bot.model.DailyPurchaseReport;
import com.mybot.eod_bot.model.Distributor;
import com.mybot.eod_bot.model.LapuSim;
import lombok.extern.slf4j.Slf4j;
// --- NEW JSOUP (HTML) IMPORTS ---
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
// --- REMOVED APACHE POI (EXCEL) IMPORTS ---
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

@Slf4j
@Service
public class ExcelParserService { // File name is the same, logic is new

    // This container class is unchanged
    public static class PurchaseParseResult {
        public final Map<String, DailyPurchaseReport> purchaseReportsMap;
        public final double totalLoadReceived;
        public final double totalCostPayable;
        public final double masterCostFactor;
        public final List<String> unassignedSims;

        public PurchaseParseResult(Map<String, DailyPurchaseReport> purchaseReportsMap, double totalLoadReceived, double totalCostPayable, double masterCostFactor, List<String> unassignedSims) {
            this.purchaseReportsMap = purchaseReportsMap;
            this.totalLoadReceived = totalLoadReceived;
            this.totalCostPayable = totalCostPayable;
            this.masterCostFactor = masterCostFactor;
            this.unassignedSims = unassignedSims;
        }
    }

    // --- THIS IS THE NEW HTML PARSING LOGIC ---
    public PurchaseParseResult parse(File file, Map<String, LapuSim> simMap) throws Exception {
        Map<String, DailyPurchaseReport> purchaseReportsMap = new HashMap<>();
        List<String> unassignedSims = new ArrayList<>();
        double totalLoadReceived = 0;
        double totalCostPayable = 0;

        // Use Jsoup to parse the file as HTML
        // This will read the file you downloaded
        Document doc = Jsoup.parse(file, "UTF-8");

        // Find the first <table> on the page.
        // This assumes the data is in the first table.
        Element table = doc.select("table").first();
        if (table == null) {
            throw new RuntimeException("No <table> found in the uploaded file.");
        }

        Elements rows = table.select("tr");
        if (rows.isEmpty() || rows.size() < 2) { // Need at least 1 header row and 1 data row
            throw new RuntimeException("No <tr> (table rows) found in the table.");
        }

        // Find header columns from the first row
        Map<String, Integer> headers = findHeaders(rows.get(0));

        // Ensure all required headers are present
        if (!headers.containsKey("LAPU NO") || !headers.containsKey("OPEN BAL") ||
                !headers.containsKey("TOTAL AMOUNT") || !headers.containsKey("CLOSE BAL")) {
            log.error("Missing headers. Found: {}", headers);
            throw new RuntimeException("Invalid file format. Missing required headers: LAPU NO, OPEN BAL, TOTAL AMOUNT, or CLOSE BAL");
        }

        // Start from row 1 (skipping header)
        for (int i = 1; i < rows.size(); i++) {
            Elements cells = rows.get(i).select("td"); // Get all cells in this row
            if (cells.size() < headers.size()) {
                log.warn("Skipping malformed row {}. Expected >= {} cells, got {}", i, headers.size(), cells.size());
                continue; // Skip rows that don't have enough columns
            }

            try {
                // Read data using header map
                long openBal = parseLongFromText(cells.get(headers.get("OPEN BAL")).text());
                long totalAmount = parseLongFromText(cells.get(headers.get("TOTAL AMOUNT")).text());
                long closeBal = parseLongFromText(cells.get(headers.get("CLOSE BAL")).text());

                String lapuNo = cells.get(headers.get("LAPU NO")).text().trim();
                lapuNo = lapuNo.split("\\.")[0]; // Clean "1234.0" to "1234"

                double loadReceived = (double) closeBal - openBal + totalAmount;
                if (loadReceived <= 0) continue;

                LapuSim sim = simMap.get(lapuNo);
                if (sim == null) {
                    String desc = headers.containsKey("DESC") ? cells.get(headers.get("DESC")).text() : "N/A";
                    unassignedSims.add(lapuNo + " (" + desc + ")");
                    continue;
                }

                Distributor dist = sim.getDistributor();
                double payableFactor = (dist.getBaseGet() == 0) ? 0 : (dist.getBasePay() / dist.getBaseGet());
                double costPayable = loadReceived * payableFactor;

                totalLoadReceived += loadReceived;
                totalCostPayable += costPayable;

                DailyPurchaseReport report = purchaseReportsMap.computeIfAbsent(dist.getName(), k -> new DailyPurchaseReport());
                report.setDistributorName(dist.getName());
                report.setTotalLoadReceived(report.getTotalLoadReceived() + loadReceived);
                report.setTotalCostPayable(report.getTotalCostPayable() + costPayable);

            } catch (Exception e) {
                log.warn("Skipping row {}: {}", i, e.getMessage());
            }
        }

        double masterCostFactor = (totalLoadReceived == 0) ? 0 : (totalCostPayable / totalLoadReceived);
        return new PurchaseParseResult(purchaseReportsMap, totalLoadReceived, totalCostPayable, masterCostFactor, unassignedSims);
    }

    // Helper to find column indices from header names in HTML
    private Map<String, Integer> findHeaders(Element headerRow) {
        Map<String, Integer> headers = new HashMap<>();
        // Select all header cells (th) or data cells (td) in the first row
        Elements headerCells = headerRow.select("th, td");

        for (int i = 0; i < headerCells.size(); i++) {
            String header = headerCells.get(i).text().trim().toUpperCase();
            // Map common variations
            switch(header) {
                case "LAPU NO":
                case "LAPU NO.":
                    headers.put("LAPU NO", i);
                    break;
                case "OPEN BAL":
                case "OPEN BAL.":
                    headers.put("OPEN BAL", i);
                    break;
                case "TOTAL AMOUNT":
                case "TOTAL":
                    headers.put("TOTAL AMOUNT", i);
                    break;
                case "CLOSE BAL":
                case "CLOSE BAL.":
                    headers.put("CLOSE BAL", i);
                    break;
                case "DESC":
                case "DESCRIPTION":
                    headers.put("DESC", i);
                    break;
            }
        }
        log.info("Found headers in HTML table: {}", headers);
        return headers;
    }

    // Helper to parse text like "1,234.00" or "5000" into a long
    private long parseLongFromText(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        // Remove commas and anything after a decimal point
        String cleanedText = text.replaceAll(",", "").split("\\.")[0];
        if (cleanedText.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(cleanedText);
        } catch (NumberFormatException e) {
            log.warn("Could not parse long from text: '{}'", text);
            return 0L;
        }
    }
}

