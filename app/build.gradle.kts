import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// ── 签名 ───────────────────────────────────────────────────────────────
// 凭据在仓库根的 keystore.properties（已 gitignore），keystore 文件本身在仓库之外。
// 刻意让 debug 与 release 用**同一套签名**：不同签名不能互相覆盖安装，
// 切换时必须卸载 → 本地数据全丢。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")?.let { file(it) }
val hasReleaseSigning = releaseStoreFile?.exists() == true

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.zzupocket.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zzupocket.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 76
        // 版本号只写纯数字，不再带 -P6 那种阶段后缀（阶段划分是 docs 里的事，别混进版本号）。
        // ⚠️ 升级判断只认 versionCode（Android 的规则），versionName 是给人看的；
        // 所以改名号（哪怕是"变小"的数字）都不会影响覆盖安装，每次发版 versionCode 必须 +1。
        versionName = "0.31.4"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("zzupocket") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("zzupocket")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("zzupocket")
            } else {
                logger.lifecycle("⚠️ 未找到 keystore.properties，release 包将使用 debug 签名")
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Room 导出 schema，便于日后写迁移。ksp{} 是项目级扩展，必须放在 android{} 之外。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    // 定时采样电量：学校不提供历史用电量，只能靠自己按点采样差分（每 4 小时一次）
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Material You 配色生成（由种子色算出整套 M3 浅/深配色，见 ui/theme/MaterialYouScheme.kt）
    implementation(libs.material.color.utilities)

    // 网络（不用 Retrofit：接口少，且 CAS 登录的 POST 走 query string，裸 OkHttp 更直接）
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // 本地存储
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
