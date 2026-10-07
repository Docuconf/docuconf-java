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

## GitHub Packages and Releases

The `github` job in `.github/workflows/release.yml` runs on the same `v*` tags. It sets the version from the tag and
runs `mvn -Pgithub deploy` (which runs the tests, with `cue vet` and the conformance suite) against
`https://maven.pkg.github.com/Docuconf/docuconf-java`, then:

- GitHub Packages gets `dev.docuconf:docuconf-parent`, `docuconf-core`, `docuconf-processor` and `docuconf-spring`,
  each with sources and javadoc jars. They are not GPG-signed. `docuconf-sample` and `examples/orders` set
  `maven.deploy.skip` and are never deployed;
- the GitHub Release for the tag is created if it does not exist, and gets the core, processor and spring jars
  (with their sources, javadoc and test jars).

The `github` profile in `pom.xml` only adds the sources and javadoc jars; the target repository comes from
`-DaltDeploymentRepository=github::https://maven.pkg.github.com/Docuconf/docuconf-java`, and `actions/setup-java`
writes a `<server id="github">` with the workflow's `GITHUB_TOKEN`. The job does not depend on the Maven Central
`publish` job, so it works before the Central Portal namespace, token, GPG key and `maven-central` environment exist.
There are no secrets or accounts to set up (`packages: write`, `contents: write` only). The only requirement is that
the `Docuconf` organization lets `GITHUB_TOKEN` write packages, which it does unless package creation has been
restricted under Organization settings > Packages.

### Installing from GitHub Packages

GitHub's Maven registry requires a token even for public packages. Create a personal access token (classic) with the
`read:packages` scope and add it to `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github-docuconf</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

Then add the repository to your `pom.xml` (the `<id>` must match the server's):

```xml
<repositories>
  <repository>
    <id>github-docuconf</id>
    <url>https://maven.pkg.github.com/Docuconf/docuconf-java</url>
  </repository>
</repositories>
```

and depend on `dev.docuconf:docuconf-spring` (or `docuconf-core` / `docuconf-processor`) as usual. With Gradle:

```kotlin
repositories {
    maven("https://maven.pkg.github.com/Docuconf/docuconf-java") {
        credentials {
            username = providers.environmentVariable("GITHUB_ACTOR").orNull ?: "YOUR_GITHUB_USERNAME"
            password = providers.environmentVariable("GITHUB_TOKEN").get()
        }
    }
}
```

Without a token, download the jars from the GitHub Release and install them into your local repository, for example
`mvn install:install-file -Dfile=docuconf-core-0.1.0.jar -DgroupId=dev.docuconf -DartifactId=docuconf-core
-Dversion=0.1.0 -Dpackaging=jar`.
