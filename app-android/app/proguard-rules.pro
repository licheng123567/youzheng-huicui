# Retrofit / OkHttp / kotlinx.serialization 的 R8 规则。
# Retrofit 3 与 OkHttp 5 自带 consumer rules，这里只补 kotlinx.serialization 与生成的 DTO。

# kotlinx.serialization：@Serializable 类的伴生 serializer() 靠反射查找，不能被裁掉
-keepattributes InnerClasses
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    *** Companion;
}

# 契约生成的模型：保留字段名（@SerialName 已固定 wire 名，但保留更稳）
-keep,allowobfuscation,allowshrinking class com.youzheng.huicui.app.api.models.** { *; }

# androidx.security-crypto 依赖 Google Tink，Tink 编译期引用 errorprone 注解，
# 但那些注解不进运行时 → R8 报 "Missing class"。仅是注解，忽略即可。
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Retrofit 接口的泛型签名不能被抹掉，否则 Response<T> 解析不出 T
-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault
-keep,allowobfuscation interface com.youzheng.huicui.app.api.apis.*
-keep,allowobfuscation interface com.youzheng.huicui.app.data.net.AuthEdgeApi
