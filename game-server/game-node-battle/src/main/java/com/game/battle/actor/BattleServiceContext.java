package com.game.battle.actor;

/** Static wiring point for the standalone battle node. */
public final class BattleServiceContext {
    private static volatile BattleCommandIngress battleCommandIngress;

    private BattleServiceContext() {
    }

    public static void setBattleCommandIngress(BattleCommandIngress ingress) {
        battleCommandIngress = ingress;
    }

    public static BattleCommandIngress getBattleCommandIngress() {
        BattleCommandIngress ingress = battleCommandIngress;
        if (ingress == null) {
            throw new IllegalStateException("Battle command ingress has not been initialized");
        }
        return ingress;
    }

    public static void clear() {
        battleCommandIngress = null;
    }
}
