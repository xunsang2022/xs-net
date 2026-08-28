package com.xs.net.rpc.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Mojo(name = "export-unity-sdk", threadSafe = true)
public final class ExportUnitySdkMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.build.directory}/generated-proto", property = "sdk.generatedProtoDirectory")
    private String generatedProtoDirectory;

    @Parameter(defaultValue = "${project.build.directory}/unity-package/Runtime", property = "sdk.outputDirectory")
    private String outputDirectory;

    @Parameter(defaultValue = "${user.home}/.m2/repository/com/google/protobuf/protoc/3.25.5/protoc-3.25.5-windows-x86_64.exe", property = "sdk.protoc")
    private String protocExecutable;

    @Parameter(defaultValue = "Game.Message", property = "sdk.csharpNamespace")
    private String csharpNamespace;

    @Parameter(defaultValue = "com.xs.game.sdk", property = "sdk.packageName")
    private String packageName;

    @Parameter(defaultValue = "0.0.1", property = "sdk.packageVersion")
    private String packageVersion;

    @Parameter(defaultValue = "XS Net Game SDK", property = "sdk.packageDisplayName")
    private String packageDisplayName;

    @Parameter(defaultValue = "Generated C# SDK for Unity", property = "sdk.packageDescription")
    private String packageDescription;

    @Parameter(defaultValue = "${project.basedir}/src/main/resources/sdk-export.properties", property = "sdk.configFile")
    private String configFile;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            // resource 配置文件可覆盖导出地址（默认输出到仓库根目录，见 pom 配置）
            Path configPath = Path.of(configFile);
            if (Files.isRegularFile(configPath)) {
                java.util.Properties properties = new java.util.Properties();
                try (var reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                String configuredOutput = properties.getProperty("export.outputDirectory");
                if (configuredOutput != null && !configuredOutput.isBlank()) {
                    outputDirectory = configuredOutput;
                }
            }
            Path protoDirectory = Path.of(generatedProtoDirectory);
            // outputDirectory 视为包根目录，C# 源码统一放入其下 Runtime 子目录，package.json 写在包根
            Path packageRoot = Path.of(outputDirectory);
            Path runtimeDirectory = packageRoot.resolve("Runtime");
            List<Path> protoFiles = findProtoFiles(protoDirectory);
            if (protoFiles.isEmpty()) {
                throw new MojoExecutionException("No generated proto files found: " + protoDirectory);
            }
            Files.createDirectories(runtimeDirectory);
            clearCsharpFiles(runtimeDirectory);
            copyProtoFiles(protoDirectory, packageRoot);
            List<String> command = new java.util.ArrayList<>();
            command.add(protocExecutable);
            command.add("--proto_path=" + protoDirectory.toAbsolutePath());
            command.add("--csharp_out=" + runtimeDirectory.toAbsolutePath());
            command.add("--csharp_opt=base_namespace=" + csharpNamespace);
            protoFiles.forEach(path -> command.add(path.toAbsolutePath().toString()));
            Process process = new ProcessBuilder(command).inheritIO().start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new MojoExecutionException("protoc exited with code " + exitCode);
            }
            writePackageJson(packageRoot, packageName, packageVersion, packageDisplayName, packageDescription);
            getLog().info("Exported Unity C# SDK to " + packageRoot.toAbsolutePath());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while exporting Unity C# SDK", exception);
        } catch (IOException exception) {
            throw new MojoExecutionException("Failed to export Unity C# SDK", exception);
        }
    }

    private List<Path> findProtoFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var paths = Files.walk(directory)) {
            return paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".proto"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * 清理 Runtime 根目录下的生成 .cs 文件。
     * <p>只删除 Runtime 根目录（protoc 输出）的 .cs，保留子目录（如 Networking/ 手写网络层）以免误删手写代码。</p>
     */
    private void clearCsharpFiles(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(item -> Files.isRegularFile(item) && item.toString().endsWith(".cs"))
                    .toList()) {
                Files.delete(path);
            }
        }
    }

    /** 将生成目录下的 proto 文件复制到包根 proto/ 子目录，先清理旧文件避免残留 */
    private void copyProtoFiles(Path protoDirectory, Path packageRoot) throws IOException {
        Path target = packageRoot.resolve("proto");
        Files.createDirectories(target);
        if (Files.isDirectory(protoDirectory)) {
            try (var paths = Files.walk(protoDirectory)) {
                for (Path path : paths.filter(item -> Files.isRegularFile(item) && item.toString().endsWith(".proto"))
                        .toList()) {
                    Files.deleteIfExists(target.resolve(path.getFileName().toString()));
                    Files.copy(path, target.resolve(path.getFileName().toString()));
                }
            }
        }
    }

    private void writePackageJson(Path packageDirectory, String name, String version, String displayName,
                                  String description) throws IOException {
        Files.createDirectories(packageDirectory);
        String content = "{\n"
                + "  \"name\": \"" + name + "\",\n"
                + "  \"version\": \"" + version + "\",\n"
                + "  \"displayName\": \"" + displayName + "\",\n"
                + "  \"description\": \"" + description + "\",\n"
                + "  \"unity\": \"2021.3\",\n"
                + "  \"author\": {\n"
                + "    \"name\": \"XS Net\"\n"
                + "  }\n"
                + "}\n";
        Files.writeString(packageDirectory.resolve("package.json"), content, StandardCharsets.UTF_8);
    }
}
