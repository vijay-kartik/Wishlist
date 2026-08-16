package com.example.app.wishlist.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Debug inspector — the app's entry screen while the pipeline is under development.
 *
 * Five tabs: Pipeline (run NER over typed text), Graph (browse what was written), Scoring
 * (watch relevance decay), Query (the three read paths that become the query service) and
 * Visualize (the graph's shape). Deliberately not a product surface; see
 * `docs/debug-ui-prompt.md` for scope.
 */
@Composable
fun DebugScreen(viewModel: DebugViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    Column(Modifier.fillMaxSize().background(Dbg.AppBg)) {

        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .background(Dbg.Surface)
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Wishlist · Debug", fontSize = Dbg.Title, fontWeight = FontWeight.Bold, color = Dbg.TextPrimary)
            StatusChip(
                text = "INTERNAL",
                background = Dbg.WarnBg,
                foreground = Dbg.WarnText,
                modifier = Modifier.padding(start = 10.dp),
            )
            Box(Modifier.weight(1f))
            Text("objectbox 5.4.2", fontSize = Dbg.SectionLabel, fontFamily = Dbg.Mono, color = Dbg.TextMuted)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Dbg.Border))

        if (state.modelMissing) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Dbg.ErrBg)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Model unavailable — assets/ner_model_quantized.tflite could not be " +
                        "loaded. NER runs will fail; Graph and Scoring tabs still work.",
                    fontSize = Dbg.Body,
                    lineHeight = 16.sp,
                    color = Dbg.ErrText,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Dbg.ErrBorder))
        }

        // Tabs. Five across 412dp is tight but deliberate: a scrolling or overflowing
        // tab bar would hide a whole view behind a gesture on a screen whose entire point
        // is that everything is one tap away.
        Row(Modifier.fillMaxWidth().background(Dbg.Surface)) {
            DebugTab.entries.forEach { tab ->
                TabButton(tab.label, state.tab == tab, Modifier.weight(1f)) {
                    viewModel.setTab(tab)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Dbg.Border))

        // Content
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = SectionGap,
        ) {
            when (state.tab) {
                DebugTab.PIPELINE -> PipelineTab(state, viewModel)
                DebugTab.GRAPH -> GraphTab(state, viewModel)
                DebugTab.SCORING -> ScoringTab(state, viewModel)
                DebugTab.QUERY -> QueryTab(state, viewModel)
                DebugTab.VISUALIZE -> VisualizeTab(state, viewModel)
            }
        }
    }
}

/** Tab captions live here rather than at each call site so the bar cannot drift out of
 *  sync with the enum it renders. */
private val DebugTab.label: String
    get() = when (this) {
        DebugTab.PIPELINE -> "Pipeline"
        DebugTab.GRAPH -> "Graph"
        DebugTab.SCORING -> "Scoring"
        DebugTab.QUERY -> "Query"
        DebugTab.VISUALIZE -> "Visualize"
    }

@Composable
private fun TabButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            modifier = Modifier.padding(top = 10.dp, bottom = 9.dp),
            fontSize = Dbg.Body,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Dbg.Accent else Dbg.TextSecondary,
            maxLines = 1,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) Dbg.Accent else androidx.compose.ui.graphics.Color.Transparent)
        )
    }
}
