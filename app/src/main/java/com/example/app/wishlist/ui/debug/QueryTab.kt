package com.example.app.wishlist.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Query tab — the three read paths over the graph, side by side.
 *
 * Gift ranking is the product question ("what should I get this person"), raw query is the
 * schema question ("what is actually stored"), and traversal is the connectivity question
 * ("how does this node reach anything"). Each mode prints the equivalent Kotlin, because
 * these are the queries that become `WishlistQueryService` and the translation from the
 * screen to that class should be mechanical rather than remembered.
 */
@Composable
fun ColumnScope.QueryTab(state: DebugUiState, vm: DebugViewModel) {

    Row(Modifier.fillMaxWidth()) {
        ModeButton("Gift ranking", state.queryMode == QueryMode.GIFT, Modifier.weight(1f)) {
            vm.setQueryMode(QueryMode.GIFT)
        }
        Box(Modifier.width(6.dp))
        ModeButton("Raw node query", state.queryMode == QueryMode.RAW, Modifier.weight(1f)) {
            vm.setQueryMode(QueryMode.RAW)
        }
        Box(Modifier.width(6.dp))
        ModeButton("Traversal", state.queryMode == QueryMode.TRAVERSAL, Modifier.weight(1f)) {
            vm.setQueryMode(QueryMode.TRAVERSAL)
        }
    }

    when (state.queryMode) {
        QueryMode.GIFT -> GiftMode(state, vm)
        QueryMode.RAW -> RawMode(state, vm)
        QueryMode.TRAVERSAL -> TraversalMode(state, vm)
    }
}

// --- gift ranking ------------------------------------------------------------

@Composable
private fun ColumnScope.GiftMode(state: DebugUiState, vm: DebugViewModel) {

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("gift for", fontSize = Dbg.Body, color = Dbg.TextSecondary)
        DebugDropdown(
            value = state.giftPerson.toString(),
            options = state.persons.map { it.graphKey.toString() to it.label },
            onSelect = { vm.selectGiftPerson(it.toLongOrNull() ?: 0L) },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        DebugCheckbox(
            checked = state.suppressOwned,
            onCheckedChange = vm::setSuppressOwned,
            label = "suppress owned",
        )
    }

    DebugListCard {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            SectionLabel("CANDIDATES · ${state.giftRows.size}")
            Text(
                text = " · topMargin ${state.giftTopMargin}",
                fontSize = Dbg.SectionLabel,
                color = Dbg.TextMuted,
            )
        }

        if (state.giftRows.isEmpty()) {
            Text(
                text = if (state.persons.isEmpty()) {
                    "No PERSON nodes yet — run the full pipeline first."
                } else {
                    "No PRODUCT or CATEGORY objects asserted for this subject."
                },
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                fontSize = Dbg.Body,
                color = Dbg.TextMuted,
            )
        }

        state.giftRows.forEach { row ->
            RowDivider()
            Column(Modifier.fillMaxWidth().clickable { vm.toggleGiftRow(row.graphKey) }) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.rank,
                        modifier = Modifier.width(20.dp),
                        fontSize = Dbg.Body,
                        fontFamily = Dbg.Mono,
                        fontWeight = FontWeight.Bold,
                        color = Dbg.TextFaint,
                    )
                    TypeChip(row.type, fontSize = Dbg.Micro, horizontalPadding = 6.dp, verticalPadding = 1.dp)
                    Text(
                        text = row.label,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        fontSize = Dbg.BodyPlus,
                        fontFamily = Dbg.Mono,
                        color = Dbg.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ScoreBar(row.barFraction, row.negative)
                    Text(
                        text = row.score,
                        modifier = Modifier.width(46.dp).padding(start = 6.dp),
                        fontSize = Dbg.Body,
                        fontFamily = Dbg.Mono,
                        fontWeight = FontWeight.SemiBold,
                        color = if (row.negative) Dbg.ErrStrong else Dbg.TextBody,
                    )
                }

                if (row.expanded) {
                    Column(Modifier.padding(start = 40.dp, end = 12.dp, bottom = 10.dp)) {
                        if (row.routeSummary.isNotEmpty()) {
                            Text(
                                text = row.routeSummary,
                                modifier = Modifier.padding(bottom = 2.dp),
                                fontSize = Dbg.Micro,
                                fontFamily = Dbg.Mono,
                                color = Dbg.TextMuted,
                            )
                        }
                        row.terms.forEach { term ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                if (term.viaBeneficiary) {
                                    // Marks the assertion as reaching this person through a
                                    // BENEFICIARY edge — someone picked it out *for* them,
                                    // rather than them saying it themselves.
                                    Text(
                                        text = "for them ",
                                        fontSize = Dbg.Micro,
                                        fontFamily = Dbg.Mono,
                                        fontWeight = FontWeight.Bold,
                                        color = TypePalette.foreground("RECIPIENT"),
                                    )
                                }
                                Text(
                                    text = term.expression,
                                    modifier = Modifier.weight(1f),
                                    fontSize = Dbg.SectionLabel,
                                    fontFamily = Dbg.Mono,
                                    color = Dbg.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = term.value,
                                    modifier = Modifier.padding(start = 8.dp),
                                    fontSize = Dbg.SectionLabel,
                                    fontFamily = Dbg.Mono,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (term.negative) Dbg.ErrStrong else Dbg.OkText,
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(1.dp).background(Dbg.Divider))
                        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                            Text(
                                text = "baseScore → normalize",
                                modifier = Modifier.weight(1f),
                                fontSize = Dbg.SectionLabel,
                                fontFamily = Dbg.Mono,
                                color = Dbg.TextBody,
                            )
                            Text(
                                text = row.detail,
                                fontSize = Dbg.SectionLabel,
                                fontFamily = Dbg.Mono,
                                fontWeight = FontWeight.Bold,
                                color = Dbg.TextBody,
                            )
                        }
                        if (row.negative) {
                            // Worth saying out loud: this is a real product consequence of
                            // normalize() flooring at zero, not a rendering quirk.
                            Text(
                                text = "base is negative — normalize() floors at 0, so this " +
                                    "ranks level with anything never mentioned",
                                modifier = Modifier.padding(top = 3.dp),
                                fontSize = Dbg.Micro,
                                lineHeight = 13.sp,
                                color = Dbg.ErrStrong,
                            )
                        }
                    }
                }
            }
        }
    }

    CodeBlock(
        text = GIFT_KOTLIN,
        background = Dbg.StackBg,
        foreground = Dbg.StackText,
        border = null,
    )
}

@Composable
private fun ScoreBar(fraction: Float, negative: Boolean) {
    Box(
        Modifier
            .width(64.dp)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Dbg.BarTrack)
    ) {
        // A negative candidate normalises to exactly 0, so there is deliberately no bar
        // to draw — the empty track plus the red score is the honest rendering of "this
        // scores the same as something never mentioned".
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (negative) Dbg.ErrStrong else Dbg.Accent)
            )
        }
    }
}

// --- raw node query ----------------------------------------------------------

@Composable
private fun ColumnScope.RawMode(state: DebugUiState, vm: DebugViewModel) {

    DebugCard {
        FilterRow("nodeType") {
            DebugDropdown(
                value = state.rawType,
                options = DebugGraphQueries.filterOptions.map { it to it },
                onSelect = vm::setRawType,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FilterRow("predicate") {
            DebugDropdown(
                value = state.rawPredicate,
                options = DebugGraphQueries.predicateOptions.map { it to it },
                onSelect = vm::setRawPredicate,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FilterRow("lookupKey ~") {
            DebugTextField(
                value = state.rawKey,
                onValueChange = vm::setRawKey,
                placeholder = "substring…",
                fontSize = Dbg.Body,
                cornerRadius = 6.dp,
                horizontalPadding = 8.dp,
                verticalPadding = 6.dp,
                singleLine = true,
            )
        }
        FilterRow("conf ≥") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = state.rawConfPct.toFloat(),
                    onValueChange = { vm.setRawConfidence((it / 5f).toInt() * 5) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Dbg.Accent,
                        activeTrackColor = Dbg.Accent,
                        inactiveTrackColor = Dbg.BarTrack,
                    ),
                )
                Text(
                    text = String.format("%.2f", state.rawConfidence),
                    modifier = Modifier.width(34.dp).padding(start = 6.dp),
                    fontSize = Dbg.Body,
                    fontFamily = Dbg.Mono,
                    color = Dbg.TextPrimary,
                )
            }
        }
        FilterRow("newer than") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = state.rawDays.toFloat(),
                    onValueChange = { vm.setRawDays(it.toInt().coerceAtLeast(1)) },
                    valueRange = 1f..365f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Dbg.Accent,
                        activeTrackColor = Dbg.Accent,
                        inactiveTrackColor = Dbg.BarTrack,
                    ),
                )
                Text(
                    text = "${state.rawDays}d",
                    modifier = Modifier.width(44.dp).padding(start = 6.dp),
                    fontSize = Dbg.Body,
                    fontFamily = Dbg.Mono,
                    color = Dbg.TextPrimary,
                )
            }
        }
    }

    CodeBlock(
        text = state.rawKotlin,
        background = Dbg.StackBg,
        foreground = Dbg.StackText,
        border = null,
    )

    DebugListCard {
        SectionLabel(
            text = "${state.rawRows.size} ROWS",
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        )
        if (state.rawRows.isEmpty()) {
            Text(
                text = "Nothing matches these filters.",
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                fontSize = Dbg.Body,
                color = Dbg.TextMuted,
            )
        }
        state.rawRows.forEach { row ->
            RowDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.openNode(row.graphKey) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TypeChip(row.type, fontSize = Dbg.Micro, horizontalPadding = 6.dp, verticalPadding = 1.dp)
                Text(
                    text = row.lookupKey,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    fontSize = Dbg.Body,
                    fontFamily = Dbg.Mono,
                    color = Dbg.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.meta,
                    fontSize = Dbg.Small,
                    fontFamily = Dbg.Mono,
                    color = Dbg.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun FilterRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(74.dp),
            fontSize = Dbg.Body,
            color = Dbg.TextSecondary,
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

// --- traversal ---------------------------------------------------------------

@Composable
private fun ColumnScope.TraversalMode(state: DebugUiState, vm: DebugViewModel) {

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("from", fontSize = Dbg.Body, color = Dbg.TextSecondary)
        DebugDropdown(
            value = state.travStart.toString(),
            options = state.nodeOptions.map { it.graphKey.toString() to it.label },
            onSelect = { vm.setTravStart(it.toLongOrNull() ?: 0L) },
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("depth", fontSize = Dbg.Body, color = Dbg.TextSecondary)
        Slider(
            value = state.travDepth.toFloat(),
            onValueChange = { vm.setTravDepth(it.toInt().coerceIn(1, 4)) },
            valueRange = 1f..4f,
            steps = 2,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = Dbg.Accent,
                activeTrackColor = Dbg.Accent,
                inactiveTrackColor = Dbg.BarTrack,
            ),
        )
        Text(
            text = state.travDepth.toString(),
            fontSize = Dbg.Normal,
            fontFamily = Dbg.Mono,
            fontWeight = FontWeight.SemiBold,
            color = Dbg.Accent,
        )
        Text(
            text = "  ${state.travRows.size} visited",
            fontSize = Dbg.SectionLabel,
            color = Dbg.TextMuted,
        )
    }

    if (state.travTruncated) {
        Text(
            text = "Stopped at ${DebugGraphQueries.MAX_TRAVERSAL_ROWS} nodes — the walk reached " +
                "the cap, so this is a partial view. Reduce depth to see a complete one.",
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

    DebugListCard {
        if (state.travRows.isEmpty()) {
            Text(
                text = "Nothing to walk — the graph is empty.",
                modifier = Modifier.padding(12.dp),
                fontSize = Dbg.Body,
                color = Dbg.TextMuted,
            )
        }
        state.travRows.forEachIndexed { index, row ->
            if (index > 0) RowDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = row.exists) { vm.openNode(row.graphKey) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.indent,
                    fontSize = Dbg.Small,
                    fontFamily = Dbg.Mono,
                    color = Dbg.BorderInput,
                )
                Text(
                    text = row.via,
                    modifier = Modifier.padding(end = 6.dp),
                    fontSize = Dbg.Tiny,
                    fontFamily = Dbg.Mono,
                    color = Dbg.TextMuted,
                )
                TypeChip(row.type, fontSize = Dbg.Micro, horizontalPadding = 6.dp, verticalPadding = 1.dp)
                Text(
                    text = row.label,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                    fontSize = Dbg.Small,
                    fontFamily = Dbg.Mono,
                    color = if (row.exists) Dbg.TextPrimary else Dbg.ErrStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    CodeBlock(
        text = TRAVERSAL_KOTLIN,
        background = Dbg.StackBg,
        foreground = Dbg.StackText,
        border = null,
    )
}

// --- shared ------------------------------------------------------------------

/** Segmented selector for the three query modes. */
@Composable
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Dbg.AccentTint else Dbg.Surface)
            .border(
                1.dp,
                if (selected) Dbg.Accent else Dbg.BorderInput,
                RoundedCornerShape(7.dp),
            )
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

private val GIFT_KOTLIN = """
// Two routes reach a person. subjectKey alone answers "what have they talked
// about" — not "what should they be given" — and returns nothing at all for
// someone who is only ever a recipient.
fun candidatesFor(personKey: Long): List<Ranked> {
  val said = nodeBox.query(KgNode_.nodeTypeId.equal(NodeType.ASSERTION.id.toLong())
      .and(KgNode_.subjectKey.equal(personKey)))
    .build().use { it.find() }

  val saidFor = edgeBox.query(KgEdge_.toKey.equal(personKey)
      .and(KgEdge_.edgeTypeId.equal(EdgeType.BENEFICIARY.id.toLong())))
    .build().use { it.find() }
    .mapNotNull { nodeBox.get(it.fromKey) }

  // Drop claims this person made for someone ELSE: that is evidence about the
  // beneficiary, not about them.
  val forOthers = beneficiaryTargets(said.map { it.graphKey }.toSet())
  val own = said.filter { forOthers[it.graphKey].let { t -> t == null || t == personKey } }

  return (own + saidFor).distinctBy { it.graphKey }
    .groupBy { it.objectKey }
    .map { (key, rows) ->
      val signals = rows.map {
        AssertionSignal(it.predicate, it.confidence,
            (today - it.observedAtEpochDay).toDouble(), it.isDismissed)
      }
      Ranked(key, Scoring.normalize(Scoring.baseScore(signals)))
    }.sortedByDescending { it.score }
}
""".trim()

private val TRAVERSAL_KOTLIN = """
fun walk(startKey: Long, maxDepth: Int): List<Hop> {
  val seen = mutableSetOf<Long>()
  val out = mutableListOf<Hop>()
  fun step(key: Long, depth: Int, via: EdgeType?) {
    if (depth > maxDepth || !seen.add(key)) return
    out += Hop(key, depth, via)
    edgeBox.query(KgEdge_.fromKey.equal(key)).build().use { it.find() }
      .forEach { step(it.toKey, depth + 1, it.edgeType) }
    edgeBox.query(KgEdge_.toKey.equal(key)).build().use { it.find() }
      .forEach { step(it.fromKey, depth + 1, it.edgeType) }
  }
  step(startKey, 0, null)
  return out
}
""".trim()
