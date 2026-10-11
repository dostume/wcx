# ─── Xposed Module Entry Points ────────────────────────────────────
# These MUST be kept with original names — Xposed framework loads them
-keep class de.robv.android.xposed.** { *; }
-keep class io.github.libxposed.** { *; }
-keep class com.Johnny.wcx.entry.** { *; }
-keep class com.Johnny.wcx.application.** { *; }

# ─── Feature / Hook / Data Classes ─────────────────────────────────
# 不再整体 -keep：功能类由 KSP 生成的 FeaturesProvider 直接引用注册
# （ALL_HOOK_ITEMS 列表逐项引用每个 Feature 类，见 app/build/generated/ksp/**/
# FeaturesProvider.kt），功能名取自 @Feature 注解的 name 字段而非类名，
# R8 只会重命名、不会误删被引用的类；删除整体 keep 后 R8 还可额外摇掉
# 包内未被任何代码路径引用的辅助类，进一步减小体积。
# （下方 @com.Johnny.wcx.annotations.* 成员 keep 保留，供编译期处理器使用）

# Keep annotation-annotated members (used by compile-time processors)
-keepclassmembers,allowobfuscation class * {
    @com.Johnny.wcx.annotations.* *;
}

# ─── Kotlin ────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keep class kotlin.coroutines.Continuation { *; }
-dontwarn kotlinx.coroutines.**

# kotlin-reflect 在运行时反射路径中使用；暂时保留其内建表，避免反射/脚本功能回归。
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

# ─── Serialization ──────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
# 不再全量 keep kotlinx.serialization 运行库。运行时入口由普通可达性分析保留，
# 应用内生成的 serializer 仍由下方规则保留，允许 R8 移除未使用的格式/实现代码。
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.Johnny.wcx.**$$serializer { *; }
-keepclassmembers class com.Johnny.wcx.** {
    *** Companion;
}
-keepclasseswithmembers class com.Johnny.wcx.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ─── Room ───────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ─── Compose ────────────────────────────────────────────────────────
# 整体 -keep androidx.compose.** 会锁死 8000+ 个类（含大量用不到的分支），
# 是包体积的主要来源。Compose 官方并不要求全量 keep，R8 可正常摇树。
# 仅保留 -dontwarn 以容忍缺失的可选依赖（test/desktop 等变体引用）。
-dontwarn androidx.compose.**
-keepclassmembers,allowobfuscation class **.ComposableSingletons* {
    public static <fields>;
}

# ─── Third-party (dontwarn only, allow R8 optimization) ──────────────
-dontwarn com.alibaba.fastjson2.**
-dontwarn io.netty.**
-dontwarn com.google.protobuf.**
-dontwarn com.tencent.wcdb.**
-dontwarn org.slf4j.**
-dontwarn org.mozilla.javascript.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.ktor.**
-dontwarn com.materialkolor.**
-dontwarn miuix.**
-dontwarn javax.**
-dontwarn java.lang.invoke.**

# ─── WeChat Stubs ───────────────────────────────────────────────────
# 微信 API stub 为 compileOnly；不应打入模块 APK，也无需对其设置 keep 规则。

# ─── Obfuscation Enhancements ───────────────────────────────────────
-repackageclasses
-allowaccessmodification
-overloadaggressively
-useuniqueclassmembernames

# ─── Attributes ─────────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes Exceptions
-keepattributes SourceFile,LineNumberTable

# ─── Keep resource names used in code ───────────────────────────────
-keepclassmembers class **.R$* {
    public static <fields>;
}

# R$plurals 类本身必须保留：R8 会因 R$plurals 成员被常量内联而整体移除该类，
# 但 Kotlin 对 pluralStringResource(R.plurals.*) 的编译引用仍指向它。
-keep class **.R$plurals { *; }
# --- Hchat 脚本子系统迁移：依赖的容错规则 ---
# kavaref-core 部分内部类在运行时按需加载，R8 静态分析缺失（不影响实际功能）
-dontwarn com.highcapable.kavaref.**
# 迁移引入的反射/DexKit 相关库
-dontwarn io.github.skylot.jadx.**
-dontwarn com.android.tools.smali.**
-dontwarn com.github.REAndroid.**
-dontwarn org.jf.dexlib2.**
-dontwarn dev.rikka.ndk.thirdparty.**
-keep class com.highcapable.kavaref.** { *; }
-keep class h.Hchat.** { *; }

-dontwarn com.termux.**
-keep class com.termux.** { *; }

# 内置 rhino jar (app/libs/rhino-45f29f6.jar) 含 fork 的 Kotlin 可空性检测扩展
# (org.mozilla.kotlin.KotlinNullabilityDetector)，其引用 Kotlin 编译器内部 metadata API
# (kotlin.metadata.*)，运行环境不提供该依赖。禁用 R8 缺失类告警，不影响常规 JS 功能。
-dontwarn kotlin.metadata.**
-dontwarn org.mozilla.kotlin.**
