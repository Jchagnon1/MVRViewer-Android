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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
                    Text("MVR Viewer Pro", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { ProAccess.showPaywall = false },
                               modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Fermer",
                            tint = Color(0xFF9E9E9E), modifier = Modifier.size(18.dp))
                    }
                }
                ProAccess.paywallReason?.let { reason ->
                    Spacer(Modifier.height(8.dp))
                    Text("Pour $reason, il faut la version Pro.",
                        style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Lire un show reste entièrement gratuit — 3D, plan, patch, univers, câblage, export PDF, projets partagés. La version Pro débloque tout ce qui MODIFIE le projet.",
                    style = MaterialTheme.typography.bodyMedium, color = Color(0xFF555555)
                )
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                ) {
                    for (line in FEATURES) {
                        Row(verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(vertical = 3.dp)) {
                            Text("•", color = Accent.inkLight, modifier = Modifier.width(16.dp))
                            Text(line, style = MaterialTheme.typography.bodyMedium)
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
                    Text(if (price != null) "Débloquer la version Pro — $price"
                         else "Débloquer la version Pro")
                }
                OutlinedButton(
                    onClick = { ProAccess.restore() },
                    enabled = !ProAccess.isBusy,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) { Text("Restaurer un achat", color = Color(0xFF111111)) }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Achat unique, sans abonnement. Valable sur tous vos appareils avec le même compte Google.",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF777777)
                )
            }
        }
    }
}

private val FEATURES = listOf(
    "Modifier le patch : adresses, univers, modes, noms",
    "Affecter le câblage électrique et les lignes DMX",
    "Retourner et orienter les projecteurs",
    "Importer et placer des plans DXF, PDF, images",
    "Importer des objets 3D (glTF, OBJ, FBX, COLLADA…)",
    "Créer des types de projecteurs personnalisés",
    "Partager un projet et pousser vos modifications",
)
