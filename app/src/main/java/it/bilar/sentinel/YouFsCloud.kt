package it.bilar.sentinel

import android.content.Context
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

internal class YouFsCloudError(val code: String) : Exception(when (code) {
    "USER_PASSWD_WRONG", "USER_EMAIL_PASSWD_WRONG" -> appText(R.string.incorrect_email_or_password)
    "MFA_REQUIRED", "MFA_NEED_SEND_CODE" -> appText(R.string.the_server_requires_an_email_verification_code)
    "MFA_CODE_ERROR", "MFA_CODE_INVALID", "MFA_VERIFY_CODE_ERROR", "USER_CODE_ERROR" -> appText(R.string.the_verification_code_is_incorrect)
    "MFA_CODE_EXPIRED", "MFA_VERIFY_CODE_EXPIRED", "USER_CODE_EXPIRED" -> appText(R.string.the_code_has_expired_request_a_new_one)
    "USER_NOT_EXIST" -> appText(R.string.account_not_found_check_email_country_and_region)
    "USER_LOGIN_TOO_FREQUENT" -> appText(R.string.too_many_sign_in_attempts_wait_before_trying_again)
    "CAPTCHA_REQUIRED" -> appText(R.string.complete_the_security_check_in_the_original_youfs_a_app_then_try_again)
    "PASSWORD_REQUIRED" -> appText(R.string.enter_your_youfs_a_account_password)
    "NETWORK" -> appText(R.string.unable_to_connect_to_the_server_check_internet_access_and_your_account)
    "DEVICE_UNSUPPORTED" -> appText(R.string.this_device_does_not_provide_compatible_bluetooth_credentials_and_sche)
    else -> appText(R.string.sign_in_failed_check_your_credentials_and_account_region)
}) {
    val requiresCode get() = code in setOf("MFA_REQUIRED", "MFA_NEED_SEND_CODE", "MFA_CODE_ERROR", "MFA_CODE_INVALID", "MFA_VERIFY_CODE_ERROR", "MFA_CODE_EXPIRED", "MFA_VERIFY_CODE_EXPIRED", "USER_CODE_ERROR", "USER_CODE_EXPIRED")
}

internal class YouFsCloudGateway(private val region: String, val installationId: String,
    private val transport: ((String, Map<String, String>) -> JSONObject)? = null) {
    var session: String? = null
    var ecode: String? = null
    private val hosts = mapOf("EU" to "a1-eu.gdyoufs.com", "AZ" to "a1-us.gdyoufs.com", "IN" to "a1-in.gdyoufs.com", "CN" to "a1-cn.gdyoufs.com")
    private val allowed = mapOf("thing.m.user.username.token.get" to "2.0", "thing.m.user.email.password.login" to "3.0",
        "thing.m.user.mobile.passwd.login" to "4.0", "thing.m.user.username.mfa.code.get" to "1.0", "thing.m.user.info.get" to "1.0",
        "m.life.group.location.list" to "7.0", "m.life.my.group.device.list" to "2.2", "thing.m.device.get" to "1.0")
    private val http by lazy { OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS)
        .callTimeout(30,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build() }
    fun call(api: String, data: JSONObject = JSONObject(), authenticated: Boolean = false): Any? {
        val version = allowed[api] ?: throw IllegalArgumentException("Only authentication and read APIs are allowed")
        if (authenticated && (session.isNullOrEmpty() || ecode.isNullOrEmpty())) throw YouFsCloudError("SESSION_INVALID")
        val requestId = UUID.randomUUID().toString()
        val params = linkedMapOf("clientId" to YouFsCloudCodec.APP_KEY, "os" to "Android", "appVersion" to "1.0.3", "lang" to "it_IT",
            "sdkVersion" to "5.8.0", "ttid" to "sdk_thing@${YouFsCloudCodec.APP_KEY}", "osSystem" to "12", "platform" to "SDY-AN00",
            "requestId" to requestId, "timeZoneId" to "Asia/Shanghai", "et" to "3", "channel" to "oem", "nd" to "1",
            "appRnVersion" to "5.79", "deviceCoreVersion" to "5.5.0", "chKey" to "3fc2a062", "deviceId" to installationId,
            "time" to (System.currentTimeMillis()/1000).toString(), "bizData" to "{\"customDomainSupport\":\"1\",\"neutralDomains\":\"1\"}",
            "a" to if (api.startsWith("thing.")) "smartlife." + api.removePrefix("thing.") else api, "v" to version, "cp" to "gzip")
        if (authenticated) params["sid"] = session!!
        val key = YouFsCloudCodec.payloadKey(requestId, if (authenticated) ecode else null)
        try {
            params["postData"] = YouFsCloudCodec.encrypt(data.toString().toByteArray(Charsets.UTF_8), key)
            params["sign"] = YouFsCloudCodec.signature(params)
            val url = "https://${hosts[region] ?: throw IllegalArgumentException("Invalid region")}/api.json"
            var payload = if (transport != null) transport.invoke(url, params) else {
                val form = FormBody.Builder().apply { params.forEach { (name,value) -> add(name,value) } }.build()
                http.newCall(Request.Builder().url(url).header("User-Agent", "TuyaThing/1.0.3 (com.yongfengshun)").post(form).build()).execute().use { response ->
                    if (!response.isSuccessful) throw YouFsCloudError("NETWORK")
                    val bytes = response.body.byteStream().use { stream ->
                        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (out.size() <= 6_000_000) { val n = stream.read(buffer); if (n < 0) break; out.write(buffer,0,n) }
                        require(out.size() <= 6_000_000); out.toByteArray()
                    }
                    JSONObject(bytes.toString(Charsets.UTF_8))
                }
            }
            if (payload.opt("result") is String && payload.has("sign")) {
                val clear = YouFsCloudCodec.decrypt(payload.getString("result"), key)
                try {
                    val bytes = if (clear.size >= 2 && clear[0] == 0x1f.toByte() && clear[1] == 0x8b.toByte()) {
                        GZIPInputStream(ByteArrayInputStream(clear)).use { stream ->
                            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (out.size() <= 6_000_000) { val n = stream.read(buffer); if (n < 0) break; out.write(buffer,0,n) }
                            require(out.size() <= 6_000_000); out.toByteArray()
                        }
                    } else clear
                    try { payload = JSONObject(bytes.toString(Charsets.UTF_8)) } finally { if (bytes !== clear) bytes.fill(0) }
                } finally { clear.fill(0) }
            }
            if (payload.optBoolean("success")) return payload.opt("result")
            val nested = payload.optJSONObject("error") ?: payload.optJSONObject("result")
            val candidate = payload.optString("errorCode", payload.optString("code", nested?.optString("errorCode").orEmpty()))
            val safeCode = candidate.takeIf { it.matches(Regex("[A-Z_]{3,50}")) } ?: "GATEWAY_REJECTED"
            throw YouFsCloudError(safeCode)
        } catch (error: YouFsCloudError) { throw error }
        catch (_: Exception) { throw YouFsCloudError("NETWORK") }
        finally { key.fill(0) }
    }
}

data class YouFsAccountDevice(val id: String, val name: String)

internal class YouFsAccount(private val context: Context,
    private val gatewayFactory: (String,String) -> YouFsCloudGateway = { region,id -> YouFsCloudGateway(region,id) }) {
    companion object { private val accountLock = Any() }
    private val vault = EncryptedJsonFile(context, "youfs-account.enc")
    private var gateway: YouFsCloudGateway? = null
    private var owner = ""
    private var challengeOwner = ""
    private val records = linkedMapOf<String, JSONObject>()
    fun disconnect() = synchronized(accountLock) {
        gateway?.apply { session = null; ecode = null }
        gateway = null; owner = ""; challengeOwner = ""; records.clear()
        vault.delete()
    }
    fun remembered(): JSONObject? = try { vault.read()?.let { clear -> try { JSONObject(clear.toString(Charsets.UTF_8)) } finally { clear.fill(0) } } } catch (_: Exception) { null }
    fun login(account: String, password: String, country: String, region: String, code: String = ""): List<YouFsAccountDevice> = synchronized(accountLock) {
        val user = normalizeAccount(account,country)
        val identity = YouFsCloudCodec.identity("${user.lowercase()}|$country|$region")
        val saved = remembered()?.takeIf { it.optString("owner") == identity }
        val id = saved?.optString("installationId")?.takeIf(String::isNotBlank)
            ?: ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val client = gateway?.takeIf { owner == identity } ?: gatewayFactory(region,id)
        gateway = client; owner = identity
        val record = JSONObject().put("owner",identity).put("account",user).put("country",country).put("region",region).put("installationId",id)
        if (saved != null) { client.session = saved.optString("session").takeIf(String::isNotBlank); client.ecode = saved.optString("ecode").takeIf(String::isNotBlank) }
        var valid = false
        if (!client.session.isNullOrEmpty() && !client.ecode.isNullOrEmpty()) {
            try { client.call("thing.m.user.info.get", authenticated = true); valid = true }
            catch (error: YouFsCloudError) {
                if (error.code !in setOf("USER_SESSION_INVALID", "SESSION_INVALID", "USER_SESSION_EXPIRED")) throw error
            }
        }
        if (!valid) {
            vault.write(record.toString().toByteArray())
            if (password.isEmpty()) throw YouFsCloudError("PASSWORD_REQUIRED")
            if (code.isNotEmpty() && (!code.matches(Regex("[0-9]{6}")) || challengeOwner != identity)) throw YouFsCloudError("MFA_CODE_INVALID")
            val fields = passwordFields(client,user,password,country,code)
            val email = '@' in user
            fields.put(if (email) "email" else "mobile",user)
            val result = try { client.call(if (email) "thing.m.user.email.password.login" else "thing.m.user.mobile.passwd.login",fields) as? JSONObject }
            catch (error: YouFsCloudError) { if (error.requiresCode) challengeOwner = identity; throw error }
                ?: throw YouFsCloudError("GATEWAY_REJECTED")
            val inner = result.optJSONObject("result") ?: result
            client.session = listOf("session","mSession","sid","mt").firstNotNullOfOrNull { inner.optString(it).takeIf { value -> value.length > 8 } }
            client.ecode = listOf("ecode","eCode").firstNotNullOfOrNull { inner.optString(it).takeIf(String::isNotBlank) }
            if (client.session.isNullOrEmpty() || client.ecode.isNullOrEmpty()) throw YouFsCloudError("GATEWAY_REJECTED")
            challengeOwner = ""
        }
        record.put("session",client.session).put("ecode",client.ecode)
        vault.write(record.toString().toByteArray())
        records.clear()
        val homes = client.call("m.life.group.location.list", authenticated = true) as? JSONArray ?: throw YouFsCloudError("GATEWAY_REJECTED")
        for (i in 0 until homes.length()) {
            val home = homes.optJSONObject(i) ?: continue
            val gid = listOf("gid","groupId","homeId").firstNotNullOfOrNull { home.optString(it).toLongOrNull() } ?: continue
            fun collect(node: Any?, depth: Int = 0) {
                if (depth > 12) return
                when (node) {
                    is JSONArray -> for (j in 0 until node.length()) collect(node.opt(j),depth+1)
                    is JSONObject -> {
                        val deviceId = listOf("devId","deviceId","id").firstNotNullOfOrNull { node.optString(it).takeIf(String::isNotBlank) }
                        if (deviceId != null && (node.has("localKey") || node.has("name") || node.has("productId"))) records[deviceId] = node
                        else node.keys().forEach { key -> collect(node.opt(key),depth+1) }
                    }
                }
            }
            collect(client.call("m.life.my.group.device.list",JSONObject().put("gid",gid),authenticated = true))
        }
        records.map { (id,data) -> YouFsAccountDevice(id,data.optString("name",appText(R.string.scooter)).take(80)) }
    }
    private fun passwordFields(client: YouFsCloudGateway, user: String, password: String, country: String, code: String): JSONObject {
        val token = client.call("thing.m.user.username.token.get",JSONObject().put("countryCode",country).put("username",user).put("isUid",false)) as? JSONObject
        if (token?.optString("token").isNullOrBlank()) throw YouFsCloudError("GATEWAY_REJECTED")
        return JSONObject().put("countryCode",country).put("passwd",YouFsCloudCodec.md5(password)).put("token",token!!.getString("token"))
            .put("ifencrypt",0).put("options",JSONObject().put("group",1).put("mfaCode",code).toString())
    }
    fun requestCode(account: String, password: String, country: String, region: String) {
        val user = normalizeAccount(account,country)
        val identity = YouFsCloudCodec.identity("${user.lowercase()}|$country|$region")
        if (challengeOwner != identity || '@' !in user || password.isEmpty()) throw YouFsCloudError("PASSWORD_REQUIRED")
        val client = gateway ?: throw YouFsCloudError("PASSWORD_REQUIRED")
        client.call("thing.m.user.username.mfa.code.get",passwordFields(client,user,password,country,"null").put("username",user))
    }
    fun fetch(id: String): ByteArray {
        val record = records[id] ?: throw YouFsCloudError("DEVICE_UNSUPPORTED")
        val detail = gateway?.call("thing.m.device.get",JSONObject().put("devId",id),authenticated = true) as? JSONObject ?: throw YouFsCloudError("DEVICE_UNSUPPORTED")
        if (detail.has("devId") && detail.optString("devId") != id) throw YouFsCloudError("DEVICE_UNSUPPORTED")
        val combined = JSONObject(record.toString()); detail.keys().forEach { combined.put(it,detail.get(it)) }
        val schema = combined.opt("schema").let { if (it is String) JSONArray(it) else it as? JSONArray } ?: throw YouFsCloudError("DEVICE_UNSUPPORTED")
        combined.put("deviceId",id).put("schema",schema).put("securityMode","new")
        val raw = combined.optString("mac").replace(Regex("[^0-9a-fA-F]"),"")
        if (raw.length == 12) combined.put("address",raw.chunked(2).joinToString(":").uppercase())
        val bytes = combined.toString().toByteArray()
        try { YouFsProfile.parse(bytes) } catch (_: Exception) { bytes.fill(0); throw YouFsCloudError("DEVICE_UNSUPPORTED") }
        return bytes
    }
    private fun normalizeAccount(account: String, country: String): String {
        require(country.matches(Regex("[1-9][0-9]{0,2}"))) { appText(R.string.invalid_country_code) }
        val text = account.trim()
        if ('@' in text) { require(text.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) { appText(R.string.invalid_email) }; return text }
        var number = text.replace(Regex("[\\s()-]"),"")
        if (number.startsWith("+")) { require(number.startsWith("+$country")) { appText(R.string.the_phone_number_does_not_match_the_country_code) }; number = number.removePrefix("+$country") }
        require(number.matches(Regex("[0-9]{5,15}"))) { appText(R.string.invalid_email_or_phone_number) }
        return number
    }
}
