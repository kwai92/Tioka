-- M1：配额表由 gov_quota（V102）取代。随 GOV-03 的 PR 一起移入 db/migration（M1-03）；ADR-029 第 33 条修订
DROP TABLE IF EXISTS sys_quota;
