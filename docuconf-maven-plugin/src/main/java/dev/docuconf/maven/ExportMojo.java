package dev.docuconf.maven;

import dev.docuconf.contract.ContractJson;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Compiles, then copies the exported contract next to the pom, to commit it: {@code mvn docuconf:export}.
 */
@Mojo(name = "export", threadSafe = true)
@Execute(phase = LifecyclePhase.PROCESS_CLASSES)
public class ExportMojo extends AbstractMojo {

    /** The class output, where the annotation processor wrote the contract. */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File classes;

    /** Where the committed contract lives. */
    @Parameter(property = "docuconf.contract", defaultValue = "${project.basedir}/contract.cue")
    private File contract;

    /** Creates the goal. */
    public ExportMojo() {
    }

    @Override
    public void execute() throws MojoExecutionException {
        Path exported = classes.toPath().resolve(ContractJson.CUE_LOCATION);
        if (!Files.isRegularFile(exported)) {
            throw new MojoExecutionException("docuconf: no contract in " + exported.getParent() + "; is"
                    + " docuconf-processor in the compiler plugin's annotationProcessorPaths, and is there an"
                    + " @Docuconf class?");
        }
        try {
            Files.copy(exported, contract.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new MojoExecutionException("docuconf: cannot write " + contract, e);
        }
        getLog().info("docuconf: wrote " + contract);
    }
}
