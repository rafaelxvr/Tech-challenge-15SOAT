package com.oficina.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record IdentidadeAutenticada(TipoPrincipal tipo, UUID id, Set<String> permissoes, long versaoIdentidade) {
    public IdentidadeAutenticada {
        Objects.requireNonNull(tipo);
        Objects.requireNonNull(id);
        permissoes = Set.copyOf(permissoes);
    }
}
