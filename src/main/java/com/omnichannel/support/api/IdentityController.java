package com.omnichannel.support.api;

import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.dto.RegisterIdentityRequest;
import com.omnichannel.support.service.IdentityResolutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/identity")
@RequiredArgsConstructor
public class IdentityController {

    private final IdentityResolutionService identityResolutionService;

    @PostMapping(path = "/links", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Void>> registerLink(@Valid @RequestBody RegisterIdentityRequest request) {
        identityResolutionService.registerLink(
                request.customerId(), request.identifierType(), request.identifierValue());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(null));
    }
}
