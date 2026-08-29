-- H4 公共导出外壳复用既有营销导出作业表，只增加各业务类型的规范化请求快照。
ALTER TABLE marketing_task_export_job
    ADD COLUMN request_payload_json JSON DEFAULT NULL
        COMMENT '超链导出规范化筛选与排序快照;历史营销导出为空'
        AFTER country_iso2s_json;

ALTER TABLE marketing_task_export_job
    MODIFY COLUMN export_mode VARCHAR(32) NOT NULL
        COMMENT '导出类型:COUNTRY_ENTRY/FULL/RECIPIENTS/ACCOUNT_STATS/ATTRIBUTION/VISIT_TREND';
