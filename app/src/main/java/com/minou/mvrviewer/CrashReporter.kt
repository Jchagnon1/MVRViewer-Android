package com.minou.mvrviewer

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Journal de diagnostic sur fichier, pour analyser les plantages ET les gels
 * (ANR) qui n'arrivent que sur l'appareil du testeur (Samsung A55…) et qu'on ne
 * reproduit pas au bureau. Écrit dans le dossier privé externe de l'appli
 * (`Android/data/<pkg>/files/mvrviewer_diag.log`) — partageable depuis l'accueil
 * (bouton « Envoyer le rapport ») et récupérable via `adb pull`.
 *
 * Capture :
 *  - les EXCEPTIONS non rattrapées (vrais plantages, y compris OutOfMemory) via
 *    un UncaughtExceptionHandler qui chaîne l'ancien (l'appli meurt normalement) ;
 *  - les ANR (« l'appli ne répond pas ») via un chien de garde : un thread poste
 *    un jeton au thread principal ; s'il n'est pas traité en 6 s, on écrit la PILE
 *    du thread principal (→ on voit EXACTEMENT où il est bloqué).
 */
object CrashReporter {
    private const val MAX_BYTES = 512 * 1024L
    @Volatile private var file: File? = null
    private val lock = Any()

    /** Emplacement du journal (stable, réutilisé par l'écran d'accueil). */
    fun logFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "mvrviewer_diag.log")

    fun install(ctx: Context) {
        val f = logFile(ctx.applicationContext)
        file = f
        // Repart de zéro si le journal a trop grossi (on ne garde que l'utile).
        runCatching { if (f.length() > MAX_BYTES) f.writeText("") }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write("PLANTAGE (exception non rattrapée) — thread « ${t.name} »", stack(e)) }
            prev?.uncaughtException(t, e) // laisse le système afficher/terminer normalement
        }
        startAnrWatchdog()
        runCatching { write("démarrage de l'appli", null) }
    }

    /** Note libre (ex. « ouverture vue plan ») — aide à situer un plantage. */
    fun note(msg: String) { runCatching { write("· $msg", null) } }

    // ---- interne ----

    private fun banner(): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "\n===== $ts | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ====="
    }

    private fun write(tag: String, detail: String?) {
        val f = file ?: return
        synchronized(lock) {
            runCatching {
                f.appendText(banner() + "\n" + tag + "\n" + (detail?.let { it + "\n" } ?: ""))
            }
        }
    }

    private fun stack(e: Throwable): String {
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw)); return sw.toString()
    }

    private fun startAnrWatchdog() {
        val main = Handler(Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread
        val posted = AtomicLong(0)
        val confirmed = AtomicLong(0)
        val t = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val n = posted.incrementAndGet()
                main.post { confirmed.set(n) }
                try { Thread.sleep(6000) } catch (_: InterruptedException) { return@Thread }
                if (confirmed.get() < n) {
                    // Le thread principal n'a pas traité le jeton en 6 s → gel.
                    val trace = runCatching {
                        mainThread.stackTrace.joinToString("\n") { "    at $it" }
                    }.getOrDefault("(pile indisponible)")
                    write("GEL / ANR : thread principal bloqué > 6 s", "Pile du thread principal :\n$trace")
                    // Attend le dégel avant de ré-armer (évite d'inonder le journal).
                    while (confirmed.get() < n && !Thread.currentThread().isInterrupted) {
                        try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
                    }
                }
            }
        }
        t.name = "mvr-anr-watchdog"; t.isDaemon = true; t.start()
    }
}
