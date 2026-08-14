package com.game.message.rpc;

import com.game.message.actor.S2C;
import com.game.message.actor.XSSystem;
import com.game.message.actor.db.DbSyncCmd;
import com.game.message.actor.logic.PlayerMessage;
import com.game.protocol.pb.CS_LOGIN;
import com.game.protocol.pb.CS_LOG_REPORT;
import com.game.protocol.pb.CS_Logout;
import com.game.protocol.pb.DbSyncLoadMessage;
import com.game.protocol.pb.DbSyncLoadResultMessage;
import com.game.protocol.pb.DbSyncMessage;
import com.game.protocol.pb.LogRecordMessage;
import com.game.protocol.pb.PlayerEnter;
import com.game.protocol.pb.SC_LOGIN;
import com.game.protocol.pb.SC_LOG_REPORT;
import com.game.protocol.pb.SC_Logout;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import com.xs.net.message.RpcMessageRegistry;
import com.xs.net.message.RpcMessageSpec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GameRpcMessages implements RpcMessageRegistry {

    public static final GameRpcMessages INSTANCE = new GameRpcMessages();

    private final List<RpcMessageSpec<? extends MessageLite>> specs;
    private final Map<Integer, RpcMessageSpec<? extends MessageLite>> byRpcNum = new HashMap<>();
    private final Map<Class<?>, RpcMessageSpec<? extends MessageLite>> byClass = new HashMap<>();

    private GameRpcMessages() {
        specs = List.of(
                new RpcMessageSpec<>(999, LogRecordMessage.class,
                        LogRecordMessage.getDefaultInstance().getParserForType(), XSSystem.class),
                new RpcMessageSpec<>(1000, CS_LOGIN.class,
                        CS_LOGIN.getDefaultInstance().getParserForType(), PlayerMessage.class),
                new RpcMessageSpec<>(1001, CS_Logout.class,
                        CS_Logout.getDefaultInstance().getParserForType(), PlayerMessage.class),
                new RpcMessageSpec<>(1002, SC_LOGIN.class,
                        SC_LOGIN.getDefaultInstance().getParserForType(), null),
                new RpcMessageSpec<>(1003, SC_Logout.class,
                        SC_Logout.getDefaultInstance().getParserForType(), S2C.class),
                new RpcMessageSpec<>(2100, CS_LOG_REPORT.class,
                        CS_LOG_REPORT.getDefaultInstance().getParserForType(), XSSystem.class),
                new RpcMessageSpec<>(2101, SC_LOG_REPORT.class,
                        SC_LOG_REPORT.getDefaultInstance().getParserForType(), S2C.class),
                new RpcMessageSpec<>(3003, PlayerEnter.class,
                        PlayerEnter.getDefaultInstance().getParserForType(), PlayerMessage.class),
                new RpcMessageSpec<>(3100, DbSyncMessage.class,
                        DbSyncMessage.getDefaultInstance().getParserForType(), DbSyncCmd.class),
                new RpcMessageSpec<>(3101, DbSyncLoadMessage.class,
                        DbSyncLoadMessage.getDefaultInstance().getParserForType(), DbSyncCmd.class),
                new RpcMessageSpec<>(3102, DbSyncLoadResultMessage.class,
                        DbSyncLoadResultMessage.getDefaultInstance().getParserForType(), PlayerMessage.class)
        );
        for (RpcMessageSpec<? extends MessageLite> spec : specs) {
            byRpcNum.put(spec.rpcNum(), spec);
            byClass.put(spec.messageClass(), spec);
        }
    }

    @Override
    public RpcMessageSpec<? extends MessageLite> find(int rpcNum) {
        return byRpcNum.get(rpcNum);
    }

    @Override
    public RpcMessageSpec<? extends MessageLite> findByClass(Class<? extends MessageLite> messageClass) {
        return byClass.get(messageClass);
    }

    @Override
    public List<RpcMessageSpec<? extends MessageLite>> all() {
        return specs;
    }

    public static GameRpcEnvelope wrap(MessageLite payload) {
        for (RpcMessageSpec<? extends MessageLite> spec : INSTANCE.specs) {
            if (spec.messageClass().equals(payload.getClass())) {
                return new GameRpcEnvelope(spec.rpcNum(), payload);
            }
        }
        throw new IllegalArgumentException("Unknown protobuf message: " + payload.getClass().getName());
    }
}
