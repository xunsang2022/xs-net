package com.game.battle.actor;

import com.game.message.battle.BattleAttackCommand;
import com.game.message.battle.BattleInputCommand;

import java.util.LinkedHashMap;
import java.util.Map;

final class BattleState {

    /** 新加入玩家的基础属性，后续可由角色或装备系统覆盖。 */
    static final int DEFAULT_ATTACK = 20;
    static final int DEFAULT_DEFENSE = 5;
    static final int DEFAULT_MAX_HP = 100;

    private final String battleId;
    private final int maxPlayers;
    private final Map<Long, ParticipantState> participants = new LinkedHashMap<>();
    private boolean created;
    private boolean ended;
    private long frameNumber;
    private BattleSnapshot lastSnapshot = new BattleSnapshot(0L, Map.of());

    BattleState(String battleId, int maxPlayers) {
        this.battleId = battleId;
        this.maxPlayers = maxPlayers;
    }

    boolean create() {
        if (created || ended) {
            return false;
        }
        created = true;
        return true;
    }

    String join(Long playerId) {
        if (ended || !created) {
            return "battle_ended";
        }
        if (playerId == null) {
            return "unknown_player";
        }
        if (participants.containsKey(playerId)) {
            return null;
        }
        if (participants.size() >= maxPlayers) {
            return "capacity_exceeded";
        }
        participants.put(playerId, new ParticipantState(DEFAULT_ATTACK, DEFAULT_DEFENSE, DEFAULT_MAX_HP));
        return null;
    }

    boolean leave(Long playerId) {
        return playerId != null && participants.remove(playerId) != null;
    }

    String acceptInput(BattleInputCommand command) {
        if (ended || !created) {
            return "battle_ended";
        }
        ParticipantState participant = command.playerId() == null ? null : participants.get(command.playerId());
        if (participant == null) {
            return "unknown_player";
        }
        if (!validDirection(command.directionX()) || !validDirection(command.directionY())) {
            return "invalid_direction";
        }
        if (command.clientSequence() == null || command.clientSequence() <= participant.lastClientSequence) {
            return "stale_sequence";
        }
        participant.lastClientSequence = command.clientSequence();
        participant.directionX = command.directionX();
        participant.directionY = command.directionY();
        return null;
    }

    /**
     * 结算一次基础攻击。
     *
     * <p>攻击和受击都在同一 BattleRuntime lane 内执行，因此无需额外锁保护状态。</p>
     */
    String attack(BattleAttackCommand command) {
        if (ended || !created) {
            return "battle_ended";
        }
        ParticipantState attacker = command.attackerPlayerId() == null
                ? null : participants.get(command.attackerPlayerId());
        ParticipantState target = command.targetPlayerId() == null
                ? null : participants.get(command.targetPlayerId());
        if (attacker == null) {
            return "unknown_attacker";
        }
        if (target == null) {
            return "unknown_target";
        }
        if (attacker.hp <= 0) {
            return "attacker_defeated";
        }
        if (target.hp <= 0) {
            return "target_defeated";
        }
        if (command.clientSequence() == null || command.clientSequence() <= attacker.lastAttackSequence) {
            return "stale_sequence";
        }
        attacker.lastAttackSequence = command.clientSequence();
        int damage = Math.max(1, attacker.attack - target.defense);
        target.hp = Math.max(0, target.hp - damage);
        return null;
    }

    void tick() {
        if (!created || ended) {
            return;
        }
        frameNumber++;
        for (ParticipantState participant : participants.values()) {
            participant.positionX += participant.directionX;
            participant.positionY += participant.directionY;
            participant.directionX = 0;
            participant.directionY = 0;
        }
        Map<Long, ParticipantSnapshot> snapshot = new LinkedHashMap<>();
        participants.forEach((playerId, participant) -> snapshot.put(playerId, new ParticipantSnapshot(
                playerId,
                participant.lastClientSequence,
                participant.positionX,
                participant.positionY,
                participant.directionX,
                participant.directionY,
                participant.attack,
                participant.defense,
                participant.hp,
                participant.maxHp
        )));
        lastSnapshot = new BattleSnapshot(frameNumber, Map.copyOf(snapshot));
    }

    boolean end() {
        if (ended) {
            return false;
        }
        ended = true;
        return true;
    }

    boolean isEmpty() {
        return participants.isEmpty();
    }

    long frameNumber() {
        return frameNumber;
    }

    ParticipantSnapshot participant(Long playerId) {
        ParticipantState participant = participants.get(playerId);
        return participant == null ? null : new ParticipantSnapshot(
                playerId,
                participant.lastClientSequence,
                participant.positionX,
                participant.positionY,
                participant.directionX,
                participant.directionY,
                participant.attack,
                participant.defense,
                participant.hp,
                participant.maxHp
        );
    }

    int size() {
        return participants.size();
    }

    String battleId() {
        return battleId;
    }

    BattleSnapshot lastSnapshot() {
        return lastSnapshot;
    }

    private static boolean validDirection(Integer value) {
        return value != null && value >= -1 && value <= 1;
    }

    record ParticipantSnapshot(
            Long playerId,
            long lastClientSequence,
            int positionX,
            int positionY,
            int directionX,
            int directionY,
            int attack,
            int defense,
            int hp,
            int maxHp
    ) {
    }

    record BattleSnapshot(long frameNumber, Map<Long, ParticipantSnapshot> participants) {
    }

    private static final class ParticipantState {
        private long lastClientSequence;
        private long lastAttackSequence;
        private int directionX;
        private int directionY;
        private int positionX;
        private int positionY;
        private final int attack;
        private final int defense;
        private final int maxHp;
        private int hp;

        private ParticipantState(int attack, int defense, int maxHp) {
            this.attack = attack;
            this.defense = defense;
            this.maxHp = maxHp;
            this.hp = maxHp;
        }
    }
}
