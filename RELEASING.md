# Releasing

docuconf-java publishes three artifacts to Maven Central: `dev.docuconf:docuconf-core`, `docuconf-processor`
and `docuconf-spring` (with `docuconf-parent`, their parent POM). `docuconf-sample` is never published.

Releases run from `.github/workflows/release.yml` when a `v*` tag is pushed. Nothing has been published yet.

## Before the first release

The project is MIT-licensed (`LICENSE`, and the `<licenses>` block in the parent `pom.xml` that Maven Central
requires).

1. **Namespace.** The groupId `dev.docuconf` requires proving ownership of `docuconf.dev` on the
   [Central Portal](https://central.sonatype.com): add the namespace there and publish the DNS TXT record it gives
   you. The domain is **not yet verified**. If it cannot be, use `io.github.docuconf` (verified through the GitHub
   organisation) and change the groupId in every `pom.xml` and the README.
2. **Portal token.** In the Central Portal, *View Account → Generate User Token*. This is the only credential
   Maven Central accepts; it has no OIDC trusted publishing, so the token is stored as a secret.
3. **Signing key.** Central requires every file to be signed with a public GPG key:
   ```sh
   gpg --quick-generate-key "docuconf releases <releases@docuconf.dev>" ed25519 sign 2y
   gpg --keyserver keys.openpgp.org --send-keys <KEYID>
   gpg --armor --export-secret-keys <KEYID>      # the value of MAVEN_GPG_PRIVATE_KEY
   ```
4. **GitHub environment.** Create an environment named `maven-central` (Settings → Environments), restrict it to
   `v*` tags and require a reviewer, and add these secrets to it:

   | Secret | Value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | Portal user token, username part |
   | `MAVEN_CENTRAL_PASSWORD` | Portal user token, password part |
   | `MAVEN_GPG_PRIVATE_KEY` | ASCII-armoured private signing key |
   | `MAVEN_GPG_PASSPHRASE` | Its passphrase |

## Each release

1. Make sure `main` is green in CI (Java 17 and 21, Spring Boot 3.5 and 4.1).
2. Tag and push: `git tag v0.1.0 && git push origin v0.1.0`. The workflow sets the version from the tag, runs the
   tests (with `cue vet`), builds sources and javadoc jars, signs everything, and uploads a deployment to the
   Central Portal.
3. The deployment is uploaded with `autoPublish=false`: open the Central Portal, check the deployment's files, and
   press **Publish**. Switch `autoPublish` to `true` in `pom.xml` once the process is trusted.
4. Bump the development version on `main` (`mvn versions:set -DnewVersion=0.2.0-SNAPSHOT`).

## Dry run locally

```sh
mvn -Prelease -DskipTests -Dgpg.skip package    # sources and javadoc jars build, nothing is signed or uploaded
```
