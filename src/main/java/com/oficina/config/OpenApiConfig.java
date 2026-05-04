package com.oficina.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${server.servlet.context-path:/api}")
    private String contextPath;

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080" + contextPath)
                                .description("Servidor Local (Dev)")
                ))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, jwtSecurityScheme())
                );
    }

    private Info apiInfo() {
        return new Info()
                .title("Oficina Mecânica API")
                .description("""
                        Sistema Integrado de Atendimento e Execução de Serviços para Oficina Mecânica.
                        
                        **Autenticação:** Use o endpoint `/auth/login` para obter um token JWT.
                        Insira o token no botão **Authorize** acima com o prefixo `Bearer`.
                        
                        **Roles disponíveis:**
                        - `ADMIN`: Acesso total ao sistema
                        - `MECANICO`: Acesso a ordens de serviço e execução
                        - `CLIENTE`: Consulta de status das suas OS
                        """)
                .version("1.0.0")
                .contact(new Contact()
                        .name("Oficina Mecânica")
                        .email("dev@oficina.com"))
                .license(new License()
                        .name("Privado")
                        .url("#"));
    }

    private SecurityScheme jwtSecurityScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Informe o token JWT obtido via /auth/login");
    }
}
