package com.oficina.domain.identidade;

import com.oficina.entity.TipoDocumento;
import com.oficina.exception.BusinessRuleException;
import com.oficina.validation.ValidadorDocumento;

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
}
