package com.armada.promotion.stats;

import static com.armada.promotion.stats.BuyerChannelStatsModels.*;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeAccess;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 基于现有渠道、配对会话和账号数据生成渠道统计，并保存人工广告数据。 */
@Service
public class BuyerChannelStatsService {

    private static final Logger log = LoggerFactory.getLogger(BuyerChannelStatsService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final JdbcTemplate jdbc;

    public BuyerChannelStatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 返回筛选项；父级用户关系当前未建模，因此该项真实返回空列表。 */
    public Options options(long tenantId) {
        DataScope scope = requireUserScope();
        String channelSql = "SELECT id,channel_name FROM promotion_channel "
                + "WHERE tenant_id=? AND deleted_at IS NULL";
        List<Object> channelArgs = new ArrayList<>();
        channelArgs.add(tenantId);
        if (scope.isSelf()) {
            channelSql += " AND owner_user_id=?";
            channelArgs.add(scope.actorUserId());
        }
        channelSql += " ORDER BY channel_name,id";
        List<Option> channels = jdbc.query(
                channelSql,
                (rs, n) -> new Option(rs.getLong(1), rs.getString(2)),
                channelArgs.toArray());
        List<Option> templates = jdbc.query(
                "SELECT id,template_name FROM promotion_landing_template WHERE tenant_id=? AND deleted_at IS NULL ORDER BY template_name,id",
                (rs, n) -> new Option(rs.getLong(1), rs.getString(2)), tenantId);
        List<CountryOption> countries = jdbc.query(
                "SELECT iso2,name_zh FROM country WHERE is_enabled=1 AND deleted_at IS NULL ORDER BY sort_order,id",
                (rs, n) -> new CountryOption(rs.getString(1), rs.getString(2)));
        String creatorSql = "SELECT id,COALESCE(NULLIF(nickname,''),username) FROM sys_user "
                + "WHERE tenant_id=? AND status=1";
        List<Object> creatorArgs = new ArrayList<>();
        creatorArgs.add(tenantId);
        if (scope.isSelf()) {
            creatorSql += " AND id=?";
            creatorArgs.add(scope.actorUserId());
        }
        creatorSql += " ORDER BY id";
        List<Option> creators = jdbc.query(
                creatorSql,
                (rs, n) -> new Option(rs.getLong(1), rs.getString(2)),
                creatorArgs.toArray());
        return new Options(channels, templates, countries, creators, List.of());
    }

    /** 查询渠道区间汇总。没有采集来源的数据保持 0，不生成模拟值。 */
    public List<StatsRow> list(Query query, long tenantId) {
        DataScope scope = requireUserScope();
        DateRange range = range(query.dateStart(), query.dateEnd());
        List<ChannelMeta> channels = channels(query, tenantId, scope);
        List<StatsRow> rows = channels.stream()
                .map(channel -> summary(channel, range, tenantId, scope))
                .toList();
        return sort(rows, query.sortField(), query.sortOrder());
    }

    /** 查询单渠道每日明细，日期连续返回，缺失日为真实零值。 */
    public List<DailyRow> daily(long channelId, String countryCode, String start, String end, long tenantId) {
        DataScope scope = requireUserScope();
        DateRange range = range(start, end);
        ChannelMeta channel = requireChannel(channelId, tenantId, scope);
        String country = normalizeCountry(countryCode, channel.countryCode());
        List<DailyRow> rows = new ArrayList<>();
        for (LocalDate date = range.start(); !date.isAfter(range.end()); date = date.plusDays(1)) {
            rows.add(dailyRow(channel, country, date, tenantId, scope));
        }
        return rows;
    }

    /** 以乐观锁保存某日人工广告数据，避免两人编辑时静默覆盖。 */
    @Transactional(rollbackFor = Exception.class)
    public UpdateResult update(long channelId, LocalDate date, DailyInput input, AuthPrincipal principal) {
        DataScope scope = DataScopeAccess.requireCurrentForPrincipal(principal);
        DateRange summaryRange = range(input.dateStart(), input.dateEnd());
        ChannelMeta channel = requireChannel(channelId, principal.tenantId(), scope);
        String country = normalizeCountry(input.countryCode(), channel.countryCode());
        validateMetric(input);
        int version = input.version() == null ? 0 : input.version();
        long now = System.currentTimeMillis();
        int affected;
        if (version == 0) {
            try {
                affected = jdbc.update("INSERT INTO promotion_channel_daily_ad_metric " +
                                "(tenant_id,channel_id,country_code,stat_date,spend,impressions,clicks,service_rate," +
                                "other_fee,version,updated_by,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,1,?,?,?)",
                        principal.tenantId(), channelId, country, date, amount(input.spend()), count(input.impressions()),
                        count(input.clicks()), amount(input.serviceRate()), amount(input.otherFee()),
                        scope.actorUserId(), now, now);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(ErrorCode.CONFLICT, "该日期数据已被其他人新增，请刷新后重试");
            }
        } else {
            affected = jdbc.update("UPDATE promotion_channel_daily_ad_metric SET spend=?,impressions=?,clicks=?," +
                            "service_rate=?,other_fee=?,version=version+1,updated_by=?,updated_at=? " +
                            "WHERE tenant_id=? AND channel_id=? AND country_code=? AND stat_date=? AND version=?",
                    amount(input.spend()), count(input.impressions()), count(input.clicks()),
                    amount(input.serviceRate()), amount(input.otherFee()), scope.actorUserId(), now,
                    principal.tenantId(), channelId, country, date, version);
            if (affected == 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "数据已被其他人修改，请刷新后重试");
            }
        }
        log.info("渠道日广告数据保存 tenantId={} channelId={} country={} date={} oldVersion={} operator={}",
                principal.tenantId(), channelId, country, date, version, principal.username());
        DailyRow daily = dailyRow(channel, country, date, principal.tenantId(), scope);
        StatsRow summary = summary(channel, summaryRange, principal.tenantId(), scope);
        return new UpdateResult(daily, summary);
    }

    private List<ChannelMeta> channels(Query query, long tenantId, DataScope scope) {
        StringBuilder sql = new StringBuilder("SELECT c.id,c.channel_name,c.channel_code,c.target_country_value," +
                "country.name_zh," +
                "t.id,t.template_name FROM promotion_channel c " +
                "JOIN promotion_domain d ON d.id=c.promotion_domain_id AND d.tenant_id=c.tenant_id AND d.deleted_at IS NULL " +
                "JOIN promotion_landing_template t ON t.id=d.landing_template_id AND t.tenant_id=c.tenant_id AND t.deleted_at IS NULL " +
                "LEFT JOIN country ON country.iso2=c.target_country_value AND country.deleted_at IS NULL " +
                "WHERE c.tenant_id=? AND c.deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (scope.isSelf()) {
            sql.append(" AND c.owner_user_id=?"); args.add(scope.actorUserId());
        }
        if (query.channelId() != null) {
            sql.append(" AND c.id=?"); args.add(query.channelId());
        }
        if (text(query.channelName())) {
            sql.append(" AND c.channel_name LIKE ?"); args.add("%" + query.channelName().trim() + "%");
        }
        if (query.templateId() != null) {
            sql.append(" AND t.id=?"); args.add(query.templateId());
        }
        if (text(query.countryCode())) {
            sql.append(" AND c.target_country_value=?"); args.add(query.countryCode().trim().toUpperCase());
        }
        if (query.createdBy() != null) {
            sql.append(" AND c.owner_user_id=?"); args.add(query.createdBy());
        }
        if (query.parentUserId() != null) {
            return List.of();
        }
        sql.append(" ORDER BY c.id DESC");
        return jdbc.query(sql.toString(), (rs, n) -> {
            String countryCode = rs.getString(4);
            String countryName = rs.getString(5);
            if (!text(countryName)) {
                countryName = "MIXED".equals(countryCode) ? "混合" : countryCode;
            }
            return new ChannelMeta(rs.getLong(1), rs.getString(2), rs.getString(3), countryCode,
                    countryName, rs.getLong(6), rs.getString(7));
        }, args.toArray());
    }

    private ChannelMeta requireChannel(long channelId, long tenantId, DataScope scope) {
        Query query = new Query("2000-01-01", "2000-01-01", channelId,
                null, null, null, null, null, null, null);
        List<ChannelMeta> rows = channels(query, tenantId, scope);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "推广渠道不存在");
        }
        return rows.get(0);
    }

    private StatsRow summary(ChannelMeta channel, DateRange range, long tenantId, DataScope scope) {
        String country = channel.countryCode();
        AdMetric ad = jdbc.queryForObject("SELECT COALESCE(SUM(spend),0),COALESCE(SUM(impressions),0)," +
                        "COALESCE(SUM(clicks),0),COALESCE(SUM(spend*service_rate),0),COALESCE(SUM(other_fee),0) " +
                        "FROM promotion_channel_daily_ad_metric WHERE tenant_id=? AND channel_id=? AND country_code=? " +
                        "AND stat_date BETWEEN ? AND ?",
                (rs, n) -> new AdMetric(rs.getBigDecimal(1), rs.getLong(2), rs.getLong(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), 0),
                tenantId, channel.id(), country, range.start(), range.end());
        PairMetric pair = pairing(channel.id(), range.startEpoch(), range.endExclusiveEpoch(), tenantId);
        long unbind = unbind(channel.id(), range.startEpoch(), range.endExclusiveEpoch(), tenantId, scope);
        return stats(channel, ad, pair, unbind);
    }

    private DailyRow dailyRow(
            ChannelMeta channel, String country, LocalDate date, long tenantId, DataScope scope) {
        List<AdMetric> ads = jdbc.query("SELECT spend,impressions,clicks,spend*service_rate,other_fee,version " +
                        "FROM promotion_channel_daily_ad_metric WHERE tenant_id=? AND channel_id=? " +
                        "AND country_code=? AND stat_date=?",
                (rs, n) -> new AdMetric(rs.getBigDecimal(1), rs.getLong(2), rs.getLong(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getInt(6)),
                tenantId, channel.id(), country, date);
        AdMetric ad = ads.isEmpty() ? AdMetric.empty() : ads.get(0);
        long start = date.atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
        long end = date.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
        PairMetric pair = pairing(channel.id(), start, end, tenantId);
        long unbind = unbind(channel.id(), start, end, tenantId, scope);
        BigDecimal rate = ratio(ad.serviceFee(), ad.spend());
        Derived derived = derived(ad.spend(), ad.impressions(), ad.clicks(), rate,
                ad.serviceFee(), ad.otherFee(), 0, pair.requestUsers(), pair.successUsers(), unbind);
        return new DailyRow(date.toString(), country, ad.spend(), ad.impressions(), ad.clicks(),
                rate == null ? ZERO : rate, ad.otherFee(), 0, 0,
                pair.requests(), pair.requestUsers(), pair.successes(), pair.successUsers(), unbind,
                derived.clickRate(), ad.serviceFee(), derived.totalFee(), derived.loginRequestRate(),
                derived.loginSuccessRate(), derived.visitorConversionRate(), derived.unbindRate(),
                derived.accountCost(), ad.version());
    }

    private PairMetric pairing(long channelId, long start, long end, long tenantId) {
        return jdbc.queryForObject("SELECT COUNT(*),COUNT(DISTINCT phone)," +
                        "COALESCE(SUM(status=4),0),COUNT(DISTINCT CASE WHEN status=4 THEN phone END) " +
                        "FROM promotion_pairing_session WHERE tenant_id=? AND promotion_channel_id=? " +
                        "AND created_at>=? AND created_at<?",
                (rs, n) -> new PairMetric(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                tenantId, channelId, start, end);
    }

    private long unbind(
            long channelId, long start, long end, long tenantId, DataScope scope) {
        String sql = "SELECT COUNT(*) FROM account a JOIN account_state s "
                + "ON s.tenant_id=a.tenant_id AND s.account_id=a.id "
                + "WHERE a.tenant_id=? AND a.promotion_channel_id=? AND a.deleted_at IS NULL "
                + "AND s.account_state=5 AND s.updated_at>=? AND s.updated_at<?";
        List<Object> args = new ArrayList<>(List.of(tenantId, channelId, start, end));
        if (scope.isSelf()) {
            sql += " AND a.owner_user_id=?";
            args.add(scope.actorUserId());
        }
        Long value = jdbc.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    /** 统计管理接口只接受登录用户范围；缺失和 SYSTEM 均失败关闭。 */
    private static DataScope requireUserScope() {
        DataScope scope = DataScopeAccess.requireCurrent();
        if (!scope.isSelf() && !scope.isAll()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "后台范围不能直接访问渠道统计");
        }
        return scope;
    }

    private static StatsRow stats(ChannelMeta channel, AdMetric ad, PairMetric pair, long unbind) {
        BigDecimal rate = ratio(ad.serviceFee(), ad.spend());
        Derived d = derived(ad.spend(), ad.impressions(), ad.clicks(), rate,
                ad.serviceFee(), ad.otherFee(), 0, pair.requestUsers(), pair.successUsers(), unbind);
        return new StatsRow(channel.id(), channel.name(), channel.code(), channel.countryCode(),
                channel.countryName(), channel.templateId(), channel.templateName(), ad.spend(),
                ad.impressions(), ad.clicks(), rate == null ? ZERO : rate, ad.otherFee(), 0, 0,
                pair.requests(), pair.requestUsers(), pair.successes(), pair.successUsers(), unbind,
                d.clickRate(), ad.serviceFee(), d.totalFee(), d.loginRequestRate(),
                d.loginSuccessRate(), d.visitorConversionRate(), d.unbindRate(), d.accountCost());
    }

    private static Derived derived(BigDecimal spend, long impressions, long clicks, BigDecimal serviceRate,
                                   BigDecimal serviceFee, BigDecimal otherFee, long uv,
                                   long requestUsers, long successUsers, long unbind) {
        BigDecimal totalFee = spend.add(serviceFee).add(otherFee);
        return new Derived(ratio(clicks, impressions), totalFee,
                ratio(requestUsers, uv), ratio(successUsers, requestUsers), ratio(successUsers, uv),
                ratio(unbind, successUsers), ratio(spend, successUsers));
    }

    private static List<StatsRow> sort(List<StatsRow> rows, String field, String order) {
        if (!text(field)) return rows;
        Map<String, java.util.function.Function<StatsRow, Comparable<?>>> values = new HashMap<>();
        values.put("spend", StatsRow::spend);
        values.put("impressions", row -> row.impressions());
        values.put("clicks", row -> row.clicks());
        values.put("totalFee", StatsRow::totalFee);
        values.put("uv", row -> row.uv());
        values.put("loginSuccessUserCount", row -> row.loginSuccessUserCount());
        values.put("unbindRate", StatsRow::unbindRate);
        values.put("accountCost", StatsRow::accountCost);
        java.util.function.Function<StatsRow, Comparable<?>> value = values.get(field);
        if (value == null) throw new BusinessException(ErrorCode.VALIDATION, "不支持的排序字段");
        Comparator<StatsRow> comparator = (left, right) -> compare(value.apply(left), value.apply(right));
        if ("desc".equalsIgnoreCase(order)) comparator = comparator.reversed();
        return rows.stream().sorted(comparator.thenComparing(StatsRow::channelId)).toList();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Comparable left, Comparable right) {
        if (left == null && right == null) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        return left.compareTo(right);
    }

    private static DateRange range(String start, String end) {
        try {
            LocalDate from = LocalDate.parse(start);
            LocalDate to = LocalDate.parse(end);
            long days = ChronoUnit.DAYS.between(from, to);
            if (days < 0 || days > 366) {
                throw new BusinessException(ErrorCode.VALIDATION, "统计日期范围必须在367天以内");
            }
            return new DateRange(from, to,
                    from.atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli(),
                    to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli());
        } catch (DateTimeParseException | NullPointerException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "统计日期格式应为yyyy-MM-dd");
        }
    }

    private static void validateMetric(DailyInput input) {
        if (input == null) throw new BusinessException(ErrorCode.VALIDATION, "统计数据不能为空");
        if (amount(input.spend()).signum() < 0 || count(input.impressions()) < 0 || count(input.clicks()) < 0
                || amount(input.otherFee()).signum() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "消耗、展示、点击和其他费用不能为负数");
        }
        BigDecimal rate = amount(input.serviceRate());
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "手续费率必须在0到1之间");
        }
    }

    private static String normalizeCountry(String requested, String channelCountry) {
        String value = text(requested) ? requested.trim().toUpperCase() : channelCountry;
        if (!value.equals(channelCountry)) {
            throw new BusinessException(ErrorCode.VALIDATION, "统计国家与渠道配置不一致");
        }
        return value;
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(BigDecimal numerator, long denominator) {
        return denominator == 0 ? null : numerator.divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() == 0 ? null : numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal amount(BigDecimal value) { return value == null ? ZERO : value; }
    private static long count(Long value) { return value == null ? 0 : value; }
    private static boolean text(String value) { return value != null && !value.isBlank(); }

    private record ChannelMeta(long id, String name, String code, String countryCode,
                               String countryName, long templateId, String templateName) { }
    private record DateRange(LocalDate start, LocalDate end, long startEpoch, long endExclusiveEpoch) { }
    private record AdMetric(BigDecimal spend, long impressions, long clicks,
                            BigDecimal serviceFee, BigDecimal otherFee, int version) {
        private static AdMetric empty() { return new AdMetric(ZERO, 0, 0, ZERO, ZERO, 0); }
    }
    private record PairMetric(long requests, long requestUsers, long successes, long successUsers) { }
    private record Derived(BigDecimal clickRate, BigDecimal totalFee, BigDecimal loginRequestRate,
                           BigDecimal loginSuccessRate, BigDecimal visitorConversionRate,
                           BigDecimal unbindRate, BigDecimal accountCost) { }
}
