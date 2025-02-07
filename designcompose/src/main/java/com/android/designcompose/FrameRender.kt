/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.designcompose

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawContext
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import com.android.designcompose.definition.element.ShaderData
import com.android.designcompose.definition.element.ShaderUniform
import com.android.designcompose.definition.element.ShaderUniformValue.ValueTypeCase
import com.android.designcompose.definition.element.StrokeAlign
import com.android.designcompose.definition.element.ViewShape
import com.android.designcompose.definition.element.ViewShapeKt.vectorArc
import com.android.designcompose.definition.element.shaderFallbackColorOrNull
import com.android.designcompose.definition.element.viewShape
import com.android.designcompose.definition.layout.Overflow
import com.android.designcompose.definition.modifier.outsetOrNull
import com.android.designcompose.definition.plugin.ArcMeterData
import com.android.designcompose.definition.plugin.MeterData
import com.android.designcompose.definition.plugin.ProgressBarMeterData
import com.android.designcompose.definition.plugin.ProgressMarkerMeterData
import com.android.designcompose.definition.plugin.ProgressVectorMeterData
import com.android.designcompose.definition.plugin.RotationMeterData
import com.android.designcompose.definition.view.ViewStyle
import com.android.designcompose.definition.view.transformOrNull
import com.android.designcompose.squoosh.SquooshResolvedNode
import com.android.designcompose.utils.asBrush
import com.android.designcompose.utils.asComposeBlendMode
import com.android.designcompose.utils.asComposeTransform
import com.android.designcompose.utils.blurFudgeFactor
import com.android.designcompose.utils.fixedHeight
import com.android.designcompose.utils.fixedWidth
import com.android.designcompose.utils.getNodeRenderSize
import com.android.designcompose.utils.max
import com.android.designcompose.utils.pointsAsDp
import com.android.designcompose.utils.toColor
import com.android.designcompose.utils.toUniform
import com.android.designcompose.utils.useLayer
import java.lang.Float.max
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Calculate the x and y offset of this node from its parent when it has a rotation of 0. Since
// the node in Figma may already be rotated, we need to do some math to basically undo the rotation
// to figure out the offsets. We extract the angle from the matrix then use trig and the offsets
// already provided by Figma in style.left, style.top, and style.transform to do the calculations.
private fun calculateParentOffsets(
    style: ViewStyle,
    nodeWidth: Double,
    nodeHeight: Double,
    density: Float,
): Pair<Double, Double> {
    val decomposed = style.nodeStyle.transformOrNull.decompose(density)

    // X Node position offset by the X translation value of the transform matrix
    val nodeX =
        style.layoutStyle.margin.start.pointsAsDp(density).value.toDouble() + decomposed.translateX
    // Y Node position offset by the Y translation value of the transform matrix
    val nodeY =
        style.layoutStyle.margin.top.pointsAsDp(density).value.toDouble() + decomposed.translateY

    // Radius of the circle encapsulating the node
    val r = sqrt(nodeWidth * nodeWidth + nodeHeight * nodeHeight) / 2
    // Angle of the top left corner when not rotated
    val topLeftAngle = Math.toDegrees(atan(nodeHeight / -nodeWidth))
    // Current angle, offset by the top left corner angle
    val angleFromTopLeft =
        Math.toRadians(decomposed.angle.toDouble()) + Math.toRadians(topLeftAngle)
    val cos = abs(cos(angleFromTopLeft))
    val sin = abs(sin(angleFromTopLeft))

    var xOffset = nodeX - nodeWidth / 2
    if (decomposed.angle >= -90 - topLeftAngle && decomposed.angle < 90 - topLeftAngle)
        xOffset += r * cos
    else xOffset -= r * cos

    var yOffset = nodeY - nodeHeight / 2
    if (decomposed.angle <= -topLeftAngle && decomposed.angle >= -topLeftAngle - 180)
        yOffset += r * sin
    else yOffset -= r * sin
    return Pair(xOffset, yOffset)
}

private fun ViewStyle.getTransform(density: Float): androidx.compose.ui.graphics.Matrix {
    return nodeStyle.transformOrNull.asComposeTransform(density)
        ?: androidx.compose.ui.graphics.Matrix()
}

private fun lerp(start: Float, end: Float, percent: Float, density: Float): Float {
    return start * density + percent / 100F * (end - start) * density
}

private fun calculateRotationData(
    rotationData: RotationMeterData,
    meterValue: Float,
    style: ViewStyle,
    density: Float,
): androidx.compose.ui.graphics.Matrix {
    val rotation =
        (rotationData.start + meterValue / 100f * (rotationData.end - rotationData.start))
            .coerceDiscrete(rotationData.discrete, rotationData.discreteValue)

    val nodeWidth = style.fixedWidth(density)
    val nodeHeight = style.fixedHeight(density)

    // Calculate offsets from parent when the rotation is 0
    val offsets =
        calculateParentOffsets(style, nodeWidth.toDouble(), nodeHeight.toDouble(), density)
    val xOffsetParent = offsets.first
    val yOffsetParent = offsets.second

    // Calculate a rotation transform that rotates about the center of the
    // node and then moves by xOffset and yOffset
    val overrideTransform = androidx.compose.ui.graphics.Matrix()
    val moveX = nodeWidth / 2
    val moveY = nodeHeight / 2

    // First translate so we rotate about the center
    val translateOrigin = androidx.compose.ui.graphics.Matrix()
    translateOrigin.translate(-moveX, -moveY, 0f)
    overrideTransform.timesAssign(translateOrigin)

    // Perform the rotation
    val rotate = androidx.compose.ui.graphics.Matrix()
    rotate.rotateZ(-rotation)
    overrideTransform.timesAssign(rotate)

    // Translate back, with an additional offset from the parent
    val translateBack = androidx.compose.ui.graphics.Matrix()
    translateBack.translate(
        moveX - style.layoutStyle.margin.start.pointsAsDp(density).value + xOffsetParent.toFloat(),
        moveY - style.layoutStyle.margin.top.pointsAsDp(density).value + yOffsetParent.toFloat(),
        0f,
    )
    overrideTransform.timesAssign(translateBack)
    return overrideTransform
}

private fun calculateProgressBarData(
    progressBarData: ProgressBarMeterData,
    meterValue: Float,
    style: ViewStyle,
    parent: SquooshResolvedNode?,
    density: Float,
): Pair<Size, androidx.compose.ui.graphics.Matrix?> {
    // Progress bar discrete values are done by percentage
    val discretizedMeterValue =
        meterValue.coerceDiscrete(progressBarData.discrete, progressBarData.discreteValue)

    // Resize the progress bar by interpolating between 0 and endX or endY depending on whether it
    // is a horizontal or vertical progress bar
    if (progressBarData.vertical) {
        val width = style.layoutStyle.width.pointsAsDp(density).value
        // Calculate bar extents from the parent layout if it exists, or from the progress bar data
        // if not.
        var endY = progressBarData.endY
        parent?.let { p ->
            val parentSize = p.computedLayout?.let { Size(it.width, it.height) }
            parentSize?.let {
                val parentRenderSize = getNodeRenderSize(null, parentSize, p.style, p.layoutId, 1f)
                endY = parentRenderSize.height
            }
        }
        val barHeight = lerp(0F, endY, discretizedMeterValue, density)
        val moveY = (endY * density - barHeight)
        val topOffset = style.layoutStyle.margin.top.pointsAsDp(density).value
        val overrideTransform = style.getTransform(density)
        overrideTransform.setYTranslation(moveY - topOffset)
        return Pair(Size(width, barHeight), overrideTransform)
    } else {
        val height = style.layoutStyle.height.pointsAsDp(density).value
        // Calculate bar extents from the parent layout if it exists, or from the progress bar data
        // if not.
        var endX = progressBarData.endX
        parent?.let { p ->
            val parentSize = p.computedLayout?.let { Size(it.width, it.height) }
            parentSize?.let {
                val parentRenderSize = getNodeRenderSize(null, parentSize, p.style, p.layoutId, 1f)
                endX = parentRenderSize.width
            }
        }
        val barWidth = lerp(0F, endX, discretizedMeterValue, density)
        return Pair(Size(barWidth, height), null)
    }
}

private fun calculateProgressMarkerData(
    markerData: ProgressMarkerMeterData,
    meterValue: Float,
    style: ViewStyle,
    node: SquooshResolvedNode?,
    parent: SquooshResolvedNode?,
    density: Float,
): androidx.compose.ui.graphics.Matrix {
    // Progress marker discrete values are done by percentage
    val discretizedMeterValue =
        meterValue.coerceDiscrete(markerData.discrete, markerData.discreteValue)

    // Calculate node and parent render sizes if available. These will only be available for
    // squoosh, and will be used to calculate the progress sizes and extents
    val mySize =
        node?.let { l ->
            val mySize = l.computedLayout?.let { Size(it.width, it.height) }
            mySize?.let { getNodeRenderSize(null, it, style, l.layoutId, 1f) }
        }
    val parentSize =
        parent?.let { p ->
            val pSize = p.computedLayout?.let { Size(it.width, it.height) }
            pSize?.let { getNodeRenderSize(null, it, p.style, p.layoutId, 1f) }
        }

    // The indicator mode means we don't resize the node; we just move it
    // along the x or y axis depending on whether it is horizontal or vertical
    val overrideTransform = style.getTransform(density)
    if (markerData.vertical) {
        val startY =
            parentSize?.let { it.height - (mySize?.height ?: 0f) / 2f } ?: markerData.startY
        val endY = mySize?.let { -it.height / 2f } ?: markerData.endY
        val moveY = lerp(startY, endY, discretizedMeterValue, density)
        val topOffset = style.layoutStyle.margin.top.pointsAsDp(density).value
        overrideTransform.setYTranslation(moveY - topOffset)
    } else {
        val startX = mySize?.let { -it.width / 2f } ?: markerData.startX
        val endX = parentSize?.let { it.width - (mySize?.width ?: 0f) / 2f } ?: markerData.endX
        val moveX = lerp(startX, endX, discretizedMeterValue, density)
        val leftOffset = style.layoutStyle.margin.start.pointsAsDp(density).value
        overrideTransform.setXTranslation(moveX - leftOffset)
    }

    return overrideTransform
}

private fun calculateArcData(
    arcData: ArcMeterData,
    meterValue: Float,
    shape: ViewShape,
): ViewShape {
    // Max out the arc to just below a full circle to avoid having the
    // path completely disappear
    val arcMeterValue = meterValue.coerceAtMost(99.999F)
    val arcAngleMeter =
        (arcMeterValue / 100f * (arcData.end - arcData.start)).coerceDiscrete(
            arcData.discrete,
            arcData.discreteValue,
        )
    return if (!shape.hasArc()) shape
    else
        viewShape {
            arc = vectorArc {
                strokeCap = shape.arc.strokeCap
                startAngleDegrees = arcData.start
                sweepAngleDegrees = arcAngleMeter
                innerRadius = shape.arc.innerRadius
                cornerRadius = arcData.cornerRadius
                isMask = shape.arc.isMask
            }
        }
}

// Set up the paint object to render a vector path as a stroke with a single dash that matches the
// length of the current progress within the vector.
private fun calculateProgressVectorData(
    data: ProgressVectorMeterData,
    paths: ComputedPaths,
    p: Paint,
    style: ViewStyle,
    meterValue: Float,
    density: Float,
) {
    val strokeWidth = style.nodeStyle.stroke.strokeWeight.toUniform() * density
    val discretizedMeterValue = meterValue.coerceDiscrete(data.discrete, data.discreteValue)

    // Get full length of path
    var pathLen = 0f
    paths.strokes.forEach {
        val measure = PathMeasure()
        measure.setPath(it, false)
        pathLen += measure.length
    }
    // Create intervals for dashed effect so that the first interval (the solid dash portion) is
    // equal to the length of the vector multiplied by the meter value. The second interval
    // (the empty portion of the dash) is large enough to cover the rest of the path.
    val intervals = floatArrayOf(discretizedMeterValue / 100f * pathLen, pathLen)
    p.pathEffect = PathEffect.dashPathEffect(intervals, 0f)
    p.strokeWidth = strokeWidth
    p.style = PaintingStyle.Stroke
    paths.strokeCap?.let { p.strokeCap = it }
}

private fun renderPaths(drawContext: DrawContext, paths: List<Path>, brushes: List<Paint>) {
    for (path in paths) {
        for (paint in brushes) {
            drawContext.canvas.drawPath(path, paint)
        }
    }
}

internal fun ContentDrawScope.squooshShapeRender(
    drawContext: DrawContext,
    density: Float,
    size: Size,
    node: SquooshResolvedNode,
    frameShape: ViewShape,
    document: DocContent,
    customizations: CustomizationContext,
    variableState: VariableState,
    computedPathCache: ComputedPathCache,
    appContext: Context,
    drawContent: () -> Unit,
) {
    if (size.width <= 0F && size.height <= 0F) return
    val overrideLayoutSize = node.overrideLayoutSize
    val style = node.style
    val name = node.view.name

    drawContext.canvas.save()

    var overrideTransform: androidx.compose.ui.graphics.Matrix? = null
    var rectSize: Size? = if (overrideLayoutSize) size else null
    var shape = frameShape
    var customArcAngle = false
    var progressVectorMeterData: ProgressVectorMeterData? = null

    val meterValue =
        customizations.getMeterValue(name) ?: customizations.getMeterState(name)?.floatValue
    // Check if there is meter data for a dial/gauge/progress bar
    if (meterValue != null && style.nodeStyle.hasMeterData()) {
        with(style.nodeStyle.meterData) {
            when (meterDataTypeCase) {
                MeterData.MeterDataTypeCase.ROTATION_DATA -> {
                    if (rotationData.enabled) {
                        overrideTransform =
                            calculateRotationData(rotationData, meterValue, style, density)
                    }
                }

                MeterData.MeterDataTypeCase.PROGRESS_BAR_DATA -> {
                    if (progressBarData.enabled) {
                        val progressBarSizeTransform =
                            calculateProgressBarData(
                                progressBarData,
                                meterValue,
                                style,
                                node.parent,
                                density,
                            )
                        rectSize = progressBarSizeTransform.first
                        overrideTransform = progressBarSizeTransform.second
                    }
                }

                MeterData.MeterDataTypeCase.PROGRESS_MARKER_DATA -> {
                    if (progressMarkerData.enabled) {
                        overrideTransform =
                            calculateProgressMarkerData(
                                progressMarkerData,
                                meterValue,
                                style,
                                node,
                                node.parent,
                                density,
                            )
                    }
                }

                MeterData.MeterDataTypeCase.ARC_DATA -> {
                    if (arcData.enabled) {
                        shape = calculateArcData(arcData, meterValue, shape)
                        customArcAngle = true
                    }
                }

                MeterData.MeterDataTypeCase.PROGRESS_VECTOR_DATA -> {
                    // If this is a vector path progress bar, save it here so we can convert it to a
                    // set of path instructions and render it instead of the normal stroke.
                    if (progressVectorData.enabled) progressVectorMeterData = progressVectorData
                }

                else -> {}
            }
        }
    }

    // Push any transforms
    val transform = overrideTransform ?: style.nodeStyle.transformOrNull.asComposeTransform(density)
    var vectorScaleX = 1F
    var vectorScaleY = 1F
    if (transform != null) {
        val decomposed = style.nodeStyle.transformOrNull.decompose(density)
        vectorScaleX = abs(decomposed.scaleX)
        vectorScaleY = abs(decomposed.scaleY)
        drawContext.transform.transform(transform)
    }

    // Compute the paths we will render from the shape.
    // This could benefit from more optimization:
    //  - Extract from the "draw" phase, or cache across draws (as the path generally doesn't
    // change)
    //  - Generate "rect" and "rounded rect" as special cases, because Skia has fastpaths for those.
    val shapePaths =
        shape.computePaths(
            style,
            density,
            size,
            // Pass in the actual layout-calculated size to ensure that layout size animations work
            // correctly. This likely breaks size calculation for rotated nodes, because
            // DesignCompose weirdly considers the size to be "post rotation bounding box" but
            // layout doesn't actually consider rotation yet.
            rectSize,
            customArcAngle,
            node.layoutId,
            variableState,
            computedPathCache,
        )

    // Blend mode
    val blendMode = style.nodeStyle.blendMode.asComposeBlendMode()
    val useBlendMode = style.nodeStyle.blendMode.useLayer()
    val opacity = style.nodeStyle.takeIf { it.hasOpacity() }?.opacity ?: 1.0f

    // Always use saveLayer for opacity; no graphicsLayer since we're not in
    // Compose.
    if (useBlendMode || opacity < 1.0f) {
        val paint = Paint()
        paint.alpha = opacity
        paint.blendMode = blendMode
        // Compute the outset of the layer - it must include the bounds of any outset
        // stroke or shadow.
        var shadowOutset = 0.0f
        for (shadow in shapePaths.shadowFills) {
            shadow.shadowStyle.outsetOrNull?.let { shadowBox ->
                shadowOutset =
                    max(
                        shadowOutset,
                        shadowBox.blurRadius * blurFudgeFactor +
                            shadowBox.spreadRadius +
                            max(shadowBox.offsetX, shadowBox.offsetY),
                    )
            }
        }
        var strokeOutset = 0.0f
        val strokeStyle = style.nodeStyle.stroke
        if (strokeStyle.strokesList.isNotEmpty()) {
            strokeOutset =
                max(
                    strokeOutset,
                    when (strokeStyle.strokeAlign) {
                        StrokeAlign.STROKE_ALIGN_OUTSIDE -> strokeStyle.strokeWeight.max()
                        StrokeAlign.STROKE_ALIGN_CENTER -> strokeStyle.strokeWeight.max() / 2.0f
                        else -> 0.0f
                    },
                )
        }
        // The shadow outset is additive to the stroke outset, as shadows are applied to the stroke
        // bounds, not the node bounds.
        val outset = strokeOutset + shadowOutset
        // Now we can save the layer with the appropriate bounds.
        drawContext.canvas.saveLayer(Rect(Offset.Zero, size).inflate(outset * density), paint)
    }

    val customFillBrush = getCustomBrush(node, customizations)

    val brushSize = getNodeRenderSize(rectSize, size, style, node.layoutId, density)
    val fillBrush: List<Paint> =
        if (customFillBrush != null) {
            val p = Paint()
            customFillBrush.applyTo(brushSize, p, 1.0f)
            listOf(p)
        } else {
            style.nodeStyle.backgroundsList.mapNotNull { background ->
                val p = Paint()
                val b = background.asBrush(appContext, document, density, variableState)
                if (b != null) {
                    val (brush, fillOpacity) = b
                    brush.applyTo(brushSize, p, fillOpacity)
                    p
                } else {
                    null
                }
            }
        }
    val strokeBrush =
        if (style.nodeStyle.stroke.hasShaderData()) {
            val p = Paint()
            progressVectorMeterData?.let {
                calculateProgressVectorData(it, shapePaths, p, style, meterValue!!, density)
            }
            val b =
                getShaderBrush(
                    style.nodeStyle.stroke.shaderData,
                    customizations.getShaderUniformCustomizations(node.view.name),
                    customizations.getShaderTimeUniformState(),
                    asBackground = false,
                )
            b.applyTo(brushSize, p, 1.0f)
            listOf(p)
        } else
            style.nodeStyle.stroke.strokesList.mapNotNull { background ->
                val p = Paint()
                progressVectorMeterData?.let {
                    calculateProgressVectorData(it, shapePaths, p, style, meterValue!!, density)
                }
                val b = background.asBrush(appContext, document, density, variableState)
                if (b != null) {
                    val (brush, strokeOpacity) = b
                    brush.applyTo(brushSize, p, strokeOpacity)
                    p
                } else {
                    null
                }
            }

    // Outset shadows
    // XXX: only do this if there are shadows.
    drawContext.canvas.save()
    // Don't draw shadows under objects.
    shapePaths.shadowClips.forEach { path -> drawContext.canvas.clipPath(path, ClipOp.Difference) }

    // Now paint the outset shadows.
    shapePaths.shadowFills
        .filter { it.shadowStyle.hasOutset() }
        .forEach { shadow ->
            val shadowBox = shadow.shadowStyle.outset

            // Make an appropriate paint.
            val shadowPaint = Paint().asFrameworkPaint()
            shadowPaint.color = shadowBox.color.getValue(variableState)?.toArgb() ?: return@forEach
            if (shadowBox.blurRadius > 0.0f) {
                shadowPaint.maskFilter =
                    BlurMaskFilter(
                        shadowBox.blurRadius * density * blurFudgeFactor,
                        BlurMaskFilter.Blur.NORMAL,
                    )
            }
            drawContext.canvas.translate(shadowBox.offsetX * density, shadowBox.offsetY * density)
            shadow.fills.forEach { shadowPath ->
                drawContext.canvas.nativeCanvas.drawPath(shadowPath.asAndroidPath(), shadowPaint)
            }
            drawContext.canvas.translate(-shadowBox.offsetX * density, -shadowBox.offsetY * density)
        }
    drawContext.canvas.restore()

    // Now draw the actual shape, or fill it with an image if we have an image
    // replacement; we might want to do image replacement as a Brush in the
    // future.
    var customImage = customizations.getImage(name)
    if (customImage == null) {
        // Check for an image customization with context. If it exists, call the custom image
        // function and provide it with the frame's background and size.
        customizations.getImageWithContext(node.view.name)?.let {
            customImage =
                it(
                    object : ImageReplacementContext {
                        override val imageContext =
                            ImageContext(
                                background = node.style.nodeStyle.backgroundsList,
                                minWidth = node.style.layoutStyle.minWidth,
                                maxWidth = node.style.layoutStyle.maxWidth,
                                width = node.style.layoutStyle.width,
                                minHeight = node.style.layoutStyle.minHeight,
                                maxHeight = node.style.layoutStyle.maxHeight,
                                height = node.style.layoutStyle.height,
                            )
                    }
                )
        }
    }
    if (customImage != null) {
        // Apply custom image as background
        drawContext.canvas.save()
        for (fill in shapePaths.fills) {
            drawContext.canvas.clipPath(fill)
        }
        drawImage(
            customImage!!.asImageBitmap(),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        )
        drawContext.canvas.restore()
    } else {
        renderPaths(drawContext, shapePaths.fills, fillBrush)
    }

    // Now do inset shadows
    drawContext.canvas.save()
    // Don't draw inset shadows outside of the stroke bounds.
    shapePaths.shadowClips.forEach { path -> drawContext.canvas.clipPath(path) }
    val shadowOutlinePaint = android.graphics.Paint()
    shadowOutlinePaint.style = android.graphics.Paint.Style.FILL_AND_STROKE
    val shadowSpreadPaint = android.graphics.Paint()
    shadowSpreadPaint.style = android.graphics.Paint.Style.STROKE

    shapePaths.shadowFills
        .filter { it.shadowStyle.hasInset() }
        .forEach { shadow ->
            val shadowBox = shadow.shadowStyle.inset

            // Make an appropriate paint.
            val shadowPaint = Paint().asFrameworkPaint()
            shadowPaint.color = shadowBox.color.getValue(variableState)?.toArgb() ?: return@forEach
            if (shadowBox.blurRadius > 0.0f) {
                shadowPaint.maskFilter =
                    BlurMaskFilter(
                        shadowBox.blurRadius * density * blurFudgeFactor,
                        BlurMaskFilter.Blur.NORMAL,
                    )
            }
            drawContext.canvas.translate(shadowBox.offsetX * density, shadowBox.offsetY * density)
            shadow.fills.forEach { shadowPath ->
                drawContext.canvas.nativeCanvas.drawPath(shadowPath.asAndroidPath(), shadowPaint)
            }
            drawContext.canvas.translate(-shadowBox.offsetX * density, -shadowBox.offsetY * density)
        }
    drawContext.canvas.restore()

    // Now draw our stroke and our children. The order of drawing the stroke and the
    // children is different depending on whether we clip children.
    val shouldClip = (style.nodeStyle.overflow) == Overflow.OVERFLOW_HIDDEN
    if (shouldClip) {
        // Clip children, and paint our stroke on top of them.
        drawContext.canvas.save()
        for (fill in shapePaths.fills) {
            drawContext.canvas.clipPath(fill)
        }
        drawContent()
        drawContext.canvas.restore()
        renderPaths(drawContext, shapePaths.strokes, strokeBrush)
    } else {
        // No clipping; paint our stroke first and then paint our children.
        renderPaths(drawContext, shapePaths.strokes, strokeBrush)
        drawContent()
    }

    if (useBlendMode || opacity < 1.0f) {
        drawContext.canvas.restore()
    }
    drawContext.canvas.restore()
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun ShaderUniform.applyToShader(
    shader: RuntimeShader,
    shaderUniformMap: Map<String, ShaderUniform>,
) {
    val definedType = shaderUniformMap[name]?.type
    when (value.valueTypeCase) {
        ValueTypeCase.FLOAT_VALUE -> {
            if (definedType == "float" || definedType == "iTime" || definedType == "half") {
                shader.setFloatUniform(name, value.floatValue)
            }
        }

        ValueTypeCase.FLOAT_VEC_VALUE -> {
            val floatVecValue = value.floatVecValue.floatsList
            when (floatVecValue.size) {
                1 ->
                    if (definedType == "float" || definedType == "half")
                        shader.setFloatUniform(name, floatVecValue[0])
                2 ->
                    if (definedType == "float2" || definedType == "half2")
                        shader.setFloatUniform(name, floatVecValue[0], floatVecValue[1])
                3 ->
                    if (definedType == "float3" || definedType == "half3")
                        shader.setFloatUniform(
                            name,
                            floatVecValue[0],
                            floatVecValue[1],
                            floatVecValue[2],
                        )
                4 ->
                    if (definedType == "float4" || definedType == "half4")
                        shader.setFloatUniform(
                            name,
                            floatVecValue[0],
                            floatVecValue[1],
                            floatVecValue[2],
                            floatVecValue[3],
                        )
                else -> Log.e(TAG, "Invalid shader uniform $name $definedType")
            }
        }

        ValueTypeCase.FLOAT_COLOR_VALUE -> {
            when (definedType) {
                "float3",
                "color3" ->
                    shader.setFloatUniform(
                        name,
                        value.floatColorValue.r,
                        value.floatColorValue.g,
                        value.floatColorValue.b,
                    )
                "float4",
                "color4" ->
                    shader.setFloatUniform(
                        name,
                        value.floatColorValue.r,
                        value.floatColorValue.g,
                        value.floatColorValue.b,
                        value.floatColorValue.a,
                    )
                else -> Log.e(TAG, "Invalid shader uniform $name $definedType")
            }
        }
        ValueTypeCase.INT_VALUE -> {
            if (definedType == "int") {
                shader.setIntUniform(name, value.intValue)
            }
        }

        else -> {
            Log.w(TAG, "Invalid shader uniform $name")
        }
    }
}

internal fun getCustomBrush(
    node: SquooshResolvedNode,
    customizations: CustomizationContext,
): Brush? {
    val nodeName = node.view.name
    var customFillBrush = customizations.getBrush(nodeName)
    if (customFillBrush == null) {
        node.view.style.nodeStyle
            .takeIf { it.hasShaderData() }
            ?.shaderData
            ?.let {
                customFillBrush =
                    getShaderBrush(
                        it,
                        customizations.getShaderUniformCustomizations(nodeName),
                        customizations.getShaderTimeUniformState(),
                    )
            }
    }
    return customFillBrush
}

internal fun getShaderBrush(
    shaderData: ShaderData,
    shaderUniformCustomizations: ShaderUniformCustomizations?,
    shaderTimeUniformState: State<ShaderUniform>?,
    asBackground: Boolean = true,
): Brush {
    lateinit var shaderBrush: Brush
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shaderProg = shaderData.shader.trim().trimIndent()
        val shader = RuntimeShader(shaderProg)
        shaderBrush = SizingShaderBrush(shader)
        shaderData.shaderUniformsMap.forEach { (_, v) ->
            v.applyToShader(shader, shaderData.shaderUniformsMap)
        }
        val shaderUniformList =
            if (asBackground) shaderUniformCustomizations?.backgroundShaderUniforms
            else shaderUniformCustomizations?.strokeShaderUniforms
        shaderUniformList?.forEach { customUniform ->
            if (shaderData.shaderUniformsMap.containsKey(customUniform.name)) {
                customUniform.applyToShader(shader, shaderData.shaderUniformsMap)
            }
        }
        val shaderUniformStateList =
            if (asBackground) shaderUniformCustomizations?.backgroundShaderUniformStates
            else shaderUniformCustomizations?.strokeShaderUniformStates
        shaderUniformStateList?.forEach { customUniformState ->
            val customUniform = customUniformState.value
            if (shaderData.shaderUniformsMap.containsKey(customUniform.name)) {
                customUniform.applyToShader(shader, shaderData.shaderUniformsMap)
            }
        }
        shaderTimeUniformState?.value?.let {
            if (shaderData.shaderUniformsMap.containsKey(it.name)) {
                it.applyToShader(shader, shaderData.shaderUniformsMap)
            }
        }
    } else {
        shaderData.shaderFallbackColorOrNull?.let { color ->
            shaderBrush = SolidColor(color.toColor())
        }
    }
    return shaderBrush
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class SizingShaderBrush(private val shader: RuntimeShader) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        shader.setFloatUniform("iResolution", size.width, size.height, 0.0f)
        return shader
    }
}
