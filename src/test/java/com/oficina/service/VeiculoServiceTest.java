package com.oficina.service;

import com.oficina.dto.VeiculoRequest;
import com.oficina.dto.VeiculoResponse;
import com.oficina.entity.Cliente;
import com.oficina.entity.TipoDocumento;
import com.oficina.entity.Veiculo;
import com.oficina.exception.DuplicateEntityException;
import com.oficina.repository.ClienteRepository;
import com.oficina.repository.VeiculoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VeiculoServiceTest {

    @Mock
    private VeiculoRepository veiculoRepository;

    @Mock
    private ClienteRepository clienteRepository;

    @InjectMocks
    private VeiculoService veiculoService;

    @Test
    void criar_placaNova_persiste() {
        UUID clienteId = UUID.randomUUID();
        Cliente cliente = cliente(clienteId);
        VeiculoRequest req = new VeiculoRequest("ABC1D23", "VW", "Gol", 2020, null, null, clienteId);

        when(veiculoRepository.findByPlaca("ABC1D23")).thenReturn(Optional.empty());
        when(clienteRepository.findById(clienteId)).thenReturn(Optional.of(cliente));
        when(veiculoRepository.save(any(Veiculo.class))).thenAnswer(inv -> inv.getArgument(0));

        VeiculoResponse r = veiculoService.criar(req);

        assertThat(r.placa()).isEqualTo("ABC1D23");
        verify(veiculoRepository).save(any(Veiculo.class));
    }

    @Test
    void criar_placaAtivaDuplicada_lanca() {
        UUID clienteId = UUID.randomUUID();
        Cliente cliente = cliente(clienteId);
        Veiculo existente = veiculo(cliente);
        existente.setAtivo(true);
        VeiculoRequest req = new VeiculoRequest("ABC1D23", "VW", "Gol", 2020, null, null, clienteId);

        when(veiculoRepository.findByPlaca("ABC1D23")).thenReturn(Optional.of(existente));
        when(clienteRepository.findById(clienteId)).thenReturn(Optional.of(cliente));

        assertThatThrownBy(() -> veiculoService.criar(req)).isInstanceOf(DuplicateEntityException.class);
    }

    @Test
    void criar_reativaVeiculoInativo() {
        UUID clienteId = UUID.randomUUID();
        Cliente cliente = cliente(clienteId);
        Veiculo inativo = veiculo(cliente);
        inativo.setAtivo(false);
        VeiculoRequest req = new VeiculoRequest("ABC1D23", "Fiat", "Uno", 2019, "Branco", null, clienteId);

        when(veiculoRepository.findByPlaca("ABC1D23")).thenReturn(Optional.of(inativo));
        when(clienteRepository.findById(clienteId)).thenReturn(Optional.of(cliente));
        when(veiculoRepository.save(any(Veiculo.class))).thenAnswer(inv -> inv.getArgument(0));

        VeiculoResponse r = veiculoService.criar(req);

        assertThat(r.marca()).isEqualTo("Fiat");
        assertThat(inativo.isAtivo()).isTrue();
    }

    @Test
    void listarPorCliente_delega() {
        UUID clienteId = UUID.randomUUID();
        Veiculo v = veiculo(cliente(clienteId));
        when(veiculoRepository.findByClienteIdAndAtivoTrue(clienteId, PageRequest.of(0, 5)))
                .thenReturn(new PageImpl<>(List.of(v)));

        assertThat(veiculoService.listarPorCliente(clienteId, PageRequest.of(0, 5)).getContent()).hasSize(1);
    }

    @Test
    void buscar_retornaAtivo() {
        UUID id = UUID.randomUUID();
        Veiculo v = veiculo(cliente(UUID.randomUUID()));
        v.setId(id);
        when(veiculoRepository.findById(id)).thenReturn(Optional.of(v));

        assertThat(veiculoService.buscar(id).placa()).isEqualTo("ABC1D23");
    }

    @Test
    void atualizar_persisteAlteracoes() {
        UUID id = UUID.randomUUID();
        UUID clienteId = UUID.randomUUID();
        Cliente cliente = cliente(clienteId);
        Veiculo v = veiculo(cliente);
        v.setId(id);
        VeiculoRequest req = new VeiculoRequest("ABC1D23", "Ford", "Ka", 2021, "Azul", null, clienteId);

        when(veiculoRepository.findById(id)).thenReturn(Optional.of(v));
        when(veiculoRepository.findByPlaca("ABC1D23")).thenReturn(Optional.of(v));
        when(clienteRepository.findById(clienteId)).thenReturn(Optional.of(cliente));
        when(veiculoRepository.save(any(Veiculo.class))).thenAnswer(inv -> inv.getArgument(0));

        VeiculoResponse r = veiculoService.atualizar(id, req);

        assertThat(r.marca()).isEqualTo("Ford");
    }

    @Test
    void obterAtivoPorPlaca_retorna() {
        Veiculo v = veiculo(cliente(UUID.randomUUID()));
        when(veiculoRepository.findByPlaca("ABC1D23")).thenReturn(Optional.of(v));

        assertThat(veiculoService.obterAtivoPorPlaca("abc-1d23").getPlaca()).isEqualTo("ABC1D23");
    }

    @Test
    void desativar_marcaInativo() {
        UUID id = UUID.randomUUID();
        Veiculo v = veiculo(cliente(UUID.randomUUID()));
        v.setId(id);
        when(veiculoRepository.findById(id)).thenReturn(Optional.of(v));

        veiculoService.desativar(id);

        assertThat(v.isAtivo()).isFalse();
    }

    private static Cliente cliente(UUID id) {
        return Cliente.builder()
                .id(id)
                .nome("N")
                .tipoDocumento(TipoDocumento.CPF)
                .documento("39053344705")
                .email("a@b.com")
                .telefone("11999999999")
                .ativo(true)
                .build();
    }

    private static Veiculo veiculo(Cliente c) {
        return Veiculo.builder()
                .placa("ABC1D23")
                .marca("VW")
                .modelo("Gol")
                .ano(2020)
                .cliente(c)
                .ativo(true)
                .build();
    }
}
