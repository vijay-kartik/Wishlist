package com.example.app.wishlist.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Text input matching the design's 1dp border / 8dp radius / monospace styling.
 *
 * Built on [BasicTextField] rather than Material's `OutlinedTextField`, whose built-in
 * label slot and 56dp minimum height would blow out the density this screen depends on.
 */
@Composable
fun DebugTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    minHeight: androidx.compose.ui.unit.Dp = 0.dp,
    fontSize: TextUnit = Dbg.Normal,
    cornerRadius: androidx.compose.ui.unit.Dp = 8.dp,
    horizontalPadding: androidx.compose.ui.unit.Dp = 12.dp,
    verticalPadding: androidx.compose.ui.unit.Dp = 10.dp,
    singleLine: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(Dbg.Surface)
            .border(1.dp, Dbg.BorderInput, RoundedCornerShape(cornerRadius))
            .heightIn(min = minHeight),
        textStyle = TextStyle(
            fontSize = fontSize,
            fontFamily = Dbg.Mono,
            color = Dbg.TextPrimary,
            lineHeight = fontSize * 1.5f,
        ),
        singleLine = singleLine,
        cursorBrush = SolidColor(Dbg.Accent),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, fontSize = fontSize, fontFamily = Dbg.Mono, color = Dbg.TextMuted)
                }
                inner()
            }
        },
    )
}

/** Square checkbox with a label, sized for this screen rather than Material's 48dp target. */
@Composable
fun DebugCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (checked) Dbg.Accent else Dbg.Surface)
                .border(1.dp, if (checked) Dbg.Accent else Dbg.BorderInput, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", fontSize = 9.sp, color = Dbg.Surface, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            text = label,
            modifier = Modifier.padding(start = 5.dp),
            fontSize = Dbg.Body,
            color = Dbg.TextSecondary,
        )
    }
}

/** Select control styled to match the design's `<select>` boxes. */
@Composable
fun DebugDropdown(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = Dbg.Body,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == value }?.second ?: value

    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Dbg.Surface)
                .border(1.dp, Dbg.BorderInput, RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f, fill = false),
                fontSize = fontSize,
                fontFamily = Dbg.Mono,
                color = Dbg.TextPrimary,
                maxLines = 1,
            )
            Text("▾", modifier = Modifier.padding(start = 6.dp), fontSize = fontSize, color = Dbg.TextMuted)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text, fontSize = fontSize, fontFamily = Dbg.Mono) },
                    onClick = { expanded = false; onSelect(key) },
                )
            }
        }
    }
}
