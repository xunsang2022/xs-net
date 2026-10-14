package com.xs.net.rpc.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public final class GenerateRpcMojo extends AbstractMojo {
    @Parameter(required = true)
    private List<String> sourceDirectories;

    @Parameter(defaultValue = "${project.build.directory}/generated-proto")
    private String protoOutputDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/rpc")
    private String javaOutputDirectory;

    @Parameter(defaultValue = "game.message")
    private String protoPackage;

    @Parameter(defaultValue = "com.game.protocol.pb")
    private String protoJavaPackage;

    @Parameter(defaultValue = "com.game.message.rpc")
    private String generatedJavaPackage;

    /** 仅生成 @ProtoRecord 声明的源类型到协议记录类型的转换工具。 */
    @Parameter(defaultValue = "false")
    private boolean convertersOnly;

    @Parameter(defaultValue = "com.game.message.convert")
    private String converterJavaPackage;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/rpc-converters")
    private String converterOutputDirectory;

    /** 仅生成 Actor handler 的「rpcNum → 方法名」映射类，供业务模块在自身模块内生成。 */
    @Parameter(defaultValue = "false")
    private boolean handlersOnly;

    @Parameter(defaultValue = "com.game.message.rpc")
    private String handlerJavaPackage;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/rpc-handlers")
    private String handlerOutputDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (sourceDirectories == null || sourceDirectories.isEmpty()) {
            throw new MojoFailureException("Configure at least one RPC source directory");
        }
        try {
            List<Path> sourcePaths = sourceDirectories.stream().map(Path::of).toList();
            RpcArtifactGenerator generator = new RpcArtifactGenerator();
            if (handlersOnly) {
                Map<Integer, String> methodNames = new RpcSourceScanner().scanHandlersOnly(sourcePaths);
                generator.generateHandlers(methodNames, Path.of(handlerOutputDirectory), handlerJavaPackage);
                project.addCompileSourceRoot(handlerOutputDirectory);
                getLog().info("Generated " + methodNames.size() + " rpc handler mappings");
                return;
            }
            if (convertersOnly) {
                List<ConverterModel> converters = new RpcSourceScanner().scanConverters(sourcePaths);
                generator.generateConverters(converters, Path.of(converterOutputDirectory), converterJavaPackage);
                project.addCompileSourceRoot(converterOutputDirectory);
                getLog().info("Generated " + converters.size() + " proto converters");
                return;
            }
            RpcModel model = new RpcSourceScanner().scan(sourcePaths);
            generator.generate(model, Path.of(protoOutputDirectory), Path.of(javaOutputDirectory),
                    protoPackage, protoJavaPackage, generatedJavaPackage);
            project.addCompileSourceRoot(javaOutputDirectory);
            getLog().info("Generated " + model.messages().size() + " RPC message definitions");
        } catch (MojoFailureException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MojoExecutionException("Failed to generate RPC artifacts", exception);
        }
    }
}
