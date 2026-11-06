-- M1：成本事件由 model_usage（V102）取代。随 MH-05 的 PR 一起移入 db/migration（M1-03）；ADR-029 第 33 条修订
DROP TABLE IF EXISTS cost_event;
