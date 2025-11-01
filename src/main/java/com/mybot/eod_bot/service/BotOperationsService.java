package com.mybot.eod_bot.service;

import com.mybot.eod_bot.model.Distributor;
import com.mybot.eod_bot.model.LapuSim;
import com.mybot.eod_bot.model.Vendor;
import com.mybot.eod_bot.repository.DistributorRepository;
import com.mybot.eod_bot.repository.LapuSimRepository;
import com.mybot.eod_bot.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * This service handles all database operations that require a transaction.
 * By moving the logic here, we avoid Spring's self-invocation proxy problem.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BotOperationsService {

    private final DistributorRepository distributorRepository;
    private final VendorRepository vendorRepository;
    private final LapuSimRepository lapuSimRepository;

    @Transactional
    public Distributor saveNewDistributor(Distributor dist) {
        log.info("Saving new distributor: {}", dist.getName());
        return distributorRepository.save(dist);
    }

    @Transactional
    public Vendor saveNewVendor(Vendor vendor) {
        log.info("Saving new vendor: {}", vendor.getName());
        return vendorRepository.save(vendor);
    }

    @Transactional
    public List<LapuSim> saveBatchSims(Distributor assignedDist, String[] simNumbers) {
        log.info("Assigning {} SIMs to {}", simNumbers.length, assignedDist.getName());
        List<LapuSim> simsToSave = new ArrayList<>();
        for (String num : simNumbers) {
            if (num.trim().isEmpty()) continue; // Skip empty entries

            LapuSim sim = new LapuSim();
            sim.setLapuNo(num.trim());
            sim.setDistributor(assignedDist);
            simsToSave.add(sim);
        }
        return lapuSimRepository.saveAll(simsToSave);
    }

    @Transactional
    public void unassignSim(String lapuNo) {
        log.info("Unassigning SIM: {}", lapuNo);
        if (!lapuSimRepository.existsById(lapuNo)) {
            throw new RuntimeException("SIM `" + lapuNo + "` not found in database.");
        }
        lapuSimRepository.deleteById(lapuNo);
    }

    @Transactional
    public void confirmDeleteDistributor(Distributor dist) {
        log.info("Deleting all SIMs for distributor: {}", dist.getName());
        lapuSimRepository.deleteAllByDistributor(dist);

        log.info("Deleting distributor: {}", dist.getName());
        distributorRepository.delete(dist);
    }

    @Transactional
    public void confirmDeleteVendor(Vendor vendor) {
        log.info("Deleting vendor: {}", vendor.getName());
        vendorRepository.delete(vendor);
    }
}
