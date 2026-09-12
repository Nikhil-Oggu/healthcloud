package com.healthcloud.auth;

import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Returns the authenticated user's context ("who am I"). */
@RestController
@RequestMapping("/api/v1")
public class CurrentUserController {

    private final CurrentUserService currentUserService;

    public CurrentUserController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @GetMapping("/me")
    public ResponseEntity<CurrentUserDto> me(Principal principal) {
        // Principal is guaranteed non-null here: SecurityConfig requires authentication for this route.
        return currentUserService.resolveByEmail(principal.getName())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(401).build());
    }
}
