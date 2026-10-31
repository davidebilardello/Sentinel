package it.bilar.sentinel

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoogleSyncTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private fun sandbox(tag: String): Context {
        val key = "sync-test-$tag-${UUID.randomUUID()}"
        val folder = File(base.cacheDir,key).apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$key-$name",mode)
            override fun getFilesDir() = folder
        }
    }
    private class Cloud {
        val files = linkedMapOf<String,Pair<String,ByteArray>>()
        var uploads = 0
        var rejectedTokens = emptySet<String>()
        var rejectedPath: String? = null
        var rejectionCode = 401
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val token = request.header("Authorization")?.removePrefix("Bearer ")
            check(token in setOf("test-token","expired-token"))
            val path = request.url.encodedPath
            if(token in rejectedTokens && (rejectedPath == null || path == rejectedPath)) {
                return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(rejectionCode).message("Denied").body("{}".toResponseBody()).build()
            }
            val bytes = when {
                path == "/oauth2/v3/userinfo" -> JSONObject().put("sub","123456789").put("email","sync-test@example.com").put("email_verified",true).toString().toByteArray()
                path == "/drive/v3/files" -> JSONObject().put("files",JSONArray().apply { files.forEach { (id,value) -> put(JSONObject().put("id",id).put("name",value.first)) } }).toString().toByteArray()
                path.startsWith("/drive/v3/files/") -> files.getValue(path.substringAfterLast('/')).second
                request.method == "POST" -> {
                    val body = request.body as MultipartBody
                    val metadata = Buffer().also { body.part(0).body.writeTo(it) }.readUtf8()
                    val data = Buffer().also { body.part(1).body.writeTo(it) }.readByteArray()
                    val id = "file${files.size+1}"; files[id] = JSONObject(metadata).getString("name") to data; uploads++
                    JSONObject().put("id",id).toString().toByteArray()
                }
                request.method == "PATCH" -> {
                    val id = path.substringAfterLast('/'); val data = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
                    files[id] = files.getValue(id).first to data; uploads++; "{}".toByteArray()
                }
                else -> error("Unexpected API")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(bytes.toResponseBody("application/octet-stream".toMediaType())).build()
        }.build()
        fun drive(token: String) = GoogleDriveStore(token,http)
    }
    private fun profile(): ByteArray = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("youfs-schema.json").bufferedReader().use { it.readText() })
        .put("uuid","1234567890123456").put("deviceId","syncTest123").put("localKey","abcdef0123456789").put("secKey","fake-secret-1234").put("securityMode","new").toString().toByteArray()

    @Test fun revokedCachedTokenIsClearedBeforeFirstConnectionIsRetried() = runBlocking {
        val context = sandbox("revoked-connect"); val cloud = Cloud().apply { rejectedTokens = setOf("expired-token") }
        val database = "revoked-connect-${UUID.randomUUID()}.db"; val trips = TripStore(context,database)
        val cleared = mutableListOf<String>(); var cached = "expired-token"; var authorizations = 0
        val sync = GoogleSync(context,trips,cloud::drive) { token -> cleared += token; cached = "test-token" }
        try {
            val id = trips.begin(listOf(TrackPoint(45.0,9.0,1000,4f),TrackPoint(45.001,9.0,2000,4f))); trips.finish(id,3000)
            sync.authorizeAndSync { email -> assertNull(email); authorizations++; cached }
            assertEquals(listOf("expired-token"),cleared); assertEquals(2,authorizations)
            assertTrue(sync.state.value.connected); assertEquals(1,cloud.uploads); assertEquals(1,cloud.files.size)
            assertEquals(id,trips.trips.value.single().id)
            val saved = EncryptedJsonFile(context,"google-sync.enc").read()!!
            try {
                val record = JSONObject(saved.toString(Charsets.UTF_8))
                assertEquals(setOf("owner","email"),record.keys().asSequence().toSet())
            } finally { saved.fill(0) }
            sync.disconnect()
        } finally { trips.close(); base.deleteDatabase(database) }
    }
    @Test fun revocationDuringUploadRefreshesSameAccountWithoutDuplicateFilesOrTrips() = runBlocking {
        val context = sandbox("revoked-upload"); val cloud = Cloud()
        val database = "revoked-upload-${UUID.randomUUID()}.db"; val trips = TripStore(context,database)
        val cleared = mutableListOf<String>(); var cached = "expired-token"; var authorizations = 0
        val sync = GoogleSync(context,trips,cloud::drive) { token -> cleared += token; cached = "test-token" }
        try {
            val id = trips.begin(listOf(TrackPoint(45.0,9.0,1000,4f),TrackPoint(45.001,9.0,2000,4f))); trips.finish(id,3000)
            sync.connect("test-token")
            val fileId = cloud.files.keys.single()
            cloud.rejectedTokens = setOf("expired-token"); cloud.rejectedPath = "/upload/drive/v3/files/$fileId"
            sync.authorizeAndSync { email -> assertEquals("sync-test@example.com",email); authorizations++; cached }
            assertEquals(listOf("expired-token"),cleared); assertEquals(2,authorizations)
            assertEquals(setOf(fileId),cloud.files.keys); assertEquals(2,cloud.uploads)
            assertEquals(id,trips.trips.value.single().id); assertTrue(sync.state.value.connected)
            sync.disconnect()
        } finally { trips.close(); base.deleteDatabase(database) }
    }
    @Test fun backgroundRevocationClearsTokenAndKeepsLocalDataAndAccountForReconnect() = runBlocking {
        val context = sandbox("revoked-background"); val cloud = Cloud()
        val database = "revoked-background-${UUID.randomUUID()}.db"; val trips = TripStore(context,database)
        val cleared = mutableListOf<String>()
        val sync = GoogleSync(context,trips,cloud::drive) { token -> cleared += token }
        try {
            val id = trips.begin(listOf(TrackPoint(45.0,9.0,1000,4f),TrackPoint(45.001,9.0,2000,4f))); trips.finish(id,3000)
            sync.connect("test-token"); cloud.rejectedTokens = setOf("expired-token")
            val saved = EncryptedJsonFile(context,"google-sync.enc").read()!!
            try {
                try { sync.sync("expired-token"); fail("Revoked token accepted") }
                catch(error: DriveSyncError) { assertTrue(error.authorizationRequired); assertFalse(error.retryable) }
                assertEquals(listOf("expired-token"),cleared); assertTrue(sync.state.value.connected)
                assertFalse(sync.state.value.busy); assertEquals(1,cloud.uploads)
                assertArrayEquals(saved,EncryptedJsonFile(context,"google-sync.enc").read())
                assertEquals(id,trips.trips.value.single().id)
                sync.authorizeAndSync { email -> assertEquals("sync-test@example.com",email); "test-token" }
                assertEquals(2,cloud.uploads); assertEquals(1,cloud.files.size)
            } finally { saved.fill(0) }
            sync.disconnect()
        } finally { trips.close(); base.deleteDatabase(database) }
    }
    @Test fun repeatedUnauthorizedStopsAfterOneRetryAndForbiddenDoesNotRetry() = runBlocking {
        for(code in listOf(401,403)) {
            val context = sandbox("denied-$code")
            val cloud = Cloud().apply { rejectedTokens = setOf("expired-token"); rejectionCode = code }
            val database = "denied-${UUID.randomUUID()}.db"; val trips = TripStore(context,database)
            val cleared = mutableListOf<String>(); var authorizations = 0
            val sync = GoogleSync(context,trips,cloud::drive) { token -> cleared += token }
            try {
                val id = trips.begin(listOf(TrackPoint(45.0,9.0,1000,4f),TrackPoint(45.001,9.0,2000,4f))); trips.finish(id,3000)
                try { sync.authorizeAndSync { authorizations++; "expired-token" }; fail("Denied token accepted") }
                catch(error: DriveSyncError) { assertEquals(code == 401,error.authorizationRequired) }
                assertEquals(if(code == 401) 2 else 1,authorizations)
                assertEquals(if(code == 401) 2 else 0,cleared.size)
                assertEquals(0,cloud.uploads); assertTrue(cloud.files.isEmpty())
                assertFalse(sync.state.value.connected); assertFalse(sync.state.value.busy)
                assertFalse(context.getSharedPreferences("google-sync",Context.MODE_PRIVATE).getBoolean("enabled",false))
                assertEquals(id,trips.trips.value.single().id)
            } finally { trips.close(); base.deleteDatabase(database) }
        }
    }
    @Test fun syncsTwoPhonesWithoutDuplicatesAndPropagatesTripAndScooterDeletion() = runBlocking {
        val one = sandbox("one"); val two = sandbox("two"); val cloud = Cloud()
        val nameOne = "sync-one-${UUID.randomUUID()}.db"; val nameTwo = "sync-two-${UUID.randomUUID()}.db"
        val tripsOne = TripStore(one,nameOne); val tripsTwo = TripStore(two,nameTwo)
        try {
            YouFsProfileStore(one).save(profile())
            ScooterRegistry(one).save(SavedScooter("youfs","YouFs test",ScooterFamily.YOUFS_TUYA,"00:11:22:33:44:55"))
            tripsOne.setConnectedScooter(ScooterRegistry(one).all().first { it.id == "youfs" })
            val id = tripsOne.begin(listOf(TrackPoint(45.0,9.0,1000,4f),TrackPoint(45.001,9.0,2000,4f)))
            tripsOne.finish(id,3000)
            val first = GoogleSync(one,tripsOne,cloud::drive); val second = GoogleSync(two,tripsTwo,cloud::drive)
            first.connect("test-token")
            second.connect("test-token")
            assertEquals(1,tripsTwo.trips.value.size); assertEquals(2,tripsTwo.points(tripsTwo.trips.value.single().id).size)
            assertEquals(tripsOne.connectedScooter.value,tripsTwo.trips.value.single().scooter)
            assertEquals("syncTest123",YouFsProfileStore(two).load()!!.json.getString("deviceId"))
            second.sync("test-token"); first.sync("test-token")
            assertEquals(1,tripsOne.trips.value.size); assertEquals(1,tripsTwo.trips.value.size)
            assertEquals(1,ScooterRegistry(two).all().count { it.syncId?.startsWith("youfs:") == true })
            tripsOne.delete(id); ScooterRegistry(one).remove("youfs"); YouFsProfileStore(one).forget()
            first.sync("test-token"); second.sync("test-token"); first.sync("test-token")
            assertTrue(tripsTwo.trips.value.isEmpty()); assertTrue(tripsOne.trips.value.isEmpty())
            assertNull(YouFsProfileStore(two).load())
            assertTrue(ScooterRegistry(two).all().none { it.syncId == "youfs:syncTest123" })
            assertEquals(2,cloud.files.size)
            cloud.files.values.forEach { (_,bytes) ->
                val wrapper = JSONObject(PortableArchive.unpack(bytes).toString(Charsets.UTF_8))
                assertEquals("123456789",wrapper.getString("owner")); assertFalse(wrapper.has("password"))
            }
            val saved = EncryptedJsonFile(one,"google-sync.enc").read()!!
            try { assertFalse(JSONObject(saved.toString(Charsets.UTF_8)).has("password")) } finally { saved.fill(0) }
            first.disconnect(); second.disconnect()
        } finally { tripsOne.close(); tripsTwo.close(); base.deleteDatabase(nameOne); base.deleteDatabase(nameTwo) }
    }
    @Test fun foreignAccountArchiveLeavesLocalHistoryAndCloudUntouched() = runBlocking {
        val one = sandbox("password-one"); val two = sandbox("password-two"); val cloud = Cloud()
        val nameOne = "sync-pwd-one-${UUID.randomUUID()}.db"; val nameTwo = "sync-pwd-two-${UUID.randomUUID()}.db"
        val a = TripStore(one,nameOne); val b = TripStore(two,nameTwo)
        try {
            GoogleSync(one,a,cloud::drive).connect("test-token")
            val file = cloud.files.entries.single()
            val foreign = JSONObject(PortableArchive.unpack(file.value.second).toString(Charsets.UTF_8)).put("owner","other-account")
            cloud.files[file.key] = file.value.first to PortableArchive.compress(foreign.toString().toByteArray())
            val id = b.begin(listOf(TrackPoint(45.0,9.0,1000,4f),TrackPoint(45.001,9.0,2000,4f))); b.finish(id,3000)
            val before = cloud.uploads
            try { GoogleSync(two,b,cloud::drive).connect("test-token"); fail("Foreign account archive accepted") } catch(_: IllegalArgumentException) { }
            assertEquals(before,cloud.uploads); assertEquals(1,b.trips.value.size)
            assertFalse(two.getSharedPreferences("google-sync",Context.MODE_PRIVATE).getBoolean("enabled",false))
        } finally { a.close(); b.close(); base.deleteDatabase(nameOne); base.deleteDatabase(nameTwo) }
    }
    @Test fun mergeIsOrderIndependentAndDeletionWinsEqualTimestamp() {
        val active = SyncRecord("trip","id",100,false,JSONObject().put("a",1).put("b",2))
        val deleted = SyncRecord("trip","id",100,true)
        assertTrue(SyncRecord.merge(listOf(active,deleted)).single().deleted)
        val another = active.copy(data = JSONObject().put("b",3).put("a",1))
        assertEquals(SyncRecord.merge(listOf(active,another)).single().json().toString(),SyncRecord.merge(listOf(another,active)).single().json().toString())
        assertEquals(1,SyncRecord.decode(SyncRecord.encode(listOf(deleted))).size)
        val legacy = JSONObject(SyncRecord.encode(listOf(deleted)).toString(Charsets.UTF_8)).put("version",1)
        assertEquals(1,SyncRecord.decode(legacy.toString().toByteArray()).size)
        legacy.put("version",3)
        assertThrows(IllegalArgumentException::class.java) { SyncRecord.decode(legacy.toString().toByteArray()) }
    }
}
