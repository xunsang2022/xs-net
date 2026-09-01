package com.xs.net.rpc.maven;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 基于 Velocity(.vm) 模板渲染生成内容的工具，供 RPC / SDK 生成器复用。
 * <p>模板位于插件 classpath 的 templates/ 目录，渲染结果统一使用 LF 换行。</p>
 */
final class VelocityRenderer {

    private final VelocityEngine engine;

    VelocityRenderer() {
        engine = new VelocityEngine();
        engine.setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath");
        engine.setProperty("resource.loader.classpath.class", ClasspathResourceLoader.class.getName());
        engine.setProperty(RuntimeConstants.ENCODING_DEFAULT, StandardCharsets.UTF_8.name());
        engine.setProperty(RuntimeConstants.INPUT_ENCODING, StandardCharsets.UTF_8.name());
        engine.init();
    }

    String render(String templatePath, Map<String, Object> context) {
        Template template = engine.getTemplate(templatePath, StandardCharsets.UTF_8.name());
        StringWriter writer = new StringWriter();
        template.merge(new VelocityContext(context), writer);
        // 统一 LF，避免模板文件换行符差异影响生成结果
        return writer.toString().replace("\r\n", "\n");
    }
}
