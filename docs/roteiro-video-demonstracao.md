# Roteiro do vídeo demonstrativo — Oficina Mecânica

Tempo-alvo: **13 a 14 minutos**, com margem até o limite de 15 minutos.

## Evidências que o vídeo precisa deixar claras

| Requisito | Evidência mostrada no vídeo |
|---|---|
| Deploy da aplicação | `terraform apply`, rollouts concluídos, pods `Running` e health `UP` |
| Execução do CI/CD | Push para `main` e execução dos três jobs no GitHub Actions |
| Consumo das APIs | Nove chamadas Postman com testes verdes e dados encadeados |
| Escalabilidade automática | HPA passando de 2 réplicas para 3 ou mais após carga Fortio |

## Preparação antes de gravar

1. Abra o Docker Desktop e aguarde o engine ficar ativo.
2. Importe no Postman:
   - `postman/Oficina-Mecanica-Demo.postman_collection.json`;
   - `postman/Oficina-Mecanica-Demo.postman_environment.json`.
3. Selecione o environment **Oficina Mecânica - Demo Local** no canto superior direito do Postman.
4. Deixe abertas estas janelas:
   - PowerShell na raiz do repositório;
   - um segundo PowerShell para observar o HPA;
   - Postman;
   - navegador autenticado em `https://github.com/SilasFurini/Tech-challenge-15SOAT/actions`.
5. Feche notificações e esconda tokens, e-mails pessoais ou outras abas.
6. Grave em 1080p, com fonte do terminal em tamanho legível.

Não execute a coleção Postman antes da gravação: ela gera dados únicos e encadeia automaticamente todas as variáveis.

## Cronograma resumido

| Tempo | Demonstração |
|---|---|
| 00:00–00:30 | Introdução e arquitetura usada |
| 00:30–01:10 | Push que dispara o CI/CD |
| 01:10–04:30 | Build e deploy local com Terraform + Kind |
| 04:30–05:30 | Instalação do Metrics Server |
| 05:30–09:00 | Consumo das APIs no Postman |
| 09:00–12:00 | Carga e escalabilidade automática |
| 12:00–13:30 | Resultado do GitHub Actions |
| 13:30–14:15 | Recapitulação e encerramento |

Se algum download demorar, corte apenas o tempo de espera. Preserve na gravação o comando executado e o resultado final.

---

## 00:00–00:30 — Introdução

### Fala sugerida

> Neste vídeo vou demonstrar o deploy local da API Oficina Mecânica em um cluster Kubernetes Kind provisionado com Terraform. Também vou executar o pipeline CI/CD no GitHub Actions, consumir o fluxo principal das APIs pelo Postman e gerar carga para comprovar o escalonamento automático pelo HPA.

Mostre rapidamente a raiz do repositório e os diretórios `.github/workflows`, `infra`, `k8s` e `postman`.

---

## 00:30–01:10 — Disparar o CI/CD

Use o commit real dos artefatos da demonstração. O `git add` abaixo é propositalmente explícito para não incluir outros arquivos locais:

```powershell
cd C:\Repository\Tech-challenge-15SOAT

git add -- `
  docs/roteiro-video-demonstracao.md `
  postman/Oficina-Mecanica-Demo.postman_collection.json `
  postman/Oficina-Mecanica-Demo.postman_environment.json

git commit -m "docs: add demonstration video assets"
git push origin main
```

Se esses arquivos já tiverem sido enviados antes da gravação, dispare uma execução completa com um commit vazio:

```powershell
git commit --allow-empty -m "ci: executar demonstracao do pipeline"
git push origin main
```

### Fala sugerida

> O push em `main` dispara o workflow CI/CD. Ele executa build e testes com Maven, constrói e publica a imagem no GitHub Container Registry e cria um cluster Kind temporário no runner para validar o deploy e o health check. Enquanto o pipeline executa, vou subir o ambiente local.

Importante: não use apenas o botão **Run workflow** para esta gravação. No workflow atual, o job `Deploy Kubernetes (Kind)` exige um evento `push` em `main` ou `master`.

---

## 01:10–04:30 — Build e deploy local

Execute no PowerShell principal:

```powershell
cd C:\Repository\Tech-challenge-15SOAT

docker version
docker build -t oficina-mecanica:local .

cd .\infra

terraform init
terraform validate
terraform apply -auto-approve
```

Depois valide o ambiente:

```powershell
kubectl config use-context kind-oficina-k8s

kubectl cluster-info
kubectl -n oficina get deploy,svc,pods,hpa,pvc

kubectl -n oficina rollout status deployment/oficina-postgres
kubectl -n oficina rollout status deployment/oficina-app

Invoke-RestMethod http://localhost:30080/api/actuator/health
```

### Resultado que deve aparecer

- dois pods `oficina-app` em `Running` e `READY 1/1`;
- PostgreSQL e MailHog em `Running`;
- service `oficina-app` expondo `30080`;
- HPA com mínimo 2 e máximo 6 réplicas;
- health com `status: UP`.

### Fala sugerida

> A imagem foi construída localmente. O Terraform criou o cluster Kind, carregou a imagem e aplicou os manifests Kubernetes. A aplicação inicia com duas réplicas, banco PostgreSQL, MailHog, Service NodePort e HPA.

---

## 04:30–05:30 — Habilitar métricas do HPA

Instale o Metrics Server logo após o deploy para que ele colete dados enquanto o Postman é demonstrado:

```powershell
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

$patch = '[{\"op\":\"add\",\"path\":\"/spec/template/spec/containers/0/args/-\",\"value\":\"--kubelet-insecure-tls\"}]'

kubectl patch deployment metrics-server `
  -n kube-system `
  --type=json `
  --patch $patch

kubectl -n kube-system rollout status deployment/metrics-server --timeout=120s
```

No Windows PowerShell 5.1, as barras em `$patch` preservam as aspas internas do JSON ao chamar o `kubectl`. Sem elas, o PowerShell envia um JSON Patch malformado e a API do Kubernetes responde com erro `422 Invalid`.

O parâmetro `--kubelet-insecure-tls` é usado somente neste cluster local Kind. A ordem correta é instalar o Metrics Server, aplicar o patch e somente depois aguardar o rollout.

Valide; se as métricas ainda não aparecerem, siga para o Postman e repita depois:

```powershell
kubectl top nodes
kubectl top pods -n oficina
kubectl -n oficina get hpa
```

### Fala sugerida

> O Kind não instala o Metrics Server por padrão. Esse componente fornece ao HPA, sigla de Horizontal Pod Autoscaler, as métricas de CPU e memória usadas para aumentar ou reduzir automaticamente a quantidade de pods.

---

## 05:30–09:00 — Consumo das APIs no Postman

Confirme que o environment **Oficina Mecânica - Demo Local** está selecionado. Execute as requisições individualmente, na ordem numérica. Mostre o request, o status HTTP, a resposta e a aba de testes.

### 01 — Health

```http
GET {{baseUrl}}/actuator/health
```

Evidência: HTTP `200`, `status = UP` e teste verde.

### 02 — Login administrador

```http
POST {{baseUrl}}/auth/login
Content-Type: application/json
```

```json
{
  "email": "admin@oficina.com",
  "senha": "Admin@123"
}
```

Evidência: HTTP `200`, role `ADMIN`. O script salva o `accessToken` como `bearerToken`; não exponha o token inteiro na gravação.

### 03 — Cadastrar cliente

```http
POST {{baseUrl}}/clientes
Authorization: Bearer {{bearerToken}}
```

A coleção gera documento e e-mail únicos. Evidência: HTTP `201` e `data.id`; o script salva `clienteId`.

### 04 — Cadastrar veículo

```http
POST {{baseUrl}}/veiculos
Authorization: Bearer {{bearerToken}}
```

O body usa `{{clienteId}}`. Evidência: HTTP `201` e `data.id`; o script salva `veiculoId`.

### 05 — Selecionar serviço do catálogo

```http
GET {{baseUrl}}/servicos?size=20
Authorization: Bearer {{bearerToken}}
```

Evidência: catálogo paginado com dados do seed; o script salva o primeiro `servicoId`.

### 06 — Selecionar peça do estoque

```http
GET {{baseUrl}}/pecas?size=20
Authorization: Bearer {{bearerToken}}
```

Evidência: estoque paginado com dados do seed; o script salva o primeiro `pecaId`.

### 07 — Abrir ordem de serviço

```http
POST {{baseUrl}}/ordens-servico
Authorization: Bearer {{bearerToken}}
```

O body usa documento, placa, serviço e peça capturados anteriormente. Evidência: HTTP `201`, status `RECEBIDA`, `data.id` e `data.numero`; o script salva `osId` e `osNumero`.

### 08 — Acompanhamento público da OS

```http
GET {{baseUrl}}/ordens-servico/{{osNumero}}/acompanhamento
```

Evidência: HTTP `200` sem JWT, mostrando o número e o status da OS.

### 09 — Métricas administrativas

```http
GET {{baseUrl}}/admin/metricas/tempo-execucao-servicos
Authorization: Bearer {{bearerToken}}
```

Evidência: HTTP `200`, comprovando também autorização administrativa.

### Fala sugerida

> O fluxo demonstrou autenticação JWT, criação de cliente e veículo, consulta ao catálogo e estoque, abertura de uma ordem de serviço e acompanhamento público pelo número da OS. Os testes da coleção validam os status HTTP e encadeiam automaticamente os identificadores retornados pela API.

---

## 09:00–12:00 — Escalabilidade automática

Primeiro mostre a configuração e o estado inicial:

```powershell
kubectl -n oficina describe hpa oficina-app-hpa
kubectl -n oficina get pods -l app=oficina-app
kubectl top pods -n oficina
```

Destaque na fala:

- mínimo: 2 réplicas;
- máximo: 6 réplicas;
- alvo de CPU: 60%;
- alvo de memória: 70%.

No segundo PowerShell, inicie a observação conjunta. O loop atualiza o HPA e os pods a cada dois segundos:

```powershell
while ($true) {
    Clear-Host

    Write-Host "=== HPA ===" -ForegroundColor Cyan
    kubectl -n oficina get hpa oficina-app-hpa

    Write-Host "`n=== PODS DA APLICAÇÃO ===" -ForegroundColor Cyan
    kubectl -n oficina get pods -l app=oficina-app

    Start-Sleep -Seconds 2
}
```

O `kubectl get ... -w` aceita apenas um tipo de recurso por processo nesta versão. Por isso, o loop consulta HPA e pods separadamente no mesmo terminal. Encerre-o com `Ctrl+C` depois de registrar a escala.

Volte ao primeiro PowerShell e gere carga HTTP dentro do cluster:

```powershell
kubectl -n oficina delete pod fortio --ignore-not-found

kubectl -n oficina run fortio `
  --image=fortio/fortio:latest `
  --restart=Never `
  -- load -qps 0 -t 4m -c 100 `
  http://oficina-app:8080/api/actuator/health
```

Aguarde a coluna `TARGETS` superar `60%` de CPU e a quantidade de réplicas passar de `2` para `3` ou mais. Em seguida mostre:

```powershell
kubectl -n oficina get hpa oficina-app-hpa
kubectl -n oficina get deployment oficina-app
kubectl -n oficina get pods -l app=oficina-app -o wide
kubectl -n oficina describe hpa oficina-app-hpa
```

### Fala sugerida

> O Fortio está enviando requisições concorrentes ao health endpoint. O consumo ultrapassou o alvo configurado e o HPA aumentou automaticamente a quantidade de réplicas da aplicação. Não executei `kubectl scale`; a decisão foi tomada pelo controlador do HPA com base nas métricas.

Para encerrar a carga após obter a evidência:

```powershell
kubectl -n oficina delete pod fortio --ignore-not-found
```

Se não houver escala após aproximadamente dois minutos, confira `kubectl top pods -n oficina`. Se a CPU continuar abaixo de 60%, inicie um segundo gerador:

```powershell
kubectl -n oficina run fortio-2 `
  --image=fortio/fortio:latest `
  --restart=Never `
  -- load -qps 0 -t 4m -c 200 `
  http://oficina-app:8080/api/actuator/health
```

---

## 12:00–13:30 — Mostrar o CI/CD concluído

Abra a execução iniciada pelo push no GitHub Actions e mostre os três jobs:

1. **Build + Testes**
   - Setup Java 17;
   - `mvn -B verify` concluído.
2. **Build imagem Docker**
   - build da imagem;
   - publicação no GHCR com tag do SHA e `latest`.
3. **Deploy Kubernetes (Kind)**
   - cluster `oficina-ci` criado no runner;
   - manifests aplicados;
   - rollouts do PostgreSQL e da aplicação concluídos;
   - smoke test em `/api/actuator/health` concluído.

Abra o log final do smoke test e mostre o resultado do health. Não é necessário ler todo o log.

### Fala sugerida

> O pipeline concluiu integração e entrega contínuas: testes, imagem versionada, publicação no registry, deploy em Kubernetes e smoke test. O cluster do CI é efêmero e existe apenas durante o runner; o cluster local que demonstrei foi provisionado separadamente pelo Terraform.

---

## 13:30–14:15 — Encerramento

Volte ao terminal e mostre uma visão final:

```powershell
kubectl -n oficina get deploy,svc,pods,hpa
```

### Fala sugerida

> Com isso foram demonstrados o deploy da aplicação, a execução completa do CI/CD, o consumo autenticado e público das APIs e o aumento automático de réplicas sob carga. O código, os manifests Kubernetes, o Terraform, o workflow e a coleção Postman estão disponíveis no repositório indicado na entrega.

---

## Checklist antes de publicar

- [ ] duração menor ou igual a 15 minutos;
- [ ] áudio compreensível e terminal legível;
- [ ] URL/repositório correto na descrição;
- [ ] vídeo público ou não listado no YouTube/Vimeo;
- [ ] `terraform apply` e pods `Running` visíveis;
- [ ] execução do GitHub Actions visível e concluída;
- [ ] chamadas Postman com status e testes verdes;
- [ ] HPA visivelmente alterando de 2 para 3 ou mais réplicas;
- [ ] nenhum JWT, senha pessoal ou segredo real exposto.

## Referências operacionais

- [Metrics Server — instalação oficial](https://github.com/kubernetes-sigs/metrics-server)
- [Fortio — documentação oficial de geração de carga](https://github.com/fortio/fortio/)
