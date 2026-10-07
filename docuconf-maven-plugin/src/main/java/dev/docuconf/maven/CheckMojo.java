package dev.docuconf.maven;

import dev.docuconf.contract.ContractJson;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Fails the build when the committed contract differs from the exported one, or when the exported one is older
 * than {@code application*.yml}. Bound to {@code verify}, so CI checks it with {@code mvn verify}.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class CheckMojo extends AbstractMojo {

    /** The class output, where the annotation processor wrote the contract. */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File classes;

    /** Where the committed contract lives. */
    @Parameter(property = "docuconf.contract", defaultValue = "${project.basedir}/contract.cue")
    private File contract;

    /** Skips the check. */
    @Parameter(property = "docuconf.check.skip", defaultValue = "false")
    private boolean skip;

    /** Creates the goal. */
    public CheckMojo() {
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            return;
        }
        Path exported = classes.toPath().resolve(ContractJson.CUE_LOCATION);
        if (!Files.isRegularFile(exported)) {
            throw new MojoFailureException("docuconf: no contract in " + exported.getParent() + "; is"
                    + " docuconf-processor in the compiler plugin's annotationProcessorPaths?");
        }
        try {
            List<String> stale = ContractFiles.staleness(classes.toPath());
            if (!stale.isEmpty()) {
                throw new MojoFailureException("docuconf: the exported contract is older than application*.yml ("
                        + String.join(", ", stale) + "); add the docuconf:refresh goal, or run mvn clean verify");
            }
            String difference = ContractFiles.difference(exported, contract.toPath());
            if (difference != null) {
                throw new MojoFailureException("docuconf: " + contract.getName() + " is out of date ("
                        + difference + "); run mvn docuconf:export and commit it");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("docuconf: cannot compare the contracts", e);
        }
        getLog().info("docuconf: " + contract.getName() + " matches the exported contract");
    }
}
