package com.cebp.fleet.vehicle;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface VehicleRepository extends JpaRepository<Vehicle, String>, JpaSpecificationExecutor<Vehicle> {

    boolean existsByLabel(String label);

    Optional<Vehicle> findByLabel(String label);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vehicle v where v.id = :id")
    Optional<Vehicle> findForUpdate(String id);

    List<Vehicle> findByStatusAndBatteryPercentGreaterThanEqualAndLatitudeBetween(
            VehicleStatus status, int batteryPercent, double minLatitude, double maxLatitude);
}
