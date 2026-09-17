package com.oficina.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.security.CustomerTokenValidator;
import com.oficina.security.IdentidadeAutenticada;
import com.oficina.security.StaffTokenValidator;
import com.oficina.security.TokenVerification;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Base64;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final CustomerTokenValidator customerValidator;
    private final StaffTokenValidator staffValidator;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        // Retired integration tombstone: no authentication or business service can revive this path.
        String applicationPath = request.getRequestURI().substring(request.getContextPath().length());
        if ("/ordens-servico/email/atualizar-status".equals(applicationPath)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            if (!header.startsWith("Bearer ")) throw TokenVerification.invalid();
            String token = header.substring(7);
            if (token.length() > 16384) throw TokenVerification.invalid();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw TokenVerification.invalid();
            // This untrusted discriminator chooses a candidate only. Each validator verifies all signed claims.
            String type = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]))
                    .path("principal_type").asText();
            IdentidadeAutenticada identity = switch (type) {
                case "customer" -> customerValidator.validar(token);
                case "staff" -> staffValidator.validar(token);
                default -> throw TokenVerification.invalid();
            };
            var authentication = new UsernamePasswordAuthenticationToken(identity, null,
                    identity.permissoes().stream().map(SimpleGrantedAuthority::new).toList());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (Exception exception) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"UNAUTHORIZED\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
