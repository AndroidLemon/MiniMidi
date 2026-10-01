package com.example.minimidi

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Direction C, TR-808 lineage. Every value here comes from the spec; don't add to it without a hardware reason. */
object Rhythm {
    val Body = Color(0xFF111111)
    val Panel = Color(0xFF1A1A1A)
    val Ink = Color(0xFFE9E4D8)
    val Led = Color(0xFFE8452C)
    val Groups = listOf(Color(0xFFC8321F), Color(0xFFE2701F), Color(0xFFE9C22C), Color(0xFFE9E4D8))

    val Label = FontFamily(Font(R.font.archivo_narrow, FontWeight.SemiBold))
    val Wordmark = FontFamily(Font(R.font.archivo_italic, FontWeight.ExtraBold, FontStyle.Italic))

    fun label(color: Color) = TextStyle(fontFamily = Label, fontSize = 11.sp, letterSpacing = 0.1.em, color = color)
}
