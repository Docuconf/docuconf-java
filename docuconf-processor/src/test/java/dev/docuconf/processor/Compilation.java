package dev.docuconf.processor;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Compiles sources in memory with the docuconf processor and collects what it writes. */
final class Compilation {

    final boolean success;
    final List<String> errors;
    final List<String> warnings;
    final Path out;
    /** Every error and warning, with its source position. */
    final List<Diagnostic<? extends JavaFileObject>> diagnostics;

    private Compilation(boolean success, List<String> errors, List<String> warnings, Path out,
            List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        this.success = success;
        this.errors = errors;
        this.warnings = warnings;
        this.out = out;
        this.diagnostics = diagnostics;
    }

    String contractCue() throws IOException {
        Path p = out.resolve("META-INF/docuconf/contract.cue");
        return Files.exists(p) ? Files.readString(p) : null;
    }

    String contractJson() throws IOException {
        Path p = out.resolve("META-INF/docuconf/contract.json");
        return Files.exists(p) ? Files.readString(p) : null;
    }

    String allErrors() {
        return String.join("\n", errors);
    }

    /**
     * @param work a temporary directory
     * @param resources application*.yml files to place in the class output, by name
     * @param sources Java sources, by class name
     */
    static Compilation compile(Path work, Map<String, String> resources, Map<String, String> sources,
            String... options) throws IOException {
        Path out = Files.createDirectories(work.resolve("classes"));
        for (Map.Entry<String, String> r : resources.entrySet()) {
            Path f = out.resolve(r.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, r.getValue());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        List<JavaFileObject> units = new ArrayList<>();
        for (Map.Entry<String, String> s : sources.entrySet()) {
            units.add(new SimpleJavaFileObject(URI.create("string:///" + s.getKey().replace('.', '/') + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return s.getValue();
                }
            });
        }
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-classpath",
                System.getProperty("java.class.path"), "-proc:only", "-parameters"));
        args.addAll(List.of(options));
        StringWriter log = new StringWriter();
        JavaCompiler.CompilationTask task = compiler.getTask(log, fm, diagnostics, args, null, units);
        task.setProcessors(List.of(new DocuconfProcessor()));
        boolean ok = task.call();
        List<String> errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(Locale.ROOT)).collect(Collectors.toList());
        List<String> warnings = diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
                .map(d -> d.getMessage(Locale.ROOT)).collect(Collectors.toList());
        return new Compilation(ok, errors, warnings, out, List.copyOf(diagnostics.getDiagnostics()));
    }
}
