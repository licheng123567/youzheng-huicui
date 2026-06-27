package com.youzheng.huicui.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SE（平台员工 PLATFORM/SE）三维数据范围（BR-M1-14）。
 * 来源：account.data_range JSONB = {areas[], properties[], providers[]}。
 *
 * 语义（PRD 01-组织与权限 BR-M1-14：默认数据范围 = 全平台，超管可收窄）：
 *   - data_range 为 null（非 SE，或 SE 未收窄）→ 全平台不限（{@link #UNRESTRICTED}）。
 *   - 某维度数组为空 → 该维度不限（默认=全平台口径，空≠无）。
 *   - 某维度非空 → 仅该维度列举的对象可见，维度间取「交集」（AND）。
 *
 *   areas      = 项目地址区域（project.area，省/市/区 字符串）的允许集。
 *   properties = 物业公司组织 id（project.org_id）的允许集。
 *   providers  = 服务商组织 id（case.provider_id）的允许集。
 *
 * 【fail-closed（issue#3 安全收口）】：
 *   data_range 文本为 null/空白 → 合法的「未收窄」→ UNRESTRICTED（全平台，与默认一致）。
 *   data_range 文本非空但解析失败/结构非法 → 视为「受限且空范围」（{@link #restricted}=true，三维皆空）。
 *   绝不把非法 data_range 放大为全平台：受限空范围的 SE 列表恒空、单对象判定恒不可见（拒绝放大越权）。
 */
public record DataRange(List<String> areas, List<Long> properties, List<Long> providers, boolean restricted) {

    /** 三维兼容旧调用：默认 restricted=false。 */
    public DataRange(List<String> areas, List<Long> properties, List<Long> providers) {
        this(areas, properties, providers, false);
    }

    /** 全平台不限（非 SE，或 SE data_range=null）。三维均为「不限」。 */
    public static final DataRange UNRESTRICTED =
            new DataRange(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false);

    /** fail-closed 受限空范围：data_range 非空但非法 → 该 SE 看不到任何对象（绝不放大为全平台）。 */
    public static final DataRange RESTRICTED_EMPTY =
            new DataRange(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), true);

    public boolean hasAreas()     { return areas != null && !areas.isEmpty(); }
    public boolean hasProperties(){ return properties != null && !properties.isEmpty(); }
    public boolean hasProviders() { return providers != null && !providers.isEmpty(); }

    /** 是否「受限空范围」(fail-closed)：受限标记置位且三维皆空 → 恒空可见集。 */
    public boolean isRestrictedEmpty() { return restricted && !hasAreas() && !hasProperties() && !hasProviders(); }

    /** 三维皆空且未受限 = 全平台不限。受限空范围不算「不限」（fail-closed）。 */
    public boolean isUnrestricted() { return !restricted && !hasAreas() && !hasProperties() && !hasProviders(); }

    /**
     * 解析 account.data_range JSON 文本。
     *   null/空白           → UNRESTRICTED（合法未收窄=全平台）。
     *   非空但解析失败/非法  → RESTRICTED_EMPTY（fail-closed，绝不放大为全平台）。
     */
    public static DataRange parse(String json) {
        if (json == null || json.isBlank()) return UNRESTRICTED;
        try {
            JsonNode n = MAPPER.readTree(json);
            if (n == null || !n.isObject()) return RESTRICTED_EMPTY;   // 非对象结构 → fail-closed
            // restricted 标记须跨 JWT 往返保真：受限空范围序列化进 claim 后回读仍 fail-closed。
            JsonNode rn = n.get("restricted");
            if (rn != null && rn.isBoolean() && rn.asBoolean()) return RESTRICTED_EMPTY;
            // 字段级 fail-closed（HIGH-2）：字段存在但类型非法（如 {"providers":"abc"}）
            //   或数组元素非法（如 ["bad"]）→ strList/longList 抛 IllegalArgumentException → catch → RESTRICTED_EMPTY；
            //   绝不静默吞成空 list（空 list 等价「不限」=放大越权）。字段缺失=该维不限，保持合法。
            return new DataRange(strList(n.get("areas")), longList(n.get("properties")), longList(n.get("providers")));
        } catch (Exception e) {
            return RESTRICTED_EMPTY;                                    // 解析异常/字段非法 → fail-closed（非放大权限）
        }
    }

    /** 紧凑序列化到 JWT claim（仅承载三个数组，可空）。 */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 JWT claim 文本反序列化；null → UNRESTRICTED。
     * 复用 {@link #parse}（手动 JsonNode 解析）——不依赖 record 参数名绑定（编译未开 -parameters，
     * Jackson 直接 readValue(record) 会失败回落 UNRESTRICTED，导致 data_range 失效）。
     */
    public static DataRange fromJson(String json) {
        return parse(json);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * areas 维解析（字段级 fail-closed）：字段缺失/为 JSON null → 空集（该维不限·合法）。
     * 字段存在但非数组 → 抛 IllegalArgumentException（上层 catch → RESTRICTED_EMPTY）。
     * 数组元素须为非空标量字符串；元素为 null/对象/数组 → 抛（非法元素不静默跳过）。
     */
    private static List<String> strList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || arr.isNull()) return out;                   // 缺失=该维不限
        if (!arr.isArray()) throw new IllegalArgumentException("areas 非数组");
        for (JsonNode e : arr) {
            if (e == null || e.isNull() || !e.isValueNode()) {
                throw new IllegalArgumentException("areas 元素非法");
            }
            String v = e.asText();
            if (v == null || v.isBlank()) throw new IllegalArgumentException("areas 元素为空");
            out.add(v);
        }
        return out;
    }

    /**
     * properties/providers 维解析（字段级 fail-closed）：字段缺失/为 JSON null → 空集（该维不限·合法）。
     * 字段存在但非数组 → 抛；数组元素须可解析为数字 id，非数字元素（如 ["bad"]）→ 抛（不静默跳过）。
     */
    private static List<Long> longList(JsonNode arr) {
        List<Long> out = new ArrayList<>();
        if (arr == null || arr.isNull()) return out;                   // 缺失=该维不限
        if (!arr.isArray()) throw new IllegalArgumentException("数组维非数组");
        for (JsonNode e : arr) {
            if (e == null || e.isNull() || !e.isValueNode()) {
                throw new IllegalArgumentException("数组维元素非法");
            }
            try { out.add(Long.valueOf(e.asText().trim())); }
            catch (NumberFormatException nfe) { throw new IllegalArgumentException("数组维元素非数字: " + e.asText()); }
        }
        return out;
    }
}
