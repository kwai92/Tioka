-- M1：会话级冻结由 model_turn_freeze（V102）取代。随 MH-01 的 PR 一起移入 db/migration（M1-03）；ADR-029 第 33 条修订
DROP TABLE IF EXISTS model_session_freeze;
