package it.bilar.sentinel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class YouFsCloudLiveTest {
    @Test fun realGatewayAcceptsSignedEncryptedTokenRequestWithoutAccountLogin() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveYouFs") == "true")
        // A reserved example address: no real password, login, email code, account or device write.
        val gateway = YouFsCloudGateway("EU",UUID.randomUUID().toString().replace("-","") + "0123456789abcdef")
        val result = gateway.call("thing.m.user.username.token.get",JSONObject().put("countryCode","39").put("username","sentinel-probe@example.com").put("isUid",false)) as JSONObject
        assertTrue(result.optString("token").isNotBlank())
    }
}
