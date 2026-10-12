package com.example.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query
import com.squareup.moshi.Moshi
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class SupabaseSignUpRequest(
    @Json(name = "email") val email: String,
    @Json(name = "password") val password: String,
    @Json(name = "data") val data: Map<String, String>
)

@JsonClass(generateAdapter = true)
data class SupabaseLoginRequest(
    @Json(name = "email") val email: String,
    @Json(name = "password") val password: String
)

@JsonClass(generateAdapter = true)
data class SupabaseRecoverRequest(
    @Json(name = "email") val email: String
)

@JsonClass(generateAdapter = true)
data class SupabaseUserDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "user_metadata") val userMetadata: Map<String, Any?>? = null
) {
    val displayName: String
        get() = (userMetadata?.get("full_name") as? String)
            ?: (userMetadata?.get("name") as? String)
            ?: email?.substringBefore("@").orEmpty()
}

@JsonClass(generateAdapter = true)
data class SupabaseAuthResponse(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "user") val user: SupabaseUserDto? = null,
    @Json(name = "id") val directId: String? = null,
    @Json(name = "email") val directEmail: String? = null,
    @Json(name = "user_metadata") val directMetadata: Map<String, Any?>? = null
) {
    fun resolveUser(): SupabaseUserDto? {
        if (user != null && !user.id.isNullOrBlank()) return user
        if (!directId.isNullOrBlank()) {
            return SupabaseUserDto(
                id = directId,
                email = directEmail,
                userMetadata = directMetadata
            )
        }
        return null
    }
}

@JsonClass(generateAdapter = true)
data class SupabaseBuildRecordDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "user_id") val userId: String,
    @Json(name = "app_name") val appName: String,
    @Json(name = "zip_file_name") val zipFileName: String,
    @Json(name = "package_name") val packageName: String,
    @Json(name = "repo_full_name") val repoFullName: String,
    @Json(name = "branch") val branch: String,
    @Json(name = "status") val status: String,
    @Json(name = "progress") val progress: Float,
    @Json(name = "status_step_message") val statusStepMessage: String,
    @Json(name = "created_at_millis") val createdAtMillis: Long,
    @Json(name = "updated_at_millis") val updatedAtMillis: Long,
    @Json(name = "workflow_run_id") val workflowRunId: Long? = null,
    @Json(name = "workflow_html_url") val workflowHtmlUrl: String? = null,
    @Json(name = "apk_download_url") val apkDownloadUrl: String? = null,
    @Json(name = "apk_file_name") val apkFileName: String? = null,
    @Json(name = "apk_size_bytes") val apkSizeBytes: Long = 0L,
    @Json(name = "error_summary") val errorSummary: String? = null,
    @Json(name = "logs_text") val logsText: String = ""
)

/**
 * Interface REST oficial do Supabase (GoTrue Auth v1 + PostgREST v1 com Row Level Security - RLS).
 *
 * SQL de referência para a tabela `builds` no Supabase com RLS isolando cada usuário:
 * ```sql
 * create table if not exists public.builds (
 *   id bigint primary key,
 *   user_id uuid not null references auth.users(id) on delete cascade,
 *   app_name text not null,
 *   zip_file_name text not null,
 *   package_name text not null,
 *   repo_full_name text not null,
 *   branch text not null default 'main',
 *   status text not null,
 *   progress real not null default 0,
 *   status_step_message text not null default '',
 *   created_at_millis bigint not null,
 *   updated_at_millis bigint not null,
 *   workflow_run_id bigint,
 *   workflow_html_url text,
 *   apk_download_url text,
 *   apk_file_name text,
 *   apk_size_bytes bigint not null default 0,
 *   error_summary text,
 *   logs_text text not null default ''
 * );
 * alter table public.builds enable row level security;
 * create policy "Users can manage only their own builds" on public.builds
 *   for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
 * ```
 */
interface SupabaseApiService {

    @POST("auth/v1/signup")
    suspend fun signUp(
        @Header("apikey") apiKey: String,
        @Body request: SupabaseSignUpRequest
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/token?grant_type=password")
    suspend fun signInWithPassword(
        @Header("apikey") apiKey: String,
        @Body request: SupabaseLoginRequest
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/recover")
    suspend fun recoverPassword(
        @Header("apikey") apiKey: String,
        @Body request: SupabaseRecoverRequest
    ): Response<ResponseBody>

    @POST("auth/v1/logout")
    suspend fun signOut(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String
    ): Response<ResponseBody>

    @GET("rest/v1/builds")
    suspend fun getUserBuilds(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("user_id") userIdFilter: String, // "eq.<userId>"
        @Query("order") order: String = "created_at_millis.desc"
    ): Response<List<SupabaseBuildRecordDto>>

    @Headers("Prefer: resolution=merge-duplicates,return=representation")
    @POST("rest/v1/builds")
    suspend fun upsertBuildRecord(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body record: SupabaseBuildRecordDto
    ): Response<List<SupabaseBuildRecordDto>>

    @DELETE("rest/v1/builds")
    suspend fun deleteBuildRecord(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("id") idFilter: String, // "eq.<id>"
        @Query("user_id") userIdFilter: String // "eq.<userId>"
    ): Response<ResponseBody>

    companion object {
        fun create(supabaseUrl: String): SupabaseApiService {
            val normalizedUrl = supabaseUrl.trim().trimEnd('/') + "/"
            val moshi = Moshi.Builder().build()
            val okHttp = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(okHttp)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(SupabaseApiService::class.java)
        }
    }
}
