package com.mybot.eod_bot.repository;

import com.mybot.eod_bot.model.DailyReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List; // <-- Import List
import java.util.Optional;

@Repository
public interface DailyReportRepository extends JpaRepository<DailyReport, String> { // ID is String
    Optional<DailyReport> findByDate(LocalDate date);

    // NEW: Find all reports between two dates
    List<DailyReport> findByDateBetween(LocalDate startDate, LocalDate endDate);
}