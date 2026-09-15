package com.oficina.domain.identidade;

import java.util.Objects;
import java.util.UUID;

public record Ator(TipoAtor tipo, UUID id) {
    public Ator {
        Objects.requireNonNull(tipo, "Tipo do ator obrigatório");
        if (tipo == TipoAtor.LEGACY_UNKNOWN) {
            throw new IllegalArgumentException("LEGACY_UNKNOWN é reservado à migração de histórico");
        }
        if ((tipo == TipoAtor.SYSTEM) != (id == null)) {
            throw new IllegalArgumentException("Staff e cliente exigem ID; sistema não admite ID");
        }
    }

    public static Ator staff(UUID id) { return new Ator(TipoAtor.STAFF, id); }
    public static Ator cliente(UUID id) { return new Ator(TipoAtor.CUSTOMER, id); }
    public static Ator sistema() { return new Ator(TipoAtor.SYSTEM, null); }
}
