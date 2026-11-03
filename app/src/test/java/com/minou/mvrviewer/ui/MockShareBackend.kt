package com.minou.mvrviewer.ui

import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.GdtfShareClient
import com.minou.mvrviewer.mvr.GdtfShareEntry
import java.io.File

/**
 * Doublure de GDTF Share (SPEC-ma-gdtfshare.md §8.1, « Mock GDTF Share ») : la liste
 * `share/getList_sample.json`, et par rid : 12345 → `share/gdtf_test_wash_3d.gdtf` ;
 * 12346 → `synthetic/gdtf_no_description.gdtf` ; 777 → panne réseau ; 901 →
 * `synthetic/gdtf_test_wash.gdtf`. Compte les appels (aucun vrai identifiant, aucun réseau).
 */
class MockShareBackend : MaShareBackend {
    private fun res(path: String) = File(javaClass.classLoader!!.getResource("ma/$path")!!.toURI())

    val entries: List<GdtfShareEntry> by lazy { GdtfShareClient.parseList(res("share/getList_sample.json").readText()) }
    fun entry(rid: Int) = entries.single { it.rid == rid }

    var listCalls = 0
    val downloads = mutableListOf<Int>()
    /** Réponses 401 à servir avant le vrai fichier (session expirée). */
    var expiredDownloads = 0
    var reloginOutcome = GdtfShareClient.LoginOutcome.OK
    var reloginCalls = 0

    override suspend fun list(): List<GdtfShareEntry> { listCalls++; return entries }

    override suspend fun download(rid: Int): ByteArray {
        downloads.add(rid)
        if (expiredDownloads > 0) { expiredDownloads--; throw GdtfShareClient.SessionExpiredException() }
        return when (rid) {
            12345 -> res("share/gdtf_test_wash_3d.gdtf").readBytes()
            12346 -> res("synthetic/gdtf_no_description.gdtf").readBytes()
            901 -> res("synthetic/gdtf_test_wash.gdtf").readBytes()
            777 -> throw GdtfShareClient.ShareException(R.string.gdtf_err_unreachable)
            else -> throw GdtfShareClient.ShareException(R.string.gdtf_err_download_failed_fmt, rid, 404)
        }
    }

    override suspend fun relogin(): GdtfShareClient.LoginOutcome { reloginCalls++; return reloginOutcome }
    override fun hasInternet() = true
    override fun hasValidatedNetwork() = true
}
