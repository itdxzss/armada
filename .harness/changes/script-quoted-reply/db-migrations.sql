-- 正式共享环境由 Flyway 执行 V188，禁止另行手工 ALTER。
-- 仅限已明确选择的一次性本地验证库；从 armada 仓库根目录通过 mysql 客户端执行。
SOURCE armada-api/src/main/resources/db/migration/V188__script_quoted_reply.sql;
