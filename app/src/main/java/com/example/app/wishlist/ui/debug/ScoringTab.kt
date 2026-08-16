package com.example.app.wishlist.ui.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app.wishlist.graph.core.Scoring

@Composable
fun ColumnScope.ScoringTab(state: DebugUiState, vm: DebugViewModel) {

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("PERSON", fontSize = Dbg.Body, color = Dbg.TextSecondary)
        DebugDropdown(
            value = state.selectedPerson.toString(),
            options = state.persons.map { it.graphKey.toString() to it.label }
                .ifEmpty { listOf("0" to "no PERSON nodes yet") },
            onSelect = { vm.selectPerson(it.toLongOrNull() ?: 0L) },
            modifier = Modifier.weight(1f).padding(start = 10.dp),
            fontSize = Dbg.Normal,
        )
    }

    DebugCard {
        Row(verticalAlignment = Alignment.Bottom) {
            SectionLabel("SIMULATE AGE")
            Text(
                text = "+${state.ageOffset} days",
                modifier = Modifier.padding(start = 8.dp),
                fontSize = Dbg.Normal,
                fontFamily = Dbg.Mono,
                fontWeight = FontWeight.SemiBold,
                color = Dbg.Accent,
            )
            Text(
                text = "display-time offset — observedAtEpochDay untouched",
                modifier = Modifier.padding(start = 8.dp),
                fontSize = Dbg.SectionLabel,
                color = Dbg.TextMuted,
            )
        }
        Slider(
            value = state.ageOffset.toFloat(),
            onValueChange = { vm.setAgeOffset(it.toInt()) },
            valueRange = 0f..365f,
            steps = 0,
            colors = SliderDefaults.colors(
                thumbColor = Dbg.Accent,
                activeTrackColor = Dbg.Accent,
                inactiveTrackColor = Dbg.BarTrack,
            ),
        )
    }

    // --- Assertion rows ------------------------------------------------------
    DebugListCard {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 5.dp)) {
            ColumnHeader("PREDICATE → OBJECT", Modifier.weight(1.4f), TextAlign.Start)
            ColumnHeader("CONF", Modifier.width(40.dp), TextAlign.End)
            ColumnHeader("AGE d", Modifier.width(44.dp), TextAlign.End)
            ColumnHeader("RELEV", Modifier.width(52.dp), TextAlign.End)
        }
        if (state.assertionRows.isEmpty()) {
            Text(
                text = if (state.persons.isEmpty()) "No PERSON nodes yet — run the full pipeline first."
                else "No assertions with this subject.",
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 4.dp),
                fontSize = Dbg.Body,
                color = Dbg.TextMuted,
            )
        }
        state.assertionRows.forEach { row ->
            RowDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${row.predicate} → ${row.objectLabel}",
                    modifier = Modifier.weight(1.4f),
                    fontSize = Dbg.Small,
                    fontFamily = Dbg.Mono,
                    color = Dbg.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Cell(row.confidence, Modifier.width(40.dp), Dbg.TextSecondary)
                Cell(row.ageDays, Modifier.width(44.dp), Dbg.TextSecondary)
                Cell(
                    text = row.relevance,
                    modifier = Modifier.width(52.dp),
                    color = when {
                        row.relevanceValue < 0 -> Dbg.ErrStrong
                        row.relevanceValue > 0.4 -> Dbg.OkText
                        else -> Dbg.TextBody
                    },
                    weight = FontWeight.SemiBold,
                )
            }
        }
    }

    // --- Rollup by object ----------------------------------------------------
    DebugListCard {
        SectionLabel(
            "BY OBJECT · baseScore → normalized",
            Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        )
        if (state.groupRows.isEmpty()) {
            Text(
                "Nothing to roll up.",
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                fontSize = Dbg.Body,
                color = Dbg.TextMuted,
            )
        }
        state.groupRows.forEach { group ->
            RowDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${group.label} ×${group.count}",
                    modifier = Modifier.weight(1f),
                    fontSize = Dbg.Body,
                    fontFamily = Dbg.Mono,
                    color = Dbg.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier
                        .padding(horizontal = 8.dp)
                        .width(80.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Dbg.BarTrack)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(group.barFraction)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Dbg.Accent)
                    )
                }
                Text(
                    text = "${group.base} → ${group.normalized}",
                    modifier = Modifier.width(94.dp),
                    fontSize = Dbg.Small,
                    fontFamily = Dbg.Mono,
                    color = Dbg.TextBody,
                    textAlign = TextAlign.End,
                )
            }
        }
    }

    // --- Acquisition effect curve -------------------------------------------
    DebugCard {
        SectionLabel("ACQUISITION EFFECT · ageDays 0–365", Modifier.padding(bottom = 6.dp))
        AcquisitionCurve()
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendItem(Dbg.ErrStrong, "durable (replenishCycleDays = null)")
            LegendItem(Dbg.Accent, "replenishable (cycle = 90d)")
        }
    }
    Box(Modifier.height(4.dp))
}

/**
 * Plots `Scoring.acquisitionEffect` over a year for both category kinds.
 *
 * The real function, not a mock of it: the durable curve should only ever approach zero
 * from below, while the replenishable one crosses zero exactly at its cycle length and
 * becomes a boost. Seeing them together is the quickest way to confirm suppression and
 * replenishment are wired to the same curve.
 */
@Composable
private fun AcquisitionCurve() {
    val maxAge = 365.0
    val cap = Scoring.ACQUISITION_CAP.toFloat()

    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        val w = size.width
        val h = size.height
        val midY = h / 2f

        // Zero line, plus the ±cap guides.
        drawLine(Dbg.Border, Offset(0f, midY), Offset(w, midY), strokeWidth = 1f)
        drawLine(Dbg.Divider, Offset(0f, midY - h * 0.42f), Offset(w, midY - h * 0.42f), strokeWidth = 1f)
        drawLine(Dbg.Divider, Offset(0f, midY + h * 0.42f), Offset(w, midY + h * 0.42f), strokeWidth = 1f)

        fun pathFor(cycle: Double?): Path = Path().apply {
            var first = true
            var x = 0f
            while (x <= w) {
                val age = (x / w) * maxAge
                val value = Scoring.acquisitionEffect(age, cycle).toFloat()
                // Normalise by the cap so both curves share one vertical scale.
                val y = midY - (value / cap) * (h * 0.42f)
                if (first) { moveTo(x, y); first = false } else { lineTo(x, y) }
                x += 2f
            }
        }

        drawPath(pathFor(null), Dbg.ErrStrong, style = Stroke(width = 1.5.dp.toPx()))
        drawPath(pathFor(90.0), Dbg.Accent, style = Stroke(width = 1.5.dp.toPx()))
    }
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 14.dp, height = 2.dp).background(color))
        Text(
            text = label,
            modifier = Modifier.padding(start = 5.dp),
            fontSize = Dbg.SectionLabel,
            color = Dbg.TextSecondary,
        )
    }
}

@Composable
private fun ColumnHeader(text: String, modifier: Modifier, align: TextAlign) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = Dbg.Tiny,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = Dbg.TextMuted,
        textAlign = align,
    )
}

@Composable
private fun Cell(
    text: String,
    modifier: Modifier,
    color: androidx.compose.ui.graphics.Color,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = Dbg.Small,
        fontFamily = Dbg.Mono,
        fontWeight = weight,
        color = color,
        textAlign = TextAlign.End,
    )
}
