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
import android.graphics.Bitmap
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.withSaveLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.tracing.trace
import com.android.designcompose.proto.getDim
import com.android.designcompose.proto.start
import com.android.designcompose.proto.top
import com.android.designcompose.proto.type
import com.android.designcompose.serdegen.Dimension
import com.android.designcompose.serdegen.GridLayoutType
import com.android.designcompose.serdegen.GridSpan
import com.android.designcompose.serdegen.ItemSpacingType
import com.android.designcompose.serdegen.NodeQuery
import com.android.designcompose.serdegen.View
import com.android.designcompose.serdegen.ViewData
import com.android.designcompose.serdegen.ViewShape
import com.android.designcompose.serdegen.ViewStyle

@Composable
internal fun DesignFrame(
    modifier: Modifier = Modifier,
    view: View,
    viewStyle: ViewStyle,
    layoutInfo: SimplifiedLayoutInfo,
    document: DocContent,
    customizations: CustomizationContext,
    layoutId: Int,
    parentComponents: List<ParentComponentInfo>,
    maskInfo: MaskInfo?,
    content: @Composable () -> Unit,
): Boolean {
    val name = view.name
    if (!customizations.getVisible(name)) return false
    customizations.getVisibleState(name)?.let { if (!it.value) return false }
    val parentLayout = LocalParentLayoutInfo.current

    var m = Modifier as Modifier
    m = m.then(modifier)
    val customModifier = customizations.getModifier(name)
    if (customModifier != null) {
        // We may need more control over where a custom modifier is placed in the list
        // than just always adding at the end.
        m = m.then(customModifier)
    }

    // Keep track of the layout state, which changes whenever this view's layout changes
    val (layoutState, setLayoutState) = remember { mutableStateOf(0) }
    var rootLayoutId = parentLayout?.rootLayoutId ?: -1
    if (rootLayoutId == -1) rootLayoutId = layoutId

    val subscribeLayout =
        @Composable { style: ViewStyle ->
            // Subscribe for layout changes whenever the view changes. The view can change if it is
            // a component instance that changes to another variant. It can also change due to a
            // live update. Subscribing when already subscribed simply updates the view in the
            // layout system.
            DisposableEffect(view) {
                trace(DCTraces.DESIGNFRAME_DE_SUBSCRIBE) {
                    val parentLayoutId = parentLayout?.parentLayoutId ?: -1
                    val childIndex = parentLayout?.childIndex ?: -1
                    // Subscribe to layout changes when the view changes or is added
                    LayoutManager.subscribeFrame(
                        layoutId,
                        setLayoutState,
                        parentLayoutId,
                        childIndex,
                        style.layout_style,
                        view.name,
                    )
                }
                onDispose {}
            }

            DisposableEffect(Unit) {
                onDispose {
                    // Unsubscribe to layout changes when the view is removed
                    LayoutManager.unsubscribe(
                        layoutId,
                        rootLayoutId,
                        parentLayout?.isWidgetAncestor == true,
                    )
                }
            }
        }

    val finishLayout =
        @Composable {
            // This must be called at the end of DesignFrame just before returning, after adding all
            // children. This lets the LayoutManager know that this frame has completed, and so if
            // there are no other parent frames performing layout, layout computation can be
            // performed.
            DisposableEffect(view) {
                trace(DCTraces.DESIGNFRAME_FINISHLAYOUT) {
                    LayoutManager.finishLayout(layoutId, rootLayoutId)
                }
                onDispose {}
            }
        }

    // Check for a customization that replaces this component completely
    // If we're replaced, then invoke the replacement here. We may want to pass more layout info
    // (row/column/etc) to the replacement component at some point.
    val replacementComponent = customizations.getComponent(name)
    if (replacementComponent != null) {
        val replacementParentLayout =
            parentLayout?.withReplacementLayoutData(viewStyle.externalLayoutData())
        DesignParentLayout(replacementParentLayout) {
            replacementComponent(
                object : ComponentReplacementContext {
                    override val layoutModifier = Modifier.layoutStyle(name, layoutId)
                    override val textStyle: TextStyle? = null
                }
            )

            // If the replacement component was a DesignCompose node, designComposeRendered would
            // have been set to true. If false, the replacement component was some other type of
            // Jetpack Compose composable. In that case, DesignView would not have been called, so
            // we need to subscribe to layout with the original node's view style so that the
            // replacement component is able to use the same layout.
            if (LocalParentLayoutInfo.current?.designComposeRendered == false) {
                subscribeLayout(viewStyle)
                finishLayout()
            }
        }
        return true
    }

    // If parentLayout has replacementLayoutData set, use it to override fields in viewStyle.
    // This ensures that a replacement component uses the same layout data as the original node.
    val style =
        parentLayout?.replacementLayoutData?.let { viewStyle.withExternalLayoutData(it) }
            ?: viewStyle

    // Check for an image customization with context. If it exists, call the custom image function
    // and provide it with the frame's background and size.
    val customImageWithContext = customizations.getImageWithContext(name)
    var customImage: Bitmap? = null
    if (customImageWithContext != null) {
        customImage =
            customImageWithContext(
                object : ImageReplacementContext {
                    override val imageContext =
                        ImageContext(
                            background = style.node_style.backgrounds,
                            minWidth = style.layout_style.min_width.getDim(),
                            maxWidth = style.layout_style.max_width.getDim(),
                            width = style.layout_style.width.getDim(),
                            minHeight = style.layout_style.min_height.getDim(),
                            maxHeight = style.layout_style.max_height.getDim(),
                            height = style.layout_style.height.getDim(),
                        )
                }
            )
    }

    // Get the modeValues used to resolve variable values
    val modeValues = VariableManager.currentModeValues(view.explicit_variable_modes)

    subscribeLayout(style)

    // Only render the frame if we don't have a replacement node and layout is absolute
    val shape = (view.data as ViewData.Container).shape
    if (replacementComponent == null && layoutInfo.shouldRender()) {
        val varMaterialState = VariableState.create(modeValues)
        m =
            m.frameRender(
                style,
                shape,
                customImage,
                document,
                name,
                customizations,
                maskInfo,
                layoutId,
                varMaterialState,
                LocalContext.current,
            )
    }

    val lazyContent = customizations.getListContent(name)

    // Select the appropriate representation for ourselves based on our layout style;
    // row or column (with or without wrapping/flow), or absolute positioning (similar to the CSS2
    // model).
    val layout = LayoutManager.getLayout(layoutId)
    when (layoutInfo) {
        is LayoutInfoRow -> {
            DesignParentLayout(rootParentLayoutInfo) {
                if (lazyContent != null) {
                    val content = lazyContent { LazyContentSpan() }
                    var count = content.count
                    var overflowNodeId: String? = null
                    if (
                        style.node_style.max_children.isPresent &&
                            style.node_style.max_children.get() < count
                    ) {
                        count = style.node_style.max_children.get()
                        if (style.node_style.overflow_node_id.isPresent)
                            overflowNodeId = style.node_style.overflow_node_id.get()
                    }

                    // If the widget is set to hug contents, don't give Row() a size and let it size
                    // itself. Then when the size is determined, inform the layout manager.
                    // Otherwise,
                    // get the fixed size from the layout manager and use it in a Modifier.
                    val hugContents = view.style.layout_style.width.getDim() is Dimension.Auto
                    val rowModifier =
                        if (hugContents)
                            Modifier.onSizeChanged {
                                LayoutManager.setNodeSize(
                                    layoutId,
                                    rootLayoutId,
                                    it.width,
                                    it.height,
                                )
                            }
                        else Modifier.layoutSizeToModifier(layout)
                    Row(
                        rowModifier
                            .then(layoutInfo.selfModifier)
                            .then(m)
                            .then(layoutInfo.marginModifier),
                        horizontalArrangement = layoutInfo.arrangement,
                        verticalAlignment = layoutInfo.alignment,
                    ) {
                        for (i in 0 until count) {
                            if (overflowNodeId != null && i == count - 1) {
                                // This is the last item we can show and there are more, and there
                                // is an
                                // overflow node, so show the overflow node here
                                val customComposable = customizations.getCustomComposable()
                                if (customComposable != null) {
                                    customComposable(
                                        Modifier,
                                        style.node_style.overflow_node_name.get(),
                                        NodeQuery.NodeId(style.node_style.overflow_node_id.get()),
                                        parentComponents,
                                        null,
                                    )
                                }
                            } else {
                                DesignListLayout(ListLayoutType.Row) { content.itemContent(i) }
                            }
                        }
                    }
                } else {
                    Row(
                        layoutInfo.selfModifier.then(m).then(layoutInfo.marginModifier),
                        horizontalArrangement = layoutInfo.arrangement,
                        verticalAlignment = layoutInfo.alignment,
                    ) {
                        content()
                    }
                }
            }
        }
        is LayoutInfoColumn -> {
            DesignParentLayout(rootParentLayoutInfo) {
                if (lazyContent != null) {
                    val content = lazyContent { LazyContentSpan() }
                    var count = content.count
                    var overflowNodeId: String? = null
                    if (
                        style.node_style.max_children.isPresent &&
                            style.node_style.max_children.get() < count
                    ) {
                        count = style.node_style.max_children.get()
                        if (style.node_style.overflow_node_id.isPresent)
                            overflowNodeId = style.node_style.overflow_node_id.get()
                    }

                    // If the widget is set to hug contents, don't give Column() a size and let it
                    // size
                    // itself. Then when the size is determined, inform the layout manager.
                    // Otherwise,
                    // get the fixed size from the layout manager and use it in a Modifier.
                    val hugContents = view.style.layout_style.height.getDim() is Dimension.Auto
                    val columnModifier =
                        if (hugContents)
                            Modifier.onSizeChanged {
                                LayoutManager.setNodeSize(
                                    layoutId,
                                    rootLayoutId,
                                    it.width,
                                    it.height,
                                )
                            }
                        else Modifier.layoutSizeToModifier(layout)
                    Column(
                        columnModifier
                            .then(layoutInfo.selfModifier)
                            .then(m)
                            .then(layoutInfo.marginModifier),
                        verticalArrangement = layoutInfo.arrangement,
                        horizontalAlignment = layoutInfo.alignment,
                    ) {
                        for (i in 0 until count) {
                            if (overflowNodeId != null && i == count - 1) {
                                // This is the last item we can show and there are more, and there
                                // is an
                                // overflow node, so show the overflow node here
                                val customComposable = customizations.getCustomComposable()
                                if (customComposable != null) {
                                    customComposable(
                                        Modifier,
                                        style.node_style.overflow_node_name.get(),
                                        NodeQuery.NodeId(style.node_style.overflow_node_id.get()),
                                        parentComponents,
                                        null,
                                    )
                                }
                            } else {
                                DesignListLayout(ListLayoutType.Column) { content.itemContent(i) }
                            }
                        }
                    }
                } else {
                    Column(
                        layoutInfo.selfModifier.then(m).then(layoutInfo.marginModifier),
                        verticalArrangement = layoutInfo.arrangement,
                        horizontalAlignment = layoutInfo.alignment,
                    ) {
                        content()
                    }
                }
            }
        }
        is LayoutInfoGrid -> {
            if (lazyContent == null) {
                finishLayout()
                return false
            }

            // Given the list of possible content that goes into this grid layout, try to find a
            // matching
            // item based on node name and variant properties, and return its span
            fun getSpan(
                gridSpanContent: List<GridSpan>,
                getDesignNodeData: GetDesignNodeData,
            ): LazyContentSpan {
                val nodeData = getDesignNodeData()
                val cachedSpan = SpanCache.getSpan(nodeData)
                if (cachedSpan != null) return cachedSpan

                gridSpanContent.forEach { item ->
                    // If not looking for a variant, just find a node name match
                    if (nodeData.variantProperties.isEmpty()) {
                        if (nodeData.nodeName == item.node_name)
                            return LazyContentSpan(span = item.span, maxLineSpan = item.max_span)
                    } else {
                        var spanFound: LazyContentSpan? = null
                        var matchesLeft = nodeData.variantProperties.size
                        item.node_variant.forEach {
                            val property = it.key.trim()
                            val value = it.value.trim()
                            val variantPropertyValue = nodeData.variantProperties[property]
                            if (value == variantPropertyValue) {
                                // We have a match. Decrement the number of matches left we are
                                // looking for
                                --matchesLeft
                                // If we matched everything, we have a possible match. If the number
                                // of properties
                                // and values in propertyValueList is the same as the number of
                                // variant properties
                                // then we are done. Otherwise, this is a possible match, and save
                                // it in spanFound.
                                // If we don't have any exact matches, return spanFound
                                if (matchesLeft == 0) {
                                    if (nodeData.variantProperties.size == item.node_variant.size) {
                                        val span =
                                            if (item.max_span) LazyContentSpan(maxLineSpan = true)
                                            else LazyContentSpan(span = item.span)
                                        SpanCache.setSpan(nodeData, span)
                                        return span
                                    } else
                                        spanFound =
                                            LazyContentSpan(
                                                span = item.span,
                                                maxLineSpan = item.max_span,
                                            )
                                }
                            }
                        }
                        if (spanFound != null) {
                            SpanCache.setSpan(nodeData, spanFound!!)
                            return spanFound!!
                        }
                    }
                }
                SpanCache.setSpan(nodeData, LazyContentSpan(span = 1))
                return LazyContentSpan(span = 1)
            }

            val (gridMainAxisSize, setGridMainAxisSize) = remember { mutableStateOf(0) }

            // Content for the lazy content parameter. This uses the grid layout but also supports
            // limiting the number of children to style.max_children, and using an overflow node if
            // one is specified.
            val lazyItemContent: LazyGridScope.() -> Unit = {
                val lContent = lazyContent { nodeData ->
                    getSpan(layoutInfo.gridSpanContent, nodeData)
                }

                // If the main axis size has not yet been set, and spacing is set to auto, show the
                // initial content composable. This avoids rendering the content in one position
                // for the first frame and then in another on the second frame after the main axis
                // size has been set.
                val showInitContent =
                    (gridMainAxisSize <= 0 &&
                        layoutInfo.mainAxisSpacing.type() is ItemSpacingType.Auto)
                if (showInitContent)
                    items(
                        count = 1,
                        span = {
                            val span = lContent.initialSpan?.invoke() ?: LazyContentSpan()
                            GridItemSpan(if (span.maxLineSpan) maxLineSpan else span.span)
                        },
                    ) {
                        DesignListLayout(ListLayoutType.Grid) { lContent.initialContent() }
                    }
                else {
                    var count = lContent.count
                    var overflowNodeId: String? = null
                    if (
                        style.node_style.max_children.isPresent &&
                            style.node_style.max_children.get() < count
                    ) {
                        count = style.node_style.max_children.get()
                        if (style.node_style.overflow_node_id.isPresent)
                            overflowNodeId = style.node_style.overflow_node_id.get()
                    }
                    items(
                        count,
                        key = { index ->
                            if (overflowNodeId != null && index == count - 1)
                            // Overflow node key
                            "overflow"
                            else lContent.key?.invoke(index) ?: index
                        },
                        span = { index ->
                            if (overflowNodeId != null && index == count - 1)
                            // Overflow node always spans 1 column/row for now
                            GridItemSpan(1)
                            else {
                                val span = lContent.span?.invoke(index) ?: LazyContentSpan()
                                GridItemSpan(if (span.maxLineSpan) maxLineSpan else span.span)
                            }
                        },
                        contentType = { index ->
                            if (overflowNodeId != null && index == count - 1)
                            // Overflow node content type
                            "overflow"
                            else lContent.contentType.invoke(index)
                        },
                        itemContent = { index ->
                            if (overflowNodeId != null && index == count - 1) {
                                // This is the last item we can show and there are more, and there
                                // is an
                                // overflow node, so show the overflow node here
                                val customComposable = customizations.getCustomComposable()
                                if (customComposable != null) {
                                    customComposable(
                                        Modifier,
                                        style.node_style.overflow_node_name.get(),
                                        NodeQuery.NodeId(style.node_style.overflow_node_id.get()),
                                        parentComponents,
                                        null,
                                    )
                                }
                            } else {
                                DesignListLayout(ListLayoutType.Grid) {
                                    lContent.itemContent(index)
                                }
                            }
                        },
                    )
                }
            }

            // Given the frame size, number of columns/rows, and spacing between them, return a list
            // of column/row widths/heights
            fun calculateCellsCrossAxisSizeImpl(
                gridSize: Int,
                slotCount: Int,
                spacing: Int,
            ): List<Int> {
                val gridSizeWithoutSpacing = gridSize - spacing * (slotCount - 1)
                val slotSize = gridSizeWithoutSpacing / slotCount
                val remainingPixels = gridSizeWithoutSpacing % slotCount
                return List(slotCount) { slotSize + if (it < remainingPixels) 1 else 0 }
            }

            // Given the grid layout type and main axis size, return the number of columns/rows
            fun calculateColumnRowCount(layoutInfo: LayoutInfoGrid, gridMainAxisSize: Int): Int {
                val count: Int
                if (
                    layoutInfo.layout is GridLayoutType.FixedColumns ||
                        layoutInfo.layout is GridLayoutType.FixedRows
                ) {
                    count = layoutInfo.numColumnsRows
                } else {
                    count =
                        gridMainAxisSize /
                            (layoutInfo.minColumnRowSize +
                                itemSpacingAbs(layoutInfo.mainAxisSpacing))
                }
                return if (count > 0) count else 1
            }

            val gridSizeModifier = Modifier.layoutSizeToModifier(layout)

            val density = LocalDensity.current.density
            if (
                layoutInfo.layout is GridLayoutType.FixedColumns ||
                    layoutInfo.layout is GridLayoutType.AutoColumns
            ) {
                val columnCount = calculateColumnRowCount(layoutInfo, gridMainAxisSize)
                val horizontalSpacing =
                    (layoutInfo.mainAxisSpacing.type() as? ItemSpacingType.Fixed)?.value ?: 0
                val verticalSpacing = layoutInfo.crossAxisSpacing

                DesignParentLayout(rootParentLayoutInfo) {
                    LazyVerticalGrid(
                        modifier = gridSizeModifier.then(layoutInfo.selfModifier).then(m),
                        columns =
                            object : GridCells {
                                override fun Density.calculateCrossAxisCellSizes(
                                    availableSize: Int,
                                    spacing: Int,
                                ): List<Int> {
                                    val mainAxisSize = (availableSize.toFloat() / density).toInt()
                                    setGridMainAxisSize(mainAxisSize)
                                    return calculateCellsCrossAxisSizeImpl(
                                        availableSize,
                                        columnCount,
                                        spacing,
                                    )
                                }
                            },
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                (when (val spacing = layoutInfo.mainAxisSpacing.type()) {
                                        is ItemSpacingType.Fixed -> spacing.value
                                        is ItemSpacingType.Auto -> {
                                            if (columnCount > 1)
                                                (gridMainAxisSize -
                                                    (spacing.value.height * columnCount)) /
                                                    (columnCount - 1)
                                            else spacing.value.width
                                        }
                                        else -> horizontalSpacing
                                    })
                                    .dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(verticalSpacing.dp),
                        userScrollEnabled = layoutInfo.scrollingEnabled,
                        contentPadding =
                            PaddingValues(
                                layoutInfo.padding.start.getDim().pointsAsDp(density),
                                layoutInfo.padding.top.getDim().pointsAsDp(density),
                                layoutInfo.padding.end.getDim().pointsAsDp(density),
                                layoutInfo.padding.bottom.getDim().pointsAsDp(density),
                            ),
                    ) {
                        lazyItemContent()
                    }
                }
            } else {
                val rowCount = calculateColumnRowCount(layoutInfo, gridMainAxisSize)
                val horizontalSpacing = layoutInfo.crossAxisSpacing
                val verticalSpacing =
                    (layoutInfo.mainAxisSpacing.type() as? ItemSpacingType.Fixed)?.value ?: 0

                DesignParentLayout(rootParentLayoutInfo) {
                    LazyHorizontalGrid(
                        modifier = layoutInfo.selfModifier.then(gridSizeModifier).then(m),
                        rows =
                            object : GridCells {
                                override fun Density.calculateCrossAxisCellSizes(
                                    availableSize: Int,
                                    spacing: Int,
                                ): List<Int> {
                                    val mainAxisSize = (availableSize.toFloat() / density).toInt()
                                    setGridMainAxisSize(mainAxisSize)
                                    return calculateCellsCrossAxisSizeImpl(
                                        availableSize,
                                        rowCount,
                                        spacing,
                                    )
                                }
                            },
                        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                (when (val spacing = layoutInfo.mainAxisSpacing.type()) {
                                        is ItemSpacingType.Fixed -> spacing.value
                                        is ItemSpacingType.Auto -> {
                                            if (rowCount > 1)
                                                (gridMainAxisSize -
                                                    (spacing.value.height * rowCount)) /
                                                    (rowCount - 1)
                                            else spacing.value.width
                                        }
                                        else -> verticalSpacing
                                    })
                                    .dp
                            ),
                        userScrollEnabled = layoutInfo.scrollingEnabled,
                    ) {
                        lazyItemContent()
                    }
                }
            }
        }
        is LayoutInfoAbsolute -> {
            val hasScroll = layoutInfo.horizontalScroll || layoutInfo.verticalScroll
            var designScroll: DesignScroll? = null
            if (hasScroll) {
                // Setup a DesignScroll object used to both provide layout with the scroll offset
                // as well as have layout set the max scroll contents based on the position of the
                // last child
                val scrollOffset = remember { mutableFloatStateOf(0F) }
                val scrollMax = remember { mutableFloatStateOf(0F) }
                var scrollOrientation = Orientation.Horizontal
                if (layoutInfo.horizontalScroll) scrollOrientation = Orientation.Horizontal
                else if (layoutInfo.verticalScroll) scrollOrientation = Orientation.Vertical
                designScroll = DesignScroll(scrollOffset.value, scrollOrientation, scrollMax)
                m =
                    m.then(
                        Modifier.scrollable(
                            orientation = scrollOrientation,
                            state =
                                rememberScrollableState { delta ->
                                    // Calculate the current scroll positioned, bounded by the
                                    // extents of the children
                                    scrollOffset.value =
                                        (scrollOffset.value + delta).coerceIn(-scrollMax.value, 0F)
                                    delta
                                },
                        )
                    )
            }

            // Use our custom layout to render the frame and to place its children
            m = m.then(Modifier.layoutStyle(name, layoutId))
            m = m.then(layoutInfo.selfModifier)
            DesignVariableExplicitModeValues(modeValues) {
                DesignFrameLayout(m, view, layoutId, rootLayoutId, layoutState, designScroll) {
                    content()
                }
            }
        }
    }

    finishLayout()
    return true
}

internal fun Modifier.frameRender(
    style: ViewStyle,
    frameShape: ViewShape,
    customImageWithContext: Bitmap?,
    document: DocContent,
    name: String,
    customizations: CustomizationContext,
    maskInfo: MaskInfo?,
    layoutId: Int,
    variableState: VariableState,
    appContext: Context,
): Modifier =
    this.then(
        Modifier.drawWithContent {
            fun render() =
                render(
                    this@frameRender,
                    style,
                    frameShape,
                    customImageWithContext,
                    document,
                    name,
                    customizations,
                    layoutId,
                    variableState,
                    appContext = appContext,
                )

            when (maskInfo?.type ?: MaskViewType.None) {
                MaskViewType.MaskNode -> {
                    // When rendering a mask, call saveLayer with blendmode DSTIN so that we blend
                    // the masks's alpha with what has already been rendered. We also need to adjust
                    // the rectangle to be the size and position of the parent because masks affect
                    // the whole area of the parent
                    val paint = Paint()
                    paint.blendMode = BlendMode.DstIn
                    val offset =
                        Offset(
                            -style.layout_style.margin.start.pointsAsDp(density).value,
                            -style.layout_style.margin.top.pointsAsDp(density).value,
                        )
                    val parentSize = maskInfo?.parentSize?.value ?: size
                    drawContext.canvas.withSaveLayer(Rect(offset, parentSize), paint) { render() }
                }
                MaskViewType.MaskParent -> {
                    // When rendering a node that has a child mask, call saveLayer so that its
                    // children are all rendered to a separate target. Save the size of this
                    // drawing context so that the child mask can adjust its drawing size to mask
                    // the entire parent
                    maskInfo?.parentSize?.value = size
                    drawContext.canvas.withSaveLayer(size.toRect(), Paint()) { render() }
                }
                else -> {
                    render()
                }
            }
        }
    )
