package com.cebp.fleet.vehicle;

import com.cebp.fleet.vehicle.dto.VehicleCreateRequest;
import com.cebp.fleet.vehicle.dto.VehicleResponse;
import com.cebp.fleet.vehicle.dto.VehicleUpdateRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /api/v1/vehicles (the base path comes from server.servlet.context-path). */
@RestController
@RequestMapping("/vehicles")
public class VehicleController {

    private final VehicleService service;
    private final VehicleMapper mapper;

    public VehicleController(VehicleService service, VehicleMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<VehicleResponse> register(@Valid @RequestBody VehicleCreateRequest request) {
        Vehicle created = service.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(created));
    }

    @GetMapping
    public List<VehicleResponse> list(@RequestParam(required = false) VehicleStatus status,
                                      @RequestParam(required = false) String zoneId,
                                      @RequestParam(required = false) Integer maxBattery,
                                      @RequestParam(required = false) Integer limit) {
        return service.list(status, zoneId, maxBattery, limit).stream().map(mapper::toResponse).toList();
    }

    @GetMapping("/{vehicleId}")
    public VehicleResponse get(@PathVariable String vehicleId) {
        return mapper.toResponse(service.get(vehicleId));
    }

    @PatchMapping("/{vehicleId}")
    public VehicleResponse update(@PathVariable String vehicleId,
                                  @Valid @RequestBody VehicleUpdateRequest request) {
        return mapper.toResponse(service.rename(vehicleId, request.label()));
    }
}
