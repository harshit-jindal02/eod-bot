package com.mybot.eod_bot.service;

import com.mybot.eod_bot.model.DailyReport;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
public class CsvReportService {

    /**
     * Creates a temporary CSV file for a single day's report
     */
    public File generateDailyReportCsv(DailyReport report) throws IOException {
        File tempFile = File.createTempFile("daily_report_" + report.getDate(), ".csv");
        try (CSVPrinter printer = new CSVPrinter(new FileWriter(tempFile), CSVFormat.DEFAULT)) {

            // P&L Section
            printer.printRecord("P&L Summary", "Amount");
            printer.printRecord("Date", report.getDate().toString());
            printer.printRecord("Total Gross Revenue", report.getTotalGrossRevenue());
            printer.printRecord("Total COGS", report.getTotalCogs());
            printer.printRecord("Total Net Profit", report.getTotalNetProfit());
            printer.printRecord(); // Empty line

            // Per-Vendor Sales & Profit
            printer.printRecord("Vendor Sales & Profit", "Gross Revenue", "Total Load Sold", "COGS", "Net Profit");
            if (report.getVendorReports() != null) {
                report.getVendorReports().forEach(vr -> {
                    try {
                        printer.printRecord(
                                vr.getVendorName(),
                                vr.getGrossRevenue(),
                                vr.getTotalLoadSold(),
                                vr.getCogs(),
                                vr.getNetProfit()
                        );
                    } catch (IOException e) {
                        // Handle exception
                    }
                });
            }
            printer.printRecord(); // Empty line

            // Per-Distributor Purchases
            printer.printRecord("Distributor Purchases", "Total Load Received", "Total Cost Payable");
            if (report.getPurchaseReports() != null) {
                report.getPurchaseReports().forEach(pr -> {
                    try {
                        printer.printRecord(
                                pr.getDistributorName(),
                                pr.getTotalLoadReceived(),
                                pr.getTotalCostPayable()
                        );
                    } catch (IOException e) {
                        // Handle exception
                    }
                });
            }
        }
        return tempFile;
    }

    /**
     * Creates a temporary CSV file for a full month's aggregated report
     */
    public File generateMonthlyReportCsv(List<DailyReport> reports, String month) throws IOException {
        File tempFile = File.createTempFile("monthly_report_" + month, ".csv");

        // Aggregate data
        double totalProfit = 0, totalRevenue = 0, totalCogs = 0;
        Map<String, Double> vendorProfits = new java.util.HashMap<>();
        Map<String, Double> distCosts = new java.util.HashMap<>();

        for (DailyReport report : reports) {
            if (report.isHasPurchaseData() && report.isHasSalesData()) {
                totalProfit += report.getTotalNetProfit();
                totalRevenue += report.getTotalGrossRevenue();
                totalCogs += report.getTotalCogs();

                report.getVendorReports().forEach(vr ->
                        vendorProfits.merge(vr.getVendorName(), vr.getNetProfit(), Double::sum)
                );
                report.getPurchaseReports().forEach(pr ->
                        distCosts.merge(pr.getDistributorName(), pr.getTotalCostPayable(), Double::sum)
                );
            }
        }

        try (CSVPrinter printer = new CSVPrinter(new FileWriter(tempFile), CSVFormat.DEFAULT)) {
            // P&L Section
            printer.printRecord("Monthly P&L Summary", "Amount");
            printer.printRecord("Month", month);
            printer.printRecord("Total Gross Revenue", totalRevenue);
            printer.printRecord("Total COGS", totalCogs);
            printer.printRecord("Total Net Profit", totalProfit);
            printer.printRecord(); // Empty line

            // Per-Vendor Sales & Profit
            printer.printRecord("Vendor Total Profit", "Net Profit");
            for (Map.Entry<String, Double> entry : vendorProfits.entrySet()) {
                printer.printRecord(entry.getKey(), entry.getValue());
            }
            printer.printRecord(); // Empty line

            // Per-Distributor Purchases
            printer.printRecord("Distributor Total Cost", "Total Cost Payable");
            for (Map.Entry<String, Double> entry : distCosts.entrySet()) {
                printer.printRecord(entry.getKey(), entry.getValue());
            }
        }
        return tempFile;
    }
}