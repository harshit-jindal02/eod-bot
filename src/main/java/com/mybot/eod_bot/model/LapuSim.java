package com.mybot.eod_bot.model; // <-- CORRECTED PACKAGE

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(name = "lapu_sims")
public class LapuSim {

    // The SIM number is the ID (it's not generated)
    @Id
    private String lapuNo;

    // A SIM belongs to one Distributor
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "distributor_id", nullable = false)
    private Distributor distributor;
}
