package com.game.logic.dbsync;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Session boundary for non-Spring-Boot MyBatis-Plus database operations. */
public final class PlayerPersistence {

    private final SqlSessionFactory sessionFactory;

    public PlayerPersistence(SqlSessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public Map<String, String> loadOrCreate(Long playerId) {
        try (SqlSession session = sessionFactory.openSession(false)) {
            try {
                PlayerPO player = session.getMapper(PlayerMapper.class)
                        .loadOrCreate(playerId, "player-" + playerId);
                session.commit();
                return player.toHash();
            } catch (RuntimeException exception) {
                session.rollback();
                throw exception;
            }
        }
    }

    public boolean upsert(PlayerPO player) {
        try (SqlSession session = sessionFactory.openSession(false)) {
            try {
                PlayerPO persisted = session.getMapper(PlayerMapper.class).upsert(player);
                if (persisted == null) {
                    session.rollback();
                    return false;
                }
                session.commit();
                player.copyPersistedState(persisted);
                return true;
            } catch (RuntimeException exception) {
                session.rollback();
                throw exception;
            }
        }
    }

    public Set<Long> upsertBatch(List<PlayerPO> players) {
        if (players.isEmpty()) {
            return Set.of();
        }
        try (SqlSession session = sessionFactory.openSession(false)) {
            try {
                List<PlayerPO> persisted = session.getMapper(PlayerMapper.class).upsertBatch(players);
                session.commit();
                Set<Long> persistedIds = new HashSet<>();
                for (PlayerPO player : persisted) {
                    persistedIds.add(player.getId());
                }
                return persistedIds;
            } catch (RuntimeException exception) {
                session.rollback();
                throw exception;
            }
        }
    }
}
