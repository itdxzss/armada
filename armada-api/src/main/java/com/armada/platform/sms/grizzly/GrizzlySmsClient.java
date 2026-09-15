package com.armada.platform.sms.grizzly;

import com.armada.platform.sms.grizzly.exception.GrizzlySmsException;
import com.armada.platform.sms.grizzly.exception.GrizzlySmsFailure;
import com.armada.platform.sms.grizzly.model.GrizzlyActivation;
import com.armada.platform.sms.grizzly.model.GrizzlyCountry;
import com.armada.platform.sms.grizzly.model.GrizzlyNumberRequest;
import com.armada.platform.sms.grizzly.model.GrizzlyPrice;
import com.armada.platform.sms.grizzly.model.GrizzlyPriceTier;
import com.armada.platform.sms.grizzly.model.GrizzlyService;
import com.armada.platform.sms.grizzly.model.GrizzlySmsStatus;
import com.armada.platform.sms.grizzly.model.GrizzlyStatusUpdate;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Grizzly SMS 官方接口客户端，供服务端注册编排调用，不自行购买、轮询或重试。
 * 写请求必须先在调用方记录业务意图；结果不明时不能直接再次购买。
 */
public final class GrizzlySmsClient {

    /** 供应商统一 API 路径，所有操作使用 action 查询参数。 */
    private static final String HANDLER_PATH = "/stubs/handler_api.php";
    /** 防止异常供应商响应耗尽应用内存。 */
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    /** 服务代码允许目录中的字母数字、下划线和短横线。 */
    private static final Pattern SERVICE_CODE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** 国家 ID 可为零，与电话区号无关。 */
    private static final Pattern COUNTRY_ID = Pattern.compile("[0-9]{1,10}");
    /** 订单及供应商 ID 不允许 URL 控制字符。 */
    private static final Pattern POSITIVE_ID = Pattern.compile("[1-9][0-9]{0,31}");
    /** 排除号码前缀使用官方文档规定的 3 至 6 位数字。 */
    private static final Pattern PHONE_PREFIX = Pattern.compile("[0-9]{3,6}");
    /** 单次筛选列表上限，限制传入请求的 URL 长度。 */
    private static final int MAX_FILTER_VALUES = 100;
    /** 单价最多 30 位有效数字，避免无界金额编码。 */
    private static final int MAX_PRICE_PRECISION = 30;
    /** 单价最多 12 位小数或指数位，避免构造超长 URL。 */
    private static final int MAX_PRICE_SCALE = 12;
    /** 仅识别已核对的固定错误文本，未知响应不直接透传。 */
    private static final Map<String, GrizzlySmsFailure> PROVIDER_REJECTIONS = Map.ofEntries(
            Map.entry("BAD_KEY", GrizzlySmsFailure.BAD_KEY),
            Map.entry("NO_KEY", GrizzlySmsFailure.NO_KEY),
            Map.entry("NO_BALANCE", GrizzlySmsFailure.NO_BALANCE),
            Map.entry("NO_NUMBERS", GrizzlySmsFailure.NO_NUMBERS),
            Map.entry("BAD_ACTION", GrizzlySmsFailure.BAD_ACTION),
            Map.entry("BAD_SERVICE", GrizzlySmsFailure.BAD_SERVICE),
            Map.entry("BAD_STATUS", GrizzlySmsFailure.BAD_STATUS),
            Map.entry("NO_ACTIVATION", GrizzlySmsFailure.NO_ACTIVATION),
            Map.entry("SERVICE_UNAVAILABLE_REGION", GrizzlySmsFailure.REGION_RESTRICTED),
            Map.entry("The service is prohibited for sale by administration", GrizzlySmsFailure.SERVICE_PROHIBITED),
            Map.entry("ERROR_SQL", GrizzlySmsFailure.PROVIDER_ERROR));

    /** 专用、禁止重试和重定向的 HTTP 客户端。 */
    private final RestClient restClient;
    /** 平台配置，在每次请求前检查启用状态。 */
    private final GrizzlySmsProperties properties;
    /** 将文档中的纯文本和 JSON 协议转换为业务模型。 */
    private final GrizzlySmsResponseParser parser;

    /**
     * 创建只在显式调用时访问供应商的客户端。
     * @param restClient 专用 HTTP 客户端，装配层必须禁止自动重试和重定向
     * @param objectMapper 项目 JSON 解析器
     * @param properties 服务端接码配置，不可由终端用户覆盖密钥
     */
    public GrizzlySmsClient(RestClient restClient, ObjectMapper objectMapper, GrizzlySmsProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
        this.parser = new GrizzlySmsResponseParser(objectMapper);
    }

    /**
     * 查询平台账户余额，不产生购买。
     * @return 原始精度余额，接口未给出币种，不默认美元
     * @throws GrizzlySmsException 当配置禁用或供应商响应异常时抛出
     */
    public BigDecimal getBalance() {
        return parser.balance(request(Action.BALANCE, Map.of()));
    }

    /**
     * 读取服务代码目录，调用方应使用目录代码购买对应服务号码。
     * @return 不可变服务目录
     * @throws GrizzlySmsException 当配置禁用或供应商响应异常时抛出
     */
    public List<GrizzlyService> getServices() {
        return parser.services(request(Action.SERVICES, Map.of()));
    }

    /**
     * 读取国家目录，ID 不等同于国际电话区号。
     * @return 不可变国家目录
     * @throws GrizzlySmsException 当配置禁用或供应商响应异常时抛出
     */
    public List<GrizzlyCountry> getCountries() {
        return parser.countries(request(Action.COUNTRIES, Map.of()));
    }

    /**
     * 查询单个服务、国家的报价快照，避免拉取不必要的全量价格数据。
     * @param service 服务目录代码
     * @param country 国家目录 ID，查询报价时必须指定国家
     * @return 平台返回的价格和库存，空集合表示没有报价
     * @throws BusinessException 参数非法时抛出，供应商错误以子类 GrizzlySmsException 表达
     */
    public List<GrizzlyPrice> getPrices(String service, String country) {
        requireMatch(service, SERVICE_CODE, "接码服务代码无效");
        requireMatch(country, COUNTRY_ID, "接码国家 ID 无效");
        return parser.prices(request(Action.PRICES, Map.of("service", service, "country", country)));
    }

    /**
     * 查询单个服务、国家的各价格档位及供应商筛选信息。
     * <p>库存取自 getPricesV2，供应商列表取自随后查询的 getPricesV3；两次查询不是原子快照，
     * 供应商总库存不当作档位库存。查询结果不预留号码，也不承诺后续价格或库存。</p>
     * @param service 服务目录代码
     * @param country 国家目录 ID，不能为 any
     * @return 按价格升序的不可变档位列表；币种未提供，供应商列表可能为空
     * @throws BusinessException 参数非法时抛出，供应商错误以子类 GrizzlySmsException 表达
     */
    public List<GrizzlyPriceTier> getPriceTiers(String service, String country) {
        requireMatch(service, SERVICE_CODE, "接码服务代码无效");
        requireMatch(country, COUNTRY_ID, "接码国家 ID 无效");
        Map<String, String> parameters = Map.of("service", service, "country", country);
        List<GrizzlyPrice> prices = parser.tierPrices(request(Action.PRICE_TIERS, parameters), service, country);
        if (prices.isEmpty()) {
            return List.of();
        }
        return parser.priceTiers(request(Action.PRICE_PROVIDERS, parameters), prices, service, country);
    }

    /**
     * 按明确最高单价购买一个号码；本方法可能扣减供应商余额。
     * @param command 服务、国家、最高价格及可选筛选条件
     * @return 供应商已返回的激活订单，不代表目标账号已经注册
     * @throws BusinessException 参数非法时抛出；GrizzlySmsException.outcomeUnknown 为 true 时禁止盲目重试
     */
    public GrizzlyActivation acquireNumber(GrizzlyNumberRequest command) {
        return parser.activation(request(Action.ACQUIRE, purchaseParameters(command)));
    }

    /**
     * 查询一次接码状态，不循环等待、不提交验证码、不触发重新发送。
     * @param activationId 供应商返回的订单 ID
     * @return 当前状态，新码与旧码分别保存
     * @throws BusinessException 订单 ID 非法或供应商不可用时抛出
     */
    public GrizzlySmsStatus getStatus(String activationId) {
        requireMatch(activationId, POSITIVE_ID, "接码订单 ID 无效");
        return parser.status(request(Action.STATUS, Map.of("id", activationId)));
    }

    /**
     * 显式变更接码订单状态；取消是否退款以供应商订单状态为准。
     * @param activationId 已持久化并确认归属当前业务操作的订单 ID
     * @param update 目标状态，再次收码必须先单独确认订单支持
     * @throws BusinessException 参数非法时抛出；结果不明时必须核对供应商状态
     */
    public void setStatus(String activationId, GrizzlyStatusUpdate update) {
        requireMatch(activationId, POSITIVE_ID, "接码订单 ID 无效");
        if (update == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "接码状态不能为空");
        }
        String response = request(Action.UPDATE, Map.of("id", activationId, "status", update.wireValue()));
        if (!update.acknowledgement().equals(response)) {
            throw new GrizzlySmsException(GrizzlySmsFailure.INVALID_RESPONSE, true);
        }
    }

    private String request(Action action, Map<String, String> parameters) {
        ensureEnabled(action.mutation);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("api_key", properties.getApiKey());
        query.put("action", action.wireValue);
        query.putAll(parameters);
        try {
            String body = restClient.get().uri(builder -> {
                builder.path(HANDLER_PATH);
                query.forEach((name, value) -> builder.queryParam(name, "{" + name + "}"));
                return builder.build(query);
            }).exchange((httpRequest, httpResponse) -> {
                if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                    throw new GrizzlySmsException(GrizzlySmsFailure.HTTP_ERROR, action.mutation);
                }
                byte[] bytes = httpResponse.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new GrizzlySmsException(GrizzlySmsFailure.INVALID_RESPONSE, action.mutation);
                }
                return new String(bytes, StandardCharsets.UTF_8).trim();
            });
            checkProviderRejection(body, action.mutation);
            return body;
        } catch (RestClientException exception) {
            // 原始传输异常可能包含带密钥的 URL，禁止保留 cause 或写入日志。
            throw new GrizzlySmsException(GrizzlySmsFailure.TRANSPORT_ERROR, action.mutation);
        }
    }

    private void ensureEnabled(boolean mutation) {
        if (!properties.isEnabled()) {
            throw new GrizzlySmsException(GrizzlySmsFailure.DISABLED, false);
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new GrizzlySmsException(GrizzlySmsFailure.CREDENTIALS_MISSING, false);
        }
        if (mutation && !properties.isPurchasesEnabled()) {
            throw new GrizzlySmsException(GrizzlySmsFailure.MUTATIONS_DISABLED, false);
        }
    }

    private void checkProviderRejection(String body, boolean mutation) {
        if (!StringUtils.hasText(body)) {
            throw new GrizzlySmsException(GrizzlySmsFailure.INVALID_RESPONSE, mutation);
        }
        GrizzlySmsFailure rejection = PROVIDER_REJECTIONS.get(body);
        if (rejection != null) {
            throw new GrizzlySmsException(rejection, mutation && rejection == GrizzlySmsFailure.PROVIDER_ERROR);
        }
    }

    private Map<String, String> purchaseParameters(GrizzlyNumberRequest command) {
        if (command == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "接码购买参数不能为空");
        }
        requireMatch(command.service(), SERVICE_CODE, "接码服务代码无效");
        if (!"any".equals(command.country())) {
            requireMatch(command.country(), COUNTRY_ID, "接码国家 ID 无效");
        }
        requirePrice(command.maxPrice(), false, "接码购买必须指定有效最高单价");
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("service", command.service());
        parameters.put("country", command.country());
        parameters.put("maxPrice", command.maxPrice().toPlainString());
        if (command.options() != null) {
            appendOptions(parameters, command.options(), command.maxPrice());
        }
        return parameters;
    }

    private void appendOptions(Map<String, String> parameters, GrizzlyNumberRequest.Options options,
                               BigDecimal maxPrice) {
        if (options.minPrice() != null) {
            requirePrice(options.minPrice(), true, "接码最低单价无效");
            if (options.minPrice().compareTo(maxPrice) > 0) {
                throw new BusinessException(ErrorCode.VALIDATION, "接码最低单价不能高于最高单价");
            }
            parameters.put("minPrice", options.minPrice().toPlainString());
        }
        appendFilter(parameters, "providerIds", options.providerIds(), POSITIVE_ID);
        appendFilter(parameters, "exceptProviderIds", options.exceptProviderIds(), POSITIVE_ID);
        appendFilter(parameters, "phoneException", options.phoneException(), PHONE_PREFIX);
    }

    private void appendFilter(Map<String, String> parameters, String name, List<String> values, Pattern pattern) {
        if (values.size() > MAX_FILTER_VALUES) {
            throw new BusinessException(ErrorCode.VALIDATION, "接码筛选项数量过多");
        }
        for (String value : values) {
            requireMatch(value, pattern, "接码筛选项无效");
        }
        if (!values.isEmpty()) {
            parameters.put(name, String.join(",", values));
        }
    }

    private void requirePrice(BigDecimal value, boolean allowZero, String message) {
        if (value == null || value.signum() < 0 || (!allowZero && value.signum() == 0)
                || value.precision() > MAX_PRICE_PRECISION || Math.abs((long) value.scale()) > MAX_PRICE_SCALE) {
            throw new BusinessException(ErrorCode.VALIDATION, message);
        }
    }

    private void requireMatch(String value, Pattern pattern, String message) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION, message);
        }
    }

    /** 供应商操作及其是否可能产生费用或状态变更。 */
    private enum Action {
        /** 查询余额。 */
        BALANCE("getBalance", false),
        /** 查询服务。 */
        SERVICES("getServicesList", false),
        /** 查询国家。 */
        COUNTRIES("getCountries", false),
        /** 查询报价。 */
        PRICES("getPrices", false),
        /** 查询每个价格档位的库存。 */
        PRICE_TIERS("getPricesV2", false),
        /** 查询供应商及其支持的价格列表。 */
        PRICE_PROVIDERS("getPricesV3", false),
        /** 购买号码。 */
        ACQUIRE("getNumberV2", true),
        /** 查询接码状态。 */
        STATUS("getStatus", false),
        /** 变更接码状态。 */
        UPDATE("setStatus", true);

        /** 供应商 action 参数。 */
        private final String wireValue;
        /** 是否可能修改供应商状态。 */
        private final boolean mutation;

        Action(String wireValue, boolean mutation) {
            this.wireValue = wireValue;
            this.mutation = mutation;
        }
    }
}
