package com.minou.mvrviewer.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

/**
 * Position GPS de l'utilisateur en direct pendant que `active` est vrai
 * (demande la permission au besoin, arrêt sinon — batterie). Équivalent
 * simplifié de GeoLocationService (iOS), sans dépendance Play Services.
 */
@Composable
fun rememberUserLocation(active: Boolean): State<Location?> {
    val context = LocalContext.current
    val location = remember { mutableStateOf<Location?>(null) }
    var granted by remember { mutableStateOf(hasLocationPermission(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(active, granted) {
        if (active && !granted) launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    DisposableEffect(active, granted) {
        if (!active || !granted) return@DisposableEffect onDispose { }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val listener = LocationListener { location.value = it }
        try {
            lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { location.value = it }
            lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let {
                if (location.value == null) location.value = it
            }
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            lm?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (_: SecurityException) {
        }
        onDispose { try { lm?.removeUpdates(listener) } catch (_: SecurityException) {} }
    }

    return location
}

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
