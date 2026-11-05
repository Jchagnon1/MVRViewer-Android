package com.minou.mvrviewer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.AppVersionInfo
import com.minou.mvrviewer.LegalLinks
import com.minou.mvrviewer.LegalPage
import com.minou.mvrviewer.R
import com.minou.mvrviewer.SignUpConsent

// « À PROPOS » de Compte & réglages (guide de mise en vente C3, C11) — même
// contenu qu'iOS : pages légales du site (Google, règle « Données
// utilisateur » : la politique de confidentialité doit être facile à trouver
// DANS l'app), « Mentions et licences » (écran interne, hors ligne) et le
// numéro de version. Section TOUJOURS présente (connecté ou non, sans
// synchro). AUCUNE signature d'éditeur ici : l'éditeur n'est nommé que dans les
// pages légales du site et, parce que la loi l'impose avant la souscription, sur
// la ligne « Éditeur » de l'écran Pro (LegalLinks.PUBLISHER_NAME).

/** Ouvre [url] dans le navigateur du système ; sans navigateur, un message bref. */
fun openExternalUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.legal_no_browser, url), Toast.LENGTH_LONG).show()
    }
}

/** Titre et icône d'une page légale (Material Icons, comme les autres lignes de réglages). */
private fun LegalPage.titleRes(): Int = when (this) {
    LegalPage.PRIVACY -> R.string.legal_privacy
    LegalPage.TERMS -> R.string.legal_terms
    LegalPage.LEGAL_NOTICE -> R.string.legal_notice
    LegalPage.SUPPORT -> R.string.legal_support
}

private fun LegalPage.icon(): ImageVector = when (this) {
    LegalPage.PRIVACY -> Icons.Filled.PrivacyTip
    LegalPage.TERMS -> Icons.Filled.Description
    LegalPage.LEGAL_NOTICE -> Icons.Filled.AccountBalance
    LegalPage.SUPPORT -> Icons.AutoMirrored.Filled.HelpOutline
}

@Composable
internal fun AboutSection(onOpenLicenses: () -> Unit) {
    val ctx = LocalContext.current
    Text(
        stringResource(R.string.account_section_about), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
    LegalPage.entries.forEach { page ->
        AboutRow(
            icon = page.icon(), title = stringResource(page.titleRes()), external = true,
            modifier = Modifier.testTag("about.${page.name.lowercase()}"),
            onClick = { openExternalUrl(ctx, LegalLinks.url(page, ctx)) }
        )
    }
    AboutRow(
        icon = Icons.AutoMirrored.Filled.MenuBook, title = stringResource(R.string.about_licenses), external = false,
        modifier = Modifier.testTag("about.licenses"), onClick = onOpenLicenses
    )
    // UN SEUL élément pour TalkBack : « Version 1.10.0 (21) », lu d'un seul tenant
    // (sinon l'icône, le libellé et le numéro étaient trois arrêts).
    val version = remember { AppVersionInfo.current(ctx) }
    val versionLabel = stringResource(R.string.about_version_a11y_fmt, version)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("about.version")
            .clearAndSetSemantics { contentDescription = versionLabel }
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.about_version), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(version, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

/**
 * Ligne de « À propos » : icône, titre, flèche « externe » (navigateur) ou chevron
 * (écran interne). TalkBack lit la ligne d'un seul tenant : « Confidentialité,
 * S'ouvre dans le navigateur » (la flèche externe porte cette description).
 */
@Composable
private fun AboutRow(
    icon: ImageVector,
    title: String,
    external: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            if (external) Icons.AutoMirrored.Filled.OpenInNew else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (external) stringResource(R.string.legal_opens_in_browser) else null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(if (external) 18.dp else 24.dp)
        )
    }
}

/**
 * Phrase posée sous « Créer le compte » (information au moment de la collecte,
 * RGPD) : « En créant un compte, vous acceptez les conditions d'utilisation et
 * la politique de confidentialité. », les deux liens DANS la phrase (navigateur),
 * dans la langue de l'app.
 */
@Composable
internal fun SignUpConsentText(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val template = stringResource(R.string.signup_consent_fmt)
    val terms = stringResource(R.string.signup_consent_terms)
    val privacy = stringResource(R.string.signup_consent_privacy)
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(template, terms, privacy, linkColor) {
        val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        buildAnnotatedString {
            for (segment in SignUpConsent.segments(template, terms, privacy)) when (segment) {
                is SignUpConsent.Segment.Plain -> append(segment.text)
                is SignUpConsent.Segment.Link ->
                    withLink(LinkAnnotation.Url(LegalLinks.url(segment.page, ctx), styles)) { append(segment.text) }
            }
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.testTag("account.signUpConsent"))
}
