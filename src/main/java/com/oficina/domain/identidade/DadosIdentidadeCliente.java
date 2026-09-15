package com.oficina.domain.identidade;

import com.oficina.entity.TipoDocumento;
import com.oficina.exception.BusinessRuleException;
import com.oficina.validation.ValidadorDocumento;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record DadosIdentidadeCliente(TipoDocumento tipoDocumento, String documento, String email, boolean ativo) {
    public DadosIdentidadeCliente {
        documento = ValidadorDocumento.normalizarDigitos(documento);
        ValidadorDocumento.validarCpfOuCnpj(documento);
        TipoDocumento inferido = ValidadorDocumento.inferirTipo(documento);
        if (tipoDocumento == null) {
            tipoDocumento = inferido;
        } else if (tipoDocumento != inferido) {
            throw new BusinessRuleException("Tipo de documento incompatível com o documento informado.");
        }
        if (email == null || email.isBlank()) {
            throw new BusinessRuleException("Email é obrigatório.");
        }
    }

    public Set<String> camposAlterados(DadosIdentidadeCliente novos) {
        Set<String> campos = new LinkedHashSet<>();
        if (tipoDocumento != novos.tipoDocumento) campos.add("tipo_documento");
        if (!Objects.equals(documento, novos.documento)) campos.add("documento");
        if (!Objects.equals(email, novos.email)) campos.add("email");
        if (ativo != novos.ativo) campos.add("ativo");
        return Set.copyOf(campos);
    }
}
