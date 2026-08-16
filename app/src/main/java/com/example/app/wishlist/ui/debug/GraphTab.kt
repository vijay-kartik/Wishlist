package com.example.app.wishlist.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun ColumnScope.GraphTab(state: DebugUiState, vm: DebugViewModel) {
    val selected = state.selectedNode
    if (selected == null) NodeListView(state, vm) else NodeDetailView(selected, vm)
}

@Composable
private fun ColumnScope.NodeListView(state: DebugUiState, vm: DebugViewModel) {

    DebugCard {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                SectionLabel("NODES · ${state.nodeTotal}", Modifier.padding(bottom = 6.dp))
                CountList(state.nodeCounts)
            }
            Box(Modifier.width(1.dp).height(52.dp).background(Dbg.Divider))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                SectionLabel("EDGES · ${state.edgeTotal}", Modifier.padding(bottom = 6.dp))
                CountList(state.edgeCounts)
            }
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DebugDropdown(
            value = state.filterType,
            options = DebugGraphQueries.filterOptions.map { it to it },
            onSelect = vm::setFilterType,
            modifier = Modifier.width(140.dp),
        )
        DebugTextField(
            value = state.search,
            onValueChange = vm::setSearch,
            modifier = Modifier.weight(1f),
            placeholder = "search lookupKey…",
            fontSize = Dbg.BodyPlus,
            cornerRadius = 6.dp,
            horizontalPadding = 10.dp,
            verticalPadding = 7.dp,
            singleLine = true,
        )
    }

    DebugListCard {
        if (state.nodeRows.isEmpty()) {
            Text(
                text = if (state.nodeTotal == 0L) "Graph is empty. Run the full pipeline to write something."
                else "No nodes match this filter.",
                modifier = Modifier.padding(12.dp),
                fontSize = Dbg.Body,
                color = Dbg.TextMuted,
            )
        }
        state.nodeRows.forEachIndexed { index, node ->
            if (index > 0) RowDivider()
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.openNode(node.graphKey) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeChip(node.type, fontSize = Dbg.Micro, horizontalPadding = 6.dp, verticalPadding = 1.dp)
                    Text(
                        text = node.lookupKey,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        fontSize = Dbg.Body,
                        fontFamily = Dbg.Mono,
                        color = Dbg.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = node.graphKey.toString(),
                        fontSize = Dbg.Tiny,
                        fontFamily = Dbg.Mono,
                        color = Dbg.TextFaint,
                    )
                }
                Text(
                    text = node.payloadPreview,
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = Dbg.SectionLabel,
                    fontFamily = Dbg.Mono,
                    color = Dbg.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (state.confirmClear) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Dbg.ErrBg)
                .border(1.dp, Dbg.ErrBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Wipe all KgNode + KgEdge rows?",
                Modifier.weight(1f),
                fontSize = Dbg.BodyPlus,
                color = Dbg.ErrText,
            )
            Text(
                text = "Wipe",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Dbg.ErrStrong)
                    .clickable { vm.doClear() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = Dbg.Body,
                fontWeight = FontWeight.SemiBold,
                color = Dbg.Surface,
            )
            Text(
                text = "Cancel",
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Dbg.Surface)
                    .border(1.dp, Dbg.BorderInput, RoundedCornerShape(6.dp))
                    .clickable { vm.cancelClear() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = Dbg.Body,
                color = Dbg.TextPrimary,
            )
        }
    } else {
        Text(
            text = "Clear graph…",
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Dbg.Surface)
                .border(1.dp, Dbg.ErrButtonBorder, RoundedCornerShape(8.dp))
                .clickable { vm.askClear() }
                .padding(horizontal = 14.dp, vertical = 7.dp),
            fontSize = Dbg.BodyPlus,
            fontWeight = FontWeight.SemiBold,
            color = Dbg.ErrStrong,
        )
    }
}

@Composable
private fun CountList(counts: List<TypeCount>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (counts.isEmpty()) {
            Text("—", fontSize = Dbg.Small, fontFamily = Dbg.Mono, color = Dbg.TextMuted)
        }
        counts.forEach { count ->
            Text(
                text = buildAnnotatedString {
                    append(count.type)
                    append(" ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(count.count.toString()) }
                },
                fontSize = Dbg.Small,
                fontFamily = Dbg.Mono,
                color = Dbg.TextBody,
            )
        }
    }
}

@Composable
private fun ColumnScope.NodeDetailView(node: NodeDetail, vm: DebugViewModel) {

    Text(
        text = "← All nodes",
        modifier = Modifier.clickable { vm.closeNode() },
        fontSize = Dbg.Normal,
        fontWeight = FontWeight.SemiBold,
        color = Dbg.Accent,
    )

    DebugCard(contentPadding = Modifier.padding(12.dp)) {
        Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TypeChip(node.type, fontSize = Dbg.SectionLabel, horizontalPadding = 8.dp)
            Text(
                text = node.lookupKey,
                modifier = Modifier.padding(start = 8.dp),
                fontSize = Dbg.Normal,
                fontFamily = Dbg.Mono,
                fontWeight = FontWeight.SemiBold,
                color = Dbg.TextPrimary,
            )
        }
        node.scalars.forEach { (key, value) -> KeyValueRow(key, value, 130.dp) }
        CodeBlock(node.payload, Modifier.padding(top = 10.dp))
    }

    if (node.isAssertion) {
        DebugCard {
            SectionLabel("ASSERTION", Modifier.padding(bottom = 8.dp))
            RoleRow("subject", node.subjectLabel) { vm.openNode(node.subjectKey) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("predicate", Modifier.width(56.dp), fontSize = Dbg.SectionLabel, color = Dbg.TextSecondary)
                Text(
                    text = node.predicate,
                    modifier = Modifier.padding(end = 8.dp),
                    fontSize = Dbg.Body,
                    fontFamily = Dbg.Mono,
                    fontWeight = FontWeight.SemiBold,
                    color = Dbg.TextPrimary,
                )
                Text(
                    text = "conf ${node.confidence} · ${node.observedDate}",
                    fontSize = Dbg.SectionLabel,
                    fontFamily = Dbg.Mono,
                    color = Dbg.TextMuted,
                )
            }
            RoleRow("object", node.objectLabel) { vm.openNode(node.objectKey) }
        }
    }

    DebugListCard {
        SectionLabel(
            "EDGES · ${node.edges.size}",
            Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        )
        if (node.edges.isEmpty()) {
            Text(
                "No edges touch this node.",
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                fontSize = Dbg.Body,
                color = Dbg.TextMuted,
            )
        }
        node.edges.forEach { edge ->
            RowDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = edge.otherExists) { vm.openNode(edge.otherKey) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = edge.direction,
                    modifier = Modifier.width(22.dp),
                    fontSize = Dbg.SectionLabel,
                    fontFamily = Dbg.Mono,
                    color = Dbg.TextMuted,
                )
                Text(
                    text = edge.edgeType,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Dbg.EdgeChipBg)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    fontSize = Dbg.Tiny,
                    fontWeight = FontWeight.Bold,
                    color = Dbg.NeutralChipText,
                )
                TypeChip(
                    type = edge.otherType,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    fontSize = Dbg.Micro,
                    horizontalPadding = 6.dp,
                    verticalPadding = 1.dp,
                )
                Text(
                    text = edge.otherLabel,
                    fontSize = Dbg.Body,
                    fontFamily = Dbg.Mono,
                    color = if (edge.otherExists) Dbg.Accent else Dbg.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RoleRow(role: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Dbg.CodeBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(role, Modifier.width(56.dp), fontSize = Dbg.SectionLabel, color = Dbg.TextSecondary)
        Text(
            text = label,
            fontSize = Dbg.Body,
            fontFamily = Dbg.Mono,
            color = Dbg.Accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
