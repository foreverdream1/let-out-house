-- =====================================================================
-- let-out-house  V7：message 增加 citations 列（RAG 引用来源）
--
-- 设计说明：
--   1. 存 end 事件里的 citations 数组原文，类型用 JSON 而不是 TEXT，
--      将来要按文档统计"被引用次数"可以直接用 JSON 函数或生成列。
--   2. 空数组存 '[]'，不存 NULL —— 区分"检索了但没命中"和"老数据/没检索"。
--   3. MyBatis 侧映射成 String 读写（无内置 JSON 类型处理器），
--      出参 DTO 用 JsonNode，Spring 序列化后就是 JSON 数组。
-- 执行账号：loh_migrate
-- 幂等：可重复执行
-- =====================================================================

USE `let_out_house`;

SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = 'let_out_house'
                 AND table_name = 'message'
                 AND column_name = 'citations');

SET @ddl := IF(@exist = 0,
    'ALTER TABLE `message` ADD COLUMN `citations` JSON NULL COMMENT ''RAG引用来源（end事件citations数组）'' AFTER `trace_id`',
    'SELECT ''citations 列已存在，跳过'' AS result');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
