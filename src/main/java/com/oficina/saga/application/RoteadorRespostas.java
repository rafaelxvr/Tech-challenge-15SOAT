package com.oficina.saga.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.oficina.saga.application.mensagem.MensagemInvalidaException;
import com.oficina.saga.application.port.OsDaSagaPort;
import com.oficina.saga.domain.MomentoSaga;
import com.oficina.saga.domain.MotivoFalha;
import com.oficina.saga.domain.OsSaga;
import com.oficina.saga.domain.Resposta;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Strategy table: one handler per reply type; the aggregate enforces which state accepts it. */
@Component
public class RoteadorRespostas {
    @FunctionalInterface
    interface Manipulador { void aplicar(OsSaga saga, JsonNode payload, MomentoSaga m); }

    private final Map<Resposta, Manipulador> manipuladores = new EnumMap<>(Resposta.class);
    private final OsDaSagaPort os;

    public RoteadorRespostas(OsDaSagaPort os) {
        this.os = os;
        manipuladores.put(Resposta.DIAGNOSTICO_CONCLUIDO, (s, p, m) -> s.diagnosticoConcluido(m));
        manipuladores.put(Resposta.DIAGNOSTICO_RECUSADO, falha(MotivoFalha.DIAGNOSTICO_RECUSADO));
        manipuladores.put(Resposta.ORCAMENTO_GERADO,
                (s, p, m) -> s.orcamentoGerado(uuid(p, "orcamentoId"), decimal(p, "total"), m));
        manipuladores.put(Resposta.ORCAMENTO_FALHOU, falha(MotivoFalha.ORCAMENTO_FALHOU));
        manipuladores.put(Resposta.ORCAMENTO_APROVADO, (s, p, m) -> s.orcamentoAprovado(texto(p, "checkoutUrl"), m));
        manipuladores.put(Resposta.ORCAMENTO_RECUSADO, (s, p, m) -> s.orcamentoRecusado(m));
        manipuladores.put(Resposta.PAGAMENTO_CONFIRMADO, this::pagamentoConfirmado);
        manipuladores.put(Resposta.PAGAMENTO_RECUSADO, falha(MotivoFalha.PAGAMENTO_RECUSADO));
        manipuladores.put(Resposta.PAGAMENTO_EXPIRADO, falha(MotivoFalha.PAGAMENTO_EXPIRADO));
        manipuladores.put(Resposta.REPARO_INICIADO, (s, p, m) -> s.reparoIniciado(m));
        manipuladores.put(Resposta.REPARO_RECUSADO, falha(MotivoFalha.REPARO_RECUSADO));
        manipuladores.put(Resposta.EXECUCAO_FINALIZADA, (s, p, m) -> s.execucaoFinalizada(m));
        manipuladores.put(Resposta.EXECUCAO_CANCELADA, (s, p, m) -> s.compensacaoConcluida(m));
        manipuladores.put(Resposta.ORCAMENTO_CANCELADO, (s, p, m) -> s.compensacaoConcluida(m));
        manipuladores.put(Resposta.PAGAMENTO_ESTORNADO, (s, p, m) -> s.compensacaoConcluida(m));
        manipuladores.put(Resposta.COMPENSACAO_FALHOU, (s, p, m) -> s.compensacaoFalhou(m));
    }

    public boolean suporta(Resposta resposta) { return manipuladores.containsKey(resposta); }

    public void aplicar(OsSaga saga, Resposta resposta, JsonNode payload, MomentoSaga m) {
        saga.registrarResposta(resposta, m);
        manipuladores.get(resposta).aplicar(saga, payload, m);
    }

    private void pagamentoConfirmado(OsSaga saga, JsonNode payload, MomentoSaga m) {
        saga.pagamentoConfirmado(m);
        if (os.reservarPecas(saga.getOsId())) saga.pecasReservadas(m);
        else saga.falhar(MotivoFalha.ESTOQUE_INSUFICIENTE, m);
    }

    private static Manipulador falha(MotivoFalha motivo) {
        return (s, p, m) -> s.falhar(motivo, m);
    }

    private static String texto(JsonNode payload, String campo) {
        JsonNode valor = payload.get(campo);
        if (valor == null || !valor.isTextual() || valor.asText().isBlank()) {
            throw new MensagemInvalidaException("Campo obrigatório ausente: " + campo);
        }
        return valor.asText();
    }

    private static UUID uuid(JsonNode payload, String campo) {
        try {
            return UUID.fromString(texto(payload, campo));
        } catch (IllegalArgumentException e) {
            throw new MensagemInvalidaException("UUID inválido: " + campo);
        }
    }

    private static BigDecimal decimal(JsonNode payload, String campo) {
        JsonNode valor = payload.get(campo);
        if (valor == null || !valor.isNumber()) throw new MensagemInvalidaException("Número obrigatório: " + campo);
        return valor.decimalValue();
    }
}
