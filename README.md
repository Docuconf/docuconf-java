# docuconf for Java (Spring Boot)

Typed configuration contracts for Spring Boot `@ConfigurationProperties`. Your properties classes, with the Bean
Validation annotations you already use, become a CUE contract that your Kubernetes platform checks **before
deploy**, and that your app checks again **at startup**. It covers environment variables, the
`application*.yml` files in your jar, and file inputs: TLS key pairs, CA bundles, keystores, structured config
files, licence files and binary data.

Part of [docuconf](https://github.com/docuconf). See the
[specification](https://github.com/docuconf/docuconf-go/blob/main/spec/SPEC.md).

> **Status:** `0.1.0-SNAPSHOT`. Nothing is on Maven Central yet, so you build the SDK from source first (step 1).
> The contract format is a draft (`v1alpha1`) and the API may change. The groupId `dev.docuconf` assumes the
> `docuconf.dev` domain, which is **not yet verified** on Maven Central (see [RELEASING.md](RELEASING.md)).

The steps below follow [`examples/orders/`](examples/orders/), a small Spring Boot web service that is set up
exactly as this README says. CI builds it, runs these commands and compiles every snippet on this page.

| Artifact | What it is |
|---|---|
| `dev.docuconf:docuconf-bom` | One version for all of the below. |
| `dev.docuconf:docuconf-spring` | Spring Boot auto-configuration: validates everything at startup. Spring Boot 3.5+ and 4.x, Java 17+. |
| `dev.docuconf:docuconf-processor` | Annotation processor: writes `META-INF/docuconf/contract.cue` at compile time. |
| `dev.docuconf:docuconf-maven-plugin` | Keeps the contract in step with `application.yml`, exports it and checks it in CI. |
| `dev.docuconf:docuconf-core` | Annotations, file handles (`TlsKeyPair`, `CaBundle`, `Keystore`), contract model, contract-first mode. JDK only. |

## 1. Install

Build the SDK and install `0.1.0-SNAPSHOT` into your local Maven repository (`~/.m2`), with Java 17+ and Maven:

```sh
git clone https://github.com/docuconf/docuconf-java.git
cd docuconf-java
mvn install -DskipTests
```

Once `0.1.0` is on Maven Central, skip this step and use version `0.1.0` below.

### Maven

Import the BOM, then add the runtime and Bean Validation:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.docuconf</groupId>
      <artifactId>docuconf-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

```xml
<dependency>
  <groupId>dev.docuconf</groupId>
  <artifactId>docuconf-spring</artifactId>
</dependency>
<!-- Bean Validation at runtime -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

Add the annotation processor. **Listing `annotationProcessorPaths` turns off javac's discovery of processors on the
class path**, so keep every processor you use in the list (Spring's configuration processor, which gives your IDE
`application.yml` completion, and Lombok, shown here); on JDK 23 and later this list is how processors run anyway:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <!-- Listing annotationProcessorPaths turns off processor discovery on the class path, so list every
         processor you use. With annotationProcessorPathsUseDepMgmt, the versions come from your dependency
         management (Spring Boot's and docuconf-bom). -->
    <annotationProcessorPathsUseDepMgmt>true</annotationProcessorPathsUseDepMgmt>
    <annotationProcessorPaths>
      <path>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-configuration-processor</artifactId>
      </path>
      <path>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
      </path>
      <path>
        <groupId>dev.docuconf</groupId>
        <artifactId>docuconf-processor</artifactId>
      </path>
    </annotationProcessorPaths>
    <compilerArgs>
      <arg>-Adocuconf.appVersion=${project.version}</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

And the Maven plugin, which keeps the contract up to date when only `application.yml` changes and checks the
committed copy in `mvn verify` (see step 6):

```xml
<plugin>
  <groupId>dev.docuconf</groupId>
  <artifactId>docuconf-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <executions>
    <execution>
      <goals>
        <!-- recompile when only application*.yml changed, so the contract never goes stale -->
        <goal>refresh</goal>
        <!-- mvn verify fails when the committed contract.cue differs from the exported one -->
        <goal>check</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

### Gradle (Kotlin DSL)

`mavenLocal()` finds the SDK you installed in step 1:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}
```

```kotlin
dependencies {
    implementation(platform("dev.docuconf:docuconf-bom:0.1.0-SNAPSHOT"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    implementation("dev.docuconf:docuconf-spring")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    annotationProcessor(platform("dev.docuconf:docuconf-bom:0.1.0-SNAPSHOT"))
    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    annotationProcessor("dev.docuconf:docuconf-processor")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
}
```

With the Spring Boot Gradle plugin, its dependency management replaces the two `platform(...)` lines for Spring
Boot. Gradle has no discovery to turn off: list Lombok in `annotationProcessor` as usual. Add the contract tasks
from step 6 too.

## 2. Declare

Ordinary Spring Boot configuration properties, marked `@Docuconf`. This is
[`OrdersProperties`](examples/orders/src/main/java/dev/docuconf/examples/orders/OrdersProperties.java):

```java
/**
 * Settings of the orders service. Each property is the environment variable Spring binds to it: {@code port} is
 * {@code ORDERS_PORT}, {@code databaseUrl} is {@code ORDERS_DATABASEURL}. The Javadoc is the contract's
 * description.
 *
 * @param port HTTP listen port
 * @param logLevel Minimum level of the log lines the service writes
 * @param databaseUrl Postgres connection URL for the orders database
 * @param allowedOrigins Origins allowed to call the API from a browser
 * @param requestTimeout Time allowed to answer one request
 * @param workerCount Background workers that process new orders
 */
@Docuconf(service = "orders", enumCase = EnumCase.LOWER)
@Validated
@ConfigurationProperties("orders")
public record OrdersProperties(
        @Min(1) @Max(65535) @DefaultValue("8080") int port,
        @DefaultValue("INFO") LogLevel logLevel,
        // @Secret: the platform must supply it from a Secret, and docuconf never prints it.
        @NotNull @Secret @UrlSchemes("postgres") URI databaseUrl,
        @NotEmpty @DefaultValue("http://localhost:3000") List<String> allowedOrigins,
        @DurationMin(seconds = 1) @DurationMax(minutes = 5) @DefaultValue("30s") Duration requestTimeout,
        @Min(1) @Max(64) @DefaultValue("4") int workerCount) {

    /** Log levels. The contract spells them in lower case (enumCase); the app accepts any case, as Spring does. */
    public enum LogLevel { DEBUG, INFO, WARN, ERROR }

    /** Prints the secret as [redacted]; a record's generated toString() would print it. */
    @Override
    public String toString() {
        return Redacted.toString(this);
    }
}
```

- Descriptions come from Javadoc (the record's `@param`, the field, or the getter); `@Description("...")`
  overrides it. Every input needs one of at least five characters.
- Defaults come from `@DefaultValue`, field initializers (JavaBeans work too) and `application.yml`.
- `@Secret` values never print: a record holding one must override `toString()` as above (the build tells you), and
  Lombok classes must mark the field `@ToString.Exclude`.
- Mistakes fail the build with a file and line: a missing description, a default that breaks its own constraint,
  an annotation that does not fit the type (`@UrlSchemes` on an `int`), a `@Secret` with a default.

## 3. Run

```sh
mvn package
ORDERS_DATABASEURL='postgres://orders:secret@localhost:5432/orders' java -jar target/orders.jar
```

Nothing to call: the auto-configuration checks every contract on the class path before any
`@ConfigurationProperties` bean is bound, reading values through Spring's own `Binder`, so relaxed names, profiles
and `application.yml` apply exactly as for your beans. A variable that is set but not declared, and within two
edits of one that is, gets a warning: `docuconf: ORDERS_PROT is set but not declared; did you mean ORDERS_PORT?`.

## 4. See an error

```sh
ORDERS_PORT=0 java -jar target/orders.jar
```

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

docuconf: 2 configuration problems:

    [missing_required] ORDERS_DATABASEURL: is required (orders.database-url)
    [out_of_range] ORDERS_PORT: is below min 1 (got 0)

Action:

Set or fix the environment variables and files listed above. Codes are defined in the docuconf spec (section 11.2). For local runs, DOCUCONF_FILE_ROOT=./dev reads file inputs from ./dev; docuconf.enabled=false skips the check (for tests and build-time tasks).
```

Every problem at once, each with its SPEC section 11.2 code, and exit status 1, without a stack trace. Secret
values never appear, not even a wrongly supplied URL's credentials. The same lines go to `/dev/termination-log` (or
`$DOCUCONF_TERMINATION_LOG`), so `kubectl describe pod` shows them.

## 5. Test your config

`DocuconfTester` runs the startup check against an environment map: it never reads or changes the process
environment and starts no threads, and Spring Boot loads `application*.yml` and profiles as at startup. From the
example's [`OrdersConfigTest`](examples/orders/src/test/java/dev/docuconf/examples/orders/OrdersConfigTest.java):

```java
class OrdersConfigTest {

    private static final String DB = "postgres://orders:secret@db.internal:5432/orders";

    @Test
    void aValidEnvironmentPasses() {
        var result = DocuconfTester.env(Map.of("ORDERS_DATABASEURL", DB, "ORDERS_LOGLEVEL", "warn")).check();
        assertTrue(result.ok(), result.violations().toString());
    }

    @Test
    void everyProblemIsReported() {
        var result = DocuconfTester.env(Map.of("ORDERS_PORT", "0", "ORDERS_REQUESTTIMEOUT", "1m30s")).check();
        assertEquals(List.of(
                "invalid_type ORDERS_REQUESTTIMEOUT",
                "missing_required ORDERS_DATABASEURL",
                "out_of_range ORDERS_PORT"), result.codes());
    }

    @Test
    void aTypoGetsAHint() {
        var result = DocuconfTester.env(Map.of("ORDERS_DATABASEURL", DB, "ORDERS_PROT", "9090")).check();
        assertEquals(List.of("ORDERS_PROT is set but not declared; did you mean ORDERS_PORT?"), result.warnings());
    }
}
```

`.fileRoot(dir)` reads file inputs under a test directory, `.properties(Map.of("spring.profiles.active", "prod"))`
selects a profile. For `ApplicationContextRunner` and slice tests, `DocuconfTestEnvironment.of(map)` is an
initializer that swaps in the map as the environment variables. To switch the check off in a `@SpringBootTest`,
set `docuconf.enabled=false`.

## 6. Export the contract

The processor writes `META-INF/docuconf/contract.cue` on every compile. Commit a copy next to your build file;
the platform deploys against it.

```sh
mvn docuconf:export
mvn verify
```

`docuconf:export` compiles and copies the contract to `contract.cue`. In `mvn verify`, the `check` goal fails when
the committed file differs from the exported one and names the first different line, so CI needs nothing else. The
`refresh` goal recompiles when only `application*.yml` changed, which javac alone would not notice.

With Gradle, add these tasks; `./gradlew docuconfExport` writes `contract.cue`, and `./gradlew check` fails when it
is out of date:

```kotlin
// application*.yml feeds the contract: make it an input of compileJava, and have the processor read it from the
// sources rather than from build/resources, which compileJava may see before processResources refreshes it.
val springResources = sourceSets.main.get().resources.sourceDirectories.filter { it.isDirectory }
tasks.compileJava {
    inputs.files(springResources.asFileTree.matching { include("application*.*", "config/application*.*") })
        .withPropertyName("docuconfSpringFiles").withPathSensitivity(PathSensitivity.RELATIVE)
    options.compilerArgs.add("-Adocuconf.resources=${springResources.first()}")
}

val exportedContract = tasks.compileJava.flatMap { it.destinationDirectory.file("META-INF/docuconf/contract.cue") }

// ./gradlew docuconfExport writes contract.cue next to the build file, to commit.
val docuconfExport by tasks.registering {
    val exported = exportedContract
    val committed = layout.projectDirectory.file("contract.cue")
    inputs.file(exported)
    outputs.file(committed)
    doLast { exported.get().asFile.copyTo(committed.asFile, overwrite = true) }
}

// ./gradlew check fails when the committed contract.cue is not the exported one.
val docuconfCheck by tasks.registering {
    val exported = exportedContract
    val committed = layout.projectDirectory.file("contract.cue")
    inputs.file(exported)
    doLast {
        val now = exported.get().asFile.readText()
        if (!committed.asFile.exists() || committed.asFile.readText() != now) {
            throw GradleException("docuconf: contract.cue is out of date; run ./gradlew docuconfExport and commit it")
        }
    }
}
tasks.check { dependsOn(docuconfCheck) }
```

As a last guard, the contract records a hash of each `application*.yml` it was exported from. A packaged app whose
`application.yml` differs from its contract's fails to start and says to rebuild; a development run only warns.
`docuconf.stale-contract` (`auto`, `fail`, `warn`, `ignore`) changes that.

Part of the exported [`contract.cue`](examples/orders/contract.cue):

```cue
		ORDERS_LOGLEVEL: {
			type: "enum"
			description: "Minimum level of the log lines the service writes"
			configKey: "orders.log-level"
			values: ["debug", "info", "warn", "error"]
			default: "info"
		}
```

## 7. Deploy

The platform team deploys against `contract.cue`, not your Java code. Before a rollout they check their values
and files with `docuconf vet -contract contract.cue -values values.yaml` and produce the container's environment
with `docuconf render` (both from [docuconf-go](https://github.com/docuconf/docuconf-go)); a Helm-based platform
uses the [docuconf Helm chart](https://github.com/docuconf/docuconf-go/tree/main/helm), which turns the same
contract into a `values.schema.json`. A missing database URL or an out-of-range port is caught before deploy, and
the startup check catches whatever still gets through.

---

# Reference

## Annotations

| docuconf annotation | Input |
|---|---|
| `@Docuconf(service = ...)` | Marks the class. The service name may also come from `spring.application.name` in `application.yml`. `enumCase` and `envNames` are below. |
| `@Secret` | Must come from a Kubernetes Secret; no default anywhere; never printed. |
| `@UrlSchemes({...})` | A `url` with allowed schemes (on `String`, `URI` or `URL`). |
| `@EnumValues(EnumCase.LOWER)` | How this enum property's values are spelled in the contract (`AS_DECLARED`, `LOWER`, `KEBAB`); `@Docuconf(enumCase = ...)` sets it for the class. |
| `@Json` | One variable holding JSON, bound with Jackson; the contract carries a JSON Schema of the type (with a top-level `@Size`/`@NotEmpty` as `minItems`/`maxItems`). |
| `@TlsFile(dir)` on `TlsKeyPair` | `tls.crt`, `tls.key` (PKCS#8, PKCS#1 or SEC1 PEM), optional `ca.crt`. Checked for key match, validity, `minRemaining`, `dnsNames` (SANs; a wildcard covers one label), `keyAlgorithms`, and the PKIX chain to `ca.crt` with `requireCA`. `sslContext()` and `keyStore()` build JSSE objects. |
| `@ConfigFile(path)` on a class or record | JSON, YAML or TOML, read with Jackson into that type (unknown properties rejected) and validated with Bean Validation through the whole object graph. The contract carries a JSON Schema generated from the type. |
| `@CaBundleFile(path)` on `CaBundle` | PEM CA certificates, at least `minCertificates`. |
| `@KeystoreFile(path, passwordProperty = ...)` on `Keystore` | PKCS#12 or JKS; must open with the `@Secret` sibling property. |
| `@TextFile(path, pattern = ...)` on `String` | Licence keys and the like; the property receives the content. |
| `@BinaryFile(path)` on `Path` | Opaque bytes; size only. |
| `@ConfigOverlay(path)` on the class | A platform-mounted `application.yml`-style file layered over yours (below). |
| `@External("vault")` | Supplied by a source the platform does not control; left out. |
| `@Group`, `@Examples`, `@Description` | Docs metadata. |

Every file annotation takes `name`, `pathEnv`, `reload` (`RESTART` or `WATCH`) and `maxSize`. Mark the property
`@NotNull` to make the input required. An annotation on a type it does not fit fails the build.

## How Java maps to the contract

| Java | Contract |
|---|---|
| Property `orders.database-url` | Variable `ORDERS_DATABASEURL`, the name Spring's relaxed binding documents for environment variables (`configKey` keeps the property name). Nested classes add segments: `ORDERS_DB_POOLSIZE`. `@Docuconf(envNames = EnvNames.UNDERSCORED)` exports `ORDERS_DATABASE_URL` instead, which Spring binds too; the build fails if two properties would share a name. |
| `String`, `Path`, `Locale`, ... | `string`. `@Size` → `minLength`/`maxLength`; `@NotBlank` → required, `minLength: 1`, `pattern: "\\S"`. |
| `@Pattern(regexp = "p")` | `pattern: "^(?:p)$"`: `@Pattern` matches the whole value, contract patterns match anywhere (SPEC section 4.3). Patterns using Java-only regex features fail the build. |
| `int`, `long`, `Integer`, ... | `int`, with `@Min`/`@Max`/`@Range`/`@Positive`... A type narrower than 64 bits also exports its own range (`int`: `min: -2147483648`, `max: 2147483647`). An `int` without a default is 0 when unset, so `@Min(1) int` needs `@DefaultValue` or `@NotNull Integer`; the build says so. |
| `double`, `BigDecimal`, ... | `float`, with inclusive `@DecimalMin`/`@DecimalMax`. Exclusive bounds (`@Positive`) have no contract form and are checked only at startup. |
| `Duration` | `duration` with `encoding: "iso8601"`; `@DurationMin`/`@DurationMax` → `min`/`max`. Spring's simple format takes one unit (`90s`), so the Go form `1m30s` does not parse; the error says `expected an ISO 8601 duration like PT30S`. |
| `URI`, `URL`, or `@UrlSchemes` | `url` |
| an `enum` | `enum` with the constant names, or as `enumCase` spells them. The platform checks that spelling exactly; the app accepts any case Spring accepts (`warn` for `WARN`), wherever the value comes from. |
| `List`/`Set`/array of strings, ints or enums | `list`, `encoding: "csv"` (`@Delimiter` sets `separator`). `@Size`/`@NotEmpty` → `minItems`/`maxItems`. `List<@Min(0) @Max(1023) Integer>` → `itemMin`/`itemMax`. Spring also reads one variable per item (`ORDERS_SHARDS_0`, `ORDERS_SHARDS__1`): items must be numbered from 0 with no gap (SPEC section 5). |
| `@NotNull`/`@NotBlank`/`@NotEmpty` without a default | `required: true` |
| Field initializer, `@DefaultValue`, value in `application.yml` | `default` (the yml value wins, as in Spring); a required property with one becomes optional. |
| Value in `application-{profile}.yml` (or a `spring.config.activate.on-profile` document) | `profiles.defaults.{profile}`, selected by `SPRING_PROFILES_ACTIVE`, which is added to the contract as a single profile name. |
| `Map`, lists of objects | Not expressible in v1alpha1: left out with a warning (file-only). |

Processor options: `-Adocuconf.name=`, `-Adocuconf.appVersion=`, `-Adocuconf.resources=<dir with application.yml>`.

Where the contract is in your jar: Spring Boot's repackaging (Maven and Gradle) moves `META-INF/` to the root, so
`unzip -p target/orders.jar META-INF/docuconf/contract.cue` (Gradle: `build/libs/<app>.jar`). The startup check
finds it either way. Prefer the exported `contract.cue` from step 6.

## At startup

- An empty variable means unset for every type but `string` (SPEC section 5): `@DefaultValue` and initializers
  apply.
- Values are never trimmed (SPEC section 5): `ORDERS_PORT=" 8080"` is `invalid_type`, as the platform says.
- A value Spring read under its other name is named in the message: `... (set as ORDERS_WORKER_COUNT)`.
- Other Bean Validation constraints on the classes (`@Email`, custom ones) are reported in the same list, with
  their own message; for a secret, the value is redacted from it.
- `DOCUCONF_FILE_ROOT=./dev` reads `/etc/orders/tls` from `./dev/etc/orders/tls` (also for paths from `pathEnv`).
- `docuconf.enabled=false` skips the check, for slice tests and build-time tasks.
- `@Json` variables and `@ConfigFile` files are read with the Jackson your app has: Jackson 3 on Spring Boot 4,
  Jackson 2 on Spring Boot 3 (`spring-boot-starter-json`), plus `jackson-dataformat-yaml` or
  `jackson-dataformat-toml` for config files in those formats. If one is missing, startup says which.

File inputs reach your properties object when it is bound. For `reload = WATCH` inputs, docuconf watches the mount
directory (Kubernetes swaps a `..data` symlink there), re-checks the input after a change and publishes it only if
it passes. Your bean keeps the startup value; read the live one from `DocuconfFiles`, for example
`files.onChange("rates", Rates.class, pricing::update)`. An unknown input name, or a listener on an input that is
not watched, throws and names the inputs there are.

## Platform overlays

Spring layers config files, so the platform can supply settings in one more `application.yml`-style file it mounts
(SPEC section 4.7), instead of one environment variable each. Declare it on a `@Docuconf` class with
`@ConfigOverlay(value = "/etc/orders/overlay/orders.yaml", reload = Reload.WATCH)`. The platform writes each value
at its variable's `configKey` (`orders.request-timeout`), in native YAML types, with durations in ISO 8601. An
`EnvironmentPostProcessor` loads the file with Spring Boot's own YAML loader right after config data, as a
property source just below `systemEnvironment`, so the order is `application.yml` < `application-{profile}.yml` <
overlay < environment variables < system properties < command line.

- A missing file is fine; a malformed one fails startup with `file_malformed` alongside the other problems.
- `DOCUCONF_FILE_ROOT` applies to the overlay path too.
- The path must end in `.yml` or `.yaml`, and its directory must be its own: the build rejects reserved directories
  (`/app`, `/etc`, ...) and directories shared with a file input.
- The overlay cannot activate profiles: set `SPRING_PROFILES_ACTIVE` in the environment.
- `reload = Reload.WATCH` polls the file (`docuconf.overlay-poll-interval`, default `5s`). On a valid change it
  rebinds each `@Docuconf` JavaBean in place and publishes a `DocuconfOverlayReloadedEvent`; an invalid change is
  logged and ignored. Records are immutable, so the build rejects `WATCH` when one holds a non-secret variable: use
  `RESTART` there.

## Injected secrets

Values that an injector supplies when the container starts (Bank-Vaults `vault-env`, `op run`, vals, an operator)
need nothing special: docuconf validates the environment the process starts with. If a secret still holds a
reference at startup (it starts with `vault:`, `op://` or `ref+`), the injector did not run, and startup fails with
`[invalid_type] ORDERS_DATABASEURL: holds an unresolved vault: reference; the injector that should resolve it did
not run`. The message names the scheme, never the reference.

## Contract-first mode

To validate against a contract written by hand in CUE instead of declared in Java, export it as JSON
(`cue export ./contract > contract.json`) and load the environment against it with `ContractFirst`, in
`docuconf-core` (no Spring needed). The one-liner prints `docuconf: N configuration problems:` with one line per
problem, writes the termination log and exits 1:

```java
Map<String, Object> config = ContractFirst.bootOrExit(Path.of("contract.json"));
```

For tests, load any map; nothing is read from the process:

```java
ContractFirst.Result r = ContractFirst.load(json, Map.of("PORT", "8080"));
```

It parses every wire encoding of SPEC section 5 and checks constraints with the same code the Spring path uses.
Values are `String`, `Long`, `BigDecimal`, `Boolean`, `Duration`, lists, and the parsed JSON of `json` variables.

## Conformance

`ConformanceTest` (in `docuconf-core`) runs the shared suite from docuconf-go (`conformance/cases.json`, SPEC
section 12) through the contract-first mode, one JUnit test per case. Set `DOCUCONF_CONFORMANCE` to the file and
`DOCUCONF_REQUIRE_CONFORMANCE=1` to fail instead of skipping when it is missing; without them it looks for
`docuconf-go/conformance/cases.json` in the working directory and its parents. CI runs it against docuconf-go
`main`. Skipped capability tags: none.

## Troubleshooting

- **Why `ORDERS_DATABASEURL` and not `ORDERS_DATABASE_URL`?** The first is the name Spring documents for
  `orders.database-url`. Spring binds both; for the second in the contract, use
  `@Docuconf(envNames = EnvNames.UNDERSCORED)`.
- **No `contract.cue` after building in IntelliJ.** Enable annotation processing (Settings → Build → Compiler →
  Annotation Processors), or delegate builds to Maven or Gradle.
- **My `application.yml` change is not in the contract.** Add the Maven plugin's `refresh` goal, or the Gradle
  `compileJava` input from step 6. Until then, `mvn clean compile` exports it again.
- **IDE completion for my properties disappeared.** `annotationProcessorPaths` turned off processor discovery; add
  `spring-boot-configuration-processor` to the list (step 1).
- **Turn the check off in a test:** `@SpringBootTest(properties = "docuconf.enabled=false")`, or test the
  configuration itself with `DocuconfTester` (step 5).
- **`ORDERS_LOGLEVEL=warn` fails on the platform but not in the app.** The platform checks the contract's
  spelling; export the enum in the case your deployments use with `enumCase`.

## Develop

```sh
mvn verify
```

Java 17+. Tests vet exported contracts with `cue vet -c` against the meta-schema at `$DOCUCONF_SPEC_CUE`, else
`../docuconf-go/spec/cue`; they skip that step when `cue` or the meta-schema is missing, unless
`DOCUCONF_REQUIRE_VET=1`. `docuconf-sample` is a gateway that uses every variable type and every file type; its
exported contract is the golden file `docuconf-sample/src/test/resources/golden/contract.cue` (regenerate with
`mvn test -pl docuconf-sample -am -Ddocuconf.updateGolden=true`). `ReadmeSnippetsTest` there checks that every
snippet on this page is in a file CI compiles or runs. The examples are separate builds: `examples/orders`
(`quickstart.sh`, `smoke.sh`) and `examples/orders-gradle`.

Not done yet: Markdown docs generation, multi-profile activation (`SPRING_PROFILES_ACTIVE=prod,eu` is rejected by
the contract; see SPEC section 13 question 5), and Gradle incremental annotation processing (the processor reads
JavaBean field initializers through the javac tree API, which Gradle's incremental mode hides).

## Licence

MIT. See [LICENSE](LICENSE).
