package com.example.data

import android.content.Context
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class AuthenticatedSession(
    val userId: String,
    val name: String,
    val email: String,
    val accessToken: String,
    val isCloudSupabase: Boolean
)

/**
 * Gerencia autenticação de usuários (Cadastro, Login, Logout, Recuperação de Senha)
 * e sincronização do histórico de compilações no Supabase com isolamento por `user_id` (RLS).
 */
class SupabaseRepository(context: Context) {

    private val database = AppDatabase.getInstance(context)
    private val userDao = database.userAccountDao()
    private val buildDao = database.buildRecordDao()

    fun resolveSupabaseUrl(customUrl: String): String {
        val cleanCustom = customUrl.trim()
        if (cleanCustom.startsWith("https://")) return cleanCustom
        val envUrl = BuildConfig.SUPABASE_URL.trim()
        if (envUrl.isNotEmpty() && envUrl != "YOUR_SUPABASE_URL_HERE" && envUrl != "null" && envUrl.startsWith("https://")) {
            return envUrl
        }
        return ""
    }

    fun resolveSupabaseAnonKey(customKey: String): String {
        val cleanCustom = customKey.trim()
        if (cleanCustom.isNotEmpty()) return cleanCustom
        val envKey = BuildConfig.SUPABASE_ANON_KEY.trim()
        if (envKey.isNotEmpty() && envKey != "YOUR_SUPABASE_ANON_KEY_HERE" && envKey != "null") {
            return envKey
        }
        return ""
    }

    fun isCloudConfigured(customUrl: String, customKey: String): Boolean {
        return resolveSupabaseUrl(customUrl).isNotEmpty() && resolveSupabaseAnonKey(customKey).isNotEmpty()
    }

    suspend fun signUp(
        name: String,
        email: String,
        password: String,
        customSupabaseUrl: String,
        customSupabaseKey: String
    ): Result<AuthenticatedSession> = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        val cleanEmail = email.trim().lowercase(Locale.ROOT)
        if (cleanName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Informe seu nome."))
        }
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            return@withContext Result.failure(IllegalArgumentException("Informe um e-mail válido."))
        }
        if (password.length < 6) {
            return@withContext Result.failure(IllegalArgumentException("A senha deve ter pelo menos 6 caracteres."))
        }

        val url = resolveSupabaseUrl(customSupabaseUrl)
        val key = resolveSupabaseAnonKey(customSupabaseKey)

        if (url.isNotEmpty() && key.isNotEmpty()) {
            try {
                val api = SupabaseApiService.create(url)
                val resp = api.signUp(
                    apiKey = key,
                    request = SupabaseSignUpRequest(
                        email = cleanEmail,
                        password = password,
                        data = mapOf("full_name" to cleanName, "name" to cleanName)
                    )
                )
                if (resp.isSuccessful && resp.body() != null) {
                    val body = resp.body()!!
                    val user = body.resolveUser()
                    val uid = user?.id ?: UUID.nameUUIDFromBytes(cleanEmail.toByteArray()).toString()
                    val token = body.accessToken ?: key
                    userDao.insertOrUpdateAccount(
                        UserAccountEntity(
                            userId = uid,
                            name = cleanName,
                            email = cleanEmail,
                            passwordHash = hashPassword(password),
                            createdAtMillis = System.currentTimeMillis()
                        )
                    )
                    val session = AuthenticatedSession(
                        userId = uid,
                        name = cleanName,
                        email = cleanEmail,
                        accessToken = token,
                        isCloudSupabase = true
                    )
                    syncUserBuildsFromSupabase(session, url, key)
                    return@withContext Result.success(session)
                } else {
                    val errMsg = resp.errorBody()?.string().orEmpty()
                    return@withContext Result.failure(
                        IllegalStateException("Não foi possível criar conta no Supabase (HTTP ${resp.code()}): $errMsg")
                    )
                }
            } catch (e: Exception) {
                return@withContext Result.failure(
                    IllegalStateException("Falha de conexão com o Supabase: ${e.localizedMessage}")
                )
            }
        }

        // Persistência local isolada com RLS por userId quando as chaves ainda não estão no painel Secrets
        val existing = userDao.getAccountByEmail(cleanEmail)
        if (existing != null) {
            return@withContext Result.failure(IllegalArgumentException("Já existe uma conta cadastrada com este e-mail."))
        }
        val uid = UUID.nameUUIDFromBytes(cleanEmail.toByteArray()).toString()
        val account = UserAccountEntity(
            userId = uid,
            name = cleanName,
            email = cleanEmail,
            passwordHash = hashPassword(password),
            createdAtMillis = System.currentTimeMillis()
        )
        userDao.insertOrUpdateAccount(account)
        Result.success(
            AuthenticatedSession(
                userId = uid,
                name = cleanName,
                email = cleanEmail,
                accessToken = "local-session-$uid",
                isCloudSupabase = false
            )
        )
    }

    suspend fun signIn(
        email: String,
        password: String,
        customSupabaseUrl: String,
        customSupabaseKey: String
    ): Result<AuthenticatedSession> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase(Locale.ROOT)
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            return@withContext Result.failure(IllegalArgumentException("Informe um e-mail válido."))
        }
        if (password.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Informe sua senha."))
        }

        val url = resolveSupabaseUrl(customSupabaseUrl)
        val key = resolveSupabaseAnonKey(customSupabaseKey)

        if (url.isNotEmpty() && key.isNotEmpty()) {
            try {
                val api = SupabaseApiService.create(url)
                val resp = api.signInWithPassword(
                    apiKey = key,
                    request = SupabaseLoginRequest(email = cleanEmail, password = password)
                )
                if (resp.isSuccessful && resp.body() != null) {
                    val body = resp.body()!!
                    val user = body.resolveUser()
                    val uid = user?.id ?: UUID.nameUUIDFromBytes(cleanEmail.toByteArray()).toString()
                    val resolvedName = user?.displayName?.ifBlank { cleanEmail.substringBefore("@") }
                        ?: cleanEmail.substringBefore("@")
                    val token = body.accessToken ?: key
                    userDao.insertOrUpdateAccount(
                        UserAccountEntity(
                            userId = uid,
                            name = resolvedName,
                            email = cleanEmail,
                            passwordHash = hashPassword(password),
                            createdAtMillis = System.currentTimeMillis()
                        )
                    )
                    val session = AuthenticatedSession(
                        userId = uid,
                        name = resolvedName,
                        email = cleanEmail,
                        accessToken = token,
                        isCloudSupabase = true
                    )
                    syncUserBuildsFromSupabase(session, url, key)
                    return@withContext Result.success(session)
                } else {
                    return@withContext Result.failure(
                        IllegalArgumentException("E-mail ou senha inválidos no Supabase.")
                    )
                }
            } catch (e: Exception) {
                return@withContext Result.failure(
                    IllegalStateException("Erro ao conectar ao Supabase: ${e.localizedMessage}")
                )
            }
        }

        val localAccount = userDao.getAccountByEmail(cleanEmail)
            ?: return@withContext Result.failure(IllegalArgumentException("Conta não encontrada. Crie uma conta primeiro."))

        if (localAccount.passwordHash != hashPassword(password)) {
            return@withContext Result.failure(IllegalArgumentException("Senha incorreta."))
        }

        Result.success(
            AuthenticatedSession(
                userId = localAccount.userId,
                name = localAccount.name,
                email = localAccount.email,
                accessToken = "local-session-${localAccount.userId}",
                isCloudSupabase = false
            )
        )
    }

    suspend fun recoverPassword(
        email: String,
        newPasswordOptional: String,
        customSupabaseUrl: String,
        customSupabaseKey: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase(Locale.ROOT)
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            return@withContext Result.failure(IllegalArgumentException("Informe um e-mail válido para recuperar a senha."))
        }

        val url = resolveSupabaseUrl(customSupabaseUrl)
        val key = resolveSupabaseAnonKey(customSupabaseKey)

        if (url.isNotEmpty() && key.isNotEmpty()) {
            try {
                val api = SupabaseApiService.create(url)
                val resp = api.recoverPassword(
                    apiKey = key,
                    request = SupabaseRecoverRequest(email = cleanEmail)
                )
                if (resp.isSuccessful) {
                    return@withContext Result.success("Link de recuperação enviado para $cleanEmail via Supabase Auth.")
                }
            } catch (_: Exception) {
            }
        }

        val existing = userDao.getAccountByEmail(cleanEmail)
            ?: return@withContext Result.failure(IllegalArgumentException("Nenhuma conta encontrada com o e-mail $cleanEmail."))

        if (newPasswordOptional.length < 6) {
            return@withContext Result.failure(IllegalArgumentException("Informe uma nova senha com pelo menos 6 caracteres."))
        }

        userDao.updatePasswordByEmail(existing.email, hashPassword(newPasswordOptional))
        Result.success("Senha atualizada com sucesso para $cleanEmail!")
    }

    suspend fun syncUserBuildsFromSupabase(
        session: AuthenticatedSession,
        customSupabaseUrl: String,
        customSupabaseKey: String
    ) = withContext(Dispatchers.IO) {
        val url = resolveSupabaseUrl(customSupabaseUrl)
        val key = resolveSupabaseAnonKey(customSupabaseKey)
        if (url.isEmpty() || key.isEmpty() || session.userId.isBlank()) return@withContext

        try {
            val api = SupabaseApiService.create(url)
            val resp = api.getUserBuilds(
                apiKey = key,
                bearerToken = "Bearer ${session.accessToken}",
                userIdFilter = "eq.${session.userId}"
            )
            if (resp.isSuccessful) {
                val remoteRecords = resp.body().orEmpty()
                for (dto in remoteRecords) {
                    val recordId = dto.id ?: continue
                    val localExisting = buildDao.getRecordById(recordId)
                    val merged = BuildRecordEntity(
                        id = recordId,
                        userId = session.userId,
                        appName = dto.appName,
                        zipFileName = dto.zipFileName,
                        localZipPath = localExisting?.localZipPath.orEmpty(),
                        packageName = dto.packageName,
                        repoFullName = dto.repoFullName,
                        branch = dto.branch,
                        status = dto.status,
                        progress = dto.progress,
                        statusStepMessage = dto.statusStepMessage,
                        createdAtMillis = dto.createdAtMillis,
                        updatedAtMillis = dto.updatedAtMillis,
                        workflowRunId = dto.workflowRunId,
                        workflowHtmlUrl = dto.workflowHtmlUrl,
                        apkDownloadUrl = dto.apkDownloadUrl,
                        logsText = dto.logsText,
                        errorSummary = dto.errorSummary,
                        apkLocalPath = localExisting?.apkLocalPath,
                        apkFileName = dto.apkFileName ?: localExisting?.apkFileName,
                        apkSizeBytes = if (dto.apkSizeBytes > 0) dto.apkSizeBytes else (localExisting?.apkSizeBytes ?: 0L)
                    )
                    buildDao.insertRecord(merged)
                }
            }
        } catch (_: Exception) {
        }
    }

    suspend fun pushBuildRecordToSupabase(
        record: BuildRecordEntity,
        accessToken: String,
        customSupabaseUrl: String,
        customSupabaseKey: String
    ) = withContext(Dispatchers.IO) {
        val url = resolveSupabaseUrl(customSupabaseUrl)
        val key = resolveSupabaseAnonKey(customSupabaseKey)
        if (url.isEmpty() || key.isEmpty() || record.userId.isBlank() || accessToken.isBlank()) return@withContext

        try {
            val api = SupabaseApiService.create(url)
            val dto = SupabaseBuildRecordDto(
                id = record.id,
                userId = record.userId,
                appName = record.appName,
                zipFileName = record.zipFileName,
                packageName = record.packageName,
                repoFullName = record.repoFullName,
                branch = record.branch,
                status = record.status,
                progress = record.progress,
                statusStepMessage = record.statusStepMessage,
                createdAtMillis = record.createdAtMillis,
                updatedAtMillis = record.updatedAtMillis,
                workflowRunId = record.workflowRunId,
                workflowHtmlUrl = record.workflowHtmlUrl,
                apkDownloadUrl = record.apkDownloadUrl,
                apkFileName = record.apkFileName,
                apkSizeBytes = record.apkSizeBytes,
                errorSummary = record.errorSummary,
                logsText = record.logsText
            )
            api.upsertBuildRecord(
                apiKey = key,
                bearerToken = "Bearer $accessToken",
                record = dto
            )
        } catch (_: Exception) {
        }
    }

    suspend fun deleteBuildRecordFromSupabase(
        record: BuildRecordEntity,
        accessToken: String,
        customSupabaseUrl: String,
        customSupabaseKey: String
    ) = withContext(Dispatchers.IO) {
        val url = resolveSupabaseUrl(customSupabaseUrl)
        val key = resolveSupabaseAnonKey(customSupabaseKey)
        if (url.isEmpty() || key.isEmpty() || record.userId.isBlank() || accessToken.isBlank()) return@withContext

        try {
            val api = SupabaseApiService.create(url)
            api.deleteBuildRecord(
                apiKey = key,
                bearerToken = "Bearer $accessToken",
                idFilter = "eq.${record.id}",
                userIdFilter = "eq.${record.userId}"
            )
        } catch (_: Exception) {
        }
    }

    private fun hashPassword(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
