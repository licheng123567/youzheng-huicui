plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// ─────────────────────────────────────────────────────────────────────────────
// 契约客户端：从 docs/api/openapi-core.yaml（SSOT）在构建期生成，产物不入库。
//
// 为什么不用 org.openapi.generator Gradle 插件：该插件在 Maven Central 最新只到 7.14.0，
// 而本仓库把生成器版本锁在 openapitools.json 的 7.23.0（前端/后端桩都用它）。
// 用插件就会让 App 端悄悄换一个生成器版本 —— 正是仓库反向 route-coverage 要防的那类漂移。
// 故直接 JavaExec 跑同一个 CLI jar，版本从 openapitools.json 读，单一事实源。
// ─────────────────────────────────────────────────────────────────────────────

val contractFile: File = rootProject.file("../docs/api/openapi-core.yaml")
val openapitoolsJson: File = rootProject.file("../openapitools.json")

/** 从仓库根 openapitools.json 解析生成器版本，避免与前后端桩版本漂移。 */
val generatorVersion: String = run {
    val text = openapitoolsJson.readText()
    Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        ?: error("无法从 ${openapitoolsJson.path} 解析 generator-cli.version")
}

val openapiCli: Configuration by configurations.creating

dependencies {
    openapiCli("org.openapitools:openapi-generator-cli:$generatorVersion")

    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.retrofit)
    api(libs.retrofit.converter.kotlinx)
    api(libs.retrofit.converter.scalars)
    api(platform(libs.okhttp.bom))
    api(libs.okhttp)
    api(libs.okhttp.logging)
}

val generatedDir: Provider<Directory> = layout.buildDirectory.dir("generated/api")

/**
 * 生成器 7.23.0 的已知缺陷：multipart 内联枚举带 default 时，参数类型出成 `kotlin.String?`，
 * 默认值却写成 `<内联枚举名>.<常量>` —— 那个枚举类根本没被生成 → 编译期 Unresolved reference。
 * 命中处：POST /cases/{id}/recordings 的 `source`（enum [APP_AUTO, MANUAL], default MANUAL）。
 *
 * 下面的补丁把 `= Source.MANUAL` 改回字面量 `= "MANUAL"`，语义等价（wire 上就是这个字符串）。
 * 用「精确期望集合」而非无脑正则替换：契约再冒出新的同类默认值、或生成器上游修好了，
 * 都会让集合不等而 **构建失败**，逼人来看一眼，不许悄悄漂移。
 */
val expectedBadDefaults: Set<String> = setOf("Source.MANUAL")

val generateApiClient by tasks.registering(JavaExec::class) {
    group = "openapi"
    description = "从契约 SSOT 生成 Kotlin/Retrofit 客户端（openapi-generator $generatorVersion）"

    inputs.file(contractFile).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(openapitoolsJson).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("generatorVersion", generatorVersion)
    inputs.property("expectedBadDefaults", expectedBadDefaults.sorted().joinToString(","))
    outputs.dir(generatedDir)

    classpath = openapiCli
    mainClass.set("org.openapitools.codegen.OpenAPIGenerator")

    argumentProviders.add {
        listOf(
            "generate",
            "-i", contractFile.absolutePath,
            "-g", "kotlin",
            "--library", "jvm-retrofit2",
            "-o", generatedDir.get().asFile.absolutePath,
            "--additional-properties=useCoroutines=true," +
                "serializationLibrary=kotlinx_serialization," +
                "packageName=com.youzheng.huicui.app.api",
        )
    }

    doFirst { delete(generatedDir) }

    doLast {
        val apisDir = generatedDir.get().asFile.resolve("src/main/kotlin/com/youzheng/huicui/app/api/apis")
        require(apisDir.isDirectory) { "生成失败：未产出 apis 目录 ${apisDir.path}" }

        val badDefault = Regex("= ([A-Z][A-Za-z0-9]*\\.[A-Z][A-Z0-9_]*)")
        val found = linkedSetOf<String>()
        apisDir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val src = f.readText()
            badDefault.findAll(src).forEach { found += it.groupValues[1] }
            val fixed = badDefault.replace(src) { m -> "= \"" + m.groupValues[1].substringAfterLast('.') + "\"" }
            if (fixed != src) f.writeText(fixed)
        }

        if (found != expectedBadDefaults) {
            throw GradleException(
                """
                |契约生成物的「坏默认值」集合发生变化，需人工确认后更新 api-client/build.gradle.kts 的 expectedBadDefaults：
                |  期望: ${expectedBadDefaults.sorted()}
                |  实际: ${found.sorted()}
                |若为契约新增的 multipart 内联枚举默认值 → 加进期望集合即可；
                |若变空 → 生成器上游已修复，删掉该补丁与本校验。
                """.trimMargin()
            )
        }
        logger.lifecycle("openapi-generator $generatorVersion：已修补 ${found.size} 处生成器缺陷 $found")
    }
}

kotlin {
    jvmToolchain(17)   // 与 :app 的 jvmTarget 对齐（Android 侧不用 21）
    sourceSets["main"].kotlin.srcDir(generatedDir.map { it.dir("src/main/kotlin") })
}

tasks.named("compileKotlin") { dependsOn(generateApiClient) }
