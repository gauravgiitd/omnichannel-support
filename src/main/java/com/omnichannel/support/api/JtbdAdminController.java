package com.omnichannel.support.api;

import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.dto.CreateCustomerJtbdRequest;
import com.omnichannel.support.dto.CustomerJtbdDto;
import com.omnichannel.support.dto.CustomerSummaryDto;
import com.omnichannel.support.dto.JtbdTypeDto;
import com.omnichannel.support.dto.UpdateCustomerJtbdRequest;
import com.omnichannel.support.dto.UpsertJtbdTypeRequest;
import com.omnichannel.support.service.JtbdService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/jtbds")
@RequiredArgsConstructor
public class JtbdAdminController {

    private final JtbdService jtbdService;

    @GetMapping("/types")
    public ResponseEntity<ApiResponse<List<JtbdTypeDto>>> listTypes() {
        return ResponseEntity.ok(ApiResponse.success(jtbdService.listTypes()));
    }

    @PostMapping("/types")
    public ResponseEntity<ApiResponse<JtbdTypeDto>> createType(@Valid @RequestBody UpsertJtbdTypeRequest request) {
        return ResponseEntity.ok(ApiResponse.success(jtbdService.createType(request)));
    }

    @PutMapping("/types/{jtbdTypeId}")
    public ResponseEntity<ApiResponse<JtbdTypeDto>> updateType(
            @PathVariable("jtbdTypeId") String jtbdTypeId, @Valid @RequestBody UpsertJtbdTypeRequest request) {
        return ResponseEntity.ok(ApiResponse.success(jtbdService.updateType(jtbdTypeId, request)));
    }

    @DeleteMapping("/types/{jtbdTypeId}")
    public ResponseEntity<ApiResponse<Void>> deleteType(@PathVariable("jtbdTypeId") String jtbdTypeId) {
        jtbdService.deleteType(jtbdTypeId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/customers")
    public ResponseEntity<ApiResponse<List<CustomerSummaryDto>>> listCustomers() {
        return ResponseEntity.ok(ApiResponse.success(jtbdService.listCustomers()));
    }

    @GetMapping("/customers/{customerId}/instances")
    public ResponseEntity<ApiResponse<List<CustomerJtbdDto>>> listCustomerInstances(
            @PathVariable("customerId") String customerId) {
        return ResponseEntity.ok(ApiResponse.success(jtbdService.listCustomerJtbds(customerId)));
    }

    @PostMapping("/customers/{customerId}/instances")
    public ResponseEntity<ApiResponse<CustomerJtbdDto>> createCustomerInstance(
            @PathVariable("customerId") String customerId, @Valid @RequestBody CreateCustomerJtbdRequest request) {
        return ResponseEntity.ok(ApiResponse.success(jtbdService.createCustomerJtbd(customerId, request)));
    }

    @PutMapping("/instances/{customerJtbdId}")
    public ResponseEntity<ApiResponse<CustomerJtbdDto>> updateCustomerInstance(
            @PathVariable("customerJtbdId") String customerJtbdId,
            @Valid @RequestBody UpdateCustomerJtbdRequest request) {
        return ResponseEntity.ok(ApiResponse.success(jtbdService.updateCustomerJtbd(customerJtbdId, request)));
    }

    @DeleteMapping("/instances/{customerJtbdId}")
    public ResponseEntity<ApiResponse<Void>> deleteCustomerInstance(
            @PathVariable("customerJtbdId") String customerJtbdId) {
        jtbdService.deleteCustomerJtbd(customerJtbdId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
