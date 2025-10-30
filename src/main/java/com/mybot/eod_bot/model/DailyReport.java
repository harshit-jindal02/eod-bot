package com.mybot.eod_bot.model; // <-- CORRECTED PACKAGE

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Entity
@Table(name = "daily_reports")
public class DailyReport {

    // The ID will be "YYYY-MM-DD"
    @Id
    private String id;

    @Column(unique = true, nullable = false)
    private LocalDate date;

    // --- Purchase Data ---
    private boolean hasPurchaseData = false;
    private double masterCostFactor = 0;

    // --- Sales Data ---
    private boolean hasSalesData = false;

    // --- P&L Data (Calculated) ---
    private double totalGrossRevenue = 0;
    private double totalCogs = 0;
    private double totalNetProfit = 0;

    // --- Relationships ---
    @OneToMany(mappedBy = "dailyReport", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<DailyPurchaseReport> purchaseReports = new ArrayList<>();

    @OneToMany(mappedBy = "dailyReport", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<DailyVendorReport> vendorReports = new ArrayList<>();

    public DailyReport(LocalDate date) {
        this.date = date;
        this.id = date.toString();
    }
}
