package com.xs.net.rpc.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Mojo(name = "export", threadSafe = true)
public final class ExportRpcMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.build.directory}/generated-proto")
    private String generatedProtoDirectory;

    @Parameter(required = true)
    private String exportProtoDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            Path sourceDirectory = Path.of(generatedProtoDirectory);
            List<Path> sourceFiles = protoFiles(sourceDirectory);
            if (sourceFiles.isEmpty()) {
                throw new MojoFailureException("Generated proto directory is empty; run xs-rpc:generate first: " + sourceDirectory);
            }
            Path targetDirectory = Path.of(exportProtoDirectory);
            Files.createDirectories(targetDirectory);
            for (Path target : protoFiles(targetDirectory)) {
                if (!Files.exists(sourceDirectory.resolve(targetDirectory.relativize(target)))) {
                    Files.delete(target);
                }
            }
            for (Path source : sourceFiles) {
                Path target = targetDirectory.resolve(sourceDirectory.relativize(source));
                Files.createDirectories(target.getParent());
                Files.writeString(target, normalize(Files.readString(source, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            }
        } catch (MojoFailureException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MojoExecutionException("Failed to export generated proto", exception);
        }
    }

    private List<Path> protoFiles(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var paths = Files.walk(directory)) {
            return paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".proto"))
                    .sorted()
                    .toList();
        }
    }

    private String normalize(String value) {
        return value.replace("\r\n", "\n");
    }
}
