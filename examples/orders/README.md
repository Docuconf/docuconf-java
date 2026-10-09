# orders: a docuconf example for Spring Boot

A small Spring Boot web service whose settings are a `@ConfigurationProperties` record,
[`OrdersProperties`](src/main/java/dev/docuconf/examples/orders/OrdersProperties.java). It is an ordinary Spring Boot
project (parent `spring-boot-starter-parent`), set up exactly as the [SDK README](../../README.md) says, so it is
what yours looks like. The docuconf annotation processor turns the record, its Bean Validation annotations and its
Javadoc into [`contract.cue`](contract.cue) at compile time, and docuconf checks the environment against that
contract at startup, before Spring binds the record. A second record,
[`WebhookProperties`](src/main/java/dev/docuconf/examples/orders/WebhookProperties.java), holds the webhook key set
under its own prefix. `GET /healthz` returns `ok`; `GET /config` returns the typed settings with the secrets
redacted; `POST /webhooks/payments` accepts a webhook signed with any key in the set.

| Variable | Type | Rules |
|---|---|---|
| `ORDERS_PORT` | int | 1 to 65535, default 8080 |
| `ORDERS_LOGLEVEL` | enum | `debug`, `info`, `warn`, `error` (the app also takes `WARN` and so on); default `info` |
| `ORDERS_DATABASEURL` | url | secret, required, scheme `postgres`, at most 2048 characters |
| `ORDERS_ALLOWEDORIGINS` | list of strings, comma-separated | at least 1 item; default `http://localhost:3000` |
| `ORDERS_REQUESTTIMEOUT` | duration, ISO 8601 (`PT30S`) | 1s to 5m, default 30s |
| `ORDERS_WORKERCOUNT` | int | 1 to 64, default 4 |
| `WEBHOOK_KEYS` | key set, comma-separated | always secret, optional; 1 to 2 keys of 32 to 256 characters each |

The names are the ones Spring's relaxed binding reads for `orders.port`, `orders.log-level`, `webhook.keys` and so
on.

## Run it

Install the SDK first: `mvn install -DskipTests` in the repository root. Then, in this directory:

```sh
mvn package
ORDERS_DATABASEURL='postgres://orders:secret@localhost:5432/orders' java -jar target/orders.jar
```

`curl localhost:8080/config` shows the settings, with `databaseUrl` and `webhookKeys` as `***`, set or not.

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

## Rotate a key

`WEBHOOK_KEYS` is a key set, a `KeySet` property: `POST /webhooks/payments` accepts a body whose `X-Signature` header
is the hex HMAC-SHA256 of the body under any key in the set
([`Webhooks`](src/main/java/dev/docuconf/examples/orders/Webhooks.java) checks it with `KeySet.anyMatch`, which tries
every key). It is one comma-separated value, so one Kubernetes Secret key holds it:

```yaml
WEBHOOK_KEYS: # a key set: one Secret key holding "old,new" while rotating
  secretKeyRef: {name: orders-webhooks, key: keys}
```

A variable is read once, at start, so a new key reaches the service only when the pods restart; with two keys valid
at once, no webhook is turned away while that happens:

1. Add the new key as the second item (`old,new` in the Secret), and roll out.
2. Switch the sender to the new key.
3. Remove the old key (`new`), and roll out.

The contract allows 1 or 2 keys of 32 to 256 characters each, so a trailing comma or a truncated key stops the
service at startup instead of locking out the sender:

```text
docuconf: 1 configuration problem:

    [out_of_range] WEBHOOK_KEYS: key 1 is empty (a stray separator?)
```

[`WebhooksTest`](src/test/java/dev/docuconf/examples/orders/WebhooksTest.java) walks through a rotation, and
[`smoke.sh`](smoke.sh) posts webhooks signed with both keys.
[SPEC section 6.1](https://github.com/docuconf/docuconf-go/blob/main/spec/SPEC.md#61-rotation) covers rotation in
general.

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
text comes from: the first sentence of its Javadoc is the description, and the rest its details. `WEBHOOK_KEYS` is a
key set, so the generated docs print its rotation steps themselves.

## Deploy

The platform team deploys against `contract.cue`, not the Java code: `docuconf vet -contract contract.cue -values
deploy/values.yaml` checks their values ([`deploy/values.yaml`](deploy/values.yaml); CI runs it), and
`docuconf render` produces the container's environment. A missing database URL or an out-of-range port is caught
before deploy, and the startup check catches whatever still gets through.
