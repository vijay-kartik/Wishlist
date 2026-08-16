package com.example.app.wishlist.ui.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Visualize tab — the graph as a picture rather than a list.
 *
 * The Graph tab answers "what is in this node"; this one answers "what shape is the graph
 * in", which is the question a list of rows is worst at. Clusters, orphans and the
 * hub-and-spoke around a chatty contact are all visible here in a second and essentially
 * invisible in the node list.
 *
 * Selection highlighting is computed here rather than in the ViewModel on purpose: the
 * layout behind [VizGraph] is an O(n²) simulation, and recomputing it on every tap would
 * make selecting a node feel broken. Positions change only when the graph, the filters or
 * the layout mode change.
 */
@Composable
fun ColumnScope.VisualizeTab(state: DebugUiState, vm: DebugViewModel) {

    val graph = state.vizGraph

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LayoutButton("Force", state.vizLayout == VizLayout.FORCE, Modifier.weight(1f)) {
            vm.setVizLayout(VizLayout.FORCE)
        }
        Box(Modifier.width(6.dp))
        LayoutButton("Ego · radial", state.vizLayout == VizLayout.RADIAL, Modifier.weight(1f)) {
            vm.setVizLayout(VizLayout.RADIAL)
        }
        Box(Modifier.width(6.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(Dbg.Surface)
                .border(1.dp, Dbg.BorderInput, RoundedCornerShape(7.dp))
                .clickable { vm.reseedViz() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("↻", fontSize = Dbg.Body, fontWeight = FontWeight.SemiBold, color = Dbg.TextSecondary)
        }
    }

    if (state.vizLayout == VizLayout.RADIAL) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("center", fontSize = Dbg.Body, color = Dbg.TextSecondary)
            DebugDropdown(
                value = state.vizCenter.toString(),
                options = state.nodeOptions.map { it.graphKey.toString() to it.label },
                onSelect = { vm.setVizCenter(it.toLongOrNull() ?: 0L) },
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }
    }

    // Type visibility. Hiding SOURCE by default is the difference between a readable
    // picture and a hairball — every assertion has one, so they double the node count
    // while saying nothing about what the graph knows.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        VIZ_TYPES.forEach { type ->
            val hidden = type in state.vizHidden
            Text(
                text = type,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (hidden) Dbg.CodeBg else TypePalette.background(type))
                    .border(
                        1.dp,
                        if (hidden) Dbg.Border else TypePalette.foreground(type),
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { vm.toggleVizType(type) }
                    .padding(horizontal = 9.dp, vertical = 2.dp),
                fontSize = Dbg.Tiny,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp,
                color = if (hidden) Dbg.TextFaint else TypePalette.foreground(type),
            )
        }
    }

    if (graph.dropped > 0) {
        Text(
            text = "Showing the ${DebugGraphLayout.MAX_RENDERED_NODES} highest-degree nodes — " +
                "${graph.dropped} more are hidden. Filter a type out to bring them into range.",
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Dbg.WarnBg)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = Dbg.Small,
            lineHeight = 15.sp,
            color = Dbg.WarnText,
        )
    }

    GraphCanvas(graph = graph, selected = state.vizSelected, onSelect = vm::selectVizNode)

    if (graph.nodes.isEmpty()) {
        Text(
            text = if (state.vizComputing) "Laying out…"
            else "Nothing to draw — the graph is empty, or every type is hidden.",
            modifier = Modifier.padding(top = 2.dp),
            fontSize = Dbg.Body,
            color = Dbg.TextMuted,
        )
    }

    val selectedNode = graph.nodes.firstOrNull { it.graphKey == state.vizSelected }
    if (selectedNode != null) {
        SelectionPanel(graph, selectedNode, vm)
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        state.vizLegendTypes.forEach { type ->
            Row(
                Modifier.padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(TypePalette.background(type))
                        .border(1.5.dp, TypePalette.foreground(type), CircleShape)
                )
                Text(
                    text = type,
                    modifier = Modifier.padding(start = 5.dp),
                    fontSize = Dbg.Tiny,
                    fontFamily = Dbg.Mono,
                    color = Dbg.TextSecondary,
                )
            }
        }
    }

    Text(
        text = "Solid edges are structural (INSTANCE_OF, BENEFICIARY). Dashed are provenance " +
            "(EVIDENCED_BY, STATED_BY, DERIVED_FROM). Node size scales with degree.",
        fontSize = Dbg.SectionLabel,
        lineHeight = 16.sp,
        color = Dbg.TextMuted,
    )
}

/**
 * The graph itself.
 *
 * Circles and edges are drawn into a [Canvas]; captions are real [Text] composables laid
 * over it. Drawing text into the canvas would mean measuring it by hand at every scale,
 * and these labels are the part of the picture most likely to be read closely.
 */
@Composable
private fun GraphCanvas(graph: VizGraph, selected: Long?, onSelect: (Long?) -> Unit) {

    // Adjacency for the current selection. Cheap (O(E), a few hundred at most) and derived
    // rather than stored, so a tap never touches the ViewModel's layout.
    val adjacent = remember(graph, selected) {
        if (selected == null) emptySet() else buildSet {
            graph.edges.forEach { edge ->
                if (edge.fromKey == selected) add(edge.toKey)
                if (edge.toKey == selected) add(edge.fromKey)
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFBFCFD))
            .border(1.dp, Dbg.Border, RoundedCornerShape(10.dp))
    ) {
        // One scale factor for the whole picture: the layout runs in a fixed 378x430 space
        // so that resizing the panel never re-runs the simulation.
        val scale = maxWidth.value / DebugGraphLayout.WIDTH
        val canvasHeight = DebugGraphLayout.HEIGHT * scale

        Box(Modifier.fillMaxWidth().height(canvasHeight.dp)) {

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(graph, selected) {
                        detectTapGestures { tap ->
                            val unitsPerPx = DebugGraphLayout.WIDTH / size.width.toFloat()
                            val x = tap.x * unitsPerPx
                            val y = tap.y * unitsPerPx
                            // Nearest node within its own radius plus a finger-sized slop.
                            // Without the slop an 8-unit circle is a ~10dp target, well
                            // under anything reliably tappable.
                            val hit = graph.nodes
                                .map { it to ((it.x - x) * (it.x - x) + (it.y - y) * (it.y - y)) }
                                .filter { (node, sq) ->
                                    val reach = node.radius + TAP_SLOP_UNITS
                                    sq <= reach * reach
                                }
                                .minByOrNull { it.second }
                                ?.first
                            onSelect(hit?.graphKey)
                        }
                    }
            ) {
                val pxPerUnit = size.width / DebugGraphLayout.WIDTH
                val dashed = PathEffect.dashPathEffect(floatArrayOf(3f * pxPerUnit, 3f * pxPerUnit))

                graph.edges.forEach { edge ->
                    val touchesSelection =
                        selected != null && (edge.fromKey == selected || edge.toKey == selected)
                    drawLine(
                        color = when {
                            touchesSelection -> Dbg.Accent
                            selected != null -> Color(0xFFEDEFF2)
                            else -> Color(0xFFD3D8E0)
                        },
                        start = Offset(edge.x1 * pxPerUnit, edge.y1 * pxPerUnit),
                        end = Offset(edge.x2 * pxPerUnit, edge.y2 * pxPerUnit),
                        strokeWidth = (if (touchesSelection) 1.8f else 1f) * pxPerUnit,
                        pathEffect = if (edge.provenance) dashed else null,
                    )
                }

                graph.nodes.forEach { node ->
                    val isSelected = node.graphKey == selected
                    val dimmed = selected != null && !isSelected && node.graphKey !in adjacent
                    val centre = Offset(node.x * pxPerUnit, node.y * pxPerUnit)

                    if (isSelected) {
                        drawCircle(
                            color = Dbg.Accent.copy(alpha = 0.16f),
                            radius = (node.radius + 6f) * pxPerUnit,
                            center = centre,
                        )
                    }
                    drawCircle(
                        color = if (dimmed) Dbg.Divider else TypePalette.background(node.type),
                        radius = node.radius * pxPerUnit,
                        center = centre,
                    )
                    drawCircle(
                        color = if (dimmed) Dbg.Border else TypePalette.foreground(node.type),
                        radius = node.radius * pxPerUnit,
                        center = centre,
                        style = Stroke(width = (if (isSelected) 2.4f else 1.4f) * pxPerUnit),
                    )
                }
            }

            graph.nodes.forEach { node ->
                val isSelected = node.graphKey == selected
                val dimmed = selected != null && !isSelected && node.graphKey !in adjacent
                Box(
                    Modifier
                        .offset(
                            x = (node.x * scale).dp - LABEL_WIDTH / 2,
                            y = ((node.y + node.radius + 4f) * scale).dp,
                        )
                        .width(LABEL_WIDTH)
                        .clickable { onSelect(node.graphKey) },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        text = node.label,
                        fontSize = 8.5.sp,
                        lineHeight = 10.sp,
                        fontFamily = Dbg.Mono,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (dimmed) Dbg.TextFaint else Dbg.TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SelectionPanel(graph: VizGraph, node: VizNodeModel, vm: DebugViewModel) {
    val incident = remember(graph, node.graphKey) {
        graph.edges.mapNotNull { edge ->
            when (node.graphKey) {
                edge.fromKey -> Triple("out →", edge.edgeType, edge.toKey)
                edge.toKey -> Triple("← in", edge.edgeType, edge.fromKey)
                else -> null
            }
        }
    }
    val labelOf = remember(graph) { graph.nodes.associate { it.graphKey to it.lookupKey } }

    DebugCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TypeChip(node.type, fontSize = Dbg.Tiny, horizontalPadding = 7.dp)
            Text(
                text = node.lookupKey,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                fontSize = Dbg.BodyPlus,
                fontFamily = Dbg.Mono,
                fontWeight = FontWeight.SemiBold,
                color = Dbg.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Inspect →",
                modifier = Modifier.clickable { vm.openNode(node.graphKey) },
                fontSize = Dbg.Body,
                fontWeight = FontWeight.SemiBold,
                color = Dbg.Accent,
            )
        }
        Text(
            text = "degree ${node.degree} · ${node.label}",
            modifier = Modifier.padding(top = 4.dp),
            fontSize = Dbg.Small,
            color = Dbg.TextSecondary,
        )
        incident.forEach { (direction, edgeType, otherKey) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.selectVizNode(otherKey) }
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = direction,
                    modifier = Modifier.width(48.dp),
                    fontSize = Dbg.Small,
                    fontFamily = Dbg.Mono,
                    color = Dbg.TextMuted,
                )
                Text(
                    text = edgeType,
                    modifier = Modifier.padding(end = 7.dp),
                    fontSize = Dbg.Tiny,
                    fontFamily = Dbg.Mono,
                    color = Dbg.NeutralChipText,
                )
                Text(
                    text = labelOf[otherKey] ?: otherKey.toString(),
                    modifier = Modifier.weight(1f),
                    fontSize = Dbg.Small,
                    fontFamily = Dbg.Mono,
                    color = Dbg.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LayoutButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Dbg.AccentTint else Dbg.Surface)
            .border(1.dp, if (selected) Dbg.Accent else Dbg.BorderInput, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = Dbg.Body,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Color(0xFF1B4FC0) else Dbg.TextSecondary,
            maxLines = 1,
        )
    }
}

/** Extra tap radius, in layout units, around a node's drawn circle. */
private const val TAP_SLOP_UNITS = 8f

private val LABEL_WIDTH = 84.dp
