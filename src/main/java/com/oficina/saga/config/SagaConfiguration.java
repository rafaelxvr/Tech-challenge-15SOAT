package com.oficina.saga.config;

import com.oficina.application.port.out.CicloOsPort;
import com.oficina.saga.application.CicloOsManual;
import com.oficina.saga.application.CicloOsSagaService;
import com.oficina.saga.application.mensagem.FabricaEnvelopes;
import com.oficina.saga.domain.PrazosSaga;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SagaProperties.class)
public class SagaConfiguration {
    @Bean PrazosSaga prazosSaga(SagaProperties propriedades) {
        var p = propriedades.prazos();
        return new PrazosSaga(p.diagnostico(), p.respostaAutomatica(), p.decisao(), p.pagamento(),
                p.compensacao(), p.maxTentativasCompensacao());
    }

    @Bean FabricaEnvelopes fabricaEnvelopes(Clock clock, SagaProperties propriedades) {
        return new FabricaEnvelopes(clock, propriedades.filialPadrao());
    }

    /**
     * The saga switch: the one {@link CicloOsPort} the service-order use cases see. Off (the default until the
     * Phase 4 rollout ships), it is the manual pass-through; on, it is the saga service itself.
     */
    @Bean @Primary CicloOsPort cicloOsPort(SagaProperties propriedades, CicloOsSagaService saga) {
        return propriedades.enabled() ? saga : new CicloOsManual();
    }
}
