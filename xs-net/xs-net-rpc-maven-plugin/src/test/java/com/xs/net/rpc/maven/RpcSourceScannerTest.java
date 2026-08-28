package com.xs.net.rpc.maven;

import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.writeString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcSourceScannerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void scansRecordAnnotationsByDtoParameterTypeThenGeneratesGroupedArtifacts() throws Exception {
        Path source = sourceDirectory();
        writeString(source.resolve("sample/Contracts.java"), """
                package sample;
                @interface ProtoField { int number(); }
                @interface Rpc {
                    enum Kind { MESSAGE, INTERNAL, REQUEST }
                    Kind kind() default Kind.REQUEST;
                    int number() default 0;
                    int requestNumber() default 0;
                    int responseNumber() default 0;
                }
                class RpcActor<T> {}
                interface PlayerMessage {}
                interface INetMessage {}
                @Rpc(kind = Rpc.Kind.MESSAGE, number = 8)
                record HttpResult(@ProtoField(number = 1) String token) implements INetMessage {}
                @Rpc(kind = Rpc.Kind.REQUEST, requestNumber = 6, responseNumber = 7)
                record Request(@ProtoField(number = 1) String playerId, @ProtoField(number = 2) Integer level) implements PlayerMessage {}
                record Response(@ProtoField(number = 1) Long result) implements PlayerMessage {}
                @Rpc(kind = Rpc.Kind.INTERNAL, number = 9)
                record Enter(@ProtoField(number = 1) String playerId) implements PlayerMessage {}
                class PlayerActor extends RpcActor<PlayerMessage> { Response handle(Request request) { return null; } void enter(Enter enter) {} }
                """);

        RpcModel model = new RpcSourceScanner().scan(java.util.List.of(source));
        assertEquals(java.util.List.of(6, 7, 8, 9), model.messages().stream().map(MessageModel::number).toList());
        assertEquals("sample.PlayerMessage", model.messages().getFirst().roleType());
        assertEquals("sample.PlayerActor", model.messages().getFirst().actorType());

        Path target = temporaryDirectory.resolve("target");
        new RpcArtifactGenerator().generate(model, target.resolve("proto"), target.resolve("java"),
                "game.message", "com.game.protocol.pb", "com.game.message.rpc");

        String playerProto = java.nio.file.Files.readString(target.resolve("proto/PlayerMessages.proto"));
        String commentProto = java.nio.file.Files.readString(target.resolve("proto/comment.proto"));
        String routesProto = java.nio.file.Files.readString(target.resolve("proto/rpc_routes.proto"));
        String registry = java.nio.file.Files.readString(target.resolve("java/com/game/message/rpc/GameRpcMessages.java"));
        String codec = java.nio.file.Files.readString(target.resolve("java/com/game/message/rpc/GeneratedRpcCodec.java"));
        assertTrue(playerProto.contains("message Request"));
        assertTrue(playerProto.contains("message Response"));
        assertTrue(playerProto.contains("message Enter"));
        assertTrue(playerProto.contains("optional string player_id = 1;"));
        // 消息结构上注释消息 ID，CS/SC 均标注
        assertTrue(playerProto.contains("// Request = 6"));
        assertTrue(playerProto.contains("// Response = 7"));
        assertTrue(playerProto.contains("// Enter = 9"));
        assertTrue(commentProto.contains("// HttpResult = 8"));
        assertFalse(playerProto.contains("message HttpResult"));
        assertTrue(commentProto.contains("message HttpResult"));
        assertFalse(commentProto.contains("message Request"));
        assertTrue(routesProto.contains("RPC_REQUEST = 6"));
        assertTrue(routesProto.contains("RPC_RESPONSE = 7"));
        assertTrue(routesProto.contains("RPC_HTTP_RESULT = 8"));
        assertTrue(routesProto.contains("RPC_ENTER = 9"));
        assertTrue(registry.contains("RpcMessageRegistryBuilder"));
        assertTrue(registry.contains("new com.xs.net.message.RpcHandlerMetadata(\"sample.PlayerActor\", \"handle\", sample.Request.class, sample.Response.class)"));
        assertTrue(registry.contains("new com.xs.net.message.RpcHandlerMetadata(\"sample.PlayerActor\", \"enter\", sample.Enter.class, null)"));
        assertTrue(codec.contains("RpcPayloadCodec<sample.Request"));
        assertTrue(java.nio.file.Files.isRegularFile(target.resolve("java/com/game/message/rpc/GeneratedRpcHandlers.java")));
    }

    @Test
    void writesCommentProtoWhenThereAreNoCommonMessages() throws Exception {
        Path source = sourceDirectory();
        writeString(source.resolve("sample/Actor.java"), """
                package sample;
                @interface ProtoField { int number(); }
                @interface Rpc {
                    enum Kind { MESSAGE, INTERNAL, REQUEST }
                    Kind kind() default Kind.REQUEST;
                    int number() default 0;
                    int requestNumber() default 0;
                    int responseNumber() default 0;
                }
                class RpcActor<T> {}
                interface PlayerMessage {}
                @Rpc(kind = Rpc.Kind.REQUEST, requestNumber = 6, responseNumber = 7)
                record Request(@ProtoField(number = 1) String playerId) implements PlayerMessage {}
                record Response(@ProtoField(number = 1) String result) implements PlayerMessage {}
                class PlayerActor extends RpcActor<PlayerMessage> { Response handle(Request request) { return null; } }
                """);

        RpcModel model = new RpcSourceScanner().scan(java.util.List.of(source));
        Path target = temporaryDirectory.resolve("target");
        new RpcArtifactGenerator().generate(model, target.resolve("proto"), target.resolve("java"),
                "game.message", "com.game.protocol.pb", "com.game.message.rpc");

        assertTrue(java.nio.file.Files.isRegularFile(target.resolve("proto/comment.proto")));
        assertTrue(java.nio.file.Files.isRegularFile(target.resolve("proto/PlayerMessages.proto")));
        assertTrue(java.nio.file.Files.isRegularFile(target.resolve("proto/rpc_routes.proto")));
    }

    @Test
    void rejectsDuplicateRpcNumbers() throws Exception {
        Path source = sourceDirectory();
        writeString(source.resolve("sample/Duplicate.java"), """
                package sample;
                @interface ProtoField { int number(); }
                @interface Rpc {
                    enum Kind { MESSAGE, INTERNAL, REQUEST }
                    Kind kind() default Kind.MESSAGE;
                    int number() default 0;
                    int requestNumber() default 0;
                    int responseNumber() default 0;
                }
                @Rpc(kind = Rpc.Kind.MESSAGE, number = 1) record One(@ProtoField(number = 1) String value) {}
                @Rpc(kind = Rpc.Kind.MESSAGE, number = 1) record Two(@ProtoField(number = 1) String value) {}
                """);

        MojoFailureException failure = assertThrows(MojoFailureException.class,
                () -> new RpcSourceScanner().scan(java.util.List.of(source)));
        assertTrue(failure.getMessage().contains("Duplicate RPC number"));
    }

    @Test
    void rejectsMissingProtoFieldAnnotation() throws Exception {
        Path source = sourceDirectory();
        writeString(source.resolve("sample/Invalid.java"), """
                package sample;
                @interface Rpc {
                    enum Kind { MESSAGE, INTERNAL, REQUEST }
                    Kind kind() default Kind.MESSAGE;
                    int number() default 0;
                    int requestNumber() default 0;
                    int responseNumber() default 0;
                }
                @Rpc(kind = Rpc.Kind.MESSAGE, number = 1) record Invalid(String value) {}
                """);

        MojoFailureException failure = assertThrows(MojoFailureException.class,
                () -> new RpcSourceScanner().scan(java.util.List.of(source)));
        assertTrue(failure.getMessage().contains("requires @ProtoField"));
    }

    private Path sourceDirectory() throws Exception {
        Path source = temporaryDirectory.resolve("src");
        createDirectories(source.resolve("sample"));
        return source;
    }
}
