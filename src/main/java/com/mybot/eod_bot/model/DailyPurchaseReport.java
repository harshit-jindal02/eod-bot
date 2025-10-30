package com.mybot.eod_bot.model; // <-- CORRECTED PACKAGE

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(name = "daily_purchase_reports")
public class DailyPurchaseReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private DailyReport dailyReport;

    private String distributorName;
    private double totalLoadReceived;
    private double totalCostPayable;
}
