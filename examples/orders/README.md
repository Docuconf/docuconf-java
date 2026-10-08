# orders: a docuconf example for Spring Boot

A small Spring Boot web service whose settings are a `@ConfigurationProperties` record,
[`OrdersProperties`](src/main/java/dev/docuconf/examples/orders/OrdersProperties.java). It is an ordinary Spring Boot
project (parent `spring-boot-starter-parent`), set up exactly as the [SDK README](../../README.md) says, so it is
what yours looks like. The docuconf annotation processor turns the record, its Bean Validation annotations and its
Javadoc into [`contract.cue`](contract.cue) at compile time, and docuconf checks the environment against that
contract at startup, before Spring binds the record. `GET /healthz` returns `ok`; `GET /config` returns the typed
settings with the secret redacted.

| Variable | Type | Rules |
|---|---|---|
| `ORDERS_PORT` | int | 1 to 65535, default 8080 |
| `ORDERS_LOGLEVEL` | enum | `debug`, `info`, `warn`, `error` (the app also takes `WARN` and so on); default `info` |
| `ORDERS_DATABASEURL` | url | secret, required, scheme `postgres`, at most 2048 characters |
| `ORDERS_ALLOWEDORIGINS` | list of strings, comma-separated | at least 1 item; default `http://localhost:3000` |
| `ORDERS_REQUESTTIMEOUT` | duration (`PT30S` or `30s`) | 1s to 5m, default 30s |
| `ORDERS_WORKERCOUNT` | int | 1 to 64, default 4 |

The names are the ones Spring's relaxed binding reads for `orders.port`, `orders.log-level` and so on.

## Run it

Install the SDK first: `mvn install -DskipTests` in the repository root. Then, in this directory:

```sh
mvn package
ORDERS_DATABASEURL='postgres://orders:secret@localhost:5432/orders' java -jar target/orders.jar
```

`curl localhost:8080/config` shows the settings, with `databaseUrl` as `***`.

## When the configuration is wrong

```sh
ORDERS_PORT=0 java -jar target/orders.jar
```

The service does not start, exits with status 1 and lists every problem with its code:

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

docuconf: 2 configuration problems:

    [missing_required] ORDERS_DATABASEURL: is required (orders.database-url)
    [out_of_range] ORDERS_PORT: is below min 1 (got 0)
```

In Kubernetes the same lines go to `/dev/termination-log`, so `kubectl describe pod` shows them.

## Test, export, check

[`OrdersConfigTest`](src/test/java/dev/docuconf/examples/orders/OrdersConfigTest.java) checks environments with
`DocuconfTester`, without starting the app. `mvn docuconf:export` writes `contract.cue`; `mvn verify` runs the tests
and fails if the committed `contract.cue` is not the exported one. Change only `application.yml` and the contract
follows: the docuconf Maven plugin's `refresh` goal recompiles.

[`quickstart.sh`](quickstart.sh) runs these commands and [`smoke.sh`](smoke.sh) checks a running service; CI runs
both. [`../orders-gradle`](../orders-gradle) builds the same sources with Gradle.

## Generated docs

[`CONFIG.md`](CONFIG.md), [`CONFIG.agents.md`](CONFIG.agents.md) and [`docs.json`](docs.json) are generated from
`contract.cue` by the `docuconf` CLI from [docuconf-go](https://github.com/docuconf/docuconf-go); never edit them by
hand. The first is the reference for developers, the second the rules and facts AI agents need to change the code or
set deployment values, and the third the docs model both are rendered from. Regenerate them after exporting the
contract:

```sh
docuconf docs contract.cue -o CONFIG.md
docuconf docs contract.cue --format agents -o CONFIG.agents.md
docuconf docs contract.cue --format model -o docs.json
```

CI runs the same commands with `--check` and fails when a file is out of date. `ORDERS_WORKERCOUNT` shows where the
text comes from: the first sentence of its Javadoc is the description, and the rest its details.

## Deploy

The platform team deploys against `contract.cue`, not the Java code: `docuconf vet -contract contract.cue -values
values.yaml` checks their values, and `docuconf render` produces the container's environment. A missing database URL
or an out-of-range port is caught before deploy, and the startup check catches whatever still gets through.
