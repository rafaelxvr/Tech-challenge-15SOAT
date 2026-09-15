package com.oficina.entity;

import com.oficina.domain.identidade.DadosIdentidadeCliente;
import com.oficina.validation.ValidadorDocumento;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(name = "clientes")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cliente {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String nome;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "tipo_documento", nullable = false, columnDefinition = "tipo_documento")
    @Setter(AccessLevel.NONE)
    private TipoDocumento tipoDocumento;

    @Column(nullable = false, unique = true, length = 18)
    @Setter(AccessLevel.NONE)
    private String documento;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private String email;

    @Column(nullable = false, length = 20)
    private String telefone;

    private String cep;
    private String logradouro;
    private String numero;
    private String complemento;
    private String bairro;
    private String cidade;

    @Column(length = 2)
    private String estado;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private boolean ativo;

    @Version
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private long versao;

    @Column(name = "versao_identidade", nullable = false)
    @Setter(AccessLevel.NONE)
    private long versaoIdentidade = 1;

    @Column(name = "usuario_id")
    private UUID usuarioId;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private LocalDateTime criadoEm;

    @Column(name = "atualizado_em", nullable = false)
    private LocalDateTime atualizadoEm;

    @Builder
    private Cliente(UUID id, String nome, TipoDocumento tipoDocumento, String documento, String email,
                    String telefone, String cep, String logradouro, String numero, String complemento,
                    String bairro, String cidade, String estado, boolean ativo, UUID usuarioId) {
        DadosIdentidadeCliente identidade = new DadosIdentidadeCliente(tipoDocumento, documento, email, ativo);
        this.id = id;
        this.nome = nome;
        this.tipoDocumento = identidade.tipoDocumento();
        this.documento = identidade.documento();
        this.email = identidade.email();
        this.ativo = identidade.ativo();
        this.telefone = telefone;
        this.cep = cep;
        this.logradouro = logradouro;
        this.numero = numero;
        this.complemento = complemento;
        this.bairro = bairro;
        this.cidade = cidade;
        this.estado = estado;
        this.usuarioId = usuarioId;
    }

    public Set<String> camposIdentidadeAlterados(DadosIdentidadeCliente novos) {
        // Compare persisted values without imposing new validation rules on legacy rows.
        Set<String> campos = new LinkedHashSet<>();
        if (tipoDocumento != novos.tipoDocumento()) campos.add("tipo_documento");
        if (!Objects.equals(ValidadorDocumento.normalizarDigitos(documento), novos.documento())) campos.add("documento");
        if (!Objects.equals(email, novos.email())) campos.add("email");
        if (ativo != novos.ativo()) campos.add("ativo");
        return Set.copyOf(campos);
    }

    public void atualizarIdentidade(DadosIdentidadeCliente novos) {
        if (camposIdentidadeAlterados(novos).isEmpty()) {
            return;
        }
        long proximaVersao = Math.incrementExact(versaoIdentidade);
        tipoDocumento = novos.tipoDocumento();
        documento = novos.documento();
        email = novos.email();
        ativo = novos.ativo();
        versaoIdentidade = proximaVersao;
    }

    public void desativar() {
        if (ativo) {
            versaoIdentidade = Math.incrementExact(versaoIdentidade);
            ativo = false;
        }
    }

    @PrePersist
    protected void onCreate() {
        criadoEm = LocalDateTime.now();
        atualizadoEm = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        atualizadoEm = LocalDateTime.now();
    }
}
