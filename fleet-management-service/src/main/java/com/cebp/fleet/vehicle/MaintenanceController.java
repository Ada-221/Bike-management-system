package com.cebp.fleet.vehicle;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MaintenanceController {
    public record ReportRequest(@NotNull MaintenanceService.Reason reason, @Size(max = 50) String faultCode,
                                @Size(max = 500) String description) {}
    public record UpdateRequest(@NotNull MaintenanceService.Status status, @Size(max = 500) String description) {}

    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    @PostMapping("/vehicles/{vehicleId}/maintenance")
    public ResponseEntity<MaintenanceService.Record> report(@PathVariable String vehicleId,
                                                            @Valid @RequestBody ReportRequest request) {
        MaintenanceService.Report result = service.report(vehicleId, request.reason(), request.faultCode(), request.description());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.record());
    }

    @GetMapping("/maintenance")
    public List<MaintenanceService.Record> list(@RequestParam(required = false) String vehicleId,
                                                @RequestParam(required = false) MaintenanceService.Status status,
                                                @RequestParam(required = false) MaintenanceService.Reason reason,
                                                @RequestParam(required = false) Integer limit) {
        return service.list(vehicleId, status, reason, limit);
    }

    @PatchMapping("/maintenance/{id}")
    public MaintenanceService.Record update(@PathVariable String id, @Valid @RequestBody UpdateRequest request) {
        return service.update(id, request.status(), request.description());
    }
}
