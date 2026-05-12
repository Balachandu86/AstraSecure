package com.explo.capstone.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.google.zxing.BarcodeFormat as ZxBarcodeFormat

// ─── QR display ───────────────────────────────────────────────────────────────

/**
 * Renders [content] as a QR code. [sizeDp] is both width and height; the QR
 * matrix is scaled to fit. Returns a placeholder rectangle on encoding failure.
 *
 * Colours come from [AstraTheme] so the QR matches the rest of the UI.
 */
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    sizeDp: Int = 220,
) {
    val sizePx = with(androidx.compose.ui.platform.LocalDensity.current) { sizeDp.dp.roundToPx() }
    val bitmap = remember(content, sizePx) { encodeQrToBitmap(content, sizePx) }
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap.asImageBitmap()),
            contentDescription = "Invite QR code",
            modifier = modifier.size(sizeDp.dp),
        )
    } else {
        Box(
            modifier
                .size(sizeDp.dp)
                .background(AstraTheme.SurfaceContainerHigh)
                .border(1.dp, AstraTheme.OutlineVariant, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "QR ENCODE FAILED",
                style = AstraTheme.Typography.labelSmall.copy(color = AstraTheme.Error),
            )
        }
    }
}

private fun encodeQrToBitmap(content: String, sizePx: Int): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val rowOffset = y * w
        for (x in 0 until w) {
            pixels[rowOffset + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}.getOrNull()

// ─── In-app scanner ───────────────────────────────────────────────────────────

/**
 * Camera-backed QR scanner. Asks for the CAMERA permission on first use; calls
 * [onScanned] exactly once when a code is decoded, then stops the preview.
 *
 * Caller is responsible for navigating away after [onScanned]. Tapping
 * [onCancel] aborts without firing [onScanned].
 */
@Composable
fun InviteQrScanner(
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(modifier.fillMaxSize().background(AstraTheme.Surface)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when {
                hasPermission -> CameraPreview(onScanned = onScanned)
                permissionDenied -> Text(
                    "> CAMERA DENIED — GRANT IN SYSTEM SETTINGS",
                    style = AstraTheme.Typography.labelMedium.copy(color = AstraTheme.Error),
                )
                else -> Text(
                    "> REQUESTING CAMERA…",
                    style = AstraTheme.Typography.labelMedium,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(AstraTheme.SurfaceContainerLow)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            OutlinedButton(
                onClick = onCancel,
                shape = RectangleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, AstraTheme.Primary.copy(0.4f)),
            ) {
                Text(
                    "> CANCEL",
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = AstraTheme.Primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
    }
}

@Composable
private fun CameraPreview(onScanned: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val barcodeView = remember { mutableStateOf<DecoratedBarcodeView?>(null) }
    var fired by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            DecoratedBarcodeView(ctx).apply {
                decoderFactory = DefaultDecoderFactory(listOf(ZxBarcodeFormat.QR_CODE))
                setStatusText("")
                decodeContinuous(object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult?) {
                        val text = result?.text ?: return
                        if (fired) return
                        fired = true
                        pause()
                        onScanned(text)
                    }
                })
                barcodeView.value = this
                resume()
            }
        },
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!fired) barcodeView.value?.resume()
                Lifecycle.Event.ON_PAUSE  -> barcodeView.value?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            barcodeView.value?.pause()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
