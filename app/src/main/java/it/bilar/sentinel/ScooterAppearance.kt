package it.bilar.sentinel

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode

internal fun updateScooterAppearance(scene: SceneView, lights: Boolean, accent: Color) {
    val model = scene.childNodes.filterIsInstance<ModelNode>().firstOrNull() ?: return
    val materials = model.renderableNodes.flatMap { it.materialInstances }.distinct()
    val linear = accent.convert(ColorSpaces.LinearSrgb)
    materials.filter { it.name == "electric lime" }.forEach {
        it.setParameter("baseColorFactor", linear.red, linear.green, linear.blue, 1f)
        it.setParameter("emissiveFactor", linear.red * .25f, linear.green * .25f, linear.blue * .25f)
    }
    model.renderableNodes.filter { it.name == "headlight" }.flatMap { it.materialInstances }.forEach {
        // Filament uses physical emission units; the old value of 2 was barely visible at this exposure.
        if (lights) {
            it.setParameter("baseColorFactor", .8f, .94f, 1f, 1f)
            it.setParameter("emissiveFactor", 120f, 140f, 160f)
        } else {
            it.setParameter("baseColorFactor", .025f, .035f, .045f, 1f)
            it.setParameter("emissiveFactor", 0f, 0f, 0f)
        }
    }
    model.renderableNodes.filter { it.name?.startsWith("headlight beam ") == true || it.name == "headlight pool" }.forEach {
        it.isVisible = lights; it.isShadowCaster = false
    }
    model.lightNodes.filter { it.name == "headlight spot" }.forEach { it.intensity = if(lights) 1200f else 0f }
    scene.view.bloomOptions = scene.view.bloomOptions.apply {
        enabled = lights; strength = .08f; resolution = 512; levels = 4; threshold = true
    }
}
