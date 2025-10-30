package com.mybot.eod_bot.model; // <-- CORRECTED PACKAGE

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(name = "daily_vendor_reports")
public class DailyVendorReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private DailyReport dailyReport;

    private String vendorName;
    private double grossRevenue;
    private double totalLoadSold;
    private double cogs;
    private double netProfit;
}
