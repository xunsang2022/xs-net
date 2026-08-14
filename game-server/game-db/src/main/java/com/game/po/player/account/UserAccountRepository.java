package com.game.po.player.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 玩家账户数据访问接口
 */
@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
}
