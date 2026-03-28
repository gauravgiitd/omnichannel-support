package com.omnichannel.support.api;

import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.service.AdminCleanupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminCleanupService adminCleanupService;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<AdminCleanupService.AdminSummary>> summary() {
        return ResponseEntity.ok(ApiResponse.success(adminCleanupService.summary()));
    }

    @PostMapping("/cleanup")
    public ResponseEntity<ApiResponse<AdminCleanupService.CleanupResult>> cleanup(
            @Valid @RequestBody CleanupRequest request) {
        if (!"DELETE ALL DATA".equals(request.confirmation())) {
            throw new com.omnichannel.support.error.ValidationException("confirmation text does not match");
        }
        return ResponseEntity.ok(ApiResponse.success(adminCleanupService.cleanupAllData()));
    }

    public record CleanupRequest(@NotBlank String confirmation) {}
}
