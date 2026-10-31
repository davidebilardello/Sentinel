package it.bilar.sentinel

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class DriveSyncError(val retryable: Boolean, message: String, val authorizationRequired: Boolean = false) : IOException(message)
internal class GoogleDriveStore(private val token: String, private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val base: String = "https://www.googleapis.com") {
    data class RemoteFile(val id: String, val name: String)
    private fun request(url: String, method: String = "GET", body: RequestBody? = null): ByteArray {
        http.newCall(Request.Builder().url(url).header("Authorization","Bearer $token").method(method,body).build()).execute().use { response ->
            if (!response.isSuccessful) throw when(response.code) {
                401 -> DriveSyncError(false,appText(R.string.google_access_has_expired_or_was_revoked_try_connecting_again_from_set),authorizationRequired = true)
                403 -> DriveSyncError(false,appText(R.string.google_drive_access_denied_check_consent_and_enable_the_api_in_your_go))
                429 -> DriveSyncError(true,appText(R.string.google_has_limited_requests_sync_will_try_again_later))
                else -> DriveSyncError(response.code >= 500,appText(R.string.google_drive_is_unavailable_try_again_later))
            }
            return response.body.byteStream().use { PortableArchive.readBounded(it,PortableArchive.MAX_FILE) }
        }
    }
    fun identity(): Pair<String,String> {
        val result = JSONObject(request("$base/oauth2/v3/userinfo").toString(Charsets.UTF_8))
        val sub = result.getString("sub"); val email = result.getString("email")
        require(result.optBoolean("email_verified") && sub.matches(Regex("[A-Za-z0-9_-]{1,100}")) && email.length in 3..254)
        return sub to email
    }
    fun files(): List<RemoteFile> {
        val files = mutableListOf<RemoteFile>(); var page: String? = null
        val seenPages = mutableSetOf<String>()
        // V1 stays untouched; updated phones upload their existing local data in V2.
        do {
            val url = "$base/drive/v3/files".toHttpUrl().newBuilder().addQueryParameter("spaces","appDataFolder")
                .addQueryParameter("q","name contains 'sentinel-sync-v2-' and trashed = false")
                .addQueryParameter("fields","nextPageToken,files(id,name)").addQueryParameter("pageSize","100")
                .apply { page?.let { addQueryParameter("pageToken",it) } }.build()
            val result = JSONObject(request(url.toString()).toString(Charsets.UTF_8)); val list = result.getJSONArray("files")
            for(i in 0 until list.length()) {
                val item = list.getJSONObject(i); val id = item.getString("id"); val name = item.getString("name")
                if(name.matches(Regex("sentinel-sync-v2-[a-f0-9-]{36}\\.sentinel"))) {
                    require(id.matches(Regex("[A-Za-z0-9_-]{1,200}"))); files += RemoteFile(id,name)
                }
            }
            require(files.size <= 100) { appText(R.string.too_many_sync_archives) }
            page = result.optString("nextPageToken").takeIf(String::isNotBlank)
            require(page == null || (seenPages.size < 100 && seenPages.add(page))) { appText(R.string.invalid_google_drive_response) }
        } while(page != null)
        return files
    }
    fun download(file: RemoteFile) = request("$base/drive/v3/files/${file.id}?alt=media")
    fun upload(name: String, file: RemoteFile?, bytes: ByteArray) {
        val data = bytes.toRequestBody("application/octet-stream".toMediaType())
        if(file != null) request("$base/upload/drive/v3/files/${file.id}?uploadType=media&fields=id","PATCH",data)
        else {
            val metadata = JSONObject().put("name",name).put("parents",JSONArray().put("appDataFolder")).put("mimeType","application/octet-stream")
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())).addPart(data).build()
            request("$base/upload/drive/v3/files?uploadType=multipart&fields=id","POST",body)
        }
    }
}
