# 时区：UTC（M0-P2）

JVM 与 PostgreSQL 会话时区固定为 **UTC**（ADR-029；M0-01 ENG-11）。

- 配置：`application.yml` / `application-local.yml` 的 JDBC URL 含 `options=-c%20TimeZone%3DUTC`
- 进程：`JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`（CI、Docker、本地建议导出同名环境变量）
- 验收：`TimeZoneIT`（`SHOW timezone` = UTC 且 `TimeZone.getDefault()` = UTC）

本地示例：

```bash
export JAVA_TOOL_OPTIONS=-Duser.timezone=UTC
mvn -pl tioka-server -am -Dtest=TimeZoneIT -Dit.test=TimeZoneIT verify
```
