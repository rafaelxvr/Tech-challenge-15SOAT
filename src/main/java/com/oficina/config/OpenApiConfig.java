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
                        
                        **Autenticação staff:** `/auth/login` (email e senha); roles ADMIN/MECANICO.
                        **Cliente:** fluxo CPF + código de email no gateway; JWT customer sem refresh, válido por 15 minutos.
                        Consultas exigem `orders:read:self`; decisões exigem `orders:decide:self` e propriedade da OS.
                        Use o número gerado da OS. Ordens ausentes ou de outro cliente retornam o mesmo 404.
                        A resposta do cliente inclui orçamento e histórico sem identidade, contato ou notas internas.
                        `/orcamento/decisao` é a operação canônica; `/aprovar` e `/orcamento/notificacao` são aliases autenticados.
                        A antiga mutação `/email/atualizar-status` foi removida e retorna 404.
                        Tokens antigos exigem novo login após a atualização. Swagger/OpenAPI exigem autenticação staff.
                        
                        **Roles disponíveis:**
                        - `ADMIN`: Acesso total ao sistema
                        - `MECANICO`: Acesso a ordens de serviço e execução
                        - Principal `customer`: somente leitura e decisão das próprias OS, conforme escopo
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
                .description("JWT staff via /auth/login ou customer via CPF + código de email no gateway; use a identidade adequada à rota");
    }
}
