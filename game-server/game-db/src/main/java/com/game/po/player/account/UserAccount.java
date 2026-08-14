package com.game.po.player.account;

import com.game.po.base.Po;
import jakarta.persistence.*;
import lombok.*;

import java.sql.Timestamp;

/**
 * 玩家账户实体类
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "user_accounts", indexes = {
        @Index(columnList = "user_id")
})
public class UserAccount extends Po {
    /**
     * 主键 ID（自增）
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 玩家 ID（外键关联 users 表）
     */
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;
    
    /**
     * 货币类型（0-金币，1-宝石）
     */
    @Column(name = "currency_type", nullable = false)
    private Integer currencyType;
    
    /**
     * 货币数量
     */
    @Column(name = "amount", nullable = false)
    private Long amount;
    
    /**
     * 最后更新时间
     */
    @Column(name = "update_time")
    private Timestamp updateTime;
}
