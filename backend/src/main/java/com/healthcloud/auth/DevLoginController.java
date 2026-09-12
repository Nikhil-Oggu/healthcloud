package com.healthcloud.auth;

import com.healthcloud.identity.AppUser;
import com.healthcloud.identity.AppUserRepository;
import com.healthcloud.identity.AppUserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LOCAL-ONLY development login stand-in. Establishes an authenticated session for a seeded,
 * ACTIVE user identified purely by email — NO password, NO MFA. This exists only under the
 * {@code local} profile and is replaced by the real Amazon Cognito + BFF + MFA flow later.
 * Do not treat this as real authentication.
 */
@RestController
@RequestMapping("/api/v1")
@Profile("local")
public class DevLoginController {

    private final AppUserRepository appUsers;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public DevLoginController(AppUserRepository appUsers) {
        this.appUsers = appUsers;
    }

    @PostMapping("/dev-login")
    public ResponseEntity<Void> devLogin(@RequestParam String email,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        Optional<AppUser> user = appUsers.findByEmailIgnoreCase(email)
                .filter(u -> u.getStatus() == AppUserStatus.ACTIVE);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).build();
        }

        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                user.get().getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // Persist into the (Spring Session JDBC-backed) HTTP session so later requests are authenticated.
        securityContextRepository.saveContext(context, request, response);

        return ResponseEntity.ok().build();
    }
}
