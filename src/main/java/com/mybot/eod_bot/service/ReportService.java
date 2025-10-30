package com.mybot.eod_bot.service; // <-- CORRECTED PACKAGE

import com.mybot.eod_bot.model.*;
import com.mybot.eod_bot.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final DailyReportRepository reportRepository;
    private final DailyPurchaseReportRepository purchaseReportRepository;
    private final DailyVendorReportRepository vendorReportRepository;

    // This annotation makes the whole method a single database transaction
    @Transactional
    public DailyReport getOrCreateDailyReport(LocalDate date) {
        Optional<DailyReport> report = reportRepository.findByDate(date);
        return report.orElseGet(() -> reportRepository.save(new DailyReport(date)));
    }

    @Transactional
    public String updatePurchases(DailyReport report, List<DailyPurchaseReport> newPurchaseReports, double costFactor) {
        // Clear old purchase data for this day
        purchaseReportRepository.deleteAll(report.getPurchaseReports());

        // Set new data
        for (DailyPurchaseReport pr : newPurchaseReports) {
            pr.setDailyReport(report);
        }
        purchaseReportRepository.saveAll(newPurchaseReports);

        report.setPurchaseReports(newPurchaseReports);
        report.setMasterCostFactor(costFactor);
        report.setHasPurchaseData(true);

        return checkAndCalculatePnl(report);
    }

    @Transactional
    public String updateSales(DailyReport report, List<DailyVendorReport> newVendorReports) {
        // Clear old sales data for this day
        vendorReportRepository.deleteAll(report.getVendorReports());

        // Set new data
        for (DailyVendorReport vr : newVendorReports) {
            vr.setDailyReport(report);
        }
        vendorReportRepository.saveAll(newVendorReports);

        report.setVendorReports(newVendorReports);
        report.setHasSalesData(true);

        return checkAndCalculatePnl(report);
    }

    @Transactional
    public String checkAndCalculatePnl(DailyReport report) {
        // If we don't have both pieces of data, just save and report progress.
        if (!report.isHasPurchaseData() || !report.isHasSalesData()) {
            reportRepository.save(report);
            if (report.isHasPurchaseData()) {
                return "✅ Purchases saved. Waiting for sales data to calculate P&L.";
            } else {
                return "✅ Sales saved. Waiting for purchase data to calculate P&L.";
            }
        }

        // --- We have both! Calculate P&L ---
        double totalRevenue = report.getVendorReports().stream()
                .mapToDouble(DailyVendorReport::getGrossRevenue).sum();

        double totalCogs = report.getVendorReports().stream()
                .mapToDouble(DailyVendorReport::getCogs).sum();

        double totalProfit = totalRevenue - totalCogs;

        report.setTotalGrossRevenue(round(totalRevenue));
        report.setTotalCogs(round(totalCogs));
        report.setTotalNetProfit(round(totalProfit));

        reportRepository.save(report);

        return "✅ P&L has been automatically calculated and saved!";
    }

    public double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
