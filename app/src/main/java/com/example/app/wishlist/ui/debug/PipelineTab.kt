package com.example.app.wishlist.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app.wishlist.graph.ingest.IngestionOutcome

@Composable
fun ColumnScope.PipelineTab(state: DebugUiState, vm: DebugViewModel) {

    DebugTextField(
        value = state.msgText,
        onValueChange = vm::setMessage,
        placeholder = "Message body…",
        minHeight = 76.dp,
    )

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("sender", fontSize = Dbg.Body, color = Dbg.TextSecondary)
        DebugTextField(
            value = state.sender,
            onValueChange = vm::setSender,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            cornerRadius = 6.dp,
            horizontalPadding = 10.dp,
            verticalPadding = 6.dp,
            singleLine = true,
        )
        DebugCheckbox(
            checked = state.reuseKey,
            onCheckedChange = vm::setReuseKey,
            label = "reuse last key",
        )
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SAMPLES.forEach { sample ->
            SamplePill(
                label = if (sample.length > 42) sample.take(40) + "…" else sample,
                onClick = { vm.setMessage(sample) },
            )
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(
            text = "Run NER only",
            onClick = vm::runNerOnly,
            modifier = Modifier.weight(1f),
            enabled = !state.running,
        )
        PrimaryButton(
            text = "Run full pipeline",
            onClick = vm::runFullPipeline,
            modifier = Modifier.weight(1f),
            enabled = !state.running,
        )
    }

    if (state.running) {
        Text(
            text = state.runningLabel,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Dbg.Divider)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            fontSize = Dbg.Body,
            color = Dbg.TextSecondary,
        )
    }

    if (!state.hasResult) return

    // --- Highlighted source --------------------------------------------------
    DebugCard {
        SectionLabel("HIGHLIGHTED SOURCE", Modifier.padding(bottom = 8.dp))
        Text(
            text = highlightedText(state.segments),
            fontSize = Dbg.Normal,
            fontFamily = Dbg.Mono,
            lineHeight = 23.sp,
            color = Dbg.TextPrimary,
        )
    }

    // --- Entities ------------------------------------------------------------
    DebugListCard {
        SectionLabel(
            "ENTITIES · ${state.entityCount}",
            Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        )
        if (!state.hasEntities) {
            Text(
                "No entities extracted.",
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                fontSize = Dbg.Body,
                color = Dbg.TextMuted,
            )
        }
        state.entities.forEach { entity ->
            RowDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TypeChip(entity.type)
                Text(
                    text = entity.text,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    fontSize = Dbg.BodyPlus,
                    fontFamily = Dbg.Mono,
                    color = Dbg.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(entity.span, fontSize = Dbg.Small, fontFamily = Dbg.Mono, color = Dbg.TextMuted)
                Text(
                    text = entity.confidence,
                    modifier = Modifier.width(40.dp).padding(start = 6.dp),
                    fontSize = Dbg.Small,
                    fontFamily = Dbg.Mono,
                    color = Dbg.TextBody,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }

    // --- Intent --------------------------------------------------------------
    DebugCard {
        SectionLabel("INTENT", Modifier.padding(bottom = 8.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text("predicate", Modifier.width(110.dp), fontSize = Dbg.BodyPlus, color = Dbg.TextSecondary)
            Text(
                text = state.intentPredicate,
                fontSize = Dbg.BodyPlus,
                fontFamily = Dbg.Mono,
                fontWeight = FontWeight.SemiBold,
                color = Dbg.TextPrimary,
            )
            Text(
                text = " ${state.intentConfidence}",
                fontSize = Dbg.BodyPlus,
                fontFamily = Dbg.Mono,
                color = Dbg.TextMuted,
            )
        }
        KeyValueRow("matched cue", state.intentCue, 110.dp)
        KeyValueRow("beneficiary", state.intentBeneficiary, 110.dp)
        KeyValueRow("is recipient", state.intentIsRecipient, 110.dp)
    }

    // --- Timing --------------------------------------------------------------
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("inference ", fontSize = Dbg.Body, color = Dbg.TextSecondary)
        Text("${state.inferenceMillis} ms", fontSize = Dbg.Body, fontFamily = Dbg.Mono, color = Dbg.TextPrimary)
        if (state.truncated) {
            Spacer(Modifier.width(10.dp))
            Text(
                // The model truncates at its sequence length in *tokens*, not characters —
                // roughly 128 WordPiece tokens, which is far fewer than 128 words.
                text = "⚠ input truncated at model sequence length",
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Dbg.WarnBg)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                fontSize = Dbg.Body,
                fontWeight = FontWeight.SemiBold,
                color = Dbg.WarnText,
            )
        }
    }

    // --- Ingestion outcome ---------------------------------------------------
    val outcome = state.outcome ?: return
    DebugCard {
        SectionLabel("INGESTION OUTCOME", Modifier.padding(bottom = 8.dp))
        when (outcome) {
            is IngestionOutcome.Written -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip("WRITTEN", Dbg.OkBg, Dbg.OkText)
                    Text(
                        text = state.outcomeSummary,
                        modifier = Modifier.padding(start = 8.dp),
                        fontSize = Dbg.BodyPlus,
                        color = Dbg.TextPrimary,
                    )
                }
                Row(Modifier.padding(top = 6.dp)) {
                    Text("notificationKey ", fontSize = Dbg.Body, color = Dbg.TextSecondary)
                    Text(state.outcomeKey, fontSize = Dbg.Body, fontFamily = Dbg.Mono, color = Dbg.TextBody)
                }
            }

            IngestionOutcome.Duplicate -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip("DUPLICATE", Dbg.NeutralChipBg, Dbg.NeutralChipText)
                    Text(
                        text = "notificationKey already ingested — nothing written.",
                        modifier = Modifier.padding(start = 8.dp),
                        fontSize = Dbg.BodyPlus,
                        color = Dbg.TextSecondary,
                    )
                }
                Text(
                    text = state.outcomeKey,
                    modifier = Modifier.padding(top = 4.dp),
                    fontSize = Dbg.Body,
                    fontFamily = Dbg.Mono,
                    color = Dbg.TextBody,
                )
            }

            IngestionOutcome.NothingExtracted -> Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip("NOTHING_EXTRACTED", Dbg.NeutralChipBg, Dbg.NeutralChipText)
                Text(
                    text = "Nothing storable in this message — normal outcome, nothing written.",
                    modifier = Modifier.padding(start = 8.dp),
                    fontSize = Dbg.BodyPlus,
                    color = Dbg.TextSecondary,
                )
            }

            is IngestionOutcome.NerFailed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip("NER_FAILED", Dbg.ErrBg, Dbg.ErrStrong)
                    Text(
                        text = state.failureName,
                        modifier = Modifier.padding(start = 8.dp),
                        fontSize = Dbg.BodyPlus,
                        fontFamily = Dbg.Mono,
                        color = Dbg.ErrText,
                    )
                }
                Text(
                    text = if (state.showStack) "Hide stack trace" else "Show stack trace",
                    modifier = Modifier.padding(top = 6.dp).clickable { vm.toggleStack() },
                    fontSize = Dbg.Body,
                    fontWeight = FontWeight.SemiBold,
                    color = Dbg.Accent,
                )
                if (state.showStack) {
                    CodeBlock(
                        text = state.stackTrace,
                        modifier = Modifier.padding(top = 8.dp),
                        background = Dbg.StackBg,
                        foreground = Dbg.StackText,
                        border = null,
                    )
                }
            }
        }
    }
    Box(Modifier.height(4.dp))
}

/**
 * Builds the inline-highlighted message.
 *
 * Each entity run gets its type's chip background, its type's foreground colour, and an
 * underline — the design's `inset 0 -2px 0` bottom rule, which Compose has no per-span
 * equivalent for. Colouring the text as well as underlining it keeps the type association
 * legible at 12sp.
 */
private fun highlightedText(segments: List<HighlightSegment>): AnnotatedString =
    buildAnnotatedString {
        segments.forEach { segment ->
            val type = segment.type
            if (type == null) {
                append(segment.text)
            } else {
                withStyle(
                    SpanStyle(
                        background = TypePalette.background(type),
                        color = TypePalette.foreground(type),
                        textDecoration = TextDecoration.Underline,
                    )
                ) { append(segment.text) }
            }
        }
    }
