package dev.docuconf.maven;

import java.io.File;
import java.io.IOException;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Makes {@code application*.yml} an input of the contract. The annotation processor bakes its values into the
 * contract, but the compiler plugin only recompiles when a Java source changes; after the resources are copied,
 * this goal compares them with the ones the contract was exported from and, when they differ, removes the compiled
 * {@code @Docuconf} classes so that {@code compile} exports the contract again.
 */
@Mojo(name = "refresh", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, threadSafe = true)
public class RefreshMojo extends AbstractMojo {

    /** The class output, where the contract and the copied resources are. */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File classes;

    /** Creates the goal. */
    public RefreshMojo() {
    }

    @Override
    public void execute() throws MojoExecutionException {
        try {
            List<String> changes = ContractFiles.refresh(classes.toPath());
            if (!changes.isEmpty()) {
                getLog().info("docuconf: " + String.join(", ", changes) + " since the contract was exported;"
                        + " recompiling the @Docuconf classes so it is exported again");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("docuconf: cannot compare the contract with application*.yml", e);
        }
    }
}
