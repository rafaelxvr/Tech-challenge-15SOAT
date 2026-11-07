package com.oficina.saga.config;

import com.oficina.application.port.out.CicloOsPort;
import com.oficina.saga.application.CicloOsManual;
import com.oficina.saga.application.CicloOsSagaService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SagaConfigurationTest {
    final CicloOsSagaService saga = mock(CicloOsSagaService.class);
    final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(SagaConfiguration.class, SagaMensageriaConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(CicloOsSagaService.class, () -> saga)
            .withPropertyValues("oficina.saga.filial-padrao=MATRIZ", "oficina.saga.prazos.diagnostico=3d",
                    "oficina.saga.prazos.resposta-automatica=5m", "oficina.saga.prazos.decisao=7d",
                    "oficina.saga.prazos.pagamento=30m", "oficina.saga.prazos.compensacao=5m",
                    "oficina.saga.prazos.max-tentativas-compensacao=5");

    @Test void sagaDesligadaPorPadraoEntregaFluxoManualSemTocarNaSaga() {
        contexto.run(ctx -> {
            CicloOsPort ciclo = ctx.getBean(CicloOsPort.class);
            assertThat(ciclo).isInstanceOf(CicloOsManual.class);
            UUID os = UUID.randomUUID();
            ciclo.iniciar(os);
            assertThat(ciclo.conduzidaPorSaga(os)).isFalse();
            assertThat(ciclo.resumo(os)).isEmpty();
            assertThatThrownBy(() -> ciclo.registrarDecisao(os, true)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(saga);
        });
    }

    @Test void sagaLigadaEntregaOServicoDaSaga() {
        contexto.withPropertyValues("oficina.saga.enabled=true")
                .run(ctx -> assertThat(ctx.getBean(CicloOsPort.class)).isSameAs(saga));
    }

    @Test void mensageriaNaoSobeComASagaDesligada() {
        contexto.withPropertyValues("oficina.saga.mensageria.enabled=true")
                .run(ctx -> assertThat(ctx).hasNotFailed()
                        .doesNotHaveBean(SagaMensageriaConfiguration.class)
                        .doesNotHaveBean(PollerPeriodico.class));
    }

    @Test void mensageriaNaoSobeSemOProprioInterruptor() {
        contexto.withPropertyValues("oficina.saga.enabled=true")
                .run(ctx -> assertThat(ctx).hasNotFailed()
                        .doesNotHaveBean(SagaMensageriaConfiguration.class)
                        .doesNotHaveBean(PollerPeriodico.class));
    }
}
