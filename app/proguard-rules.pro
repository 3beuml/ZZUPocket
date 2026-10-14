# release 开启 R8（isMinifyEnabled / isShrinkResources 在 app/build.gradle.kts 的 release 块里）。
# 没开 minify 时这个文件不起作用；开了之后这就是"哪些东西不许裁"的清单。

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# androidx.security:security-crypto 用的 Tink 引用了 google errorprone 的**编译期注解**，
# 运行时不存在这些类。规则来自 AGP 生成的 app/build/outputs/mapping/release/missing_rules.txt。
-dontwarn com.google.errorprone.annotations.**

# kotlinx.serialization：@Serializable 的 serializer 靠伴生对象与反射名查找，被裁掉就会运行时炸。
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keep class <1>$$serializer { *; }

# Tink（EncryptedSharedPreferences 背后）用反射注册 KeyManager，保守整包保留。
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
