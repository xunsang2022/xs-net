package com.game.po.player.backpack;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BackpackItemRepository extends JpaRepository<BackpackItem, Long> {
    List<BackpackItem> findByPlayerId(Long playerId);
}
