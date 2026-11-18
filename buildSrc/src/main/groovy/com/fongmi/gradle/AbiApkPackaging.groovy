package com.fongmi.gradle

import org.gradle.api.Project

class AbiApkPackaging {

    private static final Map<String, String> ABI_PAIRS = ['arm64-v8a': 'armeabi-v7a', 'armeabi-v7a': 'arm64-v8a'].asImmutable()

    static void configure(Project project, Object apkArtifact) {
        def android = project.extensions.getByName('android')
        def components = project.extensions.getByName('androidComponents')
        configureAbis(android)
        components.onVariants(components.selector().withBuildType('release')) { variant ->
            def device = configureOutputFileNames(variant)
            def prefix = kebab(device)
            configureFinalizer(project, android, components, variant, apkArtifact)
            configureReleaseExport(project, variant, device, prefix)
        }
    }

    static String otherAbi(String abi) {
        return ABI_PAIRS[abi]
    }

    /**
     * camelCase flavor 名 → kebab-case 前缀：{@code leanbackHome → leanback-home}。
     *
     * <p>只用于<b>文件名前缀</b>与<b>归档目录名兜底</b>；{@code build/outputs/apk/} 下的中间目录
     * 必须继续用 flavor 原名（AGP 自己按 flavor 原文建目录，改了会找不到产物）。
     * 标准树的两个 flavor（{@code mobile} / {@code leanback}）不含大写字母，转换结果与原值相同，
     * 所以这次收敛对标准树**零行为变化**。</p>
     */
    static String kebab(String name) {
        return name.replaceAll(/([a-z0-9])([A-Z])/, '$1-$2').toLowerCase(Locale.ROOT)
    }

    private static void configureAbis(def android) {
        android.splits.abi {
            enable = true
            reset()
            include(*ABI_PAIRS.keySet().toList())
            universalApk = false
        }
    }

    private static String configureOutputFileNames(def variant) {
        def flavors = variant.productFlavors.collectEntries { [(it.first): it.second] }
        def device = flavors['device'] ?: 'device'
        def prefix = kebab(device)
        variant.outputs.each { output ->
            def abi = output.filters.find { it.filterType.name() == 'ABI' }?.identifier?.replace('-', '_') ?: 'universal'
            output.outputFileName.set("${prefix}-${abi}.apk")
        }
        return device
    }

    private static void configureFinalizer(Project project, def android, def components, def variant, Object apkArtifact) {
        def windows = System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('windows')
        def buildToolsDir = components.sdkComponents.sdkDirectory.get().dir("build-tools/${android.buildToolsVersion}").asFile
        def signingConfig = android.signingConfigs.release
        def finalizeTask = project.tasks.register("finalize${variant.name.capitalize()}Apks", FinalizeApksTask) { task ->
            task.zipalignFile.set(new File(buildToolsDir, windows ? 'zipalign.exe' : 'zipalign'))
            task.apksignerJar.set(new File(buildToolsDir, 'lib/apksigner.jar'))
            task.javaExecutable.set(new File(System.getProperty('java.home'), windows ? 'bin/java.exe' : 'bin/java'))
            task.signingStoreFile.set(signingConfig.storeFile)
            task.keyAlias.set(signingConfig.keyAlias)
            task.storePassword.set(signingConfig.storePassword)
            task.keyPassword.set(signingConfig.keyPassword)
        }
        def request = variant.artifacts.use(finalizeTask)
                .wiredWithDirectories({ task -> task.inputDirectory }, { task -> task.outputDirectory })
                .toTransformMany(apkArtifact)
        finalizeTask.configure { task ->
            task.transformationRequest.set(request)
        }
    }

    /**
     * flavor 前缀（kebab）→ 归档子目录名（R152 / R153）。
     *
     * <p><b>为什么必须一 flavor 一目录</b>：本文件在 repo 与 repo-autostart 里各有一份，两边的
     * {@code rootProject.file('../dist')} 解析到的是同一个 {@code FongMi-TV/dist} ——
     * 谁后构建谁覆盖谁（2026-10-29 实测：分支出包把主线 dist 换成了含功能版；
     * 2026-11-15 又一次：自启版把主线 644 覆盖成 645）。
     * 现在两棵树的 {@code gradle.properties} 都写同一个 {@code fongmi.distDir=../dist}，
     * 靠这里的映射表按 flavor 分子目录，四个变体各归各位，**结构性杜绝互相覆盖**。</p>
     *
     * <p>只列「目录名 ≠ flavor 名」的条目；其余<b>兜底用 kebab 后的 flavor 名当目录名</b>
     * （{@code leanbackHome → leanback-home}、{@code leanbackGuard → leanback-guard} 正好就是要的名字）。
     * 兜底比穷举安全：将来新增 {@code leanbackXxx} 时即使忘了加表，也只会落到 {@code leanback-xxx}，
     * 不会退回同一个 dist 根目录。</p>
     */
    private static final Map<String, String> DIST_FOLDERS = [
            'mobile'  : 'mobile-standard',
            'leanback': 'leanback-standard',
    ].asImmutable()

    /**
     * @param device flavor 原名 —— 用于定位 {@code build/outputs/apk/<flavor>/release}
     * @param prefix kebab 前缀 —— 用于匹配产物文件名与决定归档子目录
     */
    private static void configureReleaseExport(Project project, def variant, String device, String prefix) {
        def taskName = "assemble${variant.name.capitalize()}"
        def apkDirectory = project.layout.buildDirectory.dir("outputs/apk/${device}/release").get().asFile
        def baseDir = project.rootProject.findProperty('fongmi.distDir') ?: '../dist'
        def folder = DIST_FOLDERS[prefix] ?: prefix
        def target = project.rootProject.file("${baseDir}/${folder}")
        project.tasks.matching { it.name == taskName }.configureEach {
            doLast {
                project.copy {
                    from project.fileTree(dir: apkDirectory, include: "${prefix}-*.apk")
                    into target
                    eachFile { it.path = it.name }
                    includeEmptyDirs = false
                }
                // 2026-10-29（R99）：出包后**就地重算** MD5.txt，替代过去的手工生成。
                writeMd5(target)
            }
        }
        // 独立任务：不跑完整构建也能刷新校验清单（`./gradlew writeDistMd5`），方便手工核对。
        if (project.tasks.findByName('writeDistMd5') == null) {
            project.tasks.register('writeDistMd5') {
                group = 'build'
                description = '按 dist 目录里现有的 apk 重算 MD5.txt'
                doLast { writeMd5(target) }
            }
        }
    }

    /**
     * 按目录里**当前实际存在**的 apk 重算 MD5.txt（md5sum 兼容格式：`<hex> *<文件名>`）。
     *
     * <p>为什么必须在构建里做：本项目「判包只看 MD5」，而 MD5.txt 过去是手工写的 ——
     * 只要构建一次，APK 换了、清单一字没动，比「没有清单」更危险。
     * 只统计目录里真实存在的 apk，所以「只重建 leanback」时不会把另一个 flavor 的条目抹掉。</p>
     */
    private static void writeMd5(File dir) {
        if (dir == null || !dir.isDirectory()) return
        def apks = dir.listFiles()?.findAll { it.isFile() && it.name.toLowerCase(Locale.ROOT).endsWith('.apk') }
        if (apks == null || apks.isEmpty()) return
        apks.sort { it.name }
        def lines = apks.collect { f ->
            def digest = java.security.MessageDigest.getInstance('MD5')
            f.withInputStream { input ->
                byte[] buf = new byte[65536]
                int n
                while ((n = input.read(buf)) > 0) digest.update(buf, 0, n)
            }
            "${digest.digest().encodeHex()} *${f.name}"
        }
        new File(dir, 'MD5.txt').setText(lines.join('\n') + '\n', 'UTF-8')
    }
}
