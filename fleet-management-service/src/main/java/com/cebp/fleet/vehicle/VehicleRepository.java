package com.cebp.fleet.vehicle;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface VehicleRepository extends JpaRepository<Vehicle, String>, JpaSpecificationExecutor<Vehicle> {

    boolean existsByLabel(String label);

    Optional<Vehicle> findByLabel(String label);
}
