# orders: a docuconf example for Spring Boot

A small Spring Boot web service whose settings are a `@ConfigurationProperties` record,
[`OrdersProperties`](src/main/java/dev/docuconf/examples/orders/OrdersProperties.java). The docuconf annotation
processor turns the record, its Bean Validation annotations and its Javadoc into [`contract.cue`](contract.cue) at
compile time, and docuconf checks the environment against that contract at startup, before Spring binds the record.
`GET /healthz` returns `ok`; `GET /config` returns the typed settings with the secret redacted.

| Variable | Type | Rules |
|---|---|---|
| `ORDERS_PORT` | int | 1 to 65535, default 8080 |
| `ORDERS_LOGLEVEL` | enum | `debug`, `info`, `warn`, `error`; default `info` |
| `ORDERS_DATABASEURL` | url | secret, required, scheme `postgres` |
| `ORDERS_ALLOWEDORIGINS` | list of strings, comma-separated | at least 1 item; default `http://localhost:3000` |
| `ORDERS_REQUESTTIMEOUT` | duration (`PT30S` or `30s`) | 1s to 5m, default 30s |
| `ORDERS_WORKERCOUNT` | int | 1 to 64, default 4 |

The names are the ones Spring's relaxed binding reads for `orders.port`, `orders.log-level` and so on.

## Run it

From the repository root (Java 17+, Maven):

```sh
mvn -pl examples/orders -am package -DskipTests
ORDERS_DATABASEURL='postgres://orders:secret@localhost:5432/orders' java -jar examples/orders/target/orders.jar
curl localhost:8080/healthz
curl localhost:8080/config
```

```json
{"port":8080,"logLevel":"info","databaseUrl":"***","allowedOrigins":["http://localhost:3000"],"requestTimeout":"PT30S","workerCount":4}
```

## When the configuration is wrong

With `ORDERS_PORT=0` and no `ORDERS_DATABASEURL`, the service does not start, exits with status 1 and lists every
problem with its code:

```
$ ORDERS_PORT=0 java -jar examples/orders/target/orders.jar
...
***************************
APPLICATION FAILED TO START
***************************

Description:

The configuration does not satisfy the docuconf contract (2 problems):

    [missing_required] ORDERS_DATABASEURL: is required (orders.database-url)
    [out_of_range] ORDERS_PORT: is below min 1 (got 0)

Action:

Set or fix the environment variables and files listed above. Codes are defined in the docuconf spec (section 11.2). For local runs, DOCUCONF_FILE_ROOT=./dev reads file inputs from ./dev; docuconf.enabled=false skips the check (for tests and build-time tasks).
```

In Kubernetes the same lines go to `/dev/termination-log`, so `kubectl describe pod` shows them.
[`smoke.sh`](smoke.sh) checks both runs: `examples/orders/smoke.sh` after building the jar.

## Export the contract

The annotation processor writes the contract on every compile. Copy it next to the app and commit it:

```sh
mvn -pl examples/orders -am compile
cp examples/orders/target/classes/META-INF/docuconf/contract.cue examples/orders/contract.cue
```

CI exports it again and fails if it differs from the committed file, then runs `cue vet -c` on it against the
docuconf meta-schema.

## Deploy

The platform team deploys against `contract.cue`, not the Java code. Before a rollout they check their values and
files with `docuconf vet -contract contract.cue -values values.yaml` and produce the container's environment with
`docuconf render`; a Helm-based platform uses the
[docuconf Helm chart](https://github.com/docuconf/docuconf-go/tree/main/helm), which generates a
`values.schema.json` from the same contract so `helm install` rejects bad values. Either way a missing database URL
or an out-of-range port is caught before deploy, and the startup check above catches whatever still gets through.
