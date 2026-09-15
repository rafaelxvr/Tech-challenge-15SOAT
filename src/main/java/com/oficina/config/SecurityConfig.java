package com.oficina.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.security.CustomerTokenValidator;
import com.oficina.security.StaffTokenValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserDetailsService userDetailsService;
    private final CustomerTokenValidator customerValidator;
    private final StaffTokenValidator staffValidator;
    private final ObjectMapper objectMapper;

    // Endpoints públicos - sem autenticação
    private static final String[] PUBLIC_URLS = {
            "/auth/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/actuator/health"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"UNAUTHORIZED\"}");
                }))
                .authorizeHttpRequests(auth -> auth
                        // Endpoints públicos
                        .requestMatchers(PUBLIC_URLS).permitAll()

                        // Consulta e aprovação de OS pelo cliente (sem JWT)
                        .requestMatchers(HttpMethod.GET, "/ordens-servico/*/acompanhamento").permitAll()
                        .requestMatchers(HttpMethod.POST, "/ordens-servico/*/aprovar").permitAll()
                        .requestMatchers(HttpMethod.POST, "/ordens-servico/*/orcamento/notificacao").permitAll()
                        .requestMatchers(HttpMethod.POST, "/ordens-servico/email/atualizar-status").permitAll()

                        // Gestão administrativa - somente ADMIN
                        .requestMatchers(HttpMethod.DELETE, "/**").hasRole("ADMIN")
                        .requestMatchers("/admin/**").hasRole("ADMIN")

                        // Demais endpoints - autenticados
                        .anyRequest().hasAnyRole("ADMIN", "MECANICO")
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(new JwtAuthenticationFilter(customerValidator, staffValidator, objectMapper),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
