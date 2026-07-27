package com.armada.promotion.channel.controller;

import com.armada.promotion.channel.model.dto.PromotionChannelCreateDTO;
import com.armada.promotion.channel.model.dto.PromotionChannelQuery;
import com.armada.promotion.channel.model.dto.PromotionChannelProbeDTO;
import com.armada.promotion.channel.model.dto.PromotionChannelUpdateDTO;
import com.armada.promotion.channel.model.vo.FacebookStandardEventVO;
import com.armada.promotion.channel.model.vo.PromotionChannelDetailVO;
import com.armada.promotion.channel.model.vo.PromotionChannelProbeVO;
import com.armada.promotion.channel.model.vo.PromotionChannelVO;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 渠道管理新增、分页、详情、编辑和删除接口。 */
@RestController
@RequestMapping("/api/promotion-channels")
public class PromotionChannelController {

    private final PromotionChannelService service;

    public PromotionChannelController(PromotionChannelService service) {
        this.service = service;
    }

    /** 返回渠道表单允许配置的 Facebook 官方标准事件。 */
    @GetMapping("/facebook-standard-events")
    public ApiResponse<List<FacebookStandardEventVO>> facebookStandardEvents() {
        return ApiResponse.ok(service.facebookStandardEvents());
    }

    /**
     * 新增推广渠道。
     *
     * <p>Controller 只负责接收入参和包装统一响应；模板、国家、域名及 Token 等业务校验均由 Service 完成。</p>
     *
     * @param request 渠道新增参数，不包含 tenantId
     * @return 新增后的渠道详情，响应中不会包含 Access Token
     */
    @PostMapping("/create")
    public ApiResponse<PromotionChannelVO> create(@RequestBody PromotionChannelCreateDTO request) {
        return ApiResponse.ok(service.create(request));
    }

    /**
     * 分页查询推广渠道。
     *
     * @param query 页面筛选与分页参数
     * @return 统一分页结果
     */
    @GetMapping("query")
    public ApiResponse<PageResult<PromotionChannelVO>> page(@ModelAttribute PromotionChannelQuery query) {
        return ApiResponse.ok(service.page(query));
    }

    /**
     * 查询渠道编辑回显数据。
     *
     * @param id 渠道 ID
     * @return 当前租户有效渠道的可编辑字段；Access Token 只返回是否已配置
     * @throws com.armada.shared.exception.BusinessException 当渠道不存在或已删除时抛出
     */
    @GetMapping("/detail/{id}")
    public ApiResponse<PromotionChannelDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    /**
     * 使用 Meta Test Event Code 探测渠道的 Facebook CAPI 测试事件链路。
     *
     * <p>前端只提交渠道 ID 和测试事件码；Pixel ID 与 Token 均由后端读取，响应只包含脱敏结果。</p>
     *
     * @param id 渠道 ID
     * @param request Meta 测试事件参数；非 Facebook 或未配置渠道允许测试码为空并返回失败详情
     * @return 成功或可供失败弹窗展示的脱敏探测详情
     * @throws com.armada.shared.exception.BusinessException 当渠道不存在、测试码非法或已有探测执行时抛出
     */
    @PostMapping("/probe/{id}")
    public ApiResponse<PromotionChannelProbeVO> probe(
            @PathVariable Long id,
            @RequestBody(required = false) PromotionChannelProbeDTO request) {
        return ApiResponse.ok(service.probe(id, request));
    }

    /**
     * 编辑推广渠道。
     *
     * <p>渠道码和创建信息不会被修改；平台和追踪 ID 均未变且已有完整密文时，空 Token 表示保留原值。</p>
     *
     * @param id 渠道 ID
     * @param request 渠道完整编辑参数，不包含 tenantId
     * @return 统一空成功响应
     * @throws com.armada.shared.exception.BusinessException 当渠道不存在或参数不符合业务约束时抛出
     */
    @PutMapping("/update/{id}")
    public ApiResponse<Void> update(
            @PathVariable Long id,
            @RequestBody PromotionChannelUpdateDTO request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    /**
     * 软删除推广渠道。
     *
     * @param id 渠道 ID
     * @return 统一空成功响应
     * @throws com.armada.shared.exception.BusinessException 当渠道不存在或已删除时抛出
     */
    @DeleteMapping("/delete/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
