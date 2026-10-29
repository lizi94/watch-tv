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
            configureFinalizer(project, android, components, variant, apkArtifact)
            configureReleaseExport(project, variant, device)
        }
    }

    static String otherAbi(String abi) {
        return ABI_PAIRS[abi]
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
        variant.outputs.each { output ->
            def abi = output.filters.find { it.filterType.name() == 'ABI' }?.identifier?.replace('-', '_') ?: 'universal'
            output.outputFileName.set("${device}-${abi}.apk")
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

    private static void configureReleaseExport(Project project, def variant, String device) {
        def taskName = "assemble${variant.name.capitalize()}"
        def apkDirectory = project.layout.buildDirectory.dir("outputs/apk/${device}/release").get().asFile
        // ⛔ 出包目录必须可配置：本文件在 repo 与 repo-autostart 里各有一份，而两边的
        //    rootProject.file('../dist') 解析到的是同一个 FongMi-TV/dist —— 谁后构建谁覆盖谁
        //    （2026-10-29 实测：分支出包把主线 dist 换成了含功能版）。改由各仓 gradle.properties
        //    的 fongmi.distDir 决定（主线 ../dist、分支 ../dist-autostart），未配置时才回退旧默认值。
        def distDir = project.rootProject.findProperty('fongmi.distDir') ?: '../dist'
        def target = project.rootProject.file(distDir.toString())
        project.tasks.matching { it.name == taskName }.configureEach {
            doLast {
                project.copy {
                    from project.fileTree(dir: apkDirectory, include: "${device}-*.apk")
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
