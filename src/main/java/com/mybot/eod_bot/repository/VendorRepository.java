package com.mybot.eod_bot.repository; // <-- CORRECTED PACKAGE

import com.mybot.eod_bot.model.Vendor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VendorRepository extends JpaRepository<Vendor, Long> {
}
