package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.AppDatabase
import com.example.data.AuthenticatedSession
import com.example.data.BuildBackgroundService
import com.example.data.BuildRecordEntity
import com.example.data.BuildStatus
import com.example.data.GitHubApiService
import com.example.data.SupabaseRepository
import com.example.data.ZipProjectHelper
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore(name = "gera_apk_prefs")

class CompilerViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenKey = stringPreferencesKey("github_pat_token")
    private val supabaseUrlKey = stringPreferencesKey("supabase_custom_url")
    private val supabaseAnonKey = stringPreferencesKey("supabase_custom_anon_key")
    private val userIdKey = stringPreferencesKey("auth_user_id")
    private val userNameKey = stringPreferencesKey("auth_user_name")
    private val userEmailKey = stringPreferencesKey("auth_user_email")
    private val userAccessTokenKey = stringPreferencesKey("auth_access_token")

    private val dao = AppDatabase.getInstance(application).buildRecordDao()
    private val supabaseRepository = SupabaseRepository(application)

    private val _currentSession = MutableStateFlow<AuthenticatedSession?>(null)
    val currentSession: StateFlow<AuthenticatedSession?> = _currentSession.asStateFlow()

    private val _isAuthLoading = MutableStateFlow(false)
    val isAuthLoading: StateFlow<Boolean> = _isAuthLoading.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val allBuildRecords: StateFlow<List<BuildRecordEntity>> = _currentSession
        .flatMapLatest { session ->
            if (session == null) {
                flowOf(emptyList())
            } else {
                dao.observeRecordsForUser(session.userId)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _selectedRecordId = MutableStateFlow<Long?>(null)
    val selectedRecordId: StateFlow<Long?> = _selectedRecordId.asStateFlow()

    private val _activeTab = MutableStateFlow(0) // 0 = Compilar, 1 = Histórico
    val activeTab: StateFlow<Int> = _activeTab.asStateFlow()

    private val _githubToken = MutableStateFlow("")
    val githubToken: StateFlow<String> = _githubToken.asStateFlow()

    private val _supabaseUrl = MutableStateFlow("")
    val supabaseUrl: StateFlow<String> = _supabaseUrl.asStateFlow()

    private val _supabaseKey = MutableStateFlow("")
    val supabaseKey: StateFlow<String> = _supabaseKey.asStateFlow()

    private val _showTokenSheet = MutableStateFlow(false)
    val showTokenSheet: StateFlow<Boolean> = _showTokenSheet.asStateFlow()

    private val _pendingExportRecord = MutableStateFlow<BuildRecordEntity?>(null)

    private val _statusToastMessage = MutableStateFlow<String?>(null)
    val statusToastMessage: StateFlow<String?> = _statusToastMessage.asStateFlow()

    val activeGitHubToken: String
        get() {
            val userToken = _githubToken.value.trim()
            if (userToken.isNotEmpty()) return userToken
            val buildToken = BuildConfig.GITHUB_TOKEN.trim()
            return if (buildToken.isNotEmpty() && buildToken != "YOUR_GITHUB_PAT_HERE" && buildToken != "null") {
                buildToken
            } else {
                ""
            }
        }

    val hasValidGitHubToken: Boolean
        get() = activeGitHubToken.isNotEmpty()

    init {
        viewModelScope.launch {
            val prefs = getApplication<Application>().dataStore.data.first()
            _githubToken.value = prefs[tokenKey]?.trim().orEmpty()
            _supabaseUrl.value = prefs[supabaseUrlKey]?.trim().orEmpty()
            _supabaseKey.value = prefs[supabaseAnonKey]?.trim().orEmpty()

            val savedUserId = prefs[userIdKey]?.trim().orEmpty()
            val savedName = prefs[userNameKey]?.trim().orEmpty()
            val savedEmail = prefs[userEmailKey]?.trim().orEmpty()
            val savedToken = prefs[userAccessTokenKey]?.trim().orEmpty()

            if (savedUserId.isNotEmpty() && savedEmail.isNotEmpty()) {
                val restoredSession = AuthenticatedSession(
                    userId = savedUserId,
                    name = savedName.ifBlank { savedEmail.substringBefore("@") },
                    email = savedEmail,
                    accessToken = savedToken,
                    isCloudSupabase = supabaseRepository.isCloudConfigured(_supabaseUrl.value, _supabaseKey.value)
                )
                _currentSession.value = restoredSession
                supabaseRepository.syncUserBuildsFromSupabase(
                    session = restoredSession,
                    customSupabaseUrl = _supabaseUrl.value,
                    customSupabaseKey = _supabaseKey.value
                )
                BuildBackgroundService.resumeActiveBuildsIfAny(
                    context = getApplication(),
                    token = activeGitHubToken,
                    supabaseAccessToken = restoredSession.accessToken,
                    supabaseUrl = _supabaseUrl.value,
                    supabaseKey = _supabaseKey.value
                )
            }
        }
    }

    fun signUp(name: String, email: String, password: String) {
        viewModelScope.launch {
            _isAuthLoading.value = true
            val result = supabaseRepository.signUp(
                name = name,
                email = email,
                password = password,
                customSupabaseUrl = _supabaseUrl.value,
                customSupabaseKey = _supabaseKey.value
            )
            _isAuthLoading.value = false
            result.onSuccess { session ->
                persistSession(session)
                _currentSession.value = session
                _statusToastMessage.value = "Bem-vindo, ${session.name}!"
            }.onFailure { err ->
                _statusToastMessage.value = err.localizedMessage ?: "Erro ao criar conta."
            }
        }
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _isAuthLoading.value = true
            val result = supabaseRepository.signIn(
                email = email,
                password = password,
                customSupabaseUrl = _supabaseUrl.value,
                customSupabaseKey = _supabaseKey.value
            )
            _isAuthLoading.value = false
            result.onSuccess { session ->
                persistSession(session)
                _currentSession.value = session
                _statusToastMessage.value = "Bem-vindo de volta, ${session.name}!"
            }.onFailure { err ->
                _statusToastMessage.value = err.localizedMessage ?: "Erro ao entrar na conta."
            }
        }
    }

    fun recoverPassword(email: String, newPassword: String) {
        viewModelScope.launch {
            _isAuthLoading.value = true
            val result = supabaseRepository.recoverPassword(
                email = email,
                newPasswordOptional = newPassword,
                customSupabaseUrl = _supabaseUrl.value,
                customSupabaseKey = _supabaseKey.value
            )
            _isAuthLoading.value = false
            result.onSuccess { msg ->
                _statusToastMessage.value = msg
            }.onFailure { err ->
                _statusToastMessage.value = err.localizedMessage ?: "Erro ao recuperar senha."
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { prefs ->
                prefs.remove(userIdKey)
                prefs.remove(userNameKey)
                prefs.remove(userEmailKey)
                prefs.remove(userAccessTokenKey)
            }
            _selectedRecordId.value = null
            _activeTab.value = 0
            _currentSession.value = null
        }
    }

    private suspend fun persistSession(session: AuthenticatedSession) {
        getApplication<Application>().dataStore.edit { prefs ->
            prefs[userIdKey] = session.userId
            prefs[userNameKey] = session.name
            prefs[userEmailKey] = session.email
            prefs[userAccessTokenKey] = session.accessToken
        }
    }

    fun selectTab(tabIndex: Int) {
        _activeTab.value = tabIndex
    }

    fun openBuildLogs(recordId: Long) {
        _selectedRecordId.value = recordId
    }

    fun closeBuildLogs() {
        _selectedRecordId.value = null
    }

    fun toggleTokenSheet(show: Boolean) {
        _showTokenSheet.value = show
    }

    fun clearStatusToast() {
        _statusToastMessage.value = null
    }

    fun saveCredentialsConfig(githubPat: String, customSupabaseUrl: String, customSupabaseAnonKey: String) {
        val cleanToken = githubPat.trim()
        val cleanUrl = customSupabaseUrl.trim()
        val cleanKey = customSupabaseAnonKey.trim()
        _githubToken.value = cleanToken
        _supabaseUrl.value = cleanUrl
        _supabaseKey.value = cleanKey
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { prefs ->
                prefs[tokenKey] = cleanToken
                prefs[supabaseUrlKey] = cleanUrl
                prefs[supabaseAnonKey] = cleanKey
            }
            val session = _currentSession.value
            if (session != null) {
                supabaseRepository.syncUserBuildsFromSupabase(session, cleanUrl, cleanKey)
                BuildBackgroundService.resumeActiveBuildsIfAny(
                    context = getApplication(),
                    token = activeGitHubToken,
                    supabaseAccessToken = session.accessToken,
                    supabaseUrl = cleanUrl,
                    supabaseKey = cleanKey
                )
            }
        }
        _showTokenSheet.value = false
        _statusToastMessage.value = "Configurações salvas."
    }

    /**
     * Inicia imediatamente uma compilação Release única para o arquivo ZIP selecionado,
     * associada ao usuário autenticado no Supabase.
     */
    fun onZipUriSelected(uri: Uri) {
        val session = _currentSession.value ?: return
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val zipFileName = withContext(Dispatchers.IO) {
                    queryFileName(context, uri) ?: "projeto-${System.currentTimeMillis()}.zip"
                }

                val uploadsDir = File(context.filesDir, "zip_queue").apply { mkdirs() }
                val localZipFile = File(uploadsDir, "build_${System.currentTimeMillis()}_$zipFileName")

                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        localZipFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalArgumentException("Não foi possível abrir o arquivo ZIP selecionado.")
                }

                val now = System.currentTimeMillis()
                val initialAppName = zipFileName.removeSuffix(".zip")
                val initialRepoSlug = zipFileName
                    .substringBeforeLast(".")
                    .lowercase()
                    .replace(Regex("[^a-z0-9._-]+"), "-")
                    .trim('-', '.', '_')
                    .ifEmpty { "gera-apk-project" }

                val newRecord = BuildRecordEntity(
                    userId = session.userId,
                    appName = initialAppName,
                    zipFileName = zipFileName,
                    localZipPath = localZipFile.absolutePath,
                    packageName = "Release",
                    repoFullName = initialRepoSlug,
                    branch = "main",
                    status = BuildStatus.WAITING,
                    progress = 0.05f,
                    statusStepMessage = "Enviando arquivos",
                    createdAtMillis = now,
                    updatedAtMillis = now,
                    logsText = "[AGUARDANDO] Compilação Release iniciada: $zipFileName"
                )

                val recordId = dao.insertRecord(newRecord)
                val insertedRecord = newRecord.copy(id = recordId)
                supabaseRepository.pushBuildRecordToSupabase(
                    record = insertedRecord,
                    accessToken = session.accessToken,
                    customSupabaseUrl = _supabaseUrl.value,
                    customSupabaseKey = _supabaseKey.value
                )

                _selectedRecordId.value = recordId

                BuildBackgroundService.enqueueBuild(
                    context = context,
                    recordId = recordId,
                    token = activeGitHubToken,
                    isRecompile = false,
                    supabaseAccessToken = session.accessToken,
                    supabaseUrl = _supabaseUrl.value,
                    supabaseKey = _supabaseKey.value
                )
            } catch (e: Exception) {
                _statusToastMessage.value = e.localizedMessage ?: "Erro ao carregar arquivo ZIP."
            }
        }
    }

    /**
     * Recompila um projeto existente no modo Release reutilizando o mesmo registro e o mesmo
     * repositório no GitHub, sem exigir novo envio de ZIP ou recadastro.
     */
    fun recompileExistingProject(record: BuildRecordEntity) {
        val session = _currentSession.value ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val resetRecord = record.copy(
                status = BuildStatus.WAITING,
                progress = 0.08f,
                statusStepMessage = "Enviando arquivos",
                workflowRunId = null,
                errorSummary = null,
                updatedAtMillis = now,
                logsText = "[RECOMPILAÇÃO RELEASE] Iniciando nova compilação Release em ${record.repoFullName}..."
            )
            dao.updateRecord(resetRecord)
            supabaseRepository.pushBuildRecordToSupabase(
                record = resetRecord,
                accessToken = session.accessToken,
                customSupabaseUrl = _supabaseUrl.value,
                customSupabaseKey = _supabaseKey.value
            )
            _selectedRecordId.value = record.id

            BuildBackgroundService.enqueueBuild(
                context = getApplication(),
                recordId = record.id,
                token = activeGitHubToken,
                isRecompile = true,
                supabaseAccessToken = session.accessToken,
                supabaseUrl = _supabaseUrl.value,
                supabaseKey = _supabaseKey.value
            )
        }
    }

    fun deleteBuildRecord(record: BuildRecordEntity) {
        val session = _currentSession.value
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteRecordById(record.id)
            record.localZipPath.takeIf { it.isNotBlank() }?.let { File(it).delete() }
            record.apkLocalPath?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
            if (session != null) {
                supabaseRepository.deleteBuildRecordFromSupabase(
                    record = record,
                    accessToken = session.accessToken,
                    customSupabaseUrl = _supabaseUrl.value,
                    customSupabaseKey = _supabaseKey.value
                )
            }
        }
        if (_selectedRecordId.value == record.id) {
            _selectedRecordId.value = null
        }
    }

    fun prepareApkExport(record: BuildRecordEntity, onLaunchCreateDocument: (String) -> Unit) {
        viewModelScope.launch {
            val localFile = record.apkLocalPath?.let { File(it) }
            if (localFile != null && localFile.exists()) {
                _pendingExportRecord.value = record
                val suggestedName = record.apkFileName ?: "${record.appName}-release.apk"
                onLaunchCreateDocument(suggestedName)
                return@launch
            }

            // Se o histórico veio sincronizado do Supabase em outro dispositivo, baixa o APK Release pela URL
            val downloadUrl = record.apkDownloadUrl
            val ghToken = activeGitHubToken
            if (!downloadUrl.isNullOrBlank() && ghToken.isNotBlank()) {
                _statusToastMessage.value = "Baixando APK Release..."
                val fetchedRecord = withContext(Dispatchers.IO) {
                    try {
                        val api = Retrofit.Builder()
                            .baseUrl("https://api.github.com/")
                            .client(OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).connectTimeout(30, TimeUnit.SECONDS).build())
                            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
                            .build()
                            .create(GitHubApiService::class.java)
                        val resp = api.downloadArtifactZip("Bearer $ghToken", downloadUrl)
                        if (resp.isSuccessful && resp.body() != null) {
                            val bytes = ZipProjectHelper.extractApkFromArtifactZip(resp.body()!!.bytes())
                            if (bytes != null) {
                                val savedFile = ZipProjectHelper.saveExtractedArtifactApk(
                                    context = getApplication(),
                                    buildId = record.id,
                                    appName = record.appName,
                                    extractedApkBytes = bytes
                                )
                                val updated = record.copy(
                                    apkLocalPath = savedFile.absolutePath,
                                    apkFileName = savedFile.name,
                                    apkSizeBytes = savedFile.length()
                                )
                                dao.updateRecord(updated)
                                updated
                            } else null
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }
                if (fetchedRecord != null) {
                    _pendingExportRecord.value = fetchedRecord
                    onLaunchCreateDocument(fetchedRecord.apkFileName ?: "${fetchedRecord.appName}-release.apk")
                    return@launch
                }
            }

            _statusToastMessage.value = "Arquivo APK Release não disponível localmente. Recompile o projeto para gerar o APK."
        }
    }

    fun exportPendingApkToUri(destinationUri: Uri) {
        val record = _pendingExportRecord.value ?: return
        val apkPath = record.apkLocalPath ?: return
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                withContext(Dispatchers.IO) {
                    val srcFile = File(apkPath)
                    context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        srcFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                }
                _statusToastMessage.value = "APK Release salvo: ${record.apkFileName ?: "app-release.apk"}"
            } catch (e: Exception) {
                _statusToastMessage.value = "Erro ao salvar APK: ${e.localizedMessage}"
            }
        }
    }

    fun shareOrInstallApk(context: Context, record: BuildRecordEntity) {
        val apkPath = record.apkLocalPath ?: return
        try {
            val apkFile = File(apkPath)
            if (!apkFile.exists()) {
                _statusToastMessage.value = "Arquivo APK não encontrado."
                return
            }
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, apkFile.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(shareIntent, "Instalar ou Compartilhar ${apkFile.name}")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            _statusToastMessage.value = "Não foi possível abrir o APK: ${e.localizedMessage}"
        }
    }

    fun copyLogsToClipboard(context: Context, record: BuildRecordEntity) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val fullReport = buildString {
            appendLine("=== GERA APK • RELEASE ===")
            appendLine("Projeto: ${record.appName}")
            appendLine("Repositório: ${record.repoFullName}")
            appendLine("Status: ${record.status}")
            if (!record.errorSummary.isNullOrBlank()) {
                appendLine("Erro: ${record.errorSummary}")
            }
            appendLine(record.logsText)
        }
        clipboard?.setPrimaryClip(ClipData.newPlainText("Logs GERA APK", fullReport))
        _statusToastMessage.value = "Logs copiados!"
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
