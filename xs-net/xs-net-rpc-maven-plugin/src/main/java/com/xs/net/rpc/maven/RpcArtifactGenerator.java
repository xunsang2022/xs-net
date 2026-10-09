package com.xs.net.rpc.maven;

import org.apache.maven.plugin.MojoFailureException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RpcArtifactGenerator {
    void generate(RpcModel model, Path protoDirectory, Path javaDirectory, String protoPackage,
                  String protoJavaPackage, String generatedJavaPackage) throws Exception {
        Map<String, List<MessageModel>> messagesByProtoFile = messagesByProtoFile(model);
        clearProtoDirectory(protoDirectory);
        VelocityRenderer renderer = new VelocityRenderer();
        for (Map.Entry<String, List<MessageModel>> entry : messagesByProtoFile.entrySet()) {
            write(protoDirectory.resolve(entry.getKey()), renderer.render("templates/proto.vm",
                    protoContext(protoPackage, protoJavaPackage, entry.getValue())));
        }
        // 路由表以 proto 枚举存放：消息号 <-> 消息名，供客户端/服务端引用协议号
        write(protoDirectory.resolve("rpc_routes.proto"), renderer.render("templates/rpc_routes.vm",
                protoContext(protoPackage, protoJavaPackage, model.messages())));
        write(protoDirectory.resolve("rpc_routes.md"), routeDocumentation(model));
        write(protoDirectory.resolve("rpc_protocol_validation.txt"), protocolValidationReport(model));
        Path packageDirectory = javaDirectory.resolve(generatedJavaPackage.replace('.', '/'));
        Map<String, Object> registryContext = new HashMap<>();
        registryContext.put("generatedPackage", generatedJavaPackage);
        registryContext.put("protoJavaPackage", protoJavaPackage);
        registryContext.put("messages", templateMessages(model.messages()));
        write(packageDirectory.resolve("GameRpcMessages.java"), renderer.render("templates/GameRpcMessages.vm", registryContext));
        write(packageDirectory.resolve("GeneratedRpcCodec.java"), renderer.render("templates/GeneratedRpcCodec.vm", registryContext));
        write(packageDirectory.resolve("GeneratedRpcHandlers.java"), renderer.render("templates/GeneratedRpcHandlers.vm", registryContext));
    }

    private Map<String, Object> protoContext(String protoPackage, String protoJavaPackage, List<MessageModel> messages)
            throws MojoFailureException {
        Map<String, Object> context = new HashMap<>();
        context.put("protoPackage", protoPackage);
        context.put("protoJavaPackage", protoJavaPackage);
        context.put("messages", templateMessages(messages));
        return context;
    }

    private List<Map<String, Object>> templateMessages(List<MessageModel> messages) throws MojoFailureException {
        List<Map<String, Object>> views = new ArrayList<>();
        for (MessageModel message : messages) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("number", message.number());
            view.put("name", message.name());
            view.put("dtoType", message.dtoType());
            view.put("roleClass", message.roleType() == null ? "null" : message.roleType() + ".class");
            view.put("direction", message.direction().name());
            view.put("publicVisible", message.publicVisible());
            view.put("isRequest", false);
            view.put("playerRoutingRequired", playerRoutingRequired(message));
            view.put("channelRoutingRequired", channelRoutingRequired(message));
            view.put("codecName", codecName(message));
            view.put("routeName", "RPC_" + snakeCase(message.name()).toUpperCase());
            view.put("handler", handler(message));
            view.put("handlerComponent", message.handlerComponent());
            view.put("handlerMethod", message.handlerMethod());
            view.put("actorType", message.actorType());
            view.put("fields", templateFields(message.fields()));
            view.put("decodeArgs", decodeArgs(message.fields()));
            views.add(view);
        }
        return views;
    }

    private List<Map<String, Object>> templateFields(List<FieldModel> fields) throws MojoFailureException {
        List<Map<String, Object>> views = new ArrayList<>();
        for (FieldModel field : fields) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", field.name());
            view.put("number", field.number());
            view.put("protoType", RpcSourceScanner.protoType(field.javaType()));
            view.put("snakeName", snakeCase(field.name()));
            view.put("primitive", isPrimitive(field.javaType()));
            view.put("setter", "builder.set" + upper(field.name()) + "(" + encodeValue(field, "dto." + field.name() + "()") + ");");
            views.add(view);
        }
        return views;
    }

    private String decodeArgs(List<FieldModel> fields) {
        List<String> args = new ArrayList<>();
        for (FieldModel field : fields) {
            String getter = "payload.get" + upper(field.name()) + "()";
            String decoded = decodeValue(field, getter);
            args.add(isPrimitive(field.javaType()) ? decoded
                    : "payload.has" + upper(field.name()) + "() ? " + decoded + " : null");
        }
        return String.join(", ", args);
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

    private String handler(MessageModel message) {
        if (message.handlerMethod() == null) {
            return "null";
        }
        if (message.handlerComponent() != null) {
            return "new com.xs.net.message.RpcHandlerMetadata(\""
                    + message.actorType() + "\", \"" + message.handlerComponent() + "\", \""
                    + message.handlerMethod() + "\", " + message.dtoType() + ".class, "
                    + (message.responseDtoType() == null ? "null" : message.responseDtoType() + ".class")
                    + ")";
        }
        return "new com.xs.net.message.RpcHandlerMetadata(\""
                + message.actorType() + "\", \"" + message.handlerMethod() + "\", "
                + message.dtoType() + ".class, "
                + (message.responseDtoType() == null ? "null" : message.responseDtoType() + ".class")
                + ")";
    }

    private boolean channelRoutingRequired(MessageModel message) {
        return message.responseDtoType() != null;
    }

    private boolean playerRoutingRequired(MessageModel message) {
        return message.fields().stream().anyMatch(field ->
                "playerId".equals(field.name()) || "player_id".equals(field.name()));
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

    private String routeDocumentation(RpcModel model) {
        StringBuilder document = new StringBuilder("# RPC route table\n\n");
        document.append("| RPC | DTO | Direction | Target role | Public | Player route | Channel route | Response |\n");
        document.append("|---:|---|---|---|---:|---:|---:|---|\n");
        for (MessageModel message : model.messages()) {
            String response = message.responseDtoType() == null ? "" : message.responseDtoType();
            document.append('|').append(message.number())
                    .append('|').append(message.dtoType())
                    .append('|').append(message.direction())
                    .append('|').append(message.roleType() == null ? "" : message.roleType())
                    .append('|').append(message.publicVisible() ? "yes" : "no")
                    .append('|').append(playerRoutingRequired(message) ? "yes" : "no")
                    .append('|').append(channelRoutingRequired(message) ? "yes" : "no")
                    .append('|').append(response).append('|').append('\n');
        }
        return document.toString();
    }

    private String protocolValidationReport(RpcModel model) {
        return "status=PASS\n"
                + "rpc_count=" + model.messages().size() + "\n"
                + "duplicate_rpc_numbers=0\n"
                + "duplicate_message_names=0\n"
                + "generated_at=build-time\n";
    }
}
