# RPC route table

| RPC | DTO | Direction | Target role | Public | Player route | Channel route | Response |
|---:|---|---|---|---:|---:|---:|---|
|999|com.game.message.c2s.LogRecordCommand|HTTP_RESPONSE||yes|yes|no||
|1000|com.game.message.c2s.LoginRequest|HTTP_RESPONSE||yes|yes|no||
|1001|com.game.message.c2s.LogoutRequest|REQUEST|com.game.message.actor.logic.PlayerMessage|yes|yes|yes|com.game.message.s2c.LogoutResponse|
|1002|com.game.message.s2c.LoginResponse|HTTP_RESPONSE|com.game.message.actor.S2C|yes|yes|no||
|1003|com.game.message.s2c.LogoutResponse|RESPONSE|com.game.message.actor.S2C|yes|yes|no||
|2100|com.game.message.c2s.LogReportRequest|HTTP_RESPONSE||yes|no|no||
|2101|com.game.message.s2c.LogReportResponse|HTTP_RESPONSE||yes|no|no||
|3003|com.game.message.c2s.PlayerEnterCommand|INTERNAL|com.game.message.actor.logic.PlayerMessage|no|yes|no||
|3004|com.game.message.management.GmKickPlayerRequest|INTERNAL|com.game.message.actor.logic.GMMessage|no|yes|no||
|3005|com.game.message.management.GmKickPlayerCommand|INTERNAL|com.game.message.actor.logic.PlayerMessage|no|no|no||
|3006|com.game.message.scene.SceneJoinCommand|INTERNAL|com.game.message.actor.logic.SceneMessage|no|yes|no||
|3007|com.game.message.scene.SceneLeaveCommand|INTERNAL|com.game.message.actor.logic.SceneMessage|no|yes|no||
|3008|com.game.message.scene.PlayerSceneChanged|INTERNAL|com.game.message.actor.logic.PlayerMessage|no|no|no||
|3010|com.game.message.battle.BattleCreateCommand|INTERNAL|com.game.message.actor.logic.BattleMessage|no|no|no||
|3011|com.game.message.battle.BattleJoinCommand|INTERNAL|com.game.message.actor.logic.BattleMessage|no|yes|no||
|3012|com.game.message.battle.BattleLeaveCommand|INTERNAL|com.game.message.actor.logic.BattleMessage|no|yes|no||
|3013|com.game.message.battle.BattleInputCommand|INTERNAL|com.game.message.actor.logic.BattleMessage|no|yes|no||
|3014|com.game.message.battle.BattleEndCommand|INTERNAL|com.game.message.actor.logic.BattleMessage|no|no|no||
|3015|com.game.message.battle.BattleAttackCommand|INTERNAL|com.game.message.actor.logic.BattleMessage|no|no|no||
