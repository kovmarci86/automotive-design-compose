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

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import com.android.designcompose.common.NodeQuery
import com.android.designcompose.common.nodeNameToPropertyValueList
import com.android.designcompose.definition.element.Background
import com.android.designcompose.definition.element.ColorOrVar.ColorOrVarTypeCase
import com.android.designcompose.definition.element.DimensionProto
import com.android.designcompose.definition.view.ComponentInfo
import com.android.designcompose.definition.view.View
import com.android.designcompose.definition.view.componentInfoOrNull
import java.util.Optional
import kotlin.jvm.optionals.getOrNull

// Node data associated with a Figma node that may be a variant. This is used for grid layouts to
// determine the span of an item in the grid layout.
data class DesignNodeData(
    var nodeName: String = "",
    var variantProperties: HashMap<String, String> = HashMap(),
)

typealias GetDesignNodeData = () -> DesignNodeData

typealias GridSpanFunc = ((GetDesignNodeData) -> LazyContentSpan)

data class LazyContentSpan(val span: Int = 1, val maxLineSpan: Boolean = false)

data class ListContentData(
    var count: Int = 0,
    var key: ((index: Int) -> Any)? = null,
    var span: ((index: Int) -> LazyContentSpan)? = null,
    var contentType: (index: Int) -> Any? = { null },
    var initialSpan: (() -> LazyContentSpan)? = null,
    var initialContent: @Composable () -> Unit = {},
    var itemContent: @Composable (index: Int) -> Unit,
)

typealias ListContent = (GridSpanFunc) -> ListContentData

fun EmptyListContent(): ListContent {
    return { ListContentData { _ -> } }
}

data class ReplacementContent(
    var count: Int = 0,
    var content: ((index: Int) -> @Composable () -> Unit),
)

typealias TapCallback = () -> Unit

typealias MeterState = FloatState

typealias Meter = Float

typealias ShaderUniformTime = Float

typealias ShaderUniformTimeState = FloatState

// A class that holds the scroll state of a horizontal or vertical autolayout view (row/column)
data class DesignScrollState(
    // The current scroll position
    val value: Float,
    // The height of a vertical scrollable container or width of a horizontal scrollable container
    val containerSize: Float,
    // The height of the contents of a vertical scrollable container or the width of the contents
    // of a vertical scrollable container
    val contentSize: Float,
    // The max scroll value, typically contentSize - containerSize
    val maxValue: Float,
)

// A customization class to get a ScrollableState object that can be used to set the scroll position
// and a callback that is called whenever the scroll state of a row or column changes
data class DesignScrollCallbacks(
    // Callback called at initialization time of a scrollable view. For a row or column, this is
    // just a ScrollableState used to set the scroll position. For a grid view, this can be casted
    // to a LazyGridState that can be used to obtain additional data about the grid view.
    val setScrollableState: ((ScrollableState) -> Unit)? = null,
    // Callback when scroll state changes for a row or column. Not used for grid views
    val scrollStateChanged: ((DesignScrollState) -> Unit)? = null,
)

// A Customization changes the way a node is presented, or changes the content of a node.
data class Customization(
    // Text content customization
    var text: Optional<String> = Optional.empty(),
    // Text function customization
    var textState: Optional<State<String>> = Optional.empty(),
    // Image fill customization
    var image: Optional<Bitmap> = Optional.empty(),
    // Image fill with context customization
    var imageWithContext: Optional<(ImageReplacementContext) -> Bitmap?> = Optional.empty(),
    // Brush customization (similar to image)
    var brush: Optional<Brush> = Optional.empty(),
    // Brush customization generated by a function.
    var brushFunction: Optional<() -> Brush> = Optional.empty(),
    // Modifier customization
    var modifier: Optional<Modifier> = Optional.empty(),
    // Tap callback customization
    var tapCallback: Optional<TapCallback> = Optional.empty(),
    // Child content customization V2
    var content: Optional<ReplacementContent> = Optional.empty(),
    var listContent: Optional<ListContent> = Optional.empty(),
    // Node substitution customization
    var component: Optional<@Composable (ComponentReplacementContext) -> Unit> = Optional.empty(),
    // Visibility customization
    var visible: Boolean = true,
    // Visibility state customization
    var visibleState: Optional<State<Boolean>> = Optional.empty(),
    // Font customizations
    var textStyle: Optional<TextStyle> = Optional.empty(),
    // Open link callback function
    var openLinkCallback: Optional<OpenLinkCallback> = Optional.empty(),
    // Meter (dial, gauge, progress bar) customization as a percentage 0-100
    var meterValue: Optional<Float> = Optional.empty(),
    // Meter (dial, gauge, progress bar) customization as a function that returns a percentage 0-100
    var meterState: Optional<MeterState> = Optional.empty(),
    var shaderUniformTimeState: Optional<ShaderUniformTimeState> = Optional.empty(),
    // Scrollable state and scroll state changed callbacks
    var scrollCallbacks: Optional<DesignScrollCallbacks> = Optional.empty(),
)

private fun Customization.clone(): Customization {
    val c = Customization()
    c.text = text
    c.textState = textState
    c.image = image
    c.imageWithContext = imageWithContext
    c.brush = brush
    c.brushFunction = brushFunction
    c.modifier = modifier
    c.tapCallback = tapCallback
    c.content = content
    c.listContent = listContent
    c.component = component
    c.visible = visible
    c.visibleState = visibleState
    c.textStyle = textStyle
    c.openLinkCallback = openLinkCallback
    c.meterValue = meterValue
    c.meterState = meterState
    c.shaderUniformTimeState = shaderUniformTimeState
    c.scrollCallbacks = scrollCallbacks

    return c
}

// This class tracks all of the customizations; currently any time a customization changes
// we invalidate the whole Composable tree, but in the future we should be able to make
// this class a singleton, query by "$DocId+$NodeName", and have nodes subscribe to their
// customizations -- then we'd only invalidate the correct node(s) when updating a customization.
data class CustomizationContext(
    val cs: HashMap<String, Customization> = HashMap(),
    var variantProperties: HashMap<String, String> = HashMap(),
    var customComposable:
        Optional<
            @Composable
            (Modifier, String, NodeQuery, List<ParentComponentInfo>, TapCallback?) -> Unit
        > =
        Optional.empty(),
    var key: String? = null,
)

private fun CustomizationContext.customize(nodeName: String, evolver: (Customization) -> Unit) {
    val x = cs[nodeName] ?: Customization()
    evolver(x)
    cs[nodeName] = x
}

fun CustomizationContext.setText(nodeName: String, text: String?) {
    customize(nodeName) { c -> c.text = Optional.ofNullable(text) }
}

fun CustomizationContext.setTextState(nodeName: String, text: State<String>?) {
    customize(nodeName) { c -> c.textState = Optional.ofNullable(text) }
}

fun CustomizationContext.setImage(nodeName: String, image: Bitmap?) {
    customize(nodeName) { c -> c.image = Optional.ofNullable(image) }
}

// ViewStyle elements that we expose in ImageReplacementContext
data class ImageContext(
    val background: List<Background>,
    val minWidth: DimensionProto,
    val maxWidth: DimensionProto,
    val width: DimensionProto,
    val minHeight: DimensionProto,
    val maxHeight: DimensionProto,
    val height: DimensionProto,
) {
    fun getBackgroundColor(): Int? {
        if (background.size == 1) {
            if (background[0].backgroundTypeCase == Background.BackgroundTypeCase.SOLID) {
                if (background[0].solid.colorOrVarTypeCase == ColorOrVarTypeCase.COLOR) {
                    val color = background[0].solid.color
                    return ((color.a shl 24) and 0xFF000000.toInt()) or
                        ((color.r shl 16) and 0x00FF0000) or
                        ((color.g shl 8) and 0x0000FF00) or
                        (color.b and 0x000000FF)
                }
            }
        }
        return null
    }

    fun getPixelWidth(): Int? {
        if (width.hasPoints()) return width.points.toInt()
        if (minWidth.hasPoints()) return minWidth.points.toInt()
        if (maxWidth.hasPoints()) return maxWidth.points.toInt()
        return null
    }

    fun getPixelHeight(): Int? {
        if (height.hasPoints()) return height.points.toInt()
        if (minHeight.hasPoints()) return minHeight.points.toInt()
        if (maxHeight.hasPoints()) return maxHeight.points.toInt()
        return null
    }
}

// Image replacements with context provide some extra data from the frame that can be used to
// influence the resulting image. For example, we expose the color and size of the frame so
// that the replacement image can be retrieved at the correct size, and optionally tinted by a
// color specified by the designer if the image is a vector image.
interface ImageReplacementContext {
    val imageContext: ImageContext
}

fun CustomizationContext.setImageWithContext(
    nodeName: String,
    imageWithContext: ((ImageReplacementContext) -> Bitmap?)?,
) {
    customize(nodeName) { c -> c.imageWithContext = Optional.ofNullable(imageWithContext) }
}

fun CustomizationContext.setBrush(nodeName: String, brush: Brush) {
    customize(nodeName) { c -> c.brush = Optional.of(brush) }
}

fun CustomizationContext.setBrushFunction(nodeName: String, brushFunction: () -> Brush) {
    customize(nodeName) { c -> c.brushFunction = Optional.of(brushFunction) }
}

fun CustomizationContext.setModifier(nodeName: String, modifier: Modifier?) {
    customize(nodeName) { c -> c.modifier = Optional.ofNullable(modifier) }
}

fun CustomizationContext.setTapCallback(nodeName: String, tapCallback: TapCallback) {
    customize(nodeName) { c -> c.tapCallback = Optional.ofNullable(tapCallback) }
}

fun CustomizationContext.setContent(nodeName: String, content: ReplacementContent?) {
    customize(nodeName) { c -> c.content = Optional.ofNullable(content) }
}

fun CustomizationContext.setListContent(nodeName: String, listContent: ListContent?) {
    customize(nodeName) { c -> c.listContent = Optional.ofNullable(listContent) }
}

fun CustomizationContext.setOpenLinkCallback(nodeName: String, callback: OpenLinkCallback) {
    customize(nodeName) { c -> c.openLinkCallback = Optional.ofNullable(callback) }
}

fun CustomizationContext.setKey(key: String?) {
    this.key = key
}

// Component Replacements are provided with information on the component they are replacing
// including the modifiers that would be applied for the layout style, visual style, and
// (if applicable) the text style.
//
// This allows a replacement component to take on the designer specified appearance while
// offering more behavioral changes than are permitted with simple Modifier customizations
// (for example, replacing a styled text node with a complete text field.
interface ComponentReplacementContext {
    // Return the custom layout modifier that this component would have used so that the layout
    // function can retrieve the component's layout properties. When replacing a node with a
    // composable that is not a DesignCompose generated function, such as a simple Box() or an
    // AndroidView, this modifier should be used as a modifier for that component in order for it to
    // retain the original node's layout (size, position) properties.
    val layoutModifier: Modifier

    // Return the text style, if the component being replaced is a text node in the Figma
    // document.
    val textStyle: TextStyle?
}

fun CustomizationContext.setComponent(
    nodeName: String,
    component: @Composable ((ComponentReplacementContext) -> Unit)?,
) {
    customize(nodeName) { c -> c.component = Optional.ofNullable(component) }
}

fun CustomizationContext.setVisible(nodeName: String, visible: Boolean) {
    customize(nodeName) { c -> c.visible = visible }
}

fun CustomizationContext.setVisibleState(nodeName: String, visible: State<Boolean>) {
    customize(nodeName) { c -> c.visibleState = Optional.ofNullable(visible) }
}

fun CustomizationContext.setTextStyle(nodeName: String, textStyle: TextStyle) {
    customize(nodeName) { c -> c.textStyle = Optional.ofNullable(textStyle) }
}

fun CustomizationContext.setMeterValue(nodeName: String, value: Float) {
    customize(nodeName) { c -> c.meterValue = Optional.ofNullable(value) }
}

fun CustomizationContext.setMeterState(nodeName: String, value: MeterState) {
    customize(nodeName) { c -> c.meterState = Optional.ofNullable(value) }
}

fun CustomizationContext.setShaderUniformTimeState(
    nodeName: String,
    value: ShaderUniformTimeState,
) {
    customize(nodeName) { c -> c.shaderUniformTimeState = Optional.ofNullable(value) }
}

fun CustomizationContext.setScrollCallbacks(nodeName: String, value: DesignScrollCallbacks) {
    customize(nodeName) { c -> c.scrollCallbacks = Optional.ofNullable(value) }
}

fun CustomizationContext.setVariantProperties(vp: HashMap<String, String>) {
    variantProperties = vp
}

fun CustomizationContext.setCustomComposable(
    composable:
        @Composable
        (Modifier, String, NodeQuery, List<ParentComponentInfo>, TapCallback?) -> Unit
) {
    customComposable = Optional.ofNullable(composable)
}

fun CustomizationContext.get(nodeName: String): Optional<Customization> {
    return Optional.ofNullable(cs[nodeName])
}

fun CustomizationContext.getImage(nodeName: String): Bitmap? {
    return cs[nodeName]?.image?.getOrNull()
}

fun CustomizationContext.getImageWithContext(
    nodeName: String
): ((ImageReplacementContext) -> Bitmap?)? {
    return cs[nodeName]?.imageWithContext?.getOrNull()
}

fun CustomizationContext.getBrush(nodeName: String): Brush? {
    return cs[nodeName]?.brush?.getOrNull()
}

fun CustomizationContext.getBrushFunction(nodeName: String): (() -> Brush)? {
    return cs[nodeName]?.brushFunction?.getOrNull()
}

fun CustomizationContext.getText(nodeName: String): String? {
    return cs[nodeName]?.text?.getOrNull()
}

fun CustomizationContext.getTextState(nodeName: String): State<String>? {
    return cs[nodeName]?.textState?.getOrNull()
}

fun CustomizationContext.getModifier(nodeName: String): Modifier? {
    return cs[nodeName]?.modifier?.getOrNull()
}

fun CustomizationContext.getTapCallback(nodeName: String): TapCallback? {
    return cs[nodeName]?.tapCallback?.getOrNull()
}

fun CustomizationContext.getTapCallback(view: View): TapCallback? {
    var tapCallback = getTapCallback(view.name)
    // If no tap callback was found but this is a variant of a component set,
    // look for a tap callback in the component set
    val componentSetName = view.componentInfoOrNull?.componentSetName
    if (tapCallback == null && !componentSetName.isNullOrBlank()) {
        tapCallback = getTapCallback(componentSetName)
    }
    return tapCallback
}

fun CustomizationContext.getContent(nodeName: String): ReplacementContent? {
    return cs[nodeName]?.content?.getOrNull()
}

fun CustomizationContext.getListContent(nodeName: String): ListContent? {
    return cs[nodeName]?.listContent?.getOrNull()
}

fun CustomizationContext.getComponent(
    nodeName: String
): @Composable ((ComponentReplacementContext) -> Unit)? {
    return cs[nodeName]?.component?.getOrNull()
}

// XXX-PERF: This function shows up on profiles because we call it for every component during render
//      and it does a lot of hashing and string operations. We can optimize this by parsing the node
//      variants and sorting them in Rust during doc generation, and by interning strings in the
//      serialized doc so that we don't need to hash.
fun CustomizationContext.getMatchingVariant(maybeComponentInfo: Optional<ComponentInfo>): String? {
    if (!maybeComponentInfo.isPresent) return null
    if (variantProperties.isEmpty()) return null

    val componentInfo = maybeComponentInfo.get()
    val nodeVariants = parseNodeVariants(componentInfo.name)

    // Check to see if any of the variant properties set match the variant properties in this node.
    // If any match, update the values of the variant properties and return a new node name that
    // uses the specified variants
    var variantChanged = false
    nodeVariants.forEach {
        val value = variantProperties[it.key]
        if (value != null && value != it.value) {
            nodeVariants[it.key] = value
            variantChanged = true
        }
    }

    if (variantChanged) {
        val newVariantList: ArrayList<String> = ArrayList()
        val sortedKeys = nodeVariants.keys.sorted()
        sortedKeys.forEach { newVariantList.add(it + "=" + nodeVariants[it]) }
        return newVariantList.joinToString(",")
    }

    return null
}

private fun parseNodeVariants(nodeName: String): HashMap<String, String> {
    // Take a node name in the form of "property1=name1, property2=name2" and return a list
    // of the property-to-name bindings as string pairs
    val ret = HashMap<String, String>()
    val propertyValueList = nodeNameToPropertyValueList(nodeName)
    for (p in propertyValueList) ret[p.first] = p.second
    return ret
}

fun CustomizationContext.getVisible(nodeName: String): Boolean {
    return cs[nodeName]?.visible ?: true
}

fun CustomizationContext.getVisibleState(nodeName: String): State<Boolean>? {
    return cs[nodeName]?.visibleState?.getOrNull()
}

fun CustomizationContext.getTextStyle(nodeName: String): TextStyle? {
    return cs[nodeName]?.textStyle?.getOrNull()
}

fun CustomizationContext.getOpenLinkCallback(nodeName: String): OpenLinkCallback? {
    return cs[nodeName]?.openLinkCallback?.getOrNull()
}

fun CustomizationContext.getMeterValue(nodeName: String): Float? {
    val meterValue = cs[nodeName]?.meterValue?.getOrNull()
    return if (meterValue == null) {
        null
    } else {
        if (meterValue.isFinite()) meterValue else 0F
    }
}

fun CustomizationContext.getMeterState(nodeName: String): MeterState? {
    return cs[nodeName]?.meterState?.getOrNull()
}

fun CustomizationContext.getShaderUniformTimeState(nodeName: String): ShaderUniformTimeState? {
    return cs[nodeName]?.shaderUniformTimeState?.getOrNull()
}

fun CustomizationContext.getScrollCallbacks(nodeName: String): DesignScrollCallbacks? {
    return cs[nodeName]?.scrollCallbacks?.getOrNull()
}

fun CustomizationContext.getKey(): String? {
    return key
}

fun CustomizationContext.getCustomComposable():
    @Composable ((Modifier, String, NodeQuery, List<ParentComponentInfo>, TapCallback?) -> Unit)? {
    return customComposable.getOrNull()
}

fun CustomizationContext.mergeFrom(other: CustomizationContext) {
    other.cs.forEach {
        // Make a copy of the customization so we don't use the same one, causing unexpected results
        cs[it.key] = it.value.clone()
    }
    other.variantProperties.forEach { variantProperties[it.key] = it.value }
    customComposable = other.customComposable
}
