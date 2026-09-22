package com.motcs.core.auth.keys.usage.quota;

/**
 * 模型 Token 单价（人民币元 / 千 tokens）。
 * <p>按模型名关键字匹配；缓存命中价格当前未采集（Spring AI Usage 不提供缓存 token），
 * 输入 token 暂按推理输入价计，后续若接入缓存 token 可在此扩展。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-22 星期二
 */
public final class ModelPricing {

    /**
     * 单价：输入(推理输入) / 输出(推理输出)，单位 元/千tokens
     */
    public record Price(double inPerK, double outPerK) {
    }

    private ModelPricing() {
    }

    public static Price of(String model) {
        String m = model == null ? "" : model.toLowerCase();
        return switch (m) {
            case "deepseek-v4.1-flash" -> new Price(0.002, 0.008);
            case "deepseek-v4-flash-0731" -> new Price(0.003, 0.009);
            case "glm-5.3-flash" -> new Price(0.0008, 0.0028);
            case "ernie-4.5-turbo-20260402", "ernie-4.5-turbo-128k" -> new Price(0.0008, 0.0032);
            default ->
                // 未识别模型暂不计费（避免多算）
                    new Price(0, 0);
        };
    }

}
