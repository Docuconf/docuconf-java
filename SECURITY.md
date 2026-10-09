# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately, through GitHub's private vulnerability reporting: open the repository's
**Security** tab and choose **Report a vulnerability**
([direct link](https://github.com/docuconf/docuconf-java/security/advisories/new)). Do not open a public issue, pull
request or discussion for a suspected vulnerability.

Include what you can of:

- the affected module and version (`dev.docuconf:docuconf-core`, `docuconf-processor`, `docuconf-spring`, the
  Maven plugin), and the Java and Spring Boot versions;
- what an attacker can do, and what they need first;
- steps, or a minimal configuration class, contract or environment that reproduces it.

We work on the fix in a private security advisory, credit you in it unless you prefer otherwise, and publish the
advisory when a fixed release is out.

## Response targets

| | |
|---|---|
| Acknowledge the report | within 3 business days |
| First assessment (confirmed or not, severity) | as soon as we can reproduce it, and we keep you updated in the advisory |
| Fix | released as a patch to the supported version, then the advisory is published |

## Supported versions

The modules are released together, under one `v*` tag (see [RELEASING.md](RELEASING.md)). Security fixes go to the
latest minor release, as a new patch release.

**During the beta, only the latest release is supported.** Upgrade to it to get a fix.

## Scope

In scope:

- `docuconf-core`: the annotations, the contract model and export, contract-first mode, and the startup checks of
  variables and file inputs (TLS key pairs, CA bundles, keystores), for example a secret value that reaches a log,
  an error message or the termination log;
- `docuconf-processor`, the annotation processor that exports the contract;
- `docuconf-spring`, the Spring Boot integration, including config-file overlays;
- `docuconf-maven-plugin` and the BOM.

Out of scope: the example applications under [`examples`](examples) and the `docuconf-sample` module,
vulnerabilities in dependencies that docuconf does not make reachable (report those upstream), and issues in a
platform or cluster that only arise from its own misconfiguration. The `docuconf` CLI, the CUE meta-schema and the
Go SDK live in [docuconf-go](https://github.com/docuconf/docuconf-go), which has its own policy.
