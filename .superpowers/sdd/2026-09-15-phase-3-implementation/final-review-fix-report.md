# B1/B2 final-review fix report

Date: 2026-09-15
Scope: APP `D:/repository/Tech-challenge-15SOAT/.worktrees/phase-3-implementation` and FUN `D:/repository/oficina-functions` only.

## Commits

- APP: `843eff05798ce221256ed1b13e614a1caacc9ad6` — `build: freeze phase 3 baseline inputs`
- FUN: `f3671f5c8159b112762694b475022fcb060f90d5` — `build: enforce functions verification gate`

The APP commit pins the Docker builder to the B1 Maven digest, freezes all five canonical contract files by exact SHA-256, and fixes Linux checkout/archive line endings for the wrapper inputs. The FUN commit records `mvnw` as `100755`, pins JaCoCo 0.8.13 with a bundle-wide 0.80 line-coverage check in `verify`, and fixes Linux line endings for the wrapper inputs.

## Verification commands and outputs

All Maven commands used `JAVA_HOME=C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot` and prepended its `bin` directory to `PATH`.

### Focused APP contract test

```powershell
./mvnw.cmd -B '-Dtest=Phase3ContractTest' test
```

```text
Running com.oficina.contracts.Phase3ContractTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 15.127 s
Exit code: 0
```

The fourth test is `canonicalContractFilesMatchFrozenSha256Set`; it requires exactly `README.md`, `lookup-views.md`, `routes.json`, `status-event.json`, and `token-claims.json` and compares every file to an embedded canonical SHA-256.

### Focused FUN contract and adapter tests

```powershell
./mvnw.cmd -B '-Dtest=Phase3ContractTest,LambdaHandlerAdapterTest' test
```

```text
Running com.oficina.functions.contracts.Phase3ContractTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
Running com.oficina.functions.handler.LambdaHandlerAdapterTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 8.240 s
Exit code: 0
```

### Full APP Java 17 gate

```powershell
./mvnw.cmd -B verify
```

```text
Tests run: 147, Failures: 0, Errors: 0, Skipped: 0
jacoco:0.8.11:check
All coverage checks have been met.
BUILD SUCCESS
Total time: 23.008 s
Exit code: 0
```

### Full FUN Java 17 and coverage gate

```powershell
./mvnw.cmd -B verify
```

```text
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
jacoco:0.8.13:report
Analyzed bundle 'oficina-functions' with 2 classes
jacoco:0.8.13:check
All coverage checks have been met.
BUILD SUCCESS
Total time: 6.964 s
Exit code: 0
```

Coverage extracted from `target/site/jacoco/jacoco.xml`:

```text
Covered : 14
Missed  : 0
Ratio   : 100.00%
Required minimum: 80.00%
```

### Locked Maven builder identity

```powershell
docker run --rm maven@sha256:880934ae394bf91bc3e57d573e4fc04774f064f3c4df7ccd7cc10b3b126737bf sh -c 'mvn -version 2>&1 | sed -n "1,3p"'
```

```text
Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
Maven home: /usr/share/maven
Java version: 17.0.20, vendor: Eclipse Adoptium, runtime: /opt/java/openjdk
Exit code: 0
```

### APP Docker builder

```powershell
docker build --progress=plain --target builder -t oficina-app-builder-final-review:verified .
```

```text
FROM docker.io/library/maven@sha256:880934ae394bf91bc3e57d573e4fc04774f064f3c4df7ccd7cc10b3b126737bf
Compiling 67 source files with javac [debug release 17]
BUILD SUCCESS
builder stage exported successfully
Exit code: 0
```

The runtime stage remains `eclipse-temurin:17-jre-alpine@sha256:27cc0849148c0fd32ee8e95988917becf9bc96a3182a24f99d9763aa8e90f8cb`; no mutable runtime image was introduced.

### APP/FUN canonical hash comparison

```powershell
$names='README.md','lookup-views.md','routes.json','status-event.json','token-claims.json'
foreach ($name in $names) { compare APP and FUN SHA-256 values }
```

```text
README.md 22c9c2586e35646ea338983d02665f831eb18fae7859cf8dba5085c2227d0c9a True
lookup-views.md 53e0cd213b6984d50e721707fb8f1b6e4207181a7eb864a86af142a5d97348bc True
routes.json af9de55d8f8250e452850cabc5334fbde296779e37ba8ca7e6961a7f4b95db68 True
status-event.json dfa194654f6da1a8dc43f7ef0fed1ebf7817c39d99bcc9e60754838aaf362059 True
token-claims.json d4251348bd258134a58c1a01da7913bcf08a3aeb45baa909fed9b732c1c0cf6a True
Exit code: 0
```

### Linux-compatible committed-wrapper validation

For each repository, `git archive HEAD` was mounted into the locked Linux Maven image, extracted, inspected with `stat`, and executed. The ephemeral image installed `unzip` first because Maven Wrapper 3.3.4 otherwise switches the configured `.zip` URL to `.tar.gz`, which cannot match the locked zip checksum. This matches the GitHub Ubuntu runner capability.

```powershell
git archive --format=tar -o <temporary-archive> HEAD
docker run --rm --mount type=bind,source=<temporary-archive>,target=/repo.tar,readonly maven@sha256:880934ae394bf91bc3e57d573e4fc04774f064f3c4df7ccd7cc10b3b126737bf sh -c 'apt-get update -qq && apt-get install -y -qq unzip >/dev/null && mkdir -p /workspace && tar -xf /repo.tar -C /workspace && stat -c "%a %n" /workspace/mvnw && cd /workspace && ./mvnw -B -version'
```

Output for APP and FUN, independently:

```text
775 /workspace/mvnw
Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
Maven home: /root/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f
Java version: 17.0.20, vendor: Eclipse Adoptium, runtime: /opt/java/openjdk
OS name: "linux", version: "6.18.33.2-microsoft-standard-wsl2", arch: "amd64", family: "unix"
Exit code: 0
```

Two diagnostic attempts preceded the successful Linux check:

```text
Attempt 1: mode was 775, but ./mvnw returned "not found" because the Windows-produced archive had a CRLF shebang.
Fix: add .gitattributes with `mvnw text eol=lf`.
Attempt 2: the script executed but checksum validation failed because Maven Wrapper selected the tar.gz fallback when `unzip` was absent.
Fix: pin wrapper properties to LF and validate in a Linux context with the Ubuntu CI extraction capability (`unzip`). Direct download in the same image produced SHA-256 5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce, matching the existing lock, so no lock value changed.
```

## Remaining concerns

- The Maven Wrapper 3.3.4 POSIX script requires `unzip` when `distributionSha256Sum` is the checksum of the configured zip. GitHub Ubuntu runners provide it; unusually minimal Linux images must install it before invoking the wrapper.
- Docker emitted the existing shade-plugin duplicate metadata/module warnings in FUN; they did not affect the shaded artifact, tests, or coverage gate.
