package com.omnichannel.support.api;

import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.security.AppUser;
import com.omnichannel.support.security.AuthenticatedUserService;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/me")
@RequiredArgsConstructor
public class SessionController {

    private final AuthenticatedUserService authenticatedUserService;

    @GetMapping
    public ResponseEntity<ApiResponse<SessionUserDto>> currentUser(Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        return ResponseEntity.ok(ApiResponse.success(new SessionUserDto(
                user.email(), user.name(), user.pictureUrl(), user.customerId(), user.roles(), user.isAgent())));
    }

    public record SessionUserDto(
            String email,
            String name,
            String pictureUrl,
            String customerId,
            Set<String> roles,
            boolean agent) {}
}
