package com.xs.net.rpc.maven;

import org.apache.maven.plugin.MojoFailureException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RpcArtifactGenerator {
    void generate(RpcModel model, Path protoDirectory, Path javaDirectory, String protoPackage,
                  String protoJavaPackage, String generatedJavaPackage) throws Exception {
        Map<String, List<MessageModel>> messagesByProtoFile = messagesByProtoFile(model);
        clearProtoDirectory(protoDirectory);
        for (Map.Entry<String, List<MessageModel>> entry : messagesByProtoFile.entrySet()) {
            write(protoDirectory.resolve(entry.getKey()), proto(entry.getValue(), protoPackage, protoJavaPackage));
        }
        // 路由表以 proto 枚举存放：消息号 <-> 消息名，供客户端/服务端引用协议号
        write(protoDirectory.resolve("rpc_routes.proto"), routingProto(model.messages(), protoPackage, protoJavaPackage));
        Path packageDirectory = javaDirectory.resolve(generatedJavaPackage.replace('.', '/'));
        write(packageDirectory.resolve("GameRpcMessages.java"), registry(model, generatedJavaPackage, protoJavaPackage));
        write(packageDirectory.resolve("GeneratedRpcCodec.java"), codec(model, generatedJavaPackage, protoJavaPackage));
        write(packageDirectory.resolve("GeneratedRpcHandlers.java"), handlers(model, generatedJavaPackage));
    }

    private String routingProto(List<MessageModel> messages, String protoPackage, String javaPackage) {
        StringBuilder value = new StringBuilder("syntax = \"proto3\";\n\npackage ").append(protoPackage).append(";\n\n")
                .append("option java_multiple_files = true;\noption java_package = \"").append(javaPackage).append("\";\n\n")
                .append("enum RpcRoute {\n")
                .append("  RPC_ROUTE_UNSPECIFIED = 0;\n");
        for (MessageModel message : messages) {
            value.append("  RPC_").append(snakeCase(message.name()).toUpperCase()).append(" = ")
                    .append(message.number()).append(";\n");
        }
        value.append("}\n");
        return value.toString();
    }

    private Map<String, List<MessageModel>> messagesByProtoFile(RpcModel model) throws MojoFailureException {
        Map<String, List<MessageModel>> messagesByProtoFile = new LinkedHashMap<>();
        for (MessageModel message : model.messages()) {
            String protoFile = protoFileName(message);
            messagesByProtoFile.computeIfAbsent(protoFile, ignored -> new ArrayList<>()).add(message);
        }
        messagesByProtoFile.computeIfAbsent("comment.proto", ignored -> new ArrayList<>());
        messagesByProtoFile.values().forEach(messages -> messages.sort(Comparator.comparingInt(MessageModel::number)));
        return messagesByProtoFile;
    }

    private String proto(List<MessageModel> messages, String protoPackage, String javaPackage) throws MojoFailureException {
        StringBuilder value = new StringBuilder("syntax = \"proto3\";\n\npackage ").append(protoPackage).append(";\n\n")
                .append("option java_multiple_files = true;\noption java_package = \"").append(javaPackage).append("\";\n");
        for (MessageModel message : messages) {
            // 在消息结构上注释对应的消息 ID，CS/SC 均标注
            value.append("\n// ").append(message.name()).append(" = ").append(message.number()).append("\n");
            value.append("message ").append(message.name()).append(" {\n");
            for (FieldModel field : message.fields()) {
                value.append("  optional ").append(RpcSourceScanner.protoType(field.javaType())).append(' ').append(snakeCase(field.name()))
                        .append(" = ").append(field.number()).append(";\n");
            }
            value.append("}\n");
        }
        return value.toString();
    }

    private String registry(RpcModel model, String generatedPackage, String protoJavaPackage) {
        StringBuilder value = new StringBuilder("package ").append(generatedPackage).append(";\n\n")
                .append("import com.google.protobuf.MessageLite;\n")
                .append("import com.xs.net.message.RpcMessageRegistry;\n")
                .append("import com.xs.net.message.RpcMessageRegistryBuilder;\n")
                .append("import com.xs.net.message.RpcMessageSpec;\n\n")
                .append("public final class GameRpcMessages {\n")
                .append("    public static final RpcMessageRegistry INSTANCE = registry();\n\n")
                .append("    private GameRpcMessages() {}\n\n")
                .append("    private static RpcMessageRegistry registry() {\n")
                .append("        RpcMessageRegistryBuilder builder = new RpcMessageRegistryBuilder();\n");
        for (MessageModel message : model.messages()) {
            value.append("        builder.register(new RpcMessageSpec<>(").append(message.number()).append(", ")
                    .append(protoJavaPackage).append('.').append(message.name()).append(".class, ")
                    .append(protoJavaPackage).append('.').append(message.name()).append(".getDefaultInstance().getParserForType(), ")
                    .append(message.dtoType()).append(".class, ")
                    .append(message.roleType() == null ? "null" : message.roleType() + ".class").append(", ")
                    .append("com.xs.net.message.RpcMessageDirection.").append(message.direction()).append(", ")
                    .append(message.publicVisible()).append(", ")
                    .append(message.direction() == Direction.REQUEST).append(", ")
                    .append(channelRoutingRequired(message)).append(", ")
                    .append("GeneratedRpcCodec.").append(codecName(message)).append("(), ")
                    .append(handler(message)).append("));\n");
        }
        return value.append("        return builder.build();\n    }\n}\n").toString();
    }

    private String codec(RpcModel model, String generatedPackage, String protoJavaPackage) {
        StringBuilder value = new StringBuilder("package ").append(generatedPackage).append(";\n\n")
                .append("import com.xs.net.message.RpcPayloadCodec;\n\n")
                .append("public final class GeneratedRpcCodec {\n")
                .append("    private GeneratedRpcCodec() {}\n\n");
        for (MessageModel message : model.messages()) {
            value.append("    public static RpcPayloadCodec<").append(message.dtoType()).append(", ")
                    .append(protoJavaPackage).append('.').append(message.name()).append("> ").append(codecName(message)).append("() {\n")
                    .append("        return new RpcPayloadCodec<>() {\n")
                    .append("            @Override\n            public ").append(protoJavaPackage).append('.').append(message.name())
                    .append(" encode(").append(message.dtoType()).append(" dto) {\n")
                    .append("                ").append(protoJavaPackage).append('.').append(message.name()).append(".Builder builder = ")
                    .append(protoJavaPackage).append('.').append(message.name()).append(".newBuilder();\n");
            for (FieldModel field : message.fields()) {
                String access = "dto." + field.name() + "()";
                String setter = "builder.set" + upper(field.name()) + "(" + encodeValue(field, access) + ");";
                if (isPrimitive(field.javaType())) {
                    value.append("                ").append(setter).append("\n");
                } else {
                    value.append("                if (").append(access).append(" != null) { ").append(setter).append(" }\n");
                }
            }
            value.append("                return builder.build();\n            }\n\n")
                    .append("            @Override\n            public ").append(message.dtoType()).append(" decode(")
                    .append(protoJavaPackage).append('.').append(message.name()).append(" payload) {\n                return new ")
                    .append(message.dtoType()).append("(");
            for (int index = 0; index < message.fields().size(); index++) {
                if (index > 0) {
                    value.append(", ");
                }
                FieldModel field = message.fields().get(index);
                String getter = "payload.get" + upper(field.name()) + "()";
                String decoded = decodeValue(field, getter);
                if (isPrimitive(field.javaType())) {
                    value.append(decoded);
                } else {
                    value.append("payload.has").append(upper(field.name())).append("() ? ").append(decoded).append(" : null");
                }
            }
            value.append(");\n            }\n        };\n    }\n\n");
        }
        return value.append("}\n").toString();
    }

    private String handlers(RpcModel model, String generatedPackage) {
        StringBuilder value = new StringBuilder("package ").append(generatedPackage).append(";\n\n")
                .append("import java.util.List;\n\npublic final class GeneratedRpcHandlers {\n    private GeneratedRpcHandlers() {}\n\n")
                .append("    public static List<HandlerMetadata> handlers() {\n        return List.of(\n");
        for (MessageModel message : model.messages()) {
            if (message.handlerMethod() != null) {
                value.append("                new HandlerMetadata(").append(message.number()).append(", \"")
                        .append(message.actorType()).append("\", \"").append(message.handlerMethod()).append("\", \"")
                        .append(message.dtoType()).append("\"),\n");
            }
        }
        if (value.toString().endsWith(",\n")) {
            value.setLength(value.length() - 2);
            value.append('\n');
        }
        return value.append("        );\n    }\n\n    public record HandlerMetadata(int rpcNumber, String actorType, String methodName, String dtoType) {}\n}\n").toString();
    }

    private String handler(MessageModel message) {
        if (message.handlerMethod() == null) {
            return "null";
        }
        return "new com.xs.net.message.RpcHandlerMetadata(\""
                + message.actorType() + "\", \"" + message.handlerMethod() + "\", "
                + message.dtoType() + ".class, "
                + (message.responseDtoType() == null ? "null" : message.responseDtoType() + ".class")
                + ")";
    }

    private boolean channelRoutingRequired(MessageModel message) {
        return message.direction() == Direction.REQUEST && message.responseDtoType() != null;
    }

    private String protoFileName(MessageModel message) {
        // 按角色组命名：实现了角色接口（如 PlayerMessage）归入 <Group>Messages.proto，未实现则归通用 comment.proto
        return message.roleType() == null ? "comment.proto" : simpleName(message.roleType()) + "s" + ".proto";
    }

    private String simpleName(String typeName) {
        int separator = typeName.lastIndexOf('.');
        return separator < 0 ? typeName : typeName.substring(separator + 1);
    }

    private String codecName(MessageModel message) {
        return "codec" + message.number();
    }

    private String encodeValue(FieldModel field, String access) {
        return "byte[]".equals(field.javaType()) ? "com.google.protobuf.ByteString.copyFrom(" + access + ")" : access;
    }

    private String decodeValue(FieldModel field, String getter) {
        return "byte[]".equals(field.javaType()) ? getter + ".toByteArray()" : getter;
    }

    private boolean isPrimitive(String javaType) {
        return switch (javaType) {
            case "int", "long", "boolean", "double", "float" -> true;
            default -> false;
        };
    }

    private String upper(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String snakeCase(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private void clearProtoDirectory(Path protoDirectory) throws Exception {
        if (!Files.isDirectory(protoDirectory)) {
            return;
        }
        try (var paths = Files.walk(protoDirectory)) {
            paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".proto"))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (Exception exception) {
                            throw new IllegalStateException("Failed to remove generated proto: " + path, exception);
                        }
                    });
        }
    }

    private void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
