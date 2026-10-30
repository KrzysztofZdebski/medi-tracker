package pl.agh.meditracker.feature.scanner

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun CameraPreview(torch: Boolean, onCode: (String) -> Unit, onError: () -> Unit, onFlashAvailable: (Boolean) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val preview = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val latestCode by rememberUpdatedState(onCode)
    val latestError by rememberUpdatedState(onError)
    val latestFlash by rememberUpdatedState(onFlashAvailable)
    AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }
    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_ITF, Barcode.FORMAT_CODE_128).build())
        val delivered = AtomicBoolean(false)
        val alive = AtomicBoolean(true)
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val cameraPreview = Preview.Builder().build().apply { setSurfaceProvider(preview.surfaceProvider) }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        analysis.setAnalyzer(executor) { frame ->
            val image = frame.image
            if (image == null || delivered.get() || !alive.get()) frame.close()
            else scanner.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
                .addOnSuccessListener(main) { codes ->
                    val value = codes.mapNotNull { it.rawValue }.firstOrNull { it.matches(Regex("[0-9]{8,14}")) }
                    if (alive.get() && value != null && delivered.compareAndSet(false, true)) latestCode(value)
                }
                .addOnFailureListener(main) { if (alive.get()) latestError() }
                .addOnCompleteListener { frame.close() }
        }
        future.addListener({
            if (alive.get()) try {
                provider = future.get()
                camera = provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis)
                latestFlash(camera?.cameraInfo?.hasFlashUnit() == true)
            } catch (_: Exception) { latestError() }
        }, main)
        onDispose {
            alive.set(false)
            analysis.clearAnalyzer()
            camera?.cameraControl?.enableTorch(false)
            provider?.unbind(cameraPreview, analysis)
            executor.shutdown()
            scanner.close()
        }
    }
}

@Composable
fun ScannerFrame(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val length = 32.dp.toPx()
        val stroke = 4.dp.toPx()
        val inset = stroke / 2
        val right = size.width - inset
        val bottom = size.height - inset
        listOf(Offset(inset, inset) to Offset(1f, 1f), Offset(right, inset) to Offset(-1f, 1f), Offset(inset, bottom) to Offset(1f, -1f), Offset(right, bottom) to Offset(-1f, -1f)).forEach { (point, direction) ->
            drawLine(Color.White, point, point + Offset(direction.x * length, 0f), stroke)
            drawLine(Color.White, point, point + Offset(0f, direction.y * length), stroke)
        }
    }
}
