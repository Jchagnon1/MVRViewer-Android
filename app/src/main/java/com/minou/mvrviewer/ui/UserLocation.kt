package com.minou.mvrviewer.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Flux GPS de la vue plan : position courante (observable), refus de la
 * permission, et fixes BRUTS pour l'assistant 3 points (iOS `rawFixHandler` /
 * `lastRawFix` de GeoLocationService) — tous les `onLocationChanged` des
 * fournisseurs GPS et NETWORK, même identiques.
 */
class UserLocationFeed {
    val location = mutableStateOf<Location?>(null)
    /**
     * Localisation REFUSÉE par le système (iOS `deniedByUser`) : pas seulement
     * pendant cette visite — un refus d'une session précédente, ou une
     * autorisation retirée dans les Réglages, compte aussi ([LocationPermissionRule]).
     */
    var denied by mutableStateOf(false)
        internal set
    /** Dernier fix brut connu (filet « appareil immobile » de l'assistant). Non observable. */
    @Volatile var lastRawFix: Location? = null
        internal set
    /** Rappel pour CHAQUE fix brut (thread principal). Non observable. */
    @Volatile var rawFixHandler: ((Location) -> Unit)? = null

    internal fun deliver(l: Location) {
        lastRawFix = l
        rawFixHandler?.invoke(l)
        location.value = l
    }
}

/** Horloge de l'assistant (s) : même base que [Location.getElapsedRealtimeNanos]. */
fun locationClockSeconds(): Double = SystemClock.elapsedRealtimeNanos() / 1e9

/**
 * Fix brut pour l'assistant : clé d'unicité = `Location.time`, horodatage dans la
 * base de [locationClockSeconds], précision seulement si `hasAccuracy()`.
 */
fun Location.toRawFix(): RawFix = RawFix(
    latitude = latitude, longitude = longitude,
    accuracy = if (hasAccuracy()) accuracy.toDouble() else 0.0,
    key = time.toDouble(),
    time = elapsedRealtimeNanos / 1e9
)

/**
 * Position GPS de l'utilisateur en direct pendant que `active` est vrai
 * (demande la permission au besoin, arrêt sinon — batterie). Équivalent
 * simplifié de GeoLocationService (iOS), sans dépendance Play Services.
 */
@Composable
fun rememberUserLocationFeed(active: Boolean): UserLocationFeed {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasLocationPermission(context)) }
    // État SYSTÈME dès la création : la ligne d'état du menu le dit sans attendre
    // que « Ma position » soit allumée.
    val feed = remember { UserLocationFeed().also { it.denied = locationDeniedBySystem(context, granted) } }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        LocationPermissionStore.markAsked(context)
        granted = ok
        feed.denied = locationDeniedBySystem(context, ok)
    }

    // Retour au premier plan (Réglages, autre appli) : l'autorisation a pu
    // changer pendant l'absence — accordée, retirée ou remise à zéro.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val g = hasLocationPermission(context)
                granted = g
                feed.denied = locationDeniedBySystem(context, g)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(active, granted) {
        if (active && !granted) launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    DisposableEffect(active, granted) {
        if (!active || !granted) return@DisposableEffect onDispose { }
        feed.denied = false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val listener = LocationListener { feed.deliver(it) }
        try {
            lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                feed.location.value = it; feed.lastRawFix = it
            }
            lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let {
                if (feed.location.value == null) { feed.location.value = it; feed.lastRawFix = it }
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
        // Chaque fournisseur à part : un fournisseur absent de l'appareil
        // (IllegalArgumentException) ne doit pas priver de l'autre.
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                lm?.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        onDispose { try { lm?.removeUpdates(listener) } catch (_: SecurityException) {} }
    }

    return feed
}

/** Position seule (vue 3D et usages historiques). */
@Composable
fun rememberUserLocation(active: Boolean): State<Location?> = rememberUserLocationFeed(active).location

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Refus de la localisation déduit de l'ÉTAT SYSTÈME (règle pure, testée en JVM).
 * Android ne dit pas « refusé » comme iOS (`.denied`/`.restricted`) : une
 * permission non accordée peut aussi n'avoir jamais été demandée. On la tient
 * pour refusée si elle a déjà été demandée (drapeau persistant), ou si le
 * système propose une explication — ce qu'il ne fait qu'après un refus.
 */
object LocationPermissionRule {
    fun denied(granted: Boolean, askedBefore: Boolean, rationale: Boolean): Boolean =
        !granted && (askedBefore || rationale)
}

/** « La permission de localisation a déjà été demandée » — persistant, par appareil. */
object LocationPermissionStore {
    private const val PREFS = "location_permission"
    private const val KEY_ASKED = "asked"
    fun asked(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)
    fun markAsked(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }
}

/** [LocationPermissionRule] appliquée à l'appareil. */
fun locationDeniedBySystem(context: Context, granted: Boolean): Boolean {
    if (granted) return false
    val rationale = context.findActivity()?.let {
        ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION)
    } ?: false
    return LocationPermissionRule.denied(granted, LocationPermissionStore.asked(context), rationale)
}

private fun Context.findActivity(): android.app.Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}
