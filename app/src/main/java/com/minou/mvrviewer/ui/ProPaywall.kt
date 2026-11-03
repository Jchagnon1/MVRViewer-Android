package com.minou.mvrviewer.ui

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.SceneModelLoader
import com.minou.mvrviewer.ui.theme.Accent
import com.minou.mvrviewer.ui.theme.accentButtonColors

/**
 * PAYWALL — parité `ProPaywallView` iOS. Posé UNE fois à la racine
 * (MainActivity) : il s'affiche par-dessus n'importe quel écran dès que
 * [ProAccess.showPaywall] passe à vrai, d'où qu'ait été appelée la garde.
 * Fond BLANC forcé comme les autres panneaux (les boutons Material3 sont
 * recolorés explicitement — cf. la leçon des boutons « morts » en thème
 * sombre, ModelPlacementPanel).
 */
@Composable
fun ProPaywallHost() {
    if (!ProAccess.showPaywall) return
    val ctx = LocalContext.current
    Dialog(onDismissRequest = { ProAccess.showPaywall = false }) {
        Surface(
            color = Color.White, contentColor = Color(0xFF111111),
            shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null,
                        tint = Accent.inkLight, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pro_section_title), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { ProAccess.showPaywall = false },
                               modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close),
                            tint = Color(0xFF9E9E9E), modifier = Modifier.size(18.dp))
                    }
                }
                ProAccess.paywallReason?.let { reason ->
                    Spacer(Modifier.height(8.dp))
                    val what = ProPaywallText.reasonRes(reason)?.let { stringResource(it) } ?: reason
                    Text(stringResource(R.string.pro_paywall_reason_fmt, what),
                        style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.pro_paywall_pitch),
                    style = MaterialTheme.typography.bodyMedium, color = Color(0xFF555555)
                )
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                ) {
                    for ((res, args) in ProPaywallText.FEATURES) {
                        Row(verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(vertical = 3.dp)) {
                            Text("•", color = Accent.inkLight, modifier = Modifier.width(16.dp))
                            Text(stringResource(res, *args.toTypedArray()), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                ProAccess.lastError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828))
                }
                Spacer(Modifier.height(12.dp))
                val price = ProAccess.price
                Button(
                    onClick = { (ctx as? Activity)?.let { ProAccess.purchase(it) } },
                    enabled = !ProAccess.isBusy,
                    colors = accentButtonColors(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Prix formaté par Google Play (devise et langue du compte), jamais codé en dur.
                    Text(if (price != null) stringResource(R.string.pro_unlock_price_fmt, price)
                         else stringResource(R.string.pro_unlock))
                }
                OutlinedButton(
                    onClick = { ProAccess.restore() },
                    enabled = !ProAccess.isBusy,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) { Text(stringResource(R.string.pro_restore), color = Color(0xFF111111)) }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.pro_one_time_purchase),
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF777777)
                )
            }
        }
    }
}

/**
 * REFUS DU RATTACHEMENT de l'achat au compte (SPEC-purchase §6.4), montré tout
 * de suite après un achat ou « Restaurer un achat » faits depuis le paywall —
 * qui est déjà FERMÉ quand le serveur répond (l'achat Play débloque l'appareil
 * d'abord). Posé UNE fois à la racine, à côté de [ProPaywallHost]. Rien
 * d'autre que le texte localisé du §6.4 ; le même message reste ensuite dans
 * le bloc Pro de l'écran compte. Pas d'alerte si ce bloc est déjà à l'écran
 * (cf. [ProAccess.setAccountLinkRefusal]).
 */
@Composable
fun ProLinkRefusalHost() {
    val message = ProAccess.linkRefusalAlert ?: return
    AlertDialog(
        onDismissRequest = { ProAccess.dismissLinkRefusalAlert() },
        title = { Text(stringResource(R.string.pro_section_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { ProAccess.dismissLinkRefusalAlert() }) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

/**
 * Textes de l'écran d'achat, en ressources (values/ et values-en/) comme le reste de
 * l'app — testés (`ProPaywallTextTest`).
 */
internal object ProPaywallText {
    /** Lignes « • » : ressource et arguments. */
    val FEATURES: List<Pair<Int, List<Any>>> = listOf(
        R.string.pro_feature_patch to emptyList(),
        R.string.pro_feature_cabling to emptyList(),
        R.string.pro_feature_orient to emptyList(),
        R.string.pro_feature_plans to emptyList(),
        // Formats RÉELLEMENT lus par l'app Android (moins qu'iOS, qui lit aussi le .dae) :
        // la même liste que l'accueil et le choix d'import (guide C9).
        R.string.pro_feature_models_fmt to listOf(SceneModelLoader.SUPPORTED_SUMMARY),
        R.string.pro_feature_custom_types to emptyList(),
        R.string.pro_feature_share to emptyList(),
    )

    /**
     * Raison passée à [ProAccess.gate] / [ProAccess.gatedSetter] (phrase française au
     * nominatif, la clé, comme les `String(localized:)` d'iOS) → son texte dans la
     * langue de l'app. Toute raison écrite dans le code doit être ici (test) ; une
     * raison inconnue s'afficherait telle quelle.
     */
    private val REASONS: Map<String, Int> = mapOf(
        "modifier le patch" to R.string.pro_reason_edit_patch,
        "importer un objet 3D" to R.string.pro_reason_import_model,
        "régler les objets 3D importés" to R.string.pro_reason_adjust_models,
        "modifier le câblage" to R.string.pro_reason_edit_cabling,
        "affecter le câblage" to R.string.pro_reason_assign_cabling,
        "créer un type personnalisé" to R.string.pro_reason_create_custom_type,
        "créer un projecteur personnalisé" to R.string.pro_reason_create_custom_fixture,
        "modifier un projecteur personnalisé" to R.string.pro_reason_edit_custom_fixture,
        "utiliser un projecteur personnalisé" to R.string.pro_reason_use_custom_fixture,
        "renseigner une consommation" to R.string.pro_reason_enter_power,
        "partager un projet" to R.string.pro_reason_share_project,
        "débloquer l'édition" to R.string.pro_reason_unlock_editing,
        GeoPanelContent.PRO_REASON to R.string.pro_reason_calibrate_gps,
        "annuler une modification" to R.string.pro_reason_undo,
        RefPlanPanelRules.IMPORT_PRO_REASON to R.string.pro_reason_import_plan,
        RefPlanPanelRules.PRO_REASON to R.string.pro_reason_adjust_plan,
        RefPlanPanelRules.LAYERS_PRO_REASON to R.string.pro_reason_plan_layers,
    )

    fun reasonRes(reason: String): Int? = REASONS[reason]
}
