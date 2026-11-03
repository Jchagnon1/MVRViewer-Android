package com.minou.mvrviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.R
import com.minou.mvrviewer.mvr.GdtfCredentialStore
import com.minou.mvrviewer.mvr.GdtfShareClient
import com.minou.mvrviewer.sync.SyncViewModel
import com.minou.mvrviewer.sync.syncMessage
import kotlinx.coroutines.launch

/**
 * « COMPTE & RÉGLAGES » — écran plein (SPEC-menus-android §3.11, iOS §10), qui
 * remplace l'ancien dialogue au titre variable. Titre FIXE ; sections, dans
 * l'ordre ([AccountSettingsSections]) :
 *
 * 1. « Compte » (si la synchro est branchée) : formulaire de connexion /
 *    création, ou nom, e-mail, serveur, « Se déconnecter » ; isolé en rouge,
 *    « Supprimer mon compte » (droit, jamais gardé Pro) ;
 * 2. « MVR Viewer Pro » : état, déblocage, restauration, refus de rattachement ;
 * 3. « Services » : ligne « GDTF Share » (Connecté / Non connecté). Non connecté :
 *    toucher la ligne ouvre la connexion (sous-volet) ; connecté : bouton « Se
 *    déconnecter » au bout de la ligne — toucher la ligne ne déconnecte JAMAIS ;
 * 4. « À propos » (TOUJOURS présente, guide de mise en vente C3 et C11) : pages
 *    légales du site (navigateur), « Mentions et licences » (sous-volet, hors
 *    ligne), version.
 *
 * Sous-volets (← et retour système ramènent ICI) : connexion GDTF Share, « Mot
 * de passe oublié » (lien du formulaire de connexion, guide C6) et « Mentions
 * et licences ».
 *
 * Ouvert depuis un projet (destination) ou depuis l'accueil (hébergé par `App`).
 * À l'ouverture, reconnexion silencieuse GDTF Share habituelle (aucune requête
 * sans identifiants gardés). Une connexion faite ici relance d'elle-même la
 * résolution des modèles dans le projet ouvert (`loggedInFlow` observé par
 * SceneScreen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    sync: SyncViewModel?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Ouvert par l'outil ancré `gdtfShare` (SPEC-menus-android §4.6, §8 D24) :
     * l'écran défile jusqu'à la section « Services » (connexion GDTF Share).
     */
    focusServices: Boolean = false,
    onFocusConsumed: () -> Unit = {}
) {
    val ctx = LocalContext.current
    var gdtfLogin by remember { mutableStateOf(false) }
    /** Adresse préremplie du sous-volet « Mot de passe oublié » ; null = fermé. */
    var passwordResetEmail by rememberSaveable { mutableStateOf<String?>(null) }
    var licensesOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!GdtfShareClient.loggedIn && GdtfCredentialStore.username(ctx) != null) {
            runCatching { GdtfShareClient.ensureLoggedIn(ctx) }
        }
    }
    // Sous-volet de connexion GDTF Share : son ← et le retour système ramènent ICI.
    if (gdtfLogin) {
        BackHandler { gdtfLogin = false }
        GdtfShareLoginPane(
            title = stringResource(R.string.gdtf_share_title),
            onBack = { gdtfLogin = false },
            onLoggedIn = { gdtfLogin = false }
        )
        return
    }
    if (licensesOpen) {
        BackHandler { licensesOpen = false }
        LicensesScreen(onBack = { licensesOpen = false }, modifier = modifier)
        return
    }
    val resetEmail = passwordResetEmail
    if (resetEmail != null && sync != null) {
        BackHandler { passwordResetEmail = null }
        PasswordResetPane(sync = sync, initialEmail = resetEmail, onBack = { passwordResetEmail = null },
            modifier = modifier)
        return
    }
    Column(modifier = modifier.fillMaxSize().testTag("accountSettings")) {
        TopAppBar(
            title = { Text(stringResource(R.string.account_settings_title), style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            }
        )
        val scroll = rememberScrollState()
        // Haut de la section « Services » dans la colonne défilante (px), mesuré.
        var servicesTop by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(focusServices, servicesTop) {
            val y = servicesTop ?: return@LaunchedEffect
            if (!focusServices) return@LaunchedEffect
            scroll.animateScrollTo(y)
            onFocusConsumed()
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AccountSettingsSections.of(sync != null).forEach { section ->
                when (section) {
                    AccountSettingsSection.ACCOUNT -> sync?.let {
                        AccountSection(it, onForgotPassword = { email -> passwordResetEmail = email })
                    }
                    AccountSettingsSection.PRO -> ProSection()
                    AccountSettingsSection.SERVICES -> Column(
                        Modifier.onGloballyPositioned { servicesTop = it.positionInParent().y.toInt() }
                    ) { ServicesSection(onSignIn = { gdtfLogin = true }) }
                    AccountSettingsSection.ABOUT -> AboutSection(onOpenLicenses = { licensesOpen = true })
                }
            }
            // Dernière ligne (« À propos ») hors de la barre de gestes.
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

// MARK: - Compte (connexion / inscription / déconnexion / suppression)

@Composable
private fun AccountSection(sync: SyncViewModel, onForgotPassword: (String) -> Unit) {
    val auth by sync.auth.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(auth.accountOrNull?.email ?: "") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var isSignUp by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    SectionHeader(stringResource(R.string.account_section_account))
    val account = auth.accountOrNull
    if (account != null) {
        Text(account.displayName, style = MaterialTheme.typography.titleMedium)
        Text(account.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.sync_backend_fmt, sync.backendLabel), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { sync.signOut(); password = ""; error = null }) {
            Text(stringResource(R.string.sync_sign_out))
        }
        // Suppression de compte (politique Google Play) : isolée, en rouge, JAMAIS
        // derrière une garde Pro — c'est un droit.
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        TextButton(
            onClick = { showDelete = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) { Text(stringResource(R.string.sync_delete_account)) }
    } else {
        OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.sync_email)) },
            singleLine = true, modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp))
        OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.sync_password)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp))
        if (isSignUp) {
            OutlinedTextField(displayName, { displayName = it },
                label = { Text(stringResource(R.string.sync_display_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            if (isSignUp) sync.signUp(email.trim(), password, displayName.trim())
                            else sync.signIn(email.trim(), password)
                            password = ""
                        } catch (e: Exception) { error = e.syncMessage(ctx) } finally { busy = false }
                    }
                }
            ) { Text(stringResource(if (isSignUp) R.string.common_create else R.string.home_sign_in)) }
            TextButton(onClick = { isSignUp = !isSignUp; error = null }) {
                Text(stringResource(if (isSignUp) R.string.sync_have_account else R.string.sync_create_account))
            }
        }
        if (isSignUp) {
            // Information au moment de la collecte (RGPD) : conditions et
            // politique de confidentialité, liens dans la phrase (guide C3).
            SignUpConsentText(Modifier.widthIn(max = 520.dp))
        } else {
            // Mot de passe oublié (guide C6) : gratuit, à la connexion seulement.
            TextButton(
                onClick = { onForgotPassword(email.trim()) },
                modifier = Modifier.testTag("account.forgotPassword")
            ) { Text(stringResource(R.string.password_forgot), style = MaterialTheme.typography.bodySmall) }
        }
        Text(stringResource(R.string.sync_backend_fmt, sync.backendLabel), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    if (showDelete) DeleteAccountDialog(
        sync = sync,
        onDismiss = { showDelete = false },
        onDeleted = { showDelete = false }
    )
}

// MARK: - Version Pro (état, déblocage, restauration) — au même endroit que le
// compte : c'est là qu'on cherche « ai-je payé ? ».

@Composable
private fun ProSection() {
    // Bloc à l'écran : un refus de rattachement s'affiche ICI (pas d'alerte en
    // double par-dessus, cf. ProAccess.setAccountLinkRefusal).
    DisposableEffect(Unit) {
        ProAccess.accountSectionShown(true)
        onDispose { ProAccess.accountSectionShown(false) }
    }
    SectionHeader(stringResource(R.string.pro_section_title))
    if (ProAccess.isPro) {
        Text(stringResource(R.string.pro_active), style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(stringResource(R.string.pro_pitch), style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { ProAccess.gate("débloquer l'édition") {} },
            colors = com.minou.mvrviewer.ui.theme.accentButtonColors()
        ) { Text(stringResource(R.string.pro_unlock)) }
    }
    // Refus DÉFINITIF du rattachement de l'achat au compte, après un achat ou
    // « Restaurer un achat » seulement (SPEC-purchase §6.4) ; rien sinon.
    ProAccess.accountLinkMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    TextButton(onClick = { ProAccess.restore() }) { Text(stringResource(R.string.pro_restore)) }
}

// MARK: - Services (GDTF Share)

@Composable
private fun ServicesSection(onSignIn: () -> Unit) {
    val ctx = LocalContext.current
    val signedIn by GdtfShareClient.loggedInFlow.collectAsState()
    SectionHeader(stringResource(R.string.account_section_services))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().testTag("services.gdtfShare")
            // Toucher la ligne CONNECTE (non connecté) ; il ne déconnecte jamais.
            .then(if (signedIn) Modifier else Modifier.clickable(onClick = onSignIn))
            .padding(vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.gdtf_share_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (signedIn) R.string.account_signed_in else R.string.account_not_signed_in),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (signedIn) {
            OutlinedButton(onClick = {
                GdtfShareClient.logout()
                GdtfCredentialStore.clear(ctx)
            }) { Text(stringResource(R.string.sync_sign_out)) }
        } else {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}
