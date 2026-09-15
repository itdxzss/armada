package com.armada.platform.sms.grizzly;

import com.armada.platform.sms.grizzly.exception.GrizzlySmsException;
import com.armada.platform.sms.grizzly.exception.GrizzlySmsFailure;
import com.armada.platform.sms.grizzly.model.GrizzlyActivation;
import com.armada.platform.sms.grizzly.model.GrizzlyCountry;
import com.armada.platform.sms.grizzly.model.GrizzlyPrice;
import com.armada.platform.sms.grizzly.model.GrizzlyPriceTier;
import com.armada.platform.sms.grizzly.model.GrizzlyService;
import com.armada.platform.sms.grizzly.model.GrizzlySmsStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** 解析 Grizzly 混合的纯文本与 JSON 响应，错误不回显原文。 */
final class GrizzlySmsResponseParser {

    /** 非负定点金额，不使用浮点数转换。 */
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    /** 订单 ID 与号码只接受文档中的十进制字符。 */
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    /** 与客户端接受的订单及供应商 ID 格式保持一致。 */
    private static final Pattern ACTIVATION_ID = Pattern.compile("[1-9][0-9]{0,31}");
    /** 标准国际号码最大 15 位，响应不含加号。 */
    private static final Pattern PHONE = Pattern.compile("[1-9][0-9]{5,14}");
    /** 限制单个字段长度，异常字段不进入模型。 */
    private static final int MAX_FIELD_LENGTH = 256;
    /** 余额成功前缀。 */
    private static final String BALANCE_PREFIX = "ACCESS_BALANCE:";
    /** 新验证码成功前缀。 */
    private static final String RECEIVED_PREFIX = "STATUS_OK:";
    /** 等待重试响应中旧验证码的前缀。 */
    private static final String RETRY_PREFIX = "STATUS_WAIT_RETRY:";
    /** 供应商数字币种代码最多 3 位。 */
    private static final int MAX_CURRENCY = 999;

    /** 独立读取配置，金额精度和尾部数据检查不改变应用全局 Jackson 行为。 */
    private final ObjectReader objectReader;

    GrizzlySmsResponseParser(ObjectMapper objectMapper) {
        this.objectReader = objectMapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    BigDecimal balance(String body) {
        if (!body.startsWith(BALANCE_PREFIX)) {
            throw invalid(false);
        }
        return decimal(body.substring(BALANCE_PREFIX.length()), false);
    }

    List<GrizzlyService> services(String body) {
        JsonNode root = json(body, false);
        if (root.has("status") && !"success".equals(root.path("status").asText())) {
            throw invalid(false);
        }
        JsonNode items = root.has("services") ? root.get("services") : root;
        if (!items.isArray()) {
            throw invalid(false);
        }
        List<GrizzlyService> services = new ArrayList<>();
        for (JsonNode item : items) {
            services.add(new GrizzlyService(text(item.get("code"), false), text(item.get("name"), false)));
        }
        return List.copyOf(services);
    }

    List<GrizzlyCountry> countries(String body) {
        JsonNode items = json(body, false);
        if (!items.isArray() && !items.isObject()) {
            throw invalid(false);
        }
        if (items.has("id")) {
            return List.of(country(items));
        }
        List<GrizzlyCountry> countries = new ArrayList<>();
        for (JsonNode item : items) {
            countries.add(country(item));
        }
        return List.copyOf(countries);
    }

    private GrizzlyCountry country(JsonNode item) {
        return new GrizzlyCountry(digits(item.get("id"), false), text(item.get("eng"), false),
                optionalText(item, "chn", false), optionalText(item, "rus", false));
    }

    List<GrizzlyPrice> prices(String body) {
        JsonNode root = json(body, false);
        requireObject(root, false);
        List<GrizzlyPrice> prices = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> countries = root.fields();
        while (countries.hasNext()) {
            Map.Entry<String, JsonNode> country = countries.next();
            if (!DIGITS.matcher(country.getKey()).matches()) {
                throw invalid(false);
            }
            appendPrices(prices, country.getKey(), country.getValue());
        }
        return List.copyOf(prices);
    }

    private void appendPrices(List<GrizzlyPrice> prices, String country, JsonNode services) {
        requireObject(services, false);
        Iterator<Map.Entry<String, JsonNode>> entries = services.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> service = entries.next();
            JsonNode quote = service.getValue();
            prices.add(new GrizzlyPrice(country, service.getKey(),
                    amount(quote.get("cost"), false), count(quote.get("count"))));
        }
    }

    List<GrizzlyPrice> tierPrices(String body, String service, String country) {
        Optional<JsonNode> quote = scopedQuote(body, service, country);
        if (quote.isEmpty()) {
            return List.of();
        }
        Map<BigDecimal, Long> counts = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> entries = quote.get().fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            BigDecimal price = decimal(entry.getKey(), false);
            if (counts.putIfAbsent(price, count(entry.getValue())) != null) {
                // 不同文本表示同一单价时不能相加，避免重复计算库存。
                throw invalid(false);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new GrizzlyPrice(country, service, entry.getKey(), entry.getValue())).toList();
    }

    List<GrizzlyPriceTier> priceTiers(String body, List<GrizzlyPrice> prices, String service, String country) {
        Optional<JsonNode> quote = scopedQuote(body, service, country);
        Map<BigDecimal, List<String>> providersByPrice = new TreeMap<>();
        if (quote.isPresent()) {
            JsonNode item = quote.get();
            amount(item.get("price"), false);
            count(item.get("count"));
            JsonNode providers = item.get("providers");
            requireObject(providers, false);
            Iterator<Map.Entry<String, JsonNode>> entries = providers.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                appendProviderPrices(providersByPrice, entry.getKey(), entry.getValue());
            }
        }
        return prices.stream().map(price -> new GrizzlyPriceTier(price.country(), price.service(),
                price.cost(), price.count(), providersByPrice.getOrDefault(price.cost(), List.of()))).toList();
    }

    private void appendProviderPrices(Map<BigDecimal, List<String>> providersByPrice, String key, JsonNode provider) {
        requireObject(provider, false);
        String providerId = digits(provider.get("provider_id"), false);
        if (!ACTIVATION_ID.matcher(providerId).matches() || !providerId.equals(key)) {
            throw invalid(false);
        }
        long available = count(provider.get("count"));
        JsonNode prices = provider.get("price");
        if (prices == null || !prices.isArray()) {
            throw invalid(false);
        }
        for (JsonNode price : prices) {
            BigDecimal cost = amount(price, false);
            if (available == 0) {
                continue;
            }
            List<String> providerIds = providersByPrice.computeIfAbsent(cost, ignored -> new ArrayList<>());
            if (!providerIds.contains(providerId)) {
                providerIds.add(providerId);
            }
        }
    }

    private Optional<JsonNode> scopedQuote(String body, String service, String country) {
        JsonNode root = json(body, false);
        requireObject(root, false);
        if (root.isEmpty()) {
            return Optional.empty();
        }
        if (root.size() != 1 || !root.has(country)) {
            throw invalid(false);
        }
        JsonNode services = root.get(country);
        requireObject(services, false);
        if (services.isEmpty()) {
            return Optional.empty();
        }
        if (services.size() != 1 || !services.has(service)) {
            throw invalid(false);
        }
        JsonNode quote = services.get(service);
        requireObject(quote, false);
        return Optional.of(quote);
    }

    GrizzlyActivation activation(String body) {
        JsonNode root = json(body, true);
        requireObject(root, true);
        String activationId = digits(root.get("activationId"), true);
        String phone = text(root.get("phoneNumber"), true);
        if (!ACTIVATION_ID.matcher(activationId).matches() || !PHONE.matcher(phone).matches()) {
            throw invalid(true);
        }
        return new GrizzlyActivation(activationId, phone,
                amount(root.get("activationCost"), true), currency(root.get("currency")),
                new GrizzlyActivation.Details(optionalText(root, "countryCode", true),
                        optionalText(root, "activationTime", true), optionalText(root, "activationEnd", true),
                        optionalText(root, "activationCancel", true), optionalText(root, "canGetAnotherSms", true)));
    }

    GrizzlySmsStatus status(String body) {
        if (body.startsWith(RECEIVED_PREFIX)) {
            return new GrizzlySmsStatus(GrizzlySmsStatus.State.RECEIVED,
                    Optional.of(code(body.substring(RECEIVED_PREFIX.length()))), Optional.empty());
        }
        if (body.startsWith(RETRY_PREFIX)) {
            return new GrizzlySmsStatus(GrizzlySmsStatus.State.WAITING_RETRY, Optional.empty(),
                    Optional.of(code(body.substring(RETRY_PREFIX.length()))));
        }
        GrizzlySmsStatus.State state = switch (body) {
            case "STATUS_WAIT_CODE" -> GrizzlySmsStatus.State.WAITING_CODE;
            case "STATUS_WAIT_RESEND" -> GrizzlySmsStatus.State.WAITING_RESEND;
            case "STATUS_CANCEL" -> GrizzlySmsStatus.State.CANCELLED;
            default -> throw invalid(false);
        };
        return new GrizzlySmsStatus(state, Optional.empty(), Optional.empty());
    }

    private JsonNode json(String body, boolean mutation) {
        try {
            JsonNode root = objectReader.readTree(body);
            if (root == null || root.isNull()) {
                throw invalid(mutation);
            }
            return root;
        } catch (JsonProcessingException exception) {
            // Jackson 的异常正文包含输入片段，不能保留 cause。
            throw invalid(mutation);
        }
    }

    private BigDecimal decimal(String value, boolean mutation) {
        if (value.length() > MAX_FIELD_LENGTH || !DECIMAL.matcher(value).matches()) {
            throw invalid(mutation);
        }
        return new BigDecimal(value);
    }

    private BigDecimal amount(JsonNode value, boolean mutation) {
        if (value == null || !value.isNumber()) {
            return decimal(text(value, mutation), mutation);
        }
        BigDecimal result = value.decimalValue();
        if (result.signum() < 0 || result.precision() > MAX_FIELD_LENGTH
                || Math.abs((long) result.scale()) > MAX_FIELD_LENGTH) {
            throw invalid(mutation);
        }
        return result;
    }

    private String text(JsonNode node, boolean mutation) {
        if (node == null || (!node.isTextual() && !node.isNumber())) {
            throw invalid(mutation);
        }
        return checkedText(node.asText(), mutation, false);
    }

    private Optional<String> optionalText(JsonNode root, String field, boolean mutation) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual() && !value.isNumber()) {
            throw invalid(mutation);
        }
        return Optional.of(checkedText(value.asText(), mutation, true)).filter(text -> !text.isBlank());
    }

    private String digits(JsonNode value, boolean mutation) {
        String result = text(value, mutation);
        if (!DIGITS.matcher(result).matches()) {
            throw invalid(mutation);
        }
        return result;
    }

    private long count(JsonNode value) {
        try {
            return Long.parseLong(digits(value, false));
        } catch (NumberFormatException exception) {
            throw invalid(false);
        }
    }

    private int currency(JsonNode value) {
        try {
            int result = Integer.parseInt(digits(value, true));
            if (result <= 0 || result > MAX_CURRENCY) {
                throw invalid(true);
            }
            return result;
        } catch (NumberFormatException exception) {
            throw invalid(true);
        }
    }

    private String code(String value) {
        return checkedText(value, false, false);
    }

    private String checkedText(String value, boolean mutation, boolean allowBlank) {
        if ((!allowBlank && value.isBlank()) || value.length() > MAX_FIELD_LENGTH
                || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw invalid(mutation);
        }
        return value;
    }

    private void requireObject(JsonNode value, boolean mutation) {
        if (value == null || !value.isObject()) {
            throw invalid(mutation);
        }
    }

    private GrizzlySmsException invalid(boolean mutation) {
        return new GrizzlySmsException(GrizzlySmsFailure.INVALID_RESPONSE, mutation);
    }
}
