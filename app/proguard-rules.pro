# 目前未开启 minify（isMinifyEnabled = false）。
# 保留文件，将来开启混淆时把 kotlinx.serialization / Room / Hilt 的规则加在这里。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
