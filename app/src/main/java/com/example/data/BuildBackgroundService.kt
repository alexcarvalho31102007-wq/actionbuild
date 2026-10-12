package com.example.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.example.BuildConfig
import com.example.MainActivity
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BuildBackgroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTasks = ConcurrentHashMap<Long, Boolean>()

    private val database by lazy { AppDatabase.getInstance(applicationContext) }
    private val dao by lazy { database.buildRecordDao() }
    private val supabaseRepo by lazy { SupabaseRepository(applicationContext) }

    private val gitHubApi: GitHubApiService by lazy {
        val moshi = Moshi.Builder().build()
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build()
                chain.proceed(req)
            }
            .build()
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(okHttp)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GitHubApiService::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Processando compilação Release no GitHub Actions..."))

        val recordId = intent?.getLongExtra(EXTRA_RECORD_ID, -1L) ?: -1L
        val tokenOverride = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        val isRecompile = intent?.getBooleanExtra(EXTRA_RECOMPILE, false) ?: false
        val supabaseAccessToken = intent?.getStringExtra(EXTRA_SUPABASE_ACCESS_TOKEN).orEmpty()
        val supabaseUrl = intent?.getStringExtra(EXTRA_SUPABASE_URL).orEmpty()
        val supabaseKey = intent?.getStringExtra(EXTRA_SUPABASE_KEY).orEmpty()

        serviceScope.launch {
            if (recordId > 0L) {
                launchBuildTaskIfNeeded(
                    recordId = recordId,
                    tokenOverride = tokenOverride,
                    isRecompile = isRecompile,
                    supabaseAccessToken = supabaseAccessToken,
                    supabaseUrl = supabaseUrl,
                    supabaseKey = supabaseKey
                )
            } else {
                val activeRecords = dao.getActiveBuilds()
                for (rec in activeRecords) {
                    launchBuildTaskIfNeeded(
                        recordId = rec.id,
                        tokenOverride = tokenOverride,
                        isRecompile = false,
                        supabaseAccessToken = supabaseAccessToken,
                        supabaseUrl = supabaseUrl,
                        supabaseKey = supabaseKey
                    )
                }
                if (activeTasks.isEmpty()) {
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private fun launchBuildTaskIfNeeded(
        recordId: Long,
        tokenOverride: String,
        isRecompile: Boolean,
        supabaseAccessToken: String,
        supabaseUrl: String,
        supabaseKey: String
    ) {
        if (activeTasks.putIfAbsent(recordId, true) != null) return
        serviceScope.launch {
            try {
                executeRealGitHubActionsBuild(
                    recordId = recordId,
                    tokenOverride = tokenOverride,
                    isRecompile = isRecompile,
                    supabaseAccessToken = supabaseAccessToken,
                    supabaseUrl = supabaseUrl,
                    supabaseKey = supabaseKey
                )
            } finally {
                activeTasks.remove(recordId)
                if (activeTasks.isEmpty()) {
                    stopSelf()
                }
            }
        }
    }

    private suspend fun executeRealGitHubActionsBuild(
        recordId: Long,
        tokenOverride: String,
        isRecompile: Boolean,
        supabaseAccessToken: String,
        supabaseUrl: String,
        supabaseKey: String
    ) {
        var record = dao.getRecordById(recordId) ?: return
        val token = tokenOverride.trim().ifEmpty {
            BuildConfig.GITHUB_TOKEN.trim().takeIf {
                it.isNotEmpty() && it != "YOUR_GITHUB_PAT_HERE" && it != "null"
            }.orEmpty()
        }

        if (token.isEmpty()) {
            failBuild(
                record = record,
                errorSummary = "Token do GitHub (GITHUB_TOKEN) não configurado. Configure seu Personal Access Token no painel Secrets ou no botão de chave.",
                logLine = "[ERRO] GITHUB_TOKEN ausente. Não é possível autenticar na API do GitHub Actions.",
                supabaseAccessToken = supabaseAccessToken,
                supabaseUrl = supabaseUrl,
                supabaseKey = supabaseKey
            )
            return
        }

        val authHeader = "Bearer $token"

        try {
            // Se não for recompilação forçada e já houver workflowRunId em andamento, retoma monitoramento
            if (!isRecompile && record.workflowRunId != null && record.repoFullName.contains("/")) {
                val parts = record.repoFullName.split("/")
                record = appendRecordLog(
                    record,
                    "[INFO] Retomando acompanhamento do Workflow Run Release #${record.workflowRunId}..."
                )
                monitorWorkflowAndFetchApk(
                    record = record,
                    authHeader = authHeader,
                    owner = parts[0],
                    repo = parts[1],
                    runId = record.workflowRunId!!,
                    supabaseAccessToken = supabaseAccessToken,
                    supabaseUrl = supabaseUrl,
                    supabaseKey = supabaseKey
                )
                return
            }

            // Recompilação direta de um projeto já existente no GitHub sem precisar enviar novo ZIP nem criar novo repositório
            val zipFile = File(record.localZipPath)
            if (isRecompile && !zipFile.exists() && record.repoFullName.contains("/")) {
                val parts = record.repoFullName.split("/")
                val owner = parts[0]
                val repoName = parts[1]
                val branch = record.branch.ifBlank { "main" }

                updateNotification("Enviando arquivos...")
                record = updateRecordState(
                    record = record,
                    status = BuildStatus.IN_PROGRESS,
                    progress = 0.25f,
                    stepMessage = "Enviando arquivos",
                    logLine = "[RECOMPILAÇÃO] Reutilizando repositório existente $owner/$repoName (branch: $branch)...",
                    supabaseAccessToken = supabaseAccessToken,
                    supabaseUrl = supabaseUrl,
                    supabaseKey = supabaseKey
                )

                val dispatchResp = gitHubApi.dispatchWorkflow(
                    authHeader = authHeader,
                    owner = owner,
                    repo = repoName,
                    workflowId = ZipSanitizer.SINGLE_WORKFLOW_FILENAME,
                    request = GitHubWorkflowDispatchRequest(ref = branch)
                )

                if (!dispatchResp.isSuccessful) {
                    // Caso workflow_dispatch ainda não esteja atualizado, cria um commit leve na árvore atual
                    val refResp = gitHubApi.getBranchRef(authHeader, owner, repoName, branch)
                    if (refResp.isSuccessful && refResp.body() != null) {
                        val headSha = refResp.body()!!.gitObject.sha
                        val commitInfo = gitHubApi.getGitCommit(authHeader, owner, repoName, headSha).body()
                        if (commitInfo != null) {
                            val newCommit = gitHubApi.createGitCommit(
                                authHeader = authHeader,
                                owner = owner,
                                repo = repoName,
                                request = GitCreateCommitRequest(
                                    message = "release: recompile ${record.appName} via GERA APK",
                                    treeSha = commitInfo.tree.sha,
                                    parents = listOf(headSha)
                                )
                            ).body()
                            if (newCommit != null) {
                                gitHubApi.updateBranchRef(
                                    authHeader = authHeader,
                                    owner = owner,
                                    repo = repoName,
                                    branch = branch,
                                    request = GitUpdateRefRequest(sha = newCommit.sha, force = true)
                                )
                            }
                        }
                    }
                }

                updateNotification("Preparando ambiente...")
                record = updateRecordState(
                    record = record,
                    status = BuildStatus.IN_PROGRESS,
                    progress = 0.45f,
                    stepMessage = "Preparando ambiente",
                    logLine = "[ETAPA 2/5] Aguardando início da compilação Release única no GitHub Actions...",
                    supabaseAccessToken = supabaseAccessToken,
                    supabaseUrl = supabaseUrl,
                    supabaseKey = supabaseKey
                )

                var matchedRun: GitHubWorkflowRun? = null
                for (attempt in 1..15) {
                    delay(3000)
                    val runsResp = gitHubApi.getWorkflowRuns(authHeader, owner, repoName, branch, perPage = 10)
                    if (runsResp.isSuccessful) {
                        val runs = runsResp.body()?.workflowRuns.orEmpty()
                        matchedRun = runs.firstOrNull { it.status != "completed" } ?: runs.firstOrNull()
                        if (matchedRun != null) break
                    }
                }

                if (matchedRun == null) {
                    failBuild(
                        record = record,
                        errorSummary = "Não foi possível iniciar a recompilação no repositório $owner/$repoName.",
                        logLine = "[ERRO] Nenhum workflow_run encontrado para recompilação.",
                        supabaseAccessToken = supabaseAccessToken,
                        supabaseUrl = supabaseUrl,
                        supabaseKey = supabaseKey
                    )
                    return
                }

                record = record.copy(
                    workflowRunId = matchedRun.id,
                    workflowHtmlUrl = matchedRun.htmlUrl,
                    updatedAtMillis = System.currentTimeMillis()
                )
                dao.updateRecord(record)
                supabaseRepo.pushBuildRecordToSupabase(record, supabaseAccessToken, supabaseUrl, supabaseKey)

                monitorWorkflowAndFetchApk(
                    record = record,
                    authHeader = authHeader,
                    owner = owner,
                    repo = repoName,
                    runId = matchedRun.id,
                    supabaseAccessToken = supabaseAccessToken,
                    supabaseUrl = supabaseUrl,
                    supabaseKey = supabaseKey
                )
                return
            }

            // 1. Validar arquivo ZIP salvo no armazenamento interno
            if (!zipFile.exists()) {
                failBuild(
                    record = record,
                    errorSummary = "O arquivo ZIP (${record.zipFileName}) não foi encontrado no armazenamento local.",
                    logLine = "[ERRO] Arquivo local ${record.localZipPath} não encontrado.",
                    supabaseAccessToken = supabaseAccessToken,
                    supabaseUrl = supabaseUrl,
                    supabaseKey = supabaseKey
                )
                return
            }

            updateNotification("Enviando arquivos...")
            record = updateRecordState(
                record = record,
                status = BuildStatus.IN_PROGRESS,
                progress = 0.12f,
                stepMessage = "Enviando arquivos",
                logLine = "[ETAPA 1/5] Validando arquivo ZIP para compilação Release: ${record.zipFileName}",
                supabaseAccessToken = supabaseAccessToken,
                supabaseUrl = supabaseUrl,
                supabaseKey = supabaseKey
            )

            val projectInfo = try {
                ZipProjectHelper.inspectZipBytes(record.zipFileName, zipFile.readBytes())
            } catch (e: Exception) {
                failBuild(
                    record = record,
                    errorSummary = e.localizedMessage ?: "Arquivo ZIP inválido ou projeto Android incompleto.",
                    logLine = "[ERRO DE ESTRUTURA] ${e.localizedMessage}",
                    supabaseAccessToken = supabaseAccessToken,
                    supabaseUrl = supabaseUrl,
                    supabaseKey = supabaseKey
                )
                return
            }

            // 2. Autenticar no GitHub e reutilizar/criar repositório automaticamente
            updateNotification("Enviando arquivos...")
            record = updateRecordState(
                record = record,
                status = BuildStatus.IN_PROGRESS,
                progress = 0.18f,
                stepMessage = "Enviando arquivos",
                logLine = "[ETAPA 1/5] Autenticando na API do GitHub...",
                supabaseAccessToken = supabaseAccessToken,
                supabaseUrl = supabaseUrl,
                supabaseKey = supabaseKey
            )

            val userResp = gitHubApi.getAuthenticatedUser(authHeader)
            if (!userResp.isSuccessful || userResp.body() == null) {
                val errBody = userResp.errorBody()?.string().orEmpty()
                failBuild(
                    record = record,
                    errorSummary = "Falha de autenticação no GitHub (HTTP ${userResp.code()}). Verifique seu GITHUB_TOKEN.",
                    logLine = "[ERRO HTTP ${userResp.code()}] GET /user falhou: $errBody",
                    supabaseAccessToken = supabaseAccessToken,
                    supabaseUrl = supabaseUrl,
                    supabaseKey = supabaseKey
                )
                return
            }

            val username = userResp.body()!!.login
            // Reutiliza o repositório existente se já estiver registrado, sem criar outro!
            val autoRepoName = if (record.repoFullName.contains("/")) {
                record.repoFullName.substringAfter("/")
            } else {
                sanitizeZipNameToRepoName(record.zipFileName)
            }
            val fullRepoName = "$username/$autoRepoName"

            var targetBranch = "main"
            val existingRepoResp = gitHubApi.getRepository(authHeader, username, autoRepoName)
            if (existingRepoResp.isSuccessful && existingRepoResp.body() != null) {
                targetBranch = existingRepoResp.body()!!.defaultBranch ?: "main"
                record = appendRecordLog(record, "[OK] Reutilizando repositório existente: $fullRepoName (branch: $targetBranch)")
            } else {
                record = appendRecordLog(record, "[INFO] Criando repositório '$autoRepoName' na conta @$username...")
                val createResp = gitHubApi.createRepository(
                    authHeader = authHeader,
                    request = GitHubCreateRepoRequest(
                        name = autoRepoName,
                        isPrivate = false,
                        autoInit = true
                    )
                )
                if (!createResp.isSuccessful || createResp.body() == null) {
                    val errBody = createResp.errorBody()?.string().orEmpty()
                    failBuild(
                        record = record,
                        errorSummary = "Não foi possível criar o repositório '$autoRepoName' no GitHub (HTTP ${createResp.code()}).",
                        logLine = "[ERRO HTTP ${createResp.code()}] POST /user/repos falhou: $errBody",
                        supabaseAccessToken = supabaseAccessToken,
                        supabaseUrl = supabaseUrl,
                        supabaseKey = supabaseKey
                    )
                    return
                }
                targetBranch = createResp.body()!!.defaultBranch ?: "main"
                delay(1500)
            }

            record = record.copy(
                appName = projectInfo.detectedAppName,
                packageName = projectInfo.detectedPackageName,
                repoFullName = fullRepoName,
                branch = targetBranch,
                updatedAtMillis = System.currentTimeMillis()
            )
            dao.updateRecord(record)
            supabaseRepo.pushBuildRecordToSupabase(record, supabaseAccessToken, supabaseUrl, supabaseKey)

            // 3. Enviar todos os arquivos normalizados + ÚNICO Workflow Release para o repositório
            updateNotification("Enviando arquivos...")
            record = updateRecordState(
                record = record,
                status = BuildStatus.IN_PROGRESS,
                progress = 0.25f,
                stepMessage = "Enviando arquivos",
                logLine = "[ETAPA 1/5] Enviando arquivos para $fullRepoName (exclusivamente 1 workflow Release)...",
                supabaseAccessToken = supabaseAccessToken,
                supabaseUrl = supabaseUrl,
                supabaseKey = supabaseKey
            )

            val normalizedFiles = ZipSanitizer.extractNormalizedFiles(projectInfo.rawZipBytes).toMutableMap()
            // Remove rigorosamente qualquer arquivo dentro de .github/ ou workflows/ para evitar workflows duplicados ("Android Builder (Home UI)" e "Build APK")
            normalizedFiles.keys.removeAll { ZipSanitizer.isAnyGitHubWorkflowOrFolder(it) }
            normalizedFiles[ZipSanitizer.SINGLE_WORKFLOW_PATH] =
                projectInfo.generatedWorkflowYaml.toByteArray(Charsets.UTF_8)

            val refResp = gitHubApi.getBranchRef(authHeader, username, autoRepoName, targetBranch)
            if (!refResp.isSuccessful || refResp.body() == null) {
                val errBody = refResp.errorBody()?.string().orEmpty()
                failBuild(
                    record = record,
                    errorSummary = "Não foi possível obter a referência da branch '$targetBranch' em $fullRepoName (HTTP ${refResp.code()}).",
                    logLine = "[ERRO HTTP ${refResp.code()}] GET /git/ref/heads/$targetBranch: $errBody",
                    supabaseAccessToken = supabaseAccessToken,
                    supabaseUrl = supabaseUrl,
                    supabaseKey = supabaseKey
                )
                return
            }

            val latestCommitSha = refResp.body()!!.gitObject.sha

            val totalFilesToUpload = normalizedFiles.size
            val uploadedCounter = AtomicInteger(0)
            val uploadSemaphore = Semaphore(8)

            val treeResults = withContext(Dispatchers.IO) {
                normalizedFiles.entries.map { (filePath, fileBytes) ->
                    async {
                        uploadSemaphore.withPermit {
                            val b64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP)
                            val blobResp = gitHubApi.createGitBlob(
                                authHeader = authHeader,
                                owner = username,
                                repo = autoRepoName,
                                request = GitCreateBlobRequest(content = b64)
                            )
                            if (!blobResp.isSuccessful || blobResp.body() == null) {
                                val errBody = blobResp.errorBody()?.string().orEmpty()
                                throw IllegalStateException("Falha ao enviar '$filePath' (HTTP ${blobResp.code()}): $errBody")
                            }
                            val done = uploadedCounter.incrementAndGet()
                            if (done % 8 == 0 || done == totalFilesToUpload) {
                                val uploadProgress = 0.25f + (0.12f * (done.toFloat() / totalFilesToUpload.coerceAtLeast(1)))
                                val progressRec = record.copy(
                                    progress = uploadProgress,
                                    statusStepMessage = "Enviando arquivos",
                                    updatedAtMillis = System.currentTimeMillis()
                                )
                                dao.updateRecord(progressRec)
                            }
                            val mode = if (filePath == "gradlew") "100755" else "100644"
                            GitTreeEntry(
                                path = filePath,
                                mode = mode,
                                type = "blob",
                                sha = blobResp.body()!!.sha
                            )
                        }
                    }
                }.awaitAll()
            }
            val treeEntries = treeResults.toList()

            // baseTree = null substitui integralmente a árvore do repositório, eliminando qualquer workflow antigo!
            val treeResp = gitHubApi.createGitTree(
                authHeader = authHeader,
                owner = username,
                repo = autoRepoName,
                request = GitCreateTreeRequest(baseTree = null, tree = treeEntries)
            )
            if (!treeResp.isSuccessful || treeResp.body() == null) {
                val errBody = treeResp.errorBody()?.string().orEmpty()
                failBuild(
                    record = record,
                    errorSummary = "Erro ao criar árvore Git em $fullRepoName (HTTP ${treeResp.code()}).",
                    logLine = "[ERRO HTTP ${treeResp.code()}] POST /git/trees: $errBody",
                    supabaseAccessToken = supabaseAccessToken,
                    supabaseUrl = supabaseUrl,
                    supabaseKey = supabaseKey
                )
                return
            }

            val newCommitResp = gitHubApi.createGitCommit(
                authHeader = authHeader,
                owner = username,
                repo = autoRepoName,
                request = GitCreateCommitRequest(
                    message = "release: compile ${projectInfo.detectedAppName} via GERA APK",
                    treeSha = treeResp.body()!!.sha,
                    parents = listOf(latestCommitSha)
                )
            )
            if (!newCommitResp.isSuccessful || newCommitResp.body() == null) {
                val errBody = newCommitResp.errorBody()?.string().orEmpty()
                failBuild(
                    record = record,
                    errorSummary = "Erro ao registrar commit no GitHub (HTTP ${newCommitResp.code()}).",
                    logLine = "[ERRO HTTP ${newCommitResp.code()}] POST /git/commits: $errBody",
                    supabaseAccessToken = supabaseAccessToken,
                    supabaseUrl = supabaseUrl,
                    supabaseKey = supabaseKey
                )
                return
            }

            val pushedCommitSha = newCommitResp.body()!!.sha
            val updateRefResp = gitHubApi.updateBranchRef(
                authHeader = authHeader,
                owner = username,
                repo = autoRepoName,
                branch = targetBranch,
                request = GitUpdateRefRequest(sha = pushedCommitSha, force = true)
            )
            if (!updateRefResp.isSuccessful) {
                val errBody = updateRefResp.errorBody()?.string().orEmpty()
                failBuild(
                    record = record,
                    errorSummary = "Erro ao atualizar branch '$targetBranch' (HTTP ${updateRefResp.code()}). Verifique se o seu Token possui permissão 'workflow'.",
                    logLine = "[ERRO HTTP ${updateRefResp.code()}] PATCH /git/refs/heads/$targetBranch: $errBody",
                    supabaseAccessToken = supabaseAccessToken,
                    supabaseUrl = supabaseUrl,
                    supabaseKey = supabaseKey
                )
                return
            }

            // 4. Localizar o Workflow Run Release único e cancelar eventuais duplicatas residuais
            updateNotification("Preparando ambiente...")
            record = updateRecordState(
                record = record,
                status = BuildStatus.IN_PROGRESS,
                progress = 0.45f,
                stepMessage = "Preparando ambiente",
                logLine = "[ETAPA 2/5] Localizando compilação Release única para o commit ${pushedCommitSha.take(7)}...",
                supabaseAccessToken = supabaseAccessToken,
                supabaseUrl = supabaseUrl,
                supabaseKey = supabaseKey
            )

            var matchedRun: GitHubWorkflowRun? = null
            for (attempt in 1..15) {
                delay(3000)
                val runsResp = gitHubApi.getWorkflowRuns(authHeader, username, autoRepoName, targetBranch, perPage = 10)
                if (runsResp.isSuccessful) {
                    val runsForCommit = runsResp.body()?.workflowRuns.orEmpty()
                        .filter { it.headSha == pushedCommitSha }

                    if (runsForCommit.isNotEmpty()) {
                        // Seleciona o workflow oficial "GERA APK" e cancela imediatamente qualquer segundo workflow residual
                        val primaryRun = runsForCommit.firstOrNull {
                            it.name?.contains("GERA APK", ignoreCase = true) == true
                        } ?: runsForCommit.first()

                        runsForCommit.filter { it.id != primaryRun.id }.forEach { duplicateRun ->
                            try {
                                gitHubApi.cancelWorkflowRun(authHeader, username, autoRepoName, duplicateRun.id)
                            } catch (_: Exception) {
                            }
                        }

                        matchedRun = primaryRun
                        break
                    }
                }
            }

            if (matchedRun == null) {
                failBuild(
                    record = record,
                    errorSummary = "O GitHub Actions não iniciou um workflow para $fullRepoName.",
                    logLine = "[ERRO] Nenhum workflow_run encontrado em $fullRepoName.",
                    supabaseAccessToken = supabaseAccessToken,
                    supabaseUrl = supabaseUrl,
                    supabaseKey = supabaseKey
                )
                return
            }

            record = record.copy(
                workflowRunId = matchedRun.id,
                workflowHtmlUrl = matchedRun.htmlUrl,
                updatedAtMillis = System.currentTimeMillis()
            )
            dao.updateRecord(record)
            supabaseRepo.pushBuildRecordToSupabase(record, supabaseAccessToken, supabaseUrl, supabaseKey)

            // 5. Acompanhar em tempo real as etapas do Job Release no GitHub Actions até concluir
            monitorWorkflowAndFetchApk(
                record = record,
                authHeader = authHeader,
                owner = username,
                repo = autoRepoName,
                runId = matchedRun.id,
                supabaseAccessToken = supabaseAccessToken,
                supabaseUrl = supabaseUrl,
                supabaseKey = supabaseKey
            )
        } catch (e: Exception) {
            failBuild(
                record = record,
                errorSummary = "Erro durante a comunicação com o GitHub Actions: ${e.localizedMessage ?: e.javaClass.simpleName}",
                logLine = "[EXCEÇÃO] ${e.stackTraceToString().lineSequence().take(8).joinToString("\n")}",
                supabaseAccessToken = supabaseAccessToken,
                supabaseUrl = supabaseUrl,
                supabaseKey = supabaseKey
            )
        }
    }

    private suspend fun monitorWorkflowAndFetchApk(
        record: BuildRecordEntity,
        authHeader: String,
        owner: String,
        repo: String,
        runId: Long,
        supabaseAccessToken: String,
        supabaseUrl: String,
        supabaseKey: String
    ) {
        var currentRecord = record
        val loggedSteps = mutableSetOf<String>()
        var pollCount = 0

        while (pollCount < 300) {
            pollCount++
            val runResp = gitHubApi.getWorkflowRun(authHeader, owner, repo, runId)
            if (!runResp.isSuccessful || runResp.body() == null) {
                delay(4000)
                continue
            }

            val run = runResp.body()!!
            val runStatus = run.status ?: "in_progress"
            val runConclusion = run.conclusion

            val jobsResp = gitHubApi.getWorkflowRunJobs(authHeader, owner, repo, runId)
            val jobs = jobsResp.body()?.jobs.orEmpty()
            var cleanStageTitle = "Preparando ambiente"
            var mappedProgress = 0.50f

            for (job in jobs) {
                for (step in job.steps.orEmpty()) {
                    val stepKey = "${job.id}:${step.number}:${step.status}:${step.conclusion}"
                    if (step.status == "in_progress") {
                        when {
                            step.name.contains("assembleRelease", ignoreCase = true) ||
                                step.name.contains("Compilar Aplicativo", ignoreCase = true) ||
                                step.name.contains("Build", ignoreCase = true) -> {
                                cleanStageTitle = "Compilando aplicativo"
                                mappedProgress = 0.72f
                            }
                            step.name.contains("Assinar", ignoreCase = true) ||
                                step.name.contains("Publicar", ignoreCase = true) ||
                                step.name.contains("Upload", ignoreCase = true) ||
                                step.name.contains("Artifact", ignoreCase = true) -> {
                                cleanStageTitle = "Gerando APK"
                                mappedProgress = 0.88f
                            }
                            else -> {
                                cleanStageTitle = "Preparando ambiente"
                                mappedProgress = 0.55f
                            }
                        }
                        if (loggedSteps.add("${job.id}:${step.number}:in_progress")) {
                            currentRecord = appendRecordLog(
                                currentRecord,
                                "> Executando etapa #${step.number}: ${step.name}..."
                            )
                        }
                    } else if (step.status == "completed" && loggedSteps.add(stepKey)) {
                        val symbol = if (step.conclusion == "success") "[OK]" else "[${step.conclusion?.uppercase() ?: "DONE"}]"
                        currentRecord = appendRecordLog(
                            currentRecord,
                            "$symbol Etapa #${step.number} (${step.name}) -> ${step.conclusion ?: "concluída"}"
                        )
                    }
                }
            }

            updateNotification("${currentRecord.appName}: $cleanStageTitle")
            currentRecord = currentRecord.copy(
                status = BuildStatus.IN_PROGRESS,
                progress = mappedProgress,
                statusStepMessage = cleanStageTitle,
                updatedAtMillis = System.currentTimeMillis()
            )
            dao.updateRecord(currentRecord)
            if (pollCount % 3 == 0) {
                supabaseRepo.pushBuildRecordToSupabase(currentRecord, supabaseAccessToken, supabaseUrl, supabaseKey)
            }

            if (runStatus == "completed") {
                val firstJobId = jobs.firstOrNull()?.id
                val rawJobLogTail = if (firstJobId != null) {
                    fetchJobLogTail(authHeader, owner, repo, firstJobId)
                } else {
                    ""
                }

                if (runConclusion == "success") {
                    currentRecord = updateRecordState(
                        record = currentRecord,
                        status = BuildStatus.IN_PROGRESS,
                        progress = 0.90f,
                        stepMessage = "Gerando APK",
                        logLine = "[ETAPA 4/5] Workflow Release #$runId concluído com SUCESSO. Baixando APK Release...",
                        supabaseAccessToken = supabaseAccessToken,
                        supabaseUrl = supabaseUrl,
                        supabaseKey = supabaseKey
                    )

                    val artifactsResp = gitHubApi.getRunArtifacts(authHeader, owner, repo, runId)
                    val artifacts = artifactsResp.body()?.artifacts.orEmpty()
                    val targetArtifact = artifacts.firstOrNull {
                        it.name.contains("release", ignoreCase = true)
                    } ?: artifacts.firstOrNull()

                    if (targetArtifact == null) {
                        failBuild(
                            record = currentRecord,
                            errorSummary = "O workflow foi concluído no GitHub Actions, mas nenhum artefato APK Release foi encontrado.",
                            logLine = "[ERRO] Lista de artefatos vazia para o Run #$runId.",
                            supabaseAccessToken = supabaseAccessToken,
                            supabaseUrl = supabaseUrl,
                            supabaseKey = supabaseKey
                        )
                        return
                    }

                    val downloadResp = gitHubApi.downloadArtifactZip(authHeader, targetArtifact.archiveDownloadUrl)
                    if (!downloadResp.isSuccessful || downloadResp.body() == null) {
                        failBuild(
                            record = currentRecord,
                            errorSummary = "Falha ao baixar o artefato APK Release do GitHub Actions (HTTP ${downloadResp.code()}).",
                            logLine = "[ERRO HTTP ${downloadResp.code()}] Falha ao baixar archive_download_url.",
                            supabaseAccessToken = supabaseAccessToken,
                            supabaseUrl = supabaseUrl,
                            supabaseKey = supabaseKey
                        )
                        return
                    }

                    val artifactZipBytes = downloadResp.body()!!.bytes()
                    val extractedApkBytes = ZipProjectHelper.extractApkFromArtifactZip(artifactZipBytes)

                    if (extractedApkBytes == null || extractedApkBytes.isEmpty()) {
                        failBuild(
                            record = currentRecord,
                            errorSummary = "O artefato baixado não continha um arquivo .apk Release válido.",
                            logLine = "[ERRO] Nenhum arquivo .apk encontrado dentro do artefato '${targetArtifact.name}'.",
                            supabaseAccessToken = supabaseAccessToken,
                            supabaseUrl = supabaseUrl,
                            supabaseKey = supabaseKey
                        )
                        return
                    }

                    val savedApkFile = ZipProjectHelper.saveExtractedArtifactApk(
                        context = applicationContext,
                        buildId = currentRecord.id,
                        appName = currentRecord.appName,
                        extractedApkBytes = extractedApkBytes
                    )

                    if (rawJobLogTail.isNotBlank()) {
                        currentRecord = appendRecordLog(currentRecord, "--- TRECHO FINAL DO LOG DO GRADLE RELEASE ---\n$rawJobLogTail")
                    }

                    currentRecord = currentRecord.copy(
                        status = BuildStatus.SUCCESS,
                        progress = 1.0f,
                        statusStepMessage = "Compilação concluída",
                        apkDownloadUrl = targetArtifact.archiveDownloadUrl,
                        apkLocalPath = savedApkFile.absolutePath,
                        apkFileName = savedApkFile.name,
                        apkSizeBytes = savedApkFile.length(),
                        errorSummary = null,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                    dao.updateRecord(currentRecord)
                    supabaseRepo.pushBuildRecordToSupabase(currentRecord, supabaseAccessToken, supabaseUrl, supabaseKey)
                    updateNotification("Compilação Release concluída: ${currentRecord.appName}")
                    return
                } else {
                    val extractedError = extractRelevantErrorFromJobLogs(rawJobLogTail, jobs)
                    if (rawJobLogTail.isNotBlank()) {
                        currentRecord = appendRecordLog(
                            currentRecord,
                            "--- LOG DE ERRO REAL DO GITHUB ACTIONS (Run #$runId) ---\n$rawJobLogTail"
                        )
                    }
                    failBuild(
                        record = currentRecord,
                        errorSummary = extractedError,
                        logLine = "[FALHA] Workflow Run #$runId encerrou com conclusão: ${runConclusion ?: "failure"}",
                        supabaseAccessToken = supabaseAccessToken,
                        supabaseUrl = supabaseUrl,
                        supabaseKey = supabaseKey
                    )
                    return
                }
            }

            delay(4500)
        }

        failBuild(
            record = currentRecord,
            errorSummary = "Tempo limite excedido aguardando a conclusão do workflow Run #$runId.",
            logLine = "[ERRO] Timeout monitorando o Workflow Run #$runId.",
            supabaseAccessToken = supabaseAccessToken,
            supabaseUrl = supabaseUrl,
            supabaseKey = supabaseKey
        )
    }

    private suspend fun fetchJobLogTail(
        authHeader: String,
        owner: String,
        repo: String,
        jobId: Long
    ): String = withContext(Dispatchers.IO) {
        try {
            val resp = gitHubApi.downloadJobLogs(authHeader, owner, repo, jobId)
            if (!resp.isSuccessful || resp.body() == null) return@withContext ""
            val fullText = resp.body()!!.string()
            val cleanLines = fullText
                .lineSequence()
                .map { line ->
                    line.replace(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+Z\s*"""), "")
                }
                .filter { it.isNotBlank() }
                .toList()
            cleanLines.takeLast(120).joinToString("\n")
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractRelevantErrorFromJobLogs(logTail: String, jobs: List<GitHubJob>): String {
        val failedStep = jobs.flatMap { it.steps.orEmpty() }
            .firstOrNull { it.conclusion == "failure" }

        val lines = logTail.lines()
        val errorLines = lines.filter {
            it.contains("FAILURE:", ignoreCase = true) ||
                it.contains("Caused by:", ignoreCase = true) ||
                it.contains("Execution failed for task", ignoreCase = true) ||
                it.contains("e: file://", ignoreCase = true) ||
                it.contains("error:", ignoreCase = true) ||
                it.contains("Exception:", ignoreCase = true)
        }.take(6)

        return buildString {
            if (failedStep != null) {
                append("Falha na etapa '${failedStep.name}'. ")
            } else {
                append("A compilação Release falhou no GitHub Actions. ")
            }
            if (errorLines.isNotEmpty()) {
                append("\n")
                append(errorLines.joinToString("\n"))
            }
        }
    }

    private suspend fun updateRecordState(
        record: BuildRecordEntity,
        status: String,
        progress: Float,
        stepMessage: String,
        logLine: String,
        supabaseAccessToken: String,
        supabaseUrl: String,
        supabaseKey: String
    ): BuildRecordEntity {
        val updatedLogs = if (record.logsText.isBlank()) {
            logLine
        } else {
            "${record.logsText}\n$logLine"
        }
        val updated = record.copy(
            status = status,
            progress = progress,
            statusStepMessage = stepMessage,
            logsText = updatedLogs,
            updatedAtMillis = System.currentTimeMillis()
        )
        dao.updateRecord(updated)
        supabaseRepo.pushBuildRecordToSupabase(updated, supabaseAccessToken, supabaseUrl, supabaseKey)
        return updated
    }

    private suspend fun appendRecordLog(record: BuildRecordEntity, logLine: String): BuildRecordEntity {
        val updatedLogs = if (record.logsText.isBlank()) {
            logLine
        } else {
            "${record.logsText}\n$logLine"
        }
        val updated = record.copy(
            logsText = updatedLogs,
            updatedAtMillis = System.currentTimeMillis()
        )
        dao.updateRecord(updated)
        return updated
    }

    private suspend fun failBuild(
        record: BuildRecordEntity,
        errorSummary: String,
        logLine: String,
        supabaseAccessToken: String,
        supabaseUrl: String,
        supabaseKey: String
    ) {
        val updatedLogs = if (record.logsText.isBlank()) {
            logLine
        } else {
            "${record.logsText}\n$logLine"
        }
        val failed = record.copy(
            status = BuildStatus.FAILED,
            statusStepMessage = "Falha na compilação",
            errorSummary = errorSummary,
            logsText = updatedLogs,
            updatedAtMillis = System.currentTimeMillis()
        )
        dao.updateRecord(failed)
        supabaseRepo.pushBuildRecordToSupabase(failed, supabaseAccessToken, supabaseUrl, supabaseKey)
        updateNotification("Falha na compilação: ${record.appName}")
    }

    private fun sanitizeZipNameToRepoName(zipFileName: String): String {
        val base = zipFileName
            .substringBeforeLast(".")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
        return base.ifEmpty { "gera-apk-project" }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Compilações Release (GERA APK)",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("GERA APK • Release")
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "gera_apk_build_channel"
        private const val NOTIFICATION_ID = 4102
        const val EXTRA_RECORD_ID = "extra_record_id"
        const val EXTRA_TOKEN = "extra_github_token"
        const val EXTRA_RECOMPILE = "extra_recompile"
        const val EXTRA_SUPABASE_ACCESS_TOKEN = "extra_supabase_access_token"
        const val EXTRA_SUPABASE_URL = "extra_supabase_url"
        const val EXTRA_SUPABASE_KEY = "extra_supabase_key"

        fun enqueueBuild(
            context: Context,
            recordId: Long,
            token: String,
            isRecompile: Boolean = false,
            supabaseAccessToken: String = "",
            supabaseUrl: String = "",
            supabaseKey: String = ""
        ) {
            val intent = Intent(context, BuildBackgroundService::class.java).apply {
                putExtra(EXTRA_RECORD_ID, recordId)
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_RECOMPILE, isRecompile)
                putExtra(EXTRA_SUPABASE_ACCESS_TOKEN, supabaseAccessToken)
                putExtra(EXTRA_SUPABASE_URL, supabaseUrl)
                putExtra(EXTRA_SUPABASE_KEY, supabaseKey)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                context.startService(intent)
            }
        }

        fun resumeActiveBuildsIfAny(
            context: Context,
            token: String,
            supabaseAccessToken: String = "",
            supabaseUrl: String = "",
            supabaseKey: String = ""
        ) {
            val intent = Intent(context, BuildBackgroundService::class.java).apply {
                putExtra(EXTRA_RECORD_ID, -1L)
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_SUPABASE_ACCESS_TOKEN, supabaseAccessToken)
                putExtra(EXTRA_SUPABASE_URL, supabaseUrl)
                putExtra(EXTRA_SUPABASE_KEY, supabaseKey)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
