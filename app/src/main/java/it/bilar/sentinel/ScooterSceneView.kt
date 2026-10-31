package it.bilar.sentinel

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlin.math.abs
import com.google.android.filament.MaterialInstance

internal class ScooterSceneView(context: Context) : SceneView(context = context,isOpaque = false) {
    val motion = ScooterMotion()
    private var scooter: ModelNode? = null
    private var wheels = emptyList<Node>()
    private var roadTiles = emptyList<Node>()
    private var roadPaint = emptyList<MaterialInstance>()
    private var asphalt: MaterialInstance? = null
    private val asphaltUv = floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
    private var lastFrame = 0L
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var horizontal = false
    private var vertical = false
    private var multiplePointers = false

    fun initialize() {
        val model = ModelNode(modelLoader.createModelInstance("models/sentinel.glb"),autoAnimate = false).apply {
            scale = Scale(1.3f)
            rotation = Rotation(y = motion.yawDegrees)
            isEditable = false
        }
        scooter = model
        wheels = model.emptyNodes.filter { it.name == "front wheel" || it.name == "rear wheel" }
        roadTiles = (0 until ScooterMotion.ROAD_TILES).map { index -> model.emptyNodes.first { it.name == "road tile $index" } }
        val materials = model.renderableNodes.flatMap { it.materialInstances }.distinct()
        asphalt = materials.first { it.name == "asphalt" }
        roadPaint = (0 until ScooterMotion.ROAD_TILES).map { index -> materials.first { it.name == "moving road paint $index" } }
        model.renderableNodes.filter { it.name == "asphalt road" || it.name == "road edge paint" || it.name == "road dash" }.forEach { it.isShadowCaster = false }
        childNodes = listOf(model)
        cameraManipulator = null
        cameraNode.position = Position(2.9f,1.7f,4.6f)
        cameraNode.lookAt(Position(0f,-.7f,0f))
        onFrame = { time ->
            if(lastFrame != 0L) motion.advance((time-lastFrame)/1e9f)
            lastFrame = time
            wheels.forEach { it.rotation = Rotation(z = motion.wheelDegrees) }
            roadTiles.forEachIndexed { index,node ->
                val x = motion.roadTileX(index)
                node.position = Position(x = x)
                roadPaint[index].setParameter("baseColorFactor",.55f,.61f,.65f,motion.roadOpacity(x))
            }
            asphaltUv[6] = motion.roadOffset/ScooterMotion.ROAD_TILE_LENGTH
            asphalt?.setParameter("baseColorUvMatrix",MaterialInstance.FloatElement.MAT3,asphaltUv,0,1)
        }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; lastX = event.x; downY = event.y
                horizontal = false; vertical = false; multiplePointers = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { multiplePointers = true }
            MotionEvent.ACTION_MOVE -> {
                if(!multiplePointers) {
                    val dx = event.x-downX; val dy = event.y-downY
                    if(!horizontal && !vertical && maxOf(abs(dx),abs(dy)) > touchSlop) {
                        horizontal = abs(dx) >= abs(dy); vertical = !horizontal
                    }
                    if(horizontal) {
                        motion.drag(event.x-lastX,width)
                        scooter?.rotation = Rotation(y = motion.yawDegrees)
                    }
                    if(vertical) parent?.requestDisallowInterceptTouchEvent(false)
                }
                lastX = event.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if(event.actionMasked == MotionEvent.ACTION_UP && !horizontal && !vertical && !multiplePointers) performClick()
            }
        }
        return true
    }
    override fun performClick(): Boolean = super.performClick()
    override fun onFrame(frameTimeNanos: Long) { if(!isDestroyed) super.onFrame(frameTimeNanos) }
    override fun destroy() {
        // SceneView can deliver one last scheduled frame after detaching the AndroidView.
        if(!isDestroyed) { onFrame = null; childNodes = emptyList(); super.destroy() }
    }
}
