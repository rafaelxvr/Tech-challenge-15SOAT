# Phase 3 baseline and tool resolution

Resolved on 2026-09-15. `toolchain.lock.json` is the machine-readable source for all values below.

## Baseline

- Host default before setup: Oracle Java 25.0.3. It was left unchanged.
- Project verification JDK: Eclipse Temurin 17.0.20.1, installed side by side and selected through `JAVA_HOME` and `PATH` only for the verification process.
- Maven container input tag: `maven:3.9-eclipse-temurin-17`.
- Immutable resolved image: `maven@sha256:880934ae394bf91bc3e57d573e4fc04774f064f3c4df7ccd7cc10b3b126737bf`.
- Windows-safe baseline command: `docker run --rm --mount "type=bind,source=<absolute-worktree>,target=/workspace" -w /workspace <immutable-image> mvn -B verify`.
- Result: `BUILD SUCCESS`; JaCoCo analyzed 29 classes and reported that all coverage checks were met. Total container time was 1 minute 51 seconds.

## Build tool lock

The Maven image resolved Maven 3.9.16 and Java 17.0.20. The wrapper uses Maven Wrapper 3.3.4 in `only-script` mode. The Maven distribution SHA-256 was calculated from the downloaded publisher artifact, and its SHA-512 matched the checksum published beside the artifact in Maven Central.

Terraform 1.15.8 is the initial CLI pin. Kind 0.33.0 is pinned for CI. Helm CLI resolution is intentionally deferred until infrastructure rendering requires it. The existing AWS CLI 2.36.44 was found at `C:\Program Files\Amazon\AWSCLIV2\aws.exe`; it was not reinstalled.

## Dependency resolution

Publisher Maven metadata resolved AWS SDK v2 BOM 2.54.18, Lambda Java Core 1.4.0, Lambda Java Events 3.16.1, and New Relic Java agent 9.4.0. Publisher checksums are stored with each artifact in `toolchain.lock.json`.

Logstash Logback Encoder metadata reports 9.0 as the current release, but release 9 migrates to Jackson 3. `dependency:tree` under the locked wrapper resolved Jackson 2.15.4 and Logback 1.4.14 from Spring Boot 3.2.5, so the compatible 8.x candidate is 8.1. The real encoding test introduced with R1 must pass before that dependency is accepted into the application POM.

Terraform Registry metadata resolved and checksummed the Linux AMD64 packages for `hashicorp/aws` 6.64.0, `hashicorp/kubernetes` 3.2.1, `hashicorp/helm` 3.3.0, and `newrelic/newrelic` 3.97.5. Provider tasks must copy the constraints from `toolchain.lock.json` and commit the generated `.terraform.lock.hcl` for each Terraform root.

Spring Boot 3.2.5, JJWT 0.12.5, and Boot-managed Flyway remain unchanged because the baseline demonstrated no compatibility or build blocker.

## Publisher evidence

- Maven artifacts and metadata: `https://repo.maven.apache.org/maven2/`
- Terraform CLI checksums: `https://releases.hashicorp.com/terraform/1.15.8/terraform_1.15.8_SHA256SUMS`
- Terraform provider packages: `https://registry.terraform.io/v1/providers/`
- Kind release checksum: `https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-windows-amd64.sha256sum`
- Logstash encoder compatibility: `https://github.com/logfellow/logstash-logback-encoder/releases`
