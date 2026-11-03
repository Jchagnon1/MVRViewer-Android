package com.minou.mvrviewer.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
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
import com.minou.mvrviewer.R

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
    /**
     * Seule la position APPROXIMATIVE est accordée (Android 12+ : « Approximative »
     * choisie dans le dialogue, ou dans les Paramètres ; guide C8). La position marche
     * quand même, en dégradé : le panneau Position GPS, l'aide de « Je suis ici » et un
     * message au retour du dialogue disent que la précision est limitée.
     */
    var approximate by mutableStateOf(false)
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
    val feed = remember {
        UserLocationFeed().also {
            it.denied = locationDeniedBySystem(context, granted)
            // Position approximative seule : lue elle aussi dès la création (guide C8).
            it.approximate = locationAccess(context).isApproximate
        }
    }

    // FINE et COARSE demandées ENSEMBLE (guide C8) : Android 12+ ignore une demande
    // de FINE seule et laisse l'utilisateur choisir « Approximative ».
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        LocationPermissionStore.markAsked(context)
        // État RÉEL après le dialogue, et non la réponse case par case : seule
        // l'approximative a pu être accordée.
        val access = locationAccess(context)
        granted = access.granted
        feed.denied = locationDeniedBySystem(context, access.granted)
        feed.approximate = access.isApproximate
        LocationAccess.grantMessage(access)?.let {
            Toast.makeText(context, context.getString(it), Toast.LENGTH_LONG).show()
        }
    }

    // Retour au premier plan (Réglages, autre appli) : l'autorisation a pu
    // changer pendant l'absence — accordée, retirée ou remise à zéro.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val access = locationAccess(context)
                val g = access.granted
                granted = g
                feed.denied = locationDeniedBySystem(context, g)
                feed.approximate = access.isApproximate
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(active, granted) {
        if (active && !granted) launcher.launch(LocationAccess.REQUESTED)
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
        // (IllegalArgumentException) ne doit pas priver de l'autre. Avec la position
        // APPROXIMATIVE seule, Android 12+ sert les deux fournisseurs en positions
        // floutées ; avant Android 12, le GPS refuse (SecurityException) et le réseau
        // suffit.
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

/** Localisation accordée, précise OU approximative (guide C8). */
fun hasLocationPermission(context: Context): Boolean = locationAccess(context).granted

/**
 * Niveau de localisation accordé (guide C8) — règle PURE, testée en JVM
 * (`LocationAccessTest`). Android 12+ propose « Précise » ou « Approximative » :
 * l'app demande les deux permissions ENSEMBLE et marche, en dégradé, avec
 * l'approximative seule ; un refus garde le message actuel (« Localisation refusée »).
 */
enum class LocationAccess {
    PRECISE, APPROXIMATE, NONE;

    val granted: Boolean get() = this != NONE
    val isApproximate: Boolean get() = this == APPROXIMATE

    companion object {
        /** Les deux permissions, demandées ensemble (manifeste : les deux sont déclarées). */
        val REQUESTED: Array<String> = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        )

        fun of(fine: Boolean, coarse: Boolean): LocationAccess = when {
            fine -> PRECISE
            coarse -> APPROXIMATE
            else -> NONE
        }

        /**
         * Message montré au retour du dialogue système : seulement si la position
         * accordée n'est qu'APPROXIMATIVE (précise : rien à dire ; refus : la ligne
         * d'état « Localisation refusée » existante le dit déjà).
         */
        fun grantMessage(access: LocationAccess): Int? =
            if (access == APPROXIMATE) R.string.location_approximate_toast else null
    }
}

/** [LocationAccess] de l'appareil, lue dans l'état système. */
fun locationAccess(context: Context): LocationAccess = LocationAccess.of(
    fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED,
    coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
)

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
    val rationale = context.findActivity()?.let { a ->
        LocationAccess.REQUESTED.any { ActivityCompat.shouldShowRequestPermissionRationale(a, it) }
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
