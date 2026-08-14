package com.game.po.log;

import com.game.enums.LogLevel;
import com.game.enums.LogType;
import com.game.po.base.Po;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.sql.Timestamp;

/**
 * 日志实体类
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "log_table", indexes = {
        @Index(columnList = "log_level"),
        @Index(columnList = "player_id"),
        @Index(columnList = "create_time")
})
public class LogEntity extends Po {
    /**
     * 主键 ID（自增）
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 日志级别（枚举：HIGH/MEDIUM/LOW）
     */
    @Enumerated(EnumType.ORDINAL)
    @Column(name = "log_level", nullable = false)
    private LogLevel logLevel;
    
    /**
     * 日志类型（枚举）
     */
    @Enumerated(EnumType.ORDINAL)
    @Column(name = "log_type", nullable = false)
    private LogType logType;
    
    /**
     * 日志内容（JSON 格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", nullable = false, columnDefinition = "jsonb")
    private String content;
    
    /**
     * 关联玩家 ID（可选）
     */
    @Column(name = "player_id")
    private Long playerId;
    
    /**
     * 日志创建时间
     */
    @Column(name = "create_time")
    private Timestamp createTime;
}
