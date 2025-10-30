package com.mybot.eod_bot.repository; // <-- CORRECTED PACKAGE

import com.mybot.eod_bot.model.Distributor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DistributorRepository extends JpaRepository<Distributor, Long> {
}
