package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.model.ZipProjectInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ZipProjectHelper {

    fun parseZipFromUri(context: Context, uri: Uri): ZipProjectInfo {
        val fileName = queryFileName(context, uri) ?: "projeto-android.zip"
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Não foi possível ler o arquivo ZIP selecionado.")
        return inspectZipBytes(fileName, rawBytes)
    }

    fun inspectZipBytes(fileName: String, rawBytes: ByteArray): ZipProjectInfo {
        val sanitizedBytes = ZipSanitizer.sanitizeZipForGradle9(rawBytes)
        var totalFiles = 0
        var kotlinCount = 0
        var javaCount = 0
        var xmlCount = 0
        var gradleCount = 0
        var hasGradleWrapper = false
        var hasManifestOrGradle = false
        var detectedPackage = "com.android.app"
        var detectedAppName = fileName.removeSuffix(".zip").replace("-", " ").replace("_", " ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        var minSdk = 24
        var targetSdk = 35
        val entryPaths = mutableListOf<String>()

        ZipInputStream(ByteArrayInputStream(sanitizedBytes)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    totalFiles++
                    if (entryPaths.size < 45) {
                        entryPaths.add(name)
                    }
                    when {
                        name.endsWith(".kt") -> kotlinCount++
                        name.endsWith(".java") -> javaCount++
                        name.endsWith(".xml") -> xmlCount++
                        name.endsWith(".gradle") || name.endsWith(".gradle.kts") || name.endsWith(".toml") -> gradleCount++
                    }
                    if (name.endsWith("gradlew") || name.contains("gradle-wrapper.properties")) {
                        hasGradleWrapper = true
                    }

                    if (name.endsWith("build.gradle.kts") || name.endsWith("build.gradle") || name.endsWith("AndroidManifest.xml")) {
                        hasManifestOrGradle = true
                        val content = readSmallTextEntry(zis)
                        val pkgMatch = Regex("""(?:applicationId|namespace|package)\s*[=:]?\s*["']([a-zA-Z0-9_.]+)["']""")
                            .find(content)
                        if (pkgMatch != null) {
                            detectedPackage = pkgMatch.groupValues[1]
                        }
                        val minSdkMatch = Regex("""minSdk(?:Version)?\s*[=:]?\s*(\d+)""").find(content)
                        if (minSdkMatch != null) {
                            minSdk = minSdkMatch.groupValues[1].toIntOrNull() ?: 24
                        }
                        val targetSdkMatch = Regex("""targetSdk(?:Version)?\s*[=:]?\s*(\d+)""").find(content)
                        if (targetSdkMatch != null) {
                            targetSdk = targetSdkMatch.groupValues[1].toIntOrNull() ?: 35
                        }
                    } else if (name.endsWith("strings.xml")) {
                        val content = readSmallTextEntry(zis)
                        val appNameMatch = Regex("""<string\s+name="app_name">([^<]+)</string>""").find(content)
                        if (appNameMatch != null) {
                            detectedAppName = appNameMatch.groupValues[1]
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        if (totalFiles == 0) {
            throw IllegalArgumentException("O arquivo selecionado não é um arquivo ZIP válido ou está vazio.")
        }

        if (!hasManifestOrGradle) {
            throw IllegalArgumentException(
                "Projeto Android incompatível ou incompleto: nenhum arquivo AndroidManifest.xml ou build.gradle(.kts) encontrado no ZIP."
            )
        }

        val workflowYaml = buildGitHubActionsWorkflowYaml(detectedAppName)

        return ZipProjectInfo(
            fileName = fileName,
            fileSizeBytes = sanitizedBytes.size.toLong(),
            totalFiles = totalFiles,
            kotlinCount = kotlinCount,
            javaCount = javaCount,
            xmlCount = xmlCount,
            gradleCount = gradleCount,
            detectedPackageName = detectedPackage,
            detectedAppName = detectedAppName,
            minSdk = minSdk,
            targetSdk = targetSdk,
            hasGradleWrapper = hasGradleWrapper,
            entryPaths = entryPaths,
            generatedWorkflowYaml = workflowYaml,
            rawZipBytes = sanitizedBytes
        )
    }

    /**
     * Gera o workflow único de compilação EXCLUSIVAMENTE em modo Release (`assembleRelease`).
     * - Nunca executa `assembleDebug`.
     * - Remove qualquer workflow adicional que porventura tenha sido descompactado no runner.
     * - Gera keystore de Release e assina automaticamente o APK final (`apksigner` / `signingConfig`)
     *   para que o APK Release esteja 100% pronto para instalação e distribuição.
     */
    fun buildGitHubActionsWorkflowYaml(appName: String): String {
        val cleanTitle = appName.replace(Regex("""["'\r\n]"""), "").trim().ifEmpty { "Projeto Android" }
        return """
name: GERA APK • Release ($cleanTitle)

on:
  push:
    branches:
      - main
      - master
  workflow_dispatch:

concurrency:
  group: gera-apk-release-${'$'}{{ github.repository }}
  cancel-in-progress: true

jobs:
  compilar-apk-release:
    name: Compilar APK Release ($cleanTitle)
    runs-on: ubuntu-latest
    env:
      GRADLE_OPTS: "-Dorg.gradle.jvmargs=-Xmx4g -Dorg.gradle.daemon=true -Dorg.gradle.parallel=true -Dorg.gradle.caching=true -Dkotlin.Incremental=true"
      KEYSTORE_PATH: "${'$'}{{ github.workspace }}/my-upload-key.jks"
      STORE_PASSWORD: "geraapkrelease2026"
      KEY_PASSWORD: "geraapkrelease2026"
    steps:
      - name: Checkout do Código-Fonte
        uses: actions/checkout@v4
        with:
          fetch-depth: 1

      - name: Preparar Ambiente e Assinatura Release
        run: |
          if [ -f "uploads/project.zip" ] && [ ! -f "settings.gradle.kts" ] && [ ! -f "settings.gradle" ]; then
            unzip -o uploads/project.zip -d .
          fi
          if [ ! -f "settings.gradle" ] && [ ! -f "settings.gradle.kts" ]; then
            SUBDIR=${'$'}(find . -maxdepth 2 -name "settings.gradle*" -printf "%h\n" | head -n 1)
            if [ -n "${'$'}SUBDIR" ] && [ "${'$'}SUBDIR" != "." ]; then
              shopt -s dotglob
              mv "${'$'}SUBDIR"/* . || true
            fi
          fi
          # Remove qualquer workflow extra descompactado para garantir execução única
          find .github/workflows -type f ! -name "gera-apk-build.yml" -delete 2>/dev/null || true
          mkdir -p gradle/wrapper
          cat << 'EOF' > gradle/wrapper/gradle-wrapper.properties
          distributionBase=GRADLE_USER_HOME
          distributionPath=wrapper/dists
          distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-bin.zip
          networkTimeout=10000
          validateDistributionUrl=true
          zipStoreBase=GRADLE_USER_HOME
          zipStorePath=wrapper/dists
          EOF
          if [ ! -f ".env.example" ]; then
            echo "GEMINI_API_KEY=PLACEHOLDER" > .env.example
          fi
          if [ ! -f "my-upload-key.jks" ]; then
            keytool -genkeypair -v -keystore my-upload-key.jks -storepass "geraapkrelease2026" -alias upload -keypass "geraapkrelease2026" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=GERA APK Release,O=GERA APK,C=BR"
          fi

      - name: Configurar JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Configurar Gradle 9.3.1 com Cache
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '9.3.1'
          cache-read-only: false

      - name: Compilar Aplicativo (assembleRelease)
        run: |
          gradle assembleRelease --parallel --build-cache --configuration-cache -x lint -x lintVitalRelease -x test

      - name: Assinar e Preparar APK Release Final
        run: |
          mkdir -p release-output
          RAW_APK=${'$'}(find . -path "*/build/outputs/apk/release/*.apk" | head -n 1)
          if [ -z "${'$'}RAW_APK" ]; then
            echo "Nenhum APK Release gerado pelo Gradle!"
            exit 1
          fi
          if [[ "${'$'}RAW_APK" == *"-unsigned.apk" ]]; then
            BUILD_TOOLS=${'$'}(ls -d ${'$'}ANDROID_HOME/build-tools/* | sort -V | tail -n 1)
            "${'$'}BUILD_TOOLS/zipalign" -v -p 4 "${'$'}RAW_APK" release-output/aligned.apk
            "${'$'}BUILD_TOOLS/apksigner" sign --ks my-upload-key.jks --ks-key-alias upload --ks-pass pass:geraapkrelease2026 --key-pass pass:geraapkrelease2026 --out release-output/app-release.apk release-output/aligned.apk
            rm -f release-output/aligned.apk
          else
            cp "${'$'}RAW_APK" release-output/app-release.apk
          fi

      - name: Publicar Artefato APK Release
        uses: actions/upload-artifact@v4
        with:
          name: compiled-apk-release
          path: "release-output/app-release.apk"
          compression-level: 0
          if-no-files-found: error
        """.trimIndent()
    }

    fun saveExtractedArtifactApk(
        context: Context,
        buildId: Long,
        appName: String,
        extractedApkBytes: ByteArray
    ): File {
        val apkDir = File(context.filesDir, "apks").apply { mkdirs() }
        val sanitizedName = appName
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "android-app" }
        val apkFile = File(apkDir, "${sanitizedName}-release-${buildId}.apk")
        apkFile.writeBytes(extractedApkBytes)
        return apkFile
    }

    fun extractApkFromArtifactZip(artifactZipBytes: ByteArray): ByteArray? {
        ZipInputStream(ByteArrayInputStream(artifactZipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                    return zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }

    private fun readSmallTextEntry(zis: ZipInputStream): String {
        val buffer = ByteArray(16384)
        val out = ByteArrayOutputStream()
        var read: Int
        var total = 0
        while (zis.read(buffer).also { read = it } != -1 && total < 65536) {
            out.write(buffer, 0, read)
            total += read
        }
        return out.toString("UTF-8")
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name ?: uri.lastPathSegment
    }
}
