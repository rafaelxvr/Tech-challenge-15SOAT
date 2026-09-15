package com.oficina.entity;

import com.oficina.domain.identidade.DadosIdentidadeCliente;
import com.oficina.support.Fixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.oficina.exception.BusinessRuleException;

class ClienteIdentityTest {
    @Test void emailChangeInvalidatesOldIdentity() {
        Cliente c = Fixtures.cliente(Fixtures.CLIENTE_A);
        long before = c.getVersaoIdentidade();
        c.atualizarIdentidade(new DadosIdentidadeCliente(
                c.getTipoDocumento(), c.getDocumento(), "changed@example.invalid", true));
        assertThat(c.getVersaoIdentidade()).isEqualTo(before + 1);
    }

    @Test void versionsStartSeparately() {
        Cliente c = Fixtures.cliente(Fixtures.CLIENTE_A);
        assertThat(c.getVersaoIdentidade()).isEqualTo(1);
        assertThat(c.getVersao()).isZero();
    }

    @Test void normalizedEquivalentDocumentDoesNotInvalidateIdentity() {
        Cliente c = Fixtures.cliente(Fixtures.CLIENTE_A);
        c.atualizarIdentidade(new DadosIdentidadeCliente(null, "390.533.447-05", c.getEmail(), true));
        assertThat(c.getDocumento()).isEqualTo("39053344705");
        assertThat(c.getVersaoIdentidade()).isEqualTo(1);
    }

    @Test void deactivationAndReactivationInvalidateIdentityOnlyOnceEach() {
        Cliente c = Fixtures.cliente(Fixtures.CLIENTE_A);
        DadosIdentidadeCliente inativo = new DadosIdentidadeCliente(TipoDocumento.CPF, c.getDocumento(), c.getEmail(), false);
        c.atualizarIdentidade(inativo);
        c.atualizarIdentidade(inativo);
        assertThat(c.getVersaoIdentidade()).isEqualTo(2);
        assertThat(c.isAtivo()).isFalse();
        c.atualizarIdentidade(new DadosIdentidadeCliente(TipoDocumento.CPF, c.getDocumento(), c.getEmail(), true));
        assertThat(c.getVersaoIdentidade()).isEqualTo(3);
        assertThat(c.isAtivo()).isTrue();
    }

    @Test void ordinaryContactAndAddressEditsDoNotInvalidateIdentity() {
        Cliente c = Fixtures.cliente(Fixtures.CLIENTE_A);
        c.setNome("Novo nome");
        c.setTelefone("11888888888");
        c.setCep("01001000");
        c.setLogradouro("Rua de teste");
        c.setNumero("12");
        c.setComplemento("Sala 1");
        c.setBairro("Centro");
        c.setCidade("São Paulo");
        c.setEstado("SP");
        assertThat(c.getVersaoIdentidade()).isEqualTo(1);
    }

    @Test void documentChangeInvalidatesIdentity() {
        Cliente c = Fixtures.cliente(Fixtures.CLIENTE_A);
        c.atualizarIdentidade(new DadosIdentidadeCliente(TipoDocumento.CPF, "529.982.247-25", c.getEmail(), true));
        assertThat(c.getDocumento()).isEqualTo("52998224725");
        assertThat(c.getVersaoIdentidade()).isEqualTo(2);
    }

    @Test void multipleIdentityFieldsAdvanceOneVersionAndExposeOnlyFieldNames() {
        Cliente c = Fixtures.cliente(Fixtures.CLIENTE_A);
        DadosIdentidadeCliente novos = new DadosIdentidadeCliente(TipoDocumento.CNPJ, "11.222.333/0001-81", "new@example.invalid", false);
        assertThat(c.dadosIdentidade().camposAlterados(novos))
                .containsExactlyInAnyOrder("tipo_documento", "documento", "email", "ativo");
        c.atualizarIdentidade(novos);
        assertThat(c.getVersaoIdentidade()).isEqualTo(2);
        assertThat(c.getVersao()).isZero();
    }

    @Test void invalidDocumentAndMismatchedTypeAreRejectedBeforeMutation() {
        Cliente c = Fixtures.cliente(Fixtures.CLIENTE_A);
        assertThatThrownBy(() -> c.atualizarIdentidade(new DadosIdentidadeCliente(TipoDocumento.CPF, "11111111111", c.getEmail(), true)))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new DadosIdentidadeCliente(TipoDocumento.CNPJ, c.getDocumento(), c.getEmail(), true))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new DadosIdentidadeCliente(TipoDocumento.CPF, c.getDocumento(), " ", true))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(c.getVersaoIdentidade()).isEqualTo(1);
    }

    @Test void buildersNormalizeIdentityAndCannotSetVersions() {
        Cliente c = Cliente.builder().tipoDocumento(TipoDocumento.CPF).documento("390.533.447-05")
                .email("c@example.invalid").ativo(true).build();
        assertThat(c.getDocumento()).isEqualTo("39053344705");
        assertThat(Cliente.class.getMethods()).extracting(java.lang.reflect.Method::getName)
                .doesNotContain("setTipoDocumento", "setDocumento", "setEmail", "setAtivo", "setVersao", "setVersaoIdentidade");
        assertThat(Cliente.ClienteBuilder.class.getMethods()).extracting(java.lang.reflect.Method::getName)
                .doesNotContain("versao", "versaoIdentidade");
    }
}
