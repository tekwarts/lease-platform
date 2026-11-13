package com.example.leaseplatform.config;

import com.example.leaseplatform.controller.DemoController;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Configuration
@EnableWebSecurity
public class DemoSecurity {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        // CSRF stays enabled. React obtains the masked token through /api/v1/csrf.
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/csrf", "/api/v1/demo/session").permitAll()
                .requestMatchers("/api/**").hasRole("DEMO")
                .anyRequest().permitAll());
        http.addFilterBefore(new DemoSessionFilter(), UsernamePasswordAuthenticationFilter.class);
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, error) -> problem(res, 401, "SESSION_REQUIRED", "Start a demo session first."))
                .accessDeniedHandler((req, res, error) -> problem(res, 403, "ACCESS_DENIED", "Access denied or CSRF token missing/expired.")));
        return http.build();
    }

    private static void problem(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }

    // Constructed only in the security chain, not registered a second time as a servlet filter.
    private static class DemoSessionFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            HttpSession session = request.getSession(false);
            if (session != null && session.getAttribute(DemoController.OWNER_ATTRIBUTE) instanceof String owner) {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(owner, null,
                        List.of(new SimpleGrantedAuthority("ROLE_DEMO"))));
                SecurityContextHolder.setContext(context);
            }
            chain.doFilter(request, response);
        }
    }
}
