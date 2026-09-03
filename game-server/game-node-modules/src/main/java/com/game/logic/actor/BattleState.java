package com.game.logic.actor;

import com.game.message.battle.BattleInputCommand;

import java.util.LinkedHashMap;
import java.util.Map;

final class BattleState {

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
        participants.put(playerId, new ParticipantState());
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
                participant.directionY
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
                participant.directionY
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
            int directionY
    ) {
    }

    record BattleSnapshot(long frameNumber, Map<Long, ParticipantSnapshot> participants) {
    }

    private static final class ParticipantState {
        private long lastClientSequence;
        private int directionX;
        private int directionY;
        private int positionX;
        private int positionY;
    }
}
