-- 一个租户内同一模板只能建立一条域名绑定；多个渠道通过 promotion_domain_id 复用该绑定。
ALTER TABLE promotion_domain
    ADD UNIQUE KEY uq_promotion_domain_tenant_template (tenant_id, landing_template_id);
