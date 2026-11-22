package com.cebp.fleet.rental;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rentals")
class RentalController {
    record StartRequest(@NotBlank @Size(max = 40) @Pattern(regexp = "^veh_[A-Za-z0-9_-]+$") String vehicleId,
                        @NotBlank @Size(max = 64) String riderId) {}
    record ReturnRequest(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                         @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}

    private final RentalService service;

    RentalController(RentalService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<RentalService.Rental> start(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody StartRequest request) {
        RentalService.Start start = service.start(request.vehicleId(), request.riderId(), key);
        return ResponseEntity.status(start.created() ? HttpStatus.CREATED : HttpStatus.OK).body(start.rental());
    }

    @GetMapping
    List<RentalService.Rental> list(@RequestParam(required = false) String riderId,
                                    @RequestParam(required = false) String vehicleId,
                                    @RequestParam(required = false) RentalService.Status status,
                                    @RequestParam(required = false) Integer limit) {
        return service.list(riderId, vehicleId, status, limit);
    }

    @GetMapping("/{id}")
    RentalService.Rental get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/{id}/return")
    RentalService.Rental end(@PathVariable String id, @Valid @RequestBody ReturnRequest request) {
        return service.end(id, request.latitude(), request.longitude());
    }
}
