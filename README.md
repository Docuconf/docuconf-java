# docuconf for Java (Spring Boot)

Typed configuration contracts for Spring Boot `@ConfigurationProperties`. Your properties classes, with the Bean
Validation annotations you already use, become a CUE contract that your Kubernetes platform checks **before
deploy**, and that your app checks again **at startup**. It covers environment variables, the
`application*.yml` files in your jar, and file inputs: TLS key pairs, CA bundles, keystores, structured config
files, licence files and binary data.

Part of [docuconf](https://github.com/docuconf). See the
[specification](https://github.com/docuconf/docuconf-go/blob/main/spec/SPEC.md).

**Example:** [`examples/orders/`](examples/orders/), a small Spring Boot web service with its exported contract.

> **Status:** `0.1.0`, not yet published. The contract format is a draft (`v1alpha1`) and the API may change.
> The Maven groupId `dev.docuconf` assumes the `docuconf.dev` domain; that namespace is **not yet verified** on
> Maven Central (see [RELEASING.md](RELEASING.md)).

| Artifact | What it is |
|---|---|
| `dev.docuconf:docuconf-core` | Annotations, file handles (`TlsKeyPair`, `CaBundle`, `Keystore`), contract model and file checks. JDK only. |
| `dev.docuconf:docuconf-processor` | Annotation processor: writes `META-INF/docuconf/contract.cue` at compile time. |
| `dev.docuconf:docuconf-spring` | Spring Boot auto-configuration: validates everything at startup. Spring Boot 3.x and 4.x, Java 17+. |

## Install

Maven:

```xml
<dependency>
  <groupId>dev.docuconf</groupId>
  <artifactId>docuconf-spring</artifactId>
  <version>0.1.0</version>
</dependency>

<!-- in maven-compiler-plugin's <configuration> -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.docuconf</groupId>
    <artifactId>docuconf-processor</artifactId>
    <version>0.1.0</version>
  </path>
</annotationProcessorPaths>
<compilerArgs>
  <arg>-Adocuconf.appVersion=${project.version}</arg>
</compilerArgs>
```

Gradle:

```kotlin
implementation("dev.docuconf:docuconf-spring:0.1.0")
annotationProcessor("dev.docuconf:docuconf-processor:0.1.0")
```

Bean Validation needs a provider at runtime, as usual: `spring-boot-starter-validation`.

## Declare

Ordinary Spring Boot configuration properties, marked `@Docuconf`:

```java
/**
 * Billing settings.
 *
 * @param databaseUrl Primary Postgres connection string
 * @param port HTTP listen port
 * @param timeout Upstream request timeout
 * @param servingTls Certificate the API serves HTTPS with
 * @param rates Pricing tiers by monthly volume
 */
@Docuconf(service = "billing-api")
@Validated
@ConfigurationProperties("billing")
public record BillingProperties(
        @NotNull @Secret @UrlSchemes({"postgres", "postgresql"}) URI databaseUrl,
        @Min(1) @Max(65535) @DefaultValue("8080") int port,
        @DurationMax(minutes = 5) @DefaultValue("30s") Duration timeout,
        @NotNull @TlsFile(value = "/etc/billing/tls", dnsNames = "billing.internal", minRemaining = "720h",
                reload = Reload.WATCH) TlsKeyPair servingTls,
        @NotNull @ConfigFile("/etc/billing/rates/rates.yaml") Rates rates) {

    /** @param tiers Tiers, cheapest last */
    public record Rates(@NotEmpty List<Tier> tiers) {}

    public record Tier(@Positive long upTo, @DecimalMin("0") BigDecimal price) {}
}
```

Descriptions come from Javadoc (the field, the record's `@param`, or the getter); `@Description("...")` overrides
it. Every input needs one of at least five characters. JavaBeans work too: field initializers are the defaults.

| docuconf annotation | Input |
|---|---|
| `@Docuconf(service = ...)` | Marks the class. The service name may also come from `spring.application.name` in `application.yml`. |
| `@Secret` | Must come from a Kubernetes Secret; no default anywhere; never printed. |
| `@UrlSchemes({...})` | A `url` with allowed schemes (on `String`, `URI` or `URL`). |
| `@Json` | One variable holding JSON, bound with Jackson; the contract carries a JSON Schema of the type. |
| `@MaxLength(n)` | `maxLength` on a `url` or `@Json` variable, for apps that store the value in a fixed-width field. A url is measured as it is; a json value as received, whitespace included, before it is parsed (as compact JSON when it comes from `application*.yml` or an overlay as nested keys). |
| `@TlsFile(dir)` on `TlsKeyPair` | `tls.crt`, `tls.key` (PKCS#8, PKCS#1 or SEC1 PEM), optional `ca.crt`. Checked for key match, validity, `minRemaining`, `dnsNames` (SANs; a wildcard covers one label), `keyAlgorithms`, and the PKIX chain to `ca.crt` with `requireCA`. `sslContext()` and `keyStore()` build JSSE objects. |
| `@ConfigFile(path)` on a class or record | JSON, YAML or TOML, read with Jackson into that type (unknown properties rejected) and validated with Bean Validation through the whole object graph. The contract carries a JSON Schema generated from the type. |
| `@CaBundleFile(path)` on `CaBundle` | PEM CA certificates, at least `minCertificates`. |
| `@KeystoreFile(path, passwordProperty = ...)` on `Keystore` | PKCS#12 or JKS; must open with the `@Secret` sibling property. |
| `@TextFile(path, pattern = ...)` on `String` | Licence keys and the like; the property receives the content. |
| `@BinaryFile(path)` on `Path` | Opaque bytes; size only. |
| `@External("vault")` | Supplied by a source the platform does not control; left out. |
| `@Group`, `@Examples` | Docs metadata. |

Every file annotation takes `name`, `pathEnv`, `reload` (`RESTART` or `WATCH`) and `maxSize`. Mark the property
`@NotNull` to make the input required.

## What the contract says

The processor writes `META-INF/docuconf/contract.cue` into your class output (so `target/classes/...` and the jar's
`BOOT-INF/classes/...`), plus `contract.json`, which the runtime reads:

```sh
mvn compile && cp target/classes/META-INF/docuconf/contract.cue contract.cue
unzip -p target/app.jar BOOT-INF/classes/META-INF/docuconf/contract.cue    # from a Boot jar
```

```cue
BILLING_PORT: {
	type: "int"
	description: "HTTP listen port"
	configKey: "billing.port"
	min: 1
	max: 65535
	default: 8080
}
```

How Java maps to the contract:

| Java | Contract |
|---|---|
| Property `billing.database-url` | Variable `BILLING_DATABASEURL`, Spring's relaxed binding for environment variables (`configKey` keeps the property name). Nested classes add segments: `BILLING_DB_POOLSIZE`. |
| `String`, `Path`, `Locale`, ... | `string`. `@Size` → `minLength`/`maxLength`; `@NotBlank` → required, `minLength: 1`, `pattern: "\\S"`. |
| `@Pattern(regexp = "p")` | `pattern: "^(?:p)$"`: `@Pattern` matches the whole value, contract patterns match anywhere (SPEC §4.3). Patterns using Java-only regex features fail the build. |
| `int`, `long`, `Integer`, ... | `int`, with `@Min`/`@Max`/`@Range`/`@Positive`... A type narrower than 64 bits also exports its own range (`int`/`Integer`: `min: -2147483648`, `max: 2147483647`; likewise `short` and `byte`), so the platform never accepts a value the field cannot hold (SPEC §5). |
| `double`, `BigDecimal`, ... | `float`, with inclusive `@DecimalMin`/`@DecimalMax`. Exclusive bounds (`@Positive`) have no contract form and are checked only at startup. |
| `Duration` | `duration` with `encoding: "iso8601"`; `@DurationMin`/`@DurationMax` (Hibernate Validator) → `min`/`max`. Spring's simple format takes one unit (`90s`), so the canonical Go form `1m30s` would not parse; ISO-8601 (`PT1M30S`) does. |
| `URI`, `URL`, or `@UrlSchemes` | `url`. `@MaxLength`, or `@Size(max)` on a `String`, → `maxLength`. |
| an `enum` | `enum` with the constant names. Values are case-sensitive, as the platform checks them: an environment variable must spell the constant exactly (`WARN`, not `warn`), while `application*.yml` keeps Spring's lenient matching. |
| `List`/`Set`/array of strings, ints or enums | `list`, `encoding: "csv"` (Spring splits comma-separated values; `@Delimiter` sets `separator`). `@Size`/`@NotEmpty` → `minItems`/`maxItems`. Container-element constraints on int items, `List<@Min(0) @Max(1023) Integer>`, → `itemMin`/`itemMax`, checked at startup (`out_of_range`); `Integer`, `Short` and `Byte` items also export their type's range, `Long` items do not. On string items, `List<@Size(min = 2, max = 4) String>` → `itemMinLength`/`itemMaxLength`, checked on each item after splitting, so the separator is never counted. `@Size` on int items fails the build. |
| `@NotNull`/`@NotBlank`/`@NotEmpty` without a default | `required: true` |
| Field initializer, `@DefaultValue`, value in `application.yml` | `default` (the yml value wins, as in Spring); a required property with one becomes optional. |
| Value in `application-{profile}.yml` (or a `spring.config.activate.on-profile` document) | `profiles.defaults.{profile}`, selected by `SPRING_PROFILES_ACTIVE`, which is added to the contract as a single profile name defaulting to `profiles.default` (`spring.profiles.active` or `spring.profiles.default` from `application.yml`, else `default`). |
| `Map`, lists of objects | Not expressible in v1alpha1: left out with a warning (file-only). |

Lengths (`minLength`, `maxLength`, `itemMinLength`, `itemMaxLength`) count characters, meaning Unicode code points
(`codePointCount`), never bytes or UTF-16 units (SPEC §4.3): `日本` is 2 and `ZÜ01` fits `@Size(max = 4)`. An emoji
is one character but two Java `char`s, and Bean Validation's own `@Size` counts `char`s, so near the limit
docuconf's startup check and Bean Validation can disagree on astral characters. A value above a limit is
`out_of_range`; a too-long secret reports its length, never its value.

The build fails on declaration errors: a missing description, a default that breaks its own constraint, a
`@Secret` with a default or a value in any `application*.yml`, a non-RE2 pattern, two inputs mounted in one
directory, a reserved mount directory such as `/etc/ssl/certs`. Names that look like feature flags (`ENABLE_*`)
warn (SPEC §10).

Processor options: `-Adocuconf.name=`, `-Adocuconf.appVersion=`, `-Adocuconf.resources=<dir with application.yml>`
(the class output is used by default; with Gradle, `build/resources/main` or `src/main/resources` are tried).

## Validate at startup

Nothing to call: the auto-configuration checks every contract on the class path once the environment is complete
and before any `@ConfigurationProperties` bean is bound. Values are read through Spring's own `Binder`, so relaxed
names, profiles, `application.yml` and Spring's conversions apply exactly as for your beans. Startup fails with
every problem at once:

```
***************************
APPLICATION FAILED TO START
***************************

Description:

The configuration does not satisfy the docuconf contract (5 problems):

    [invalid_scheme] BILLING_DATABASEURL: must use one of the schemes postgres, postgresql
    [invalid_type] BILLING_PORT: is not a valid integer (got "eighty")
    [invalid_type] BILLING_TIMEOUT: is not a valid duration (ISO-8601 such as PT1M30S, or 90s) (got "1m30s")
    [schema_mismatch] rates: /etc/billing/rates/rates.yaml: tiers[0].upTo: must be greater than 0
    [certificate_expiring] serving-tls: the certificate expires at 2026-11-01T00:00:00Z, 239h59m58s from now; at least 720h must remain
```

- Codes are the SPEC §11.2 codes. Secret values never appear, not even a wrongly supplied URL's credentials.
- The same lines go to `/dev/termination-log` (or `$DOCUCONF_TERMINATION_LOG`), so `kubectl describe pod` shows them.
- An empty variable means unset for every type but `string` (SPEC §5): docuconf hides it from Spring, so
  `@DefaultValue` and initializers apply.
- Other Bean Validation constraints on the classes (`@Email`, custom ones) are reported in the same list.
- Spring's relaxed binding also reads a list from one variable per item (`SHOP_ORIGINS_0`, `SHOP_ORIGINS__1`) when the
  plain variable is unset. Items must be numbered from 0 with no gap (SPEC §5): `_0` and `_2` without `_1`, a list
  starting at `_1`, or an index with a leading zero is `invalid_type`, named as such instead of Spring's bind error.
- `DOCUCONF_FILE_ROOT=./dev` reads `/etc/billing/tls` from `./dev/etc/billing/tls` (also for paths from `pathEnv`).
- `docuconf.enabled=false` skips the check, for slice tests and build-time tasks.

File inputs reach your properties object when it is bound. For `reload = WATCH` inputs, docuconf watches the mount
directory (Kubernetes swaps a `..data` symlink there), re-checks the input after a change, and publishes it only
if it passes; your bean keeps the startup value, so read the live one from `DocuconfFiles`:

```java
files.onChange("rates", value -> pricing.update((Rates) value));
TlsKeyPair tls = props.servingTls();   // re-reads tls.crt / tls.key on each call
```

## Platform overlays

Spring layers config files, so the platform can supply settings in one more `application.yml`-style file it mounts
(SPEC §4.7), instead of one environment variable each. Declare it on a `@Docuconf` class:

```java
@Docuconf
@ConfigOverlay(value = "/etc/orders/overlay/orders.yaml", reload = Reload.WATCH)
@Validated
@ConfigurationProperties("orders")
public class OrdersProperties { ... }
```

```cue
overlays: platform: {
	format:       "yaml"
	path:         "/etc/orders/overlay/orders.yaml"
	keySeparator: "."
	reload:       "watch"
}
```

The platform writes each value at its variable's `configKey` (`orders.checkout-timeout`), in native YAML types,
with durations in ISO-8601. Nothing to call at runtime: an `EnvironmentPostProcessor` loads the file with Spring
Boot's own YAML loader right after config data, as a property source just below `systemEnvironment`, so

```
application.yml < application-{profile}.yml < overlay < environment variables < system properties < command line
```

- A missing file is fine; a malformed one fails startup with `file_malformed` alongside the other problems.
- Overlay values are checked like any other, by the same startup check.
- `DOCUCONF_FILE_ROOT` applies to the overlay path too.
- The path must end in `.yml` or `.yaml`, and its directory must be its own: the mount hides whatever the image has
  there. The build rejects reserved directories (`/app`, `/etc`, ...) and directories shared with a file input; at
  startup, docuconf refuses an overlay in the jar's directory or the working directory.
- The overlay is not Spring config data, so it cannot activate profiles: set `SPRING_PROFILES_ACTIVE` in the
  environment (docuconf warns if an overlay sets `spring.profiles.active`).

`reload = Reload.WATCH`: Spring Boot has no reload of its own for `@ConfigurationProperties` (Spring Cloud's
refresh scope is a separate project), so docuconf polls the file (every `docuconf.overlay-poll-interval`, default
`5s`; polling sees the kubelet's symlink swap however it is done). When the content changes, it checks every
variable against the environment as it would be with the new file. If anything fails, the change is logged and
ignored. Otherwise it swaps the property source, binds a fresh instance of each `@Docuconf` JavaBean, copies it
into the live bean (a key the platform removes falls back to its default) and publishes a
`DocuconfOverlayReloadedEvent`. Read values from the bean when you need them, not once at startup; a reader on
another thread may briefly see a mix of old and new values. Records and other constructor-bound classes are
immutable and cannot be rebound, so the build rejects `WATCH` when one of them holds a non-secret variable (secrets
never come from overlays). Use `RESTART` there: the platform renders an immutable ConfigMap and a change rolls the
pods.

## Injected secrets

Values that an injector supplies when the container starts (Bank-Vaults `vault-env`, `op run`, vals, an operator)
need nothing special: docuconf reads the environment the process starts with, after injection, and validates
those values like any other. It never resolves references itself. If a secret still holds a reference at startup
(it starts with `vault:`, `op://` or `ref+`), the injector did not run, and startup fails with:

```
[invalid_type] BILLING_DATABASEURL: holds an unresolved vault: reference; the injector that should resolve it did not run
```

The message names the scheme, never the reference itself.

## Contract-first mode

To validate against a contract written by hand in CUE instead of declared in Java, export it as JSON and load the
environment against it with `ContractFirst` (in `docuconf-core`, no Spring needed):

```sh
cue export ./contract > contract.json   # the package holding contract.#Contract & {...}
```

```java
Map<String, Object> values = ContractFirst.boot(Path.of("contract.json"));  // System.getenv(); throws on violations
ContractFirst.Result r = ContractFirst.load(json, Map.of("PORT", "8080"));    // or any map, without throwing
```

It parses every wire encoding of SPEC §5 (lists: `csv` with `separator`, `json`, `indexed` as `NAME__0`,
`NAME__1`, ... numbered from 0 with no gap, other suffixes such as `NAME__HOST` not being items; durations: `go`, `iso8601`, `seconds`, `timespan`) and checks constraints with the same code the
Spring path uses (`VarChecker`). Values are `String` (string, url, enum), `Long`, `BigDecimal`, `Boolean`,
`Duration`, `List<String>`/`List<Long>`, and the parsed JSON for `json` variables, which are validated against
their JSON Schema. Unset optional variables are `null`. An unset variable takes the default of the profile its `profiles.selector`
variable selects, else its own default. File inputs are checked too (existence, size, TLS, CA
bundles, keystores, text files); a config file is schema-checked only when it is JSON. `boot` writes violations to
the termination log, as the Spring check does. The contract itself is validated first; an invalid one throws
`IllegalArgumentException`.

## Conformance

`ConformanceTest` (in `docuconf-core`) runs the shared suite from docuconf-go (`conformance/cases.json`, SPEC §12)
through the contract-first mode, one JUnit test per case, named by the case `id`:

```sh
DOCUCONF_CONFORMANCE=../docuconf-go/conformance/cases.json DOCUCONF_REQUIRE_CONFORMANCE=1 \
  mvn test -pl docuconf-core -Dtest=ConformanceTest
```

Without `DOCUCONF_CONFORMANCE` it looks for `docuconf-go/conformance/cases.json` in the working directory and its
parents (so a sibling checkout is found), and skips when there is none unless `DOCUCONF_REQUIRE_CONFORMANCE=1`.
CI runs it against docuconf-go `main` with both set.

Skipped capability tags: none. Java holds every 64-bit integer (`int64`), and contract-first mode validates `json`
values against their JSON Schema (`json-schema`).

## Develop

```sh
mvn verify          # Java 17+; the cue CLI is used when present
go install cuelang.org/go/cmd/cue@v0.17.1
```

Tests vet exported contracts with `cue vet -c` against the meta-schema at `$DOCUCONF_SPEC_CUE`, else
`../docuconf-go/spec/cue`; they skip that step when cue or the meta-schema is missing, unless
`DOCUCONF_REQUIRE_VET=1`. `docuconf-sample` is a gateway that uses every variable type and every file type; its
exported contract is the golden file `docuconf-sample/src/test/resources/golden/contract.cue`
(regenerate with `mvn test -pl docuconf-sample -am -Ddocuconf.updateGolden=true`). Certificates for tests are
generated with Bouncy Castle (test scope only).

Not done yet: Markdown docs generation, multi-profile activation
(`SPRING_PROFILES_ACTIVE=prod,eu` is rejected by the contract; see SPEC §13 question 5).

## Licence

MIT. See [LICENSE](LICENSE).
