package com.explo.capstone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// --- DESIGN SYSTEM: COLORS ---
object AstraTheme {
    val SurfaceDim = Color(0xFF0E0E0E)
    val Surface = Color(0xFF0E0E0E)
    val SurfaceContainerLowest = Color(0xFF000000)
    val SurfaceContainerLow = Color(0xFF131313)
    val SurfaceContainerHigh = Color(0xFF1F2020)
    val SurfaceContainerHighest = Color(0xFF252626)
    
    val Primary = Color(0xFFB6C8E1)
    val OnPrimary = Color(0xFF314156)
    val PrimaryContainer = Color(0xFF37485D)
    
    val Tertiary = Color(0xFFACFFA4) // Secure Green
    val Secondary = Color(0xFFFEB300) // Warning Amber
    val Error = Color(0xFFEE7D77) // Breach Red
    
    val OnSurface = Color(0xFFE7E5E4)
    val OutlineVariant = Color(0xFF484848)

    // --- DESIGN SYSTEM: TYPOGRAPHY ---
    val Typography = Typography(
        // Space Grotesk equivalent (Headers)
        headlineMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            letterSpacing = 0.sp,
            color = OnSurface
        ),
        headlineSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Black,
            fontSize = 20.sp,
            color = OnSurface,
            letterSpacing = 1.sp
        ),
        // Inter / Monospace (Body & Data values)
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            color = OnSurface,
            letterSpacing = 0.5.sp
        ),
        labelMedium = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = Primary,
            letterSpacing = 1.sp
        ),
        labelSmall = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            color = OnSurface,
            letterSpacing = 1.5.sp
        )
    )
}

// --- COMPOSE WRAPPER ---
@Composable
fun AstraSecureTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = AstraTheme.SurfaceDim,
            surface = AstraTheme.Surface,
            primary = AstraTheme.Primary,
            onPrimary = AstraTheme.OnPrimary,
            tertiary = AstraTheme.Tertiary,
            error = AstraTheme.Error,
            onSurface = AstraTheme.OnSurface
        ),
        typography = AstraTheme.Typography,
        shapes = Shapes(
            small = RoundedCornerShape(0.dp),
            medium = RoundedCornerShape(0.dp),
            large = RoundedCornerShape(0.dp)
        )
    ) {
        Surface(color = AstraTheme.SurfaceDim, content = content)
    }
}

// --- COMPONENTS ---

@Composable
fun AstraButtonPrimary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = AstraTheme.Primary,
            contentColor = AstraTheme.OnPrimary
        )
    ) {
        Text(text.uppercase(), style = AstraTheme.Typography.labelMedium.copy(color = AstraTheme.OnPrimary, fontWeight = FontWeight.Bold))
    }
}

@Composable
fun AstraButtonTertiary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        shape = RectangleShape,
        colors = ButtonDefaults.textButtonColors(contentColor = AstraTheme.Primary)
    ) {
        Text("> ${text.uppercase()}", style = AstraTheme.Typography.labelMedium)
    }
}

@Composable
fun SignalStatus(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(modifier = Modifier
            .size(8.dp)
            .background(color, shape = androidx.compose.foundation.shape.CircleShape))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label.uppercase(), style = AstraTheme.Typography.labelMedium.copy(color = color))
    }
}

@Composable
fun AstraInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text("> $placeholder", style = AstraTheme.Typography.labelMedium.copy(color = AstraTheme.OutlineVariant)) },
        modifier = modifier.fillMaxWidth(),
        textStyle = AstraTheme.Typography.labelMedium.copy(color = AstraTheme.OnSurface),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = AstraTheme.SurfaceContainerLowest,
            unfocusedContainerColor = AstraTheme.SurfaceContainerLowest,
            focusedIndicatorColor = AstraTheme.Primary,
            unfocusedIndicatorColor = AstraTheme.OutlineVariant,
            focusedTextColor = AstraTheme.OnSurface,
            unfocusedTextColor = AstraTheme.OnSurface,
            cursorColor = AstraTheme.Primary
        ),
        shape = RectangleShape
    )
}

@Composable
fun MissionCard(
    missionName: String,
    missionId: String,
    statusText: String,
    statusColor: Color,
    timeAgo: String,
    channels: Int,
    classification: String,
    actionText: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AstraTheme.SurfaceContainerLow)
            .border(1.dp, AstraTheme.SurfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(missionName.uppercase(), style = AstraTheme.Typography.headlineSmall)
            Text("ID: $missionId", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.OutlineVariant))
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.border(1.dp, AstraTheme.OutlineVariant).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(classification.uppercase(), style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Primary))
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            SignalStatus(label = statusText, color = statusColor)
            Text("LAST: $timeAgo", style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.OnSurface))
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        Text("$channels CHANNELS", style = AstraTheme.Typography.labelMedium.copy(color = AstraTheme.OnSurface))
        
        Spacer(modifier = Modifier.height(16.dp))
        AstraButtonPrimary(
            text = actionText,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
