# shop: roles, requiredIf and fragments in contract-first mode

A tiny program with two roles, `web` and `worker`, that shows three parts of the contract working end to end:

- **roles** ([SPEC section 4.9](https://github.com/docuconf/docuconf-go/blob/main/spec/SPEC.md)): `DOCUCONF_ROLE`
  picks the workload's role, and an input used only by the other role is not required;
- **requiredIf** (SPEC section 4.2.1): `LOG_SCHEMA_URL` is required when `LOG_FORMAT` is `json`, and the
  `webhook-signing-key` file when `WEBHOOKS_ENABLED` is `true`;
- an **included fragment** (SPEC section 4.8): the contract includes `otel@1`, whose `OTEL_*` variables are inline
  and checked at startup like the app's own.

The Java declaration API has no `requiredIf`, roles or includes yet, so the example uses
[contract-first mode](../../README.md#contract-first-mode): the contract is [`contract.cue`](contract.cue), written by
hand, and [`contract.json`](contract.json) is the same contract as JSON, which
[`ShopApplication`](src/main/java/dev/docuconf/examples/shop/ShopApplication.java) loads with
`ContractFirst.bootOrExit` and only `docuconf-core` on the class path. There is no server, database or queue: with a
valid configuration the app prints one line from the typed values and exits 0.

| Variable or file | Type | Roles | Rules |
|---|---|---|---|
| `DOCUCONF_ROLE` | enum | all | required, `web` or `worker` |
| `DATABASE_URL` | url | all | required, secret, scheme `postgres` or `postgresql` |
| `PORT` | int | `web` | 1 to 65535, default 8080 |
| `ALLOWED_ORIGINS` | list of strings, comma-separated | `web` | required |
| `QUEUES` | list of strings, comma-separated | `worker` | required |
| `WEBHOOKS_ENABLED` | bool | `worker` | default `false` |
| `LOG_FORMAT` | enum | all | `text` or `json`, default `text` |
| `LOG_SCHEMA_URL` | url | all | scheme `https`; required when `LOG_FORMAT` is `json` |
| `OTEL_*` (11 variables, from `otel@1`) | various | all | optional; see [`CONFIG.md`](CONFIG.md) |
| file `webhook-signing-key` | text, at `/etc/shop/webhooks/signing.key` | `worker` | secret, at least 32 characters; required when `WEBHOOKS_ENABLED` is `true` |

## Run it

Install the SDK first: `mvn install -DskipTests` in the repository root. Then, in this directory:

```sh
mvn package
DOCUCONF_ROLE=web DATABASE_URL=postgres://shop@db:5432/shop ALLOWED_ORIGINS=https://shop.example java -jar target/shop.jar
DOCUCONF_ROLE=worker DATABASE_URL=postgres://shop@db:5432/shop QUEUES=default,mail java -jar target/shop.jar
```

```text
shop web: port 8080, origins https://shop.example
shop worker: queues default,mail, webhooks off
```

The app reads `contract.json` from the working directory; pass another path as its argument. File inputs are read
under `DOCUCONF_FILE_ROOT`, so a worker with webhooks reads its key from
`$DOCUCONF_FILE_ROOT/etc/shop/webhooks/signing.key` (`/etc/shop/webhooks/signing.key` when it is unset):

```sh
mkdir -p dev/etc/shop/webhooks && openssl rand -hex 32 > dev/etc/shop/webhooks/signing.key
DOCUCONF_ROLE=worker DATABASE_URL=postgres://shop@db:5432/shop QUEUES=default,mail WEBHOOKS_ENABLED=true \
  DOCUCONF_FILE_ROOT=./dev java -jar target/shop.jar
```

prints `shop worker: queues default,mail, webhooks on`.

## When the configuration is wrong

The app exits with status 1 and lists every problem, without printing a secret:

```text
$ DOCUCONF_ROLE=worker DATABASE_URL=postgres://shop@db:5432/shop QUEUES=default LOG_FORMAT=json java -jar target/shop.jar
docuconf: 1 configuration problem:
  [missing_required] LOG_SCHEMA_URL: is required when LOG_FORMAT is "json"
```

In Kubernetes the same lines go to `/dev/termination-log`, so `kubectl describe pod` shows them.

## The test matrix

[`ShopConfigTest`](src/test/java/dev/docuconf/examples/shop/ShopConfigTest.java) loads each environment against
`contract.json`, with `DATABASE_URL=postgres://shop@db:5432/shop` and a new, empty `DOCUCONF_FILE_ROOT`, and checks
the codes, the messages and the line the app prints. Lists are comma-separated (the contract's default `csv`
encoding).

| # | Environment (plus the base) | Expected |
|---|---|---|
| 1 | `DOCUCONF_ROLE=web ALLOWED_ORIGINS=https://shop.example` | ok: `shop web: port 8080, origins https://shop.example` |
| 2 | `DOCUCONF_ROLE=worker QUEUES=default,mail` | ok, a worker needs no `ALLOWED_ORIGINS`: `shop worker: queues default,mail, webhooks off` |
| 3 | `DOCUCONF_ROLE=web` | `ALLOWED_ORIGINS` `missing_required` |
| 4 | `QUEUES=default` (no role) | `DOCUCONF_ROLE` `missing_required`, and nothing else |
| 5 | `DOCUCONF_ROLE=cron` | `DOCUCONF_ROLE` `not_in_enum`, and nothing else |
| 6 | `DOCUCONF_ROLE=worker QUEUES=default LOG_FORMAT=json` | `LOG_SCHEMA_URL` `missing_required`: `is required when LOG_FORMAT is "json"` |
| 7 | as 6, plus `LOG_SCHEMA_URL=https://logs.example/v1.json` | ok |
| 8 | `DOCUCONF_ROLE=worker QUEUES=default WEBHOOKS_ENABLED=true`, no key file | `webhook-signing-key` `file_missing`: `..., and is required when WEBHOOKS_ENABLED is true` |
| 9 | as 8, with a key of 32 or more characters at the path under the root | ok: `webhooks on` |
| 10 | `DOCUCONF_ROLE=web ALLOWED_ORIGINS=https://shop.example WEBHOOKS_ENABLED=true` | ok: the key file belongs to the worker |
| 11 | `DOCUCONF_ROLE=web ALLOWED_ORIGINS=https://shop.example OTEL_EXPORTER_OTLP_PROTOCOL=carrier-pigeon` | `OTEL_EXPORTER_OTLP_PROTOCOL` `not_in_enum`: fragment variables are checked at startup |

[`smoke.sh`](smoke.sh) runs the built jar for cases 1, 2 and 6 and checks its output and exit status. `mvn verify`
runs the tests; CI runs both.

## The contract

Edit [`contract.cue`](contract.cue), then write `contract.json` from it (`cue export`, in a directory with the
meta-schema, as [`check-contract.sh`](check-contract.sh) sets one up). `check-contract.sh <docuconf-go>/spec/cue`
vets both files with `cue vet -c` against the meta-schema and fails when they are not the same contract; CI runs it.

## Generated docs

[`CONFIG.md`](CONFIG.md), [`CONFIG.agents.md`](CONFIG.agents.md) and [`docs.json`](docs.json) are generated from
`contract.cue` by the `docuconf` CLI from [docuconf-go](https://github.com/docuconf/docuconf-go); never edit them by
hand:

```sh
docuconf docs contract.cue -o CONFIG.md
docuconf docs contract.cue --format agents -o CONFIG.agents.md
docuconf docs contract.cue --format model -o docs.json
```

CI runs the same commands with `--check` and fails when a file is out of date.
