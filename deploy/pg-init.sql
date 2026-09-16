-- 初始化脚本：由 docker-entrypoint 自动执行（当 POSTGRES_DB=gamedb 为空库时）
-- 与 logic 模块 PlayerUpsertSql / PlayerState 的表结构一致，确保首次 DBSyncEngine 写入即有表

CREATE TABLE IF NOT EXISTS users (
    id             BIGINT PRIMARY KEY,
    name           VARCHAR(64)  NOT NULL DEFAULT '',
    email          VARCHAR(128) NOT NULL DEFAULT '',
    online         INT          NOT NULL DEFAULT 0,
    last_login_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version        INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS log_events (
    id          BIGSERIAL PRIMARY KEY,
    source_stream_id VARCHAR(64) NOT NULL,
    player_id   BIGINT,
    type        VARCHAR(32)  NOT NULL,
    level       VARCHAR(16)  NOT NULL,
    message     TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

ALTER TABLE log_events ADD COLUMN IF NOT EXISTS source_stream_id VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS uk_log_events_source_stream_id ON log_events (source_stream_id);

CREATE INDEX IF NOT EXISTS idx_log_events_player_created ON log_events (player_id, created_at DESC);

-- 允许 root 无密码连接（dev 环境）
ALTER USER root WITH PASSWORD '';
