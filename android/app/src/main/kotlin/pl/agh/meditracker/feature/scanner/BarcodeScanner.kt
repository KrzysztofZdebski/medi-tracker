package pl.agh.meditracker.feature.scanner

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

// skaner z Google Play Services: własny ekran kamery, bez uprawnienia CAMERA w aplikacji.
// Anulowanie, błąd (np. brak Play Services) i nierozpoznany kod kończą się onFallback.
fun scanBarcode(context: Context, onResult: (Scan) -> Unit, onFallback: () -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A)
        .enableAutoZoom()
        .build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { code -> code.rawValue?.let(::parseScan)?.let(onResult) ?: onFallback() }
        .addOnCanceledListener { onFallback() }
        .addOnFailureListener { onFallback() }
}
