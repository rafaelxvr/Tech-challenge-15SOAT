package com.oficina.support;

import com.oficina.entity.Cliente;
import com.oficina.entity.TipoDocumento;

import java.util.UUID;

public final class Fixtures {
    public static final UUID CLIENTE_A = UUID.fromString("10000000-0000-0000-0000-000000000001");

    private Fixtures() {}

    public static Cliente cliente(UUID id) {
        return Cliente.builder().id(id).nome("Cliente de teste")
                .tipoDocumento(TipoDocumento.CPF).documento("39053344705")
                .email("cliente@example.invalid").telefone("11999999999").ativo(true).build();
    }
}
