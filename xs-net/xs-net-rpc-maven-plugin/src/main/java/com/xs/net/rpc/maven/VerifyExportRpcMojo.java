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

@Mojo(name = "verify-export", threadSafe = true)
public final class VerifyExportRpcMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.build.directory}/generated-proto")
    private String generatedProtoDirectory;

    @Parameter(required = true)
    private String exportProtoDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            Path generatedDirectory = Path.of(generatedProtoDirectory);
            Path exportedDirectory = Path.of(exportProtoDirectory);
            List<Path> generatedFiles = protoFiles(generatedDirectory);
            List<Path> exportedFiles = protoFiles(exportedDirectory);
            if (generatedFiles.isEmpty()) {
                throw new MojoFailureException("Generated proto directory is empty; run xs-rpc:generate first: " + generatedDirectory);
            }
            List<Path> generatedRelativePaths = generatedFiles.stream()
                    .map(generatedDirectory::relativize)
                    .toList();
            List<Path> exportedRelativePaths = exportedFiles.stream()
                    .map(exportedDirectory::relativize)
                    .toList();
            if (!generatedRelativePaths.equals(exportedRelativePaths)) {
                throw new MojoFailureException("Exported proto file set differs from generated proto; run xs-rpc:export");
            }
            for (Path relativePath : generatedRelativePaths) {
                String expected = normalize(Files.readString(generatedDirectory.resolve(relativePath), StandardCharsets.UTF_8));
                String actual = normalize(Files.readString(exportedDirectory.resolve(relativePath), StandardCharsets.UTF_8));
                if (!expected.equals(actual)) {
                    throw new MojoFailureException("Exported proto differs from generated proto: " + relativePath
                            + "; run xs-rpc:export");
                }
            }
        } catch (MojoFailureException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MojoExecutionException("Failed to verify exported proto", exception);
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
