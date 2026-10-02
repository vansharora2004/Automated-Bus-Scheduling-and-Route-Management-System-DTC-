package com.dtc.transit.masterdata.bus;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BusUnavailabilityRepository extends JpaRepository<BusUnavailability, Long> {

    List<BusUnavailability> findByBusIdOrderByStartsAtAsc(Long busId);
}
