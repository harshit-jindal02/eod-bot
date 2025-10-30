package com.mybot.eod_bot.repository; // <-- CORRECTED PACKAGE

import com.mybot.eod_bot.model.LapuSim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LapuSimRepository extends JpaRepository<LapuSim, String> { // ID is String
}
