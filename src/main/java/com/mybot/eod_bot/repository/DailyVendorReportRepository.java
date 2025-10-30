package com.mybot.eod_bot.repository; // <-- CORRECTED PACKAGE

import com.mybot.eod_bot.model.DailyVendorReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DailyVendorReportRepository extends JpaRepository<DailyVendorReport, Long> {
}
