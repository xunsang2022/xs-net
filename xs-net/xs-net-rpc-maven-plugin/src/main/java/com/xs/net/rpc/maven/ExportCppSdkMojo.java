package com.xs.net.rpc.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Mojo(name = "export-cpp-sdk", threadSafe = true)
public final class ExportCppSdkMojo extends AbstractMojo {

    @Parameter(required = true)
    private List<String> protoSourceDirectories;

    @Parameter(defaultValue = "${project.build.directory}/cpp-sdk")
    private String outputDirectory;

    @Parameter(defaultValue = "${user.home}/.m2/repository/com/google/protobuf/protoc/3.25.5/protoc-3.25.5-windows-x86_64.exe", property = "sdk.protoc")
    private String protocExecutable;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            Path root = Path.of(outputDirectory);
            Files.createDirectories(root);
            Path proto = root.resolve("proto");
            Path generated = root.resolve("generated");
            List<Path> protoFiles = SdkProtoExporter.copyProtoFiles(protoSourceDirectories, proto);
            if (protoFiles.isEmpty()) {
                throw new MojoExecutionException("No proto files found in configured source directories");
            }
            Files.createDirectories(generated);
            SdkProtoExporter.clearGeneratedDirectory(generated, ".h", ".cc");

            List<String> command = new ArrayList<>();
            command.add(protocExecutable);
            command.add("--proto_path=" + proto.toAbsolutePath());
            command.add("--cpp_out=" + generated.toAbsolutePath());
            protoFiles.stream().map(path -> path.toAbsolutePath().toString()).forEach(command::add);
            SdkProtoExporter.run(command, "C++ SDK generation");
            getLog().info("Exported C++ SDK to " + root.toAbsolutePath());
        } catch (MojoExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MojoExecutionException("Failed to export C++ SDK", exception);
        }
    }
}
