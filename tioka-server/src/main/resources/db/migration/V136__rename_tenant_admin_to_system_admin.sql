-- 规约试点：tenant_admin → system_admin（ADR-029 第 60 条 2026-11-07 修订；47-GL）
-- 历史脚本 V100/V103/V110 仍写入旧码；本迁移在运行时改写。

UPDATE sys_role SET code = 'system_admin', name = '系统管理员'
 WHERE code = 'tenant_admin';

UPDATE sys_role_permission SET role_code = 'system_admin'
 WHERE role_code = 'tenant_admin';

UPDATE sys_user_role SET role_code = 'system_admin'
 WHERE role_code = 'tenant_admin';

-- 若仍有遗留成员行挂旧码（V110 前数据），一并改写
UPDATE sys_member SET role_code = 'system_admin'
 WHERE role_code = 'tenant_admin';
