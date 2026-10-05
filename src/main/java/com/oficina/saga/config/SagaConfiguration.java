package com.oficina.saga.config;

import com.oficina.saga.application.mensagem.FabricaEnvelopes;
import com.oficina.saga.domain.PrazosSaga;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
