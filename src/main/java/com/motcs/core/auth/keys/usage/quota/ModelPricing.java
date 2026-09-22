package com.motcs.core.auth.keys.usage.quota;

/**
 * 模型 Token 单价（人民币元 / 千 tokens），与百度千帆计费口径一致：
 * 输入分两部分——未命中缓存的输入按输入价计，命中缓存的输入按缓存价计；输出按输出价计。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-22 星期二
 */
public final class ModelPricing {

    /**
     * 单价：输入(未命中缓存) / 输出 / 缓存命中，单位 元/千tokens
     */
    public record Price(double inPerK, double outPerK, double cachePerK) {
    }

    private ModelPricing() {
    }

    public static Price of(String model) {
        String m = model == null ? "" : model.toLowerCase();
        return switch (m) {
            case "deepseek-v4.1-flash" -> new Price(0.002, 0.008, 0.0002);
            case "deepseek-v4-flash-0731" -> new Price(0.003, 0.009, 0.0003);
            case "glm-5.3-flash" -> new Price(0.0008, 0.0028, 0.00023);
            case "ernie-4.5-turbo-20260402", "ernie-4.5-turbo-128k" -> new Price(0.0008, 0.0032, 0.0002);
            default ->
                // 未识别模型暂不计费（避免多算）
                    new Price(0, 0, 0);
        };
    }

}
