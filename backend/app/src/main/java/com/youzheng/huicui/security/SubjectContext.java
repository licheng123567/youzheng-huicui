package com.youzheng.huicui.security;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;

/** 请求级当前主体持有器（ThreadLocal）。JwtAuthFilter 写入、Controller/Service 读取。 */
public final class SubjectContext {
    private static final ThreadLocal<CurrentSubject> HOLDER = new ThreadLocal<>();

    private SubjectContext() {}

    public static void set(CurrentSubject s) { HOLDER.set(s); }
    public static void clear() { HOLDER.remove(); }

    public static CurrentSubject get() {
        CurrentSubject s = HOLDER.get();
        if (s == null) throw new ApiException(BizError.AUTH_401, "未认证或令牌无效");
        return s;
    }

    public static CurrentSubject getOrNull() { return HOLDER.get(); }
}
