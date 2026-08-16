package com.example.app.wishlist.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The white rounded panel every section on this screen sits in. */
@Composable
fun DebugCard(
    modifier: Modifier = Modifier,
    contentPadding: Modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Dbg.Surface)
            .border(1.dp, Dbg.Border, RoundedCornerShape(10.dp))
            .then(contentPadding),
        content = content,
    )
}

/** Card variant for list content, where rows draw their own dividers edge to edge. */
@Composable
fun DebugListCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Dbg.Surface)
            .border(1.dp, Dbg.Border, RoundedCornerShape(10.dp)),
        content = content,
    )
}

/** The small tracked-out grey heading above each section. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = Dbg.SectionLabel,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        color = Dbg.TextMuted,
    )
}

/** Coloured type chip, used for both entity types and node types. */
@Composable
fun TypeChip(
    type: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = Dbg.Tiny,
    horizontalPadding: androidx.compose.ui.unit.Dp = 7.dp,
    verticalPadding: androidx.compose.ui.unit.Dp = 2.dp,
) {
    Text(
        text = type,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(TypePalette.background(type))
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.4.sp,
        color = TypePalette.foreground(type),
    )
}

/** Status chip with explicit colours — WRITTEN, DUPLICATE, NER_FAILED and friends. */
@Composable
fun StatusChip(text: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = Dbg.SectionLabel,
        fontWeight = FontWeight.Bold,
        color = foreground,
    )
}

/** One row of the label/value grids in the Intent and node-detail panels. */
@Composable
fun KeyValueRow(
    key: String,
    value: String,
    keyWidth: androidx.compose.ui.unit.Dp,
    valueWeight: FontWeight = FontWeight.Normal,
    valueColor: Color = Dbg.TextPrimary,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = key,
            modifier = Modifier.width(keyWidth),
            fontSize = Dbg.Body,
            color = Dbg.TextSecondary,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            fontSize = Dbg.Body,
            fontFamily = Dbg.Mono,
            fontWeight = valueWeight,
            color = valueColor,
        )
        trailing?.invoke(this)
    }
}

/** Monospace preformatted block — payload JSON, stack traces. */
@Composable
fun CodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = Dbg.CodeBg,
    foreground: Color = Dbg.TextBody,
    border: Color? = Dbg.CodeBorder,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .then(if (border != null) Modifier.border(1.dp, border, RoundedCornerShape(8.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            fontSize = Dbg.Small,
            fontFamily = Dbg.Mono,
            lineHeight = 17.sp,
            color = foreground,
        )
    }
}

/** Filled primary button. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Dbg.Accent else Dbg.Accent.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = Dbg.Normal, fontWeight = FontWeight.SemiBold, color = Dbg.Surface)
    }
}

/** Outlined secondary button. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = Dbg.Accent,
    borderColor: Color = Dbg.Accent,
) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Dbg.Surface)
            .border(1.dp, borderColor.copy(alpha = alpha), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = Dbg.Normal,
            fontWeight = FontWeight.SemiBold,
            color = contentColor.copy(alpha = alpha),
        )
    }
}

/** Pill-shaped sample-message button. */
@Composable
fun SamplePill(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Dbg.Surface)
            .border(1.dp, Dbg.BorderInput, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        fontSize = Dbg.Small,
        color = Dbg.TextBody,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Full-bleed 1dp separator between list rows. */
@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Dbg.Divider))
}

/** Vertical spacing helper so tabs share the design's 10dp rhythm. */
val SectionGap = Arrangement.spacedBy(10.dp)
