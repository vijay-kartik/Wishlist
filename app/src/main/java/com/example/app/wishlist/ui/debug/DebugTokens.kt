package com.example.app.wishlist.ui.debug

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * Colours and type sizes lifted verbatim from the Debug Inspector design.
 *
 * Deliberately not routed through `MaterialTheme`: this is an internal tool with a fixed,
 * light, high-density palette. Wiring it into the app's colour scheme would make it drift
 * whenever the product theme changes, and a debug tool that quietly restyles itself is
 * harder to read at a glance.
 */
object Dbg {
    val AppBg = Color(0xFFFAFAFB)
    val Surface = Color(0xFFFFFFFF)
    val Border = Color(0xFFE5E7EB)
    val BorderInput = Color(0xFFD5D8DE)
    val Divider = Color(0xFFF1F3F6)

    val TextPrimary = Color(0xFF17181C)
    val TextBody = Color(0xFF3B4048)
    val TextSecondary = Color(0xFF6B7280)
    val TextMuted = Color(0xFF9AA0AA)
    val TextFaint = Color(0xFFB9BEC7)

    val Accent = Color(0xFF1B6EF3)
    val AccentPressed = Color(0xFF0F55C8)
    val AccentTint = Color(0xFFF0F5FE)
    val RowHover = Color(0xFFF6F9FE)

    val WarnBg = Color(0xFFFFF3C4)
    val WarnBorder = Color(0xFFF0DE8A)
    val WarnText = Color(0xFF8A6D00)

    val ErrBg = Color(0xFFFDECEA)
    val ErrBorder = Color(0xFFF5C6C0)
    val ErrText = Color(0xFF8C2318)
    val ErrStrong = Color(0xFFC0392B)
    val ErrButtonBorder = Color(0xFFE4B5B0)

    val OkBg = Color(0xFFE1F4DE)
    val OkText = Color(0xFF2E7D32)

    val NeutralChipBg = Color(0xFFECEFF1)
    val NeutralChipText = Color(0xFF546E7A)
    val EdgeChipBg = Color(0xFFEEF1F5)

    val CodeBg = Color(0xFFF6F7F9)
    val CodeBorder = Color(0xFFEDEFF2)
    val StackBg = Color(0xFF17181C)
    val StackText = Color(0xFFD6DAE1)

    val BarTrack = Color(0xFFEEF1F5)

    val Mono = FontFamily.Monospace

    // The design's exact type scale. Fractional sizes are intentional — this screen packs
    // a lot of information into 412dp and the half-points matter for row density.
    val SectionLabel = 10.sp
    val Tiny = 9.5.sp
    val Micro = 9.sp
    val Small = 10.5.sp
    val Body = 11.sp
    val BodyPlus = 11.5.sp
    val Normal = 12.sp
    val Title = 16.sp
}

/**
 * Chip background / foreground per entity and node type, from the design's TYPECOLOR map.
 *
 * Covers both the NER entity types (from `assets/tags.txt`) and the graph node types,
 * because the same chip renders in both tabs and a type should not change colour when you
 * cross from the Pipeline tab to the Graph tab.
 */
object TypePalette {
    private val map: Map<String, Pair<Color, Color>> = mapOf(
        // NER entity types
        "PRODUCT" to (Color(0xFFE3ECFD) to Color(0xFF1B4FC0)),
        "CATEGORY" to (Color(0xFFDFF3EF) to Color(0xFF0B6E5E)),
        "COLOR" to (Color(0xFFF3E4FA) to Color(0xFF7A2E9E)),
        "SIZE" to (Color(0xFFFDEBD9) to Color(0xFFA15413)),
        "BUDGET" to (Color(0xFFE1F4DE) to Color(0xFF2E7D32)),
        "RECIPIENT" to (Color(0xFFFCE4EC) to Color(0xFFB0316B)),
        "OCCASION" to (Color(0xFFFFF3D6) to Color(0xFF9A6A00)),
        "TIME" to (Color(0xFFECEFF1) to Color(0xFF546E7A)),
        // Graph node types not already covered above
        "PERSON" to (Color(0xFFFCE4EC) to Color(0xFFB0316B)),
        "SOURCE" to (Color(0xFFECEFF1) to Color(0xFF546E7A)),
        "ASSERTION" to (Color(0xFFFFF3D6) to Color(0xFF9A6A00)),
        "NAME_VARIANT" to (Color(0xFFF3E4FA) to Color(0xFF7A2E9E)),
        "BRAND" to (Color(0xFFFDEBD9) to Color(0xFFA15413)),
        "PHONE_NUMBER" to (Color(0xFFECEFF1) to Color(0xFF546E7A)),
        "EMAIL_ADDRESS" to (Color(0xFFECEFF1) to Color(0xFF546E7A)),
        "UNKNOWN" to (Color(0xFFECEFF1) to Color(0xFF546E7A)),
    )

    fun background(type: String): Color = map[type]?.first ?: map.getValue("UNKNOWN").first
    fun foreground(type: String): Color = map[type]?.second ?: map.getValue("UNKNOWN").second
}
