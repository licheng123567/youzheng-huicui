package com.youzheng.huicui.common;

/**
 * Wilson 置信区间下界——话术飞轮用它作排序权威（BR-M5-12）：小样本高转化率不被高估，
 * 样本越大下界越贴近真实转化率。比原始比率更稳健地排序话术。
 */
public final class WilsonStats {

    private WilsonStats() {}

    /** 95% 置信下界（z=1.96）。n=0 → 0。ok=成功数（如承诺兑现数），n=样本数（如关联承诺数）。 */
    public static double lower(long ok, long n) {
        if (n <= 0) return 0.0;
        double z = 1.96, p = (double) ok / n;
        double denom = 1 + z * z / n;
        double centre = p + z * z / (2 * n);
        double margin = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n));
        return Math.max(0.0, (centre - margin) / denom);
    }

    /** 保留 3 位小数（DB wilson 列口径）。 */
    public static double lower3(long ok, long n) {
        return Math.round(lower(ok, n) * 1000.0) / 1000.0;
    }
}
