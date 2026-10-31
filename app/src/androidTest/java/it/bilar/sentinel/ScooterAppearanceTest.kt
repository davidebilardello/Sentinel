package it.bilar.sentinel

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScooterAppearanceTest {
    @Test fun headlightChangesTheRenderedModelAndTurnsOffAgain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val lights = mutableStateOf(false)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var scene: SceneView? = null
        var animationFrame: ((Long) -> Unit)? = null
        fun find(view: View): SceneView? {
            if (view is SceneView) return view.takeIf { it.isAttachedToWindow && it.childNodes.any { child -> child is ModelNode } }
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        fun brightPixels(bitmap: Bitmap): Int {
            val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return pixels.count { pixel -> (pixel ushr 16 and 255) > 225 && (pixel ushr 8 and 255) > 225 && (pixel and 255) > 225 }
        }
        fun capture(name: String): Bitmap {
            Thread.sleep(700)
            val image = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.filesDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return image
        }
        try {
            scenario.onActivity { activity ->
                activity.setContent { MaterialTheme { Box(Modifier.fillMaxSize().background(Color(0xFF0B0E11)),contentAlignment = Alignment.Center) {
                    ScooterScene(lights.value,Color(0xFFFF4D5A),Modifier.fillMaxWidth().height(266.dp).padding(horizontal = 24.dp))
                } } }
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            val deadline = System.nanoTime() + 15_000_000_000L
            while (scene == null && System.nanoTime() < deadline) {
                scenario.onActivity { scene = find(it.window.decorView) }
                Thread.sleep(100)
            }
            scenario.onActivity {
                val model = scene!!.childNodes.filterIsInstance<ModelNode>().first()
                assertTrue(model.renderableNodes.any { it.name == "headlight" })
                assertTrue(model.renderableNodes.flatMap { it.materialInstances }.any { it.name == "electric lime" })
                assertEquals(1,model.renderableNodes.count { it.name?.startsWith("headlight beam ") == true })
                assertTrue(model.lightNodes.any { it.name == "headlight spot" })
                val asphalt = model.renderableNodes.first { it.name == "asphalt road" }.materialInstances.first()
                assertTrue("Texture transform missing: ${asphalt.material.parameters.map { it.name }}",asphalt.material.hasParameter("baseColorUvMatrix"))
                animationFrame = scene!!.onFrame
                scene!!.onFrame = null // Hold still while comparing the light.
            }
            val off = brightPixels(capture("headlight-off.png"))
            scenario.onActivity { lights.value = true }
            val on = brightPixels(capture("headlight-on.png"))
            assertTrue("The headlight must visibly illuminate: off=$off on=$on", on > off + 30)
            scenario.onActivity { lights.value = false }
            val restored = brightPixels(capture("headlight-restored.png"))
            assertTrue("Turning off must remove the headlight glow", restored < on - 30)
            scenario.onActivity { verifyMotionAndTouch(scene as ScooterSceneView,requireNotNull(animationFrame)) }
        } finally { scenario.close() }
    }

    private fun verifyMotionAndTouch(view: ScooterSceneView,frame: (Long) -> Unit) {
        val model = view.childNodes.filterIsInstance<ModelNode>().single()
        val front = model.emptyNodes.first { it.name == "front wheel" }
        val rear = model.emptyNodes.first { it.name == "rear wheel" }
        val tile = model.emptyNodes.first { it.name == "road tile 0" }
        val originalTileX = tile.position.x
        val time = System.nanoTime(); frame(time)
        view.motion.targetSpeedKmh = 18f
        repeat(120) { index -> frame(time+(index+1)*16_666_667L) }
        assertNotEquals(0f,front.rotation.z,.01f)
        assertEquals(front.rotation.z,rear.rotation.z,.001f)
        assertNotEquals(originalTileX,tile.position.x,.01f)
        view.motion.targetSpeedKmh = 0f
        repeat(180) { index -> frame(time+(index+121)*16_666_667L) }
        assertEquals(0f,view.motion.speedKmh,0f)
        val camera = view.cameraNode.position.copy()
        val scale = model.scale.copy()
        val yaw = model.rotation.y
        fun gesture(dx: Float,dy: Float) {
            val now = SystemClock.uptimeMillis()
            listOf(Triple(MotionEvent.ACTION_DOWN,50f,100f),Triple(MotionEvent.ACTION_MOVE,50f+dx,100f+dy),Triple(MotionEvent.ACTION_UP,50f+dx,100f+dy))
                .forEachIndexed { index,event -> MotionEvent.obtain(now,now+index*16L,event.first,event.second,event.third,0).also { view.dispatchTouchEvent(it); it.recycle() } }
        }
        gesture(200f,40f)
        assertNotEquals(yaw,model.rotation.y,.01f)
        val horizontalYaw = model.rotation.y
        gesture(0f,160f)
        assertEquals(horizontalYaw,model.rotation.y,.001f)
        assertEquals(0f,model.rotation.x,.001f); assertEquals(0f,model.rotation.z,.001f)
        assertEquals(camera,view.cameraNode.position); assertEquals(scale,model.scale)
        assertNull(view.cameraManipulator)
    }
}
