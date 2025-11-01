package com.mybot.eod_bot.repository;

import com.mybot.eod_bot.model.Distributor;
import com.mybot.eod_bot.model.LapuSim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LapuSimRepository extends JpaRepository<LapuSim, String> { // ID is String

    // NEW: Find all SIMs for a specific distributor object
    List<LapuSim> findByDistributor(Distributor distributor);

    // NEW: Delete all SIMs associated with a distributor
    void deleteAllByDistributor(Distributor distributor);
}