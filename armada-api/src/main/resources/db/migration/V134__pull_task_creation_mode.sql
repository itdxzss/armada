-- 新群模式：任务级区分「群链接模式」与「新群模式」。
--
-- 为什么不复用同表已有的 group_source：
--   group_source 由 V088 引入，注释是「群组来源:HISTORICAL历史群 SELF_COLLECTED自收群
--   MIXED混合;普通任务为空」，配套 PullTaskGroupSource 枚举，服务于拉群营销的任务列表
--   筛选。名字看着贴切，语义完全无关，复用会污染营销筛选。
--   新列取名 creation_mode 而不是 group_origin 之类，也是为了跟它拉开距离。
--
-- 为什么不新增 mode 取值：
--   字面量 'NORMAL_LINK' 硬编码在 26 个主代码文件里（调度器的执行行认领条件、全部
--   *TransactionService 的准入闸门、列表筛选与各读服务），它表达的是「本任务走新 PRD
--   普通拉群执行链路」而不是「群来自粘贴的链接」。新群模式正是走同一条链路，因此
--   mode 保持 NORMAL_LINK。若另起 mode 取值，调度器不认领执行行，任务建完一步不动
--   且不报错。详见 ADR-0010。
--
-- 默认值 PASTED_LINK 让存量任务语义正确：它们全部是群链接模式，
-- 默认成空会让「按模式筛选」漏掉全部历史任务。

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task'
       AND column_name = 'creation_mode') = 0,
    'ALTER TABLE pull_task ADD COLUMN creation_mode VARCHAR(32) NOT NULL DEFAULT ''PASTED_LINK'' COMMENT ''新建任务时选择的模式:PASTED_LINK群链接模式 NEW_GROUP新群模式;与group_source(拉群营销的历史群/自收群来源)无关'' AFTER mode',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
