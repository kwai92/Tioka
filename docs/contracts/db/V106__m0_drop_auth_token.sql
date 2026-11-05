-- M0：令牌迁到 Redis 后删除旧表。随 FND-02 的 PR 一起移入 db/migration（M0-02）；ADR-029 第 33 条修订
DROP TABLE IF EXISTS auth_token;
