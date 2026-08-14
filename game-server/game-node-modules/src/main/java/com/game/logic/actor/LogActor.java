package com.game.logic.actor;

import akka.actor.typed.Behavior;
import akka.actor.typed.PostStop;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.game.message.actor.XSSystem;
import com.xs.core.spring.SpringUtil;
import com.xs.core.akka.AkkaKit;
import com.game.enums.LogLevel;
import com.game.enums.LogType;
import com.game.message.action.CS_LOG_REPORT;
import com.game.message.action.SC_LOG_REPORT;
import com.game.message.actor.LogRecordMessage;
import com.game.message.actor.S2C;
import com.game.po.log.LogEntity;
import com.game.po.log.LogRepository;
import lombok.extern.slf4j.Slf4j;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 日志处理 Actor
 * 功能:
 * 1. 异步接收并处理日志记录请求
 * 2. 批量写入数据库，提高性能
 * 3. 支持按级别和类型分类存储
 * 4. JSON 格式存储灵活的业务数据
 */
@Slf4j
public class LogActor extends AbstractBehavior<XSSystem> {
    
    private final LogRepository dao = SpringUtil.getBean(LogRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 日志缓存列表
     */
    private final List<LogEntity> logCache = new ArrayList<>();
    
    /**
     * 批量保存阈值
     */
    private static final int BATCH_SIZE = 1;

    public LogActor(ActorContext<XSSystem> context) {
        super(context);
        init();
    }

    private void init() {
        log.info("LogActor initialized");
    }

    public static Behavior<XSSystem> create() {
        return Behaviors.setup(LogActor::new);
    }

    @Override
    public Receive<XSSystem> createReceive() {
        return newReceiveBuilder()
                .onMessage(CS_LOG_REPORT.class, this::handleClientLogReport)
                .onMessage(LogRecordMessage.class, this::handleLogRecord)
                .onSignal(PostStop.class, signal -> onPostStop())
                .build();
    }

    /**
     * 处理客户端日志上报
     */
    private Behavior<XSSystem> handleClientLogReport(CS_LOG_REPORT msg) {
        try {
            // 验证参数
            if (msg.getLogLevel() < 0 || msg.getLogLevel() >= LogLevel.values().length) {
                log.warn("Invalid log level: {}", msg.getLogLevel());
                AkkaKit.tell(S2C.class, new SC_LOG_REPORT(msg.getSessionId(), 1, null), msg.getSessionId());
                return this;
            }
            
            if (msg.getLogType() < 0 || msg.getLogType() >= LogType.values().length) {
                log.warn("Invalid log type: {}", msg.getLogType());
                AkkaKit.tell(S2C.class, new SC_LOG_REPORT(msg.getSessionId(), 1, null), msg.getSessionId());
                return this;
            }
            
            // 创建日志实体
            LogEntity logEntity = new LogEntity();
            logEntity.setLogLevel(LogLevel.valueOf(msg.getLogLevel()));
            logEntity.setLogType(LogType.valueOf(msg.getLogType()));
            logEntity.setContent(msg.getContent());
            logEntity.setCreateTime(new Timestamp(java.lang.System.currentTimeMillis()));
            
            // 添加到缓存
            logCache.add(logEntity);
            
            // 达到阈值才批量保存
            if (logCache.size() >= BATCH_SIZE) {
                flushLogs();
            }
            
            // 返回成功响应
            AkkaKit.tell(S2C.class, new SC_LOG_REPORT(msg.getSessionId(), 0, logEntity.getId()), msg.getSessionId());
            log.debug("Log received: level={}, type={}", logEntity.getLogLevel(), logEntity.getLogType());
            
        } catch (Exception e) {
            log.error("Error processing log report", e);
            AkkaKit.tell(S2C.class, new SC_LOG_REPORT(msg.getSessionId(), 1, null), msg.getSessionId());
        }
        
        return this;
    }

    /**
     * 处理内部日志记录消息
     */
    private Behavior<XSSystem> handleLogRecord(LogRecordMessage msg) {
        try {
            // 创建日志实体
            LogEntity logEntity = new LogEntity();
            logEntity.setLogLevel(LogLevel.values()[msg.getLevel()]);
            logEntity.setLogType(LogType.values()[msg.getType()]);
            logEntity.setContent(toJson(msg.getMessage()));
            logEntity.setPlayerId(msg.getPlayerId());
            logEntity.setCreateTime(new Timestamp(java.lang.System.currentTimeMillis()));
            
            // 添加到缓存
            logCache.add(logEntity);
            
            // 达到阈值才批量保存
            if (logCache.size() >= BATCH_SIZE) {
                flushLogs();
            }
            
            log.debug("Internal log recorded: level={}, type={}, playerId={}", 
                    msg.getLevel(), msg.getType(), msg.getPlayerId());
            
        } catch (Exception e) {
            log.error("Error processing internal log record", e);
        }
        
        return this;
    }

    /**
     * 公共方法：记录日志（供其他 Actor 调用）
     */
    public void log(LogLevel level, LogType type, String content, Long playerId) {
        LogEntity logEntity = new LogEntity();
        logEntity.setLogLevel(level);
        logEntity.setLogType(type);
        logEntity.setContent(toJson(content));
        logEntity.setPlayerId(playerId);
        logEntity.setCreateTime(new Timestamp(java.lang.System.currentTimeMillis()));
        
        logCache.add(logEntity);
        
        // 达到阈值才批量保存
        if (logCache.size() >= BATCH_SIZE) {
            flushLogs();
        }
    }

    /**
     * 批量保存日志到数据库
     */
    private void flushLogs() {
        if (logCache.isEmpty()) {
            return;
        }
        
        try {
            List<LogEntity> logsToSave = new ArrayList<>(logCache);
            dao.saveAll(logsToSave);
            logCache.clear();
            log.debug("Flushed {} logs to database", logsToSave.size());
        } catch (Exception e) {
            log.error("Error flushing logs to database", e);
            // 保存失败时不清空缓存，等待下次重试
        }
    }

    /**
     * Actor 停止前保存所有剩余日志
     */
    private Behavior<XSSystem> onPostStop() {
        log.info("LogActor stopping, flushing remaining logs: count={}", logCache.size());
        flushLogs();
        return Behaviors.same();
    }

    /**
     * 将字符串转换为 JSON 格式
     */
    private String toJson(String message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("Failed to convert message to JSON", e);
            return "\"" + message.replace("\"", "\\\"") + "\"";
        }
    }
}
