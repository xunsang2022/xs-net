package com.xs.net.rpc.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Mojo(name = "export-typescript-sdk", threadSafe = true)
public final class ExportTypescriptSdkMojo extends AbstractMojo {

    @Parameter(required = true)
    private List<String> protoSourceDirectories;

    @Parameter(defaultValue = "${project.build.directory}/typescript-sdk")
    private String outputDirectory;

    @Parameter(defaultValue = "${user.home}/.m2/repository/com/google/protobuf/protoc/3.25.5/protoc-3.25.5-windows-x86_64.exe", property = "sdk.protoc")
    private String protocExecutable;

    @Parameter(defaultValue = "protoc-gen-ts_proto", property = "sdk.tsProtoPlugin")
    private String tsProtoPluginExecutable;

    @Parameter(defaultValue = "snakeToCamel", property = "sdk.tsProtoOutputOptions")
    private String tsProtoOutputOptions;

    @Parameter(defaultValue = "@xs-net/game-sdk", property = "sdk.packageName")
    private String packageName;

    @Parameter(defaultValue = "0.0.1", property = "sdk.packageVersion")
    private String packageVersion;

    @Parameter(defaultValue = "Generated TypeScript SDK for XS Net", property = "sdk.packageDescription")
    private String packageDescription;

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
            SdkProtoExporter.clearGeneratedDirectory(generated, ".ts");

            List<String> command = new ArrayList<>();
            command.add(protocExecutable);
            command.add("--proto_path=" + proto.toAbsolutePath());
            command.add("--plugin=protoc-gen-ts_proto=" + tsProtoPluginExecutable);
            command.add("--ts_proto_out=" + generated.toAbsolutePath());
            command.add("--ts_proto_opt=" + tsProtoOutputOptions);
            protoFiles.stream().map(path -> path.toAbsolutePath().toString()).forEach(command::add);
            SdkProtoExporter.run(command, "TypeScript SDK generation");
            writeManifest(generated);
            getLog().info("Exported TypeScript SDK to " + root.toAbsolutePath());
        } catch (MojoExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MojoExecutionException("Failed to export TypeScript SDK", exception);
        }
    }

    private void writeManifest(Path generatedDirectory) throws Exception {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("name", SdkProtoExporter.json(packageName));
        context.put("version", SdkProtoExporter.json(packageVersion));
        context.put("description", SdkProtoExporter.json(packageDescription));
        String content = new VelocityRenderer().render("templates/ts-sdk-manifest.json.vm", context);
        Files.writeString(generatedDirectory.resolve("sdk-manifest.json"), content, java.nio.charset.StandardCharsets.UTF_8);
    }
}
