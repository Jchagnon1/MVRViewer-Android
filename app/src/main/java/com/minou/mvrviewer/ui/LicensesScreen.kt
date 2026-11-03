package com.minou.mvrviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minou.mvrviewer.AppVersionInfo
import com.minou.mvrviewer.LicenseTextLayout
import com.minou.mvrviewer.R
import com.minou.mvrviewer.ThirdPartyLicenseCatalog
import com.minou.mvrviewer.ThirdPartyLicenseCatalog.Component
import com.minou.mvrviewer.mvr.IgnImagery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Page d'accueil de GDTF Share (ligne « Profils de projecteurs »). */
internal const val GDTF_SHARE_HOME_URL = "https://gdtf-share.com"

/**
 * « MENTIONS ET LICENCES » (Compte & réglages › À propos, guide C11) — portage de
 * LicensesView.swift : l'app et sa version, les sources de données, puis CHAQUE
 * composant embarqué dans l'APK de release avec le texte COMPLET de sa licence
 * et l'adresse de son code source (ou de sa page, pour un SDK fermé de Google).
 * Tout se lit HORS LIGNE (`assets/third_party_licenses.json`, régénéré par
 * Tools/generate_licenses.py). Recherche par nom, licence, bibliothèque ou
 * composant parent. AUCUNE signature d'éditeur (ni nom de société, ni symbole de copyright) :
 * l'éditeur n'est nommé que dans les pages légales du site.
 *
 * Navigation INTERNE : liste → composant → composant inclus ; ← et le retour
 * système remontent d'un cran, puis [onBack] ferme l'écran.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    // ~1 Mo de JSON : lu hors du fil principal.
    val state by produceState<LoadState>(LoadState.Loading) {
        value = withContext(Dispatchers.IO) {
            ThirdPartyLicenseCatalog.load(ctx.applicationContext)?.let { LoadState.Ready(it) } ?: LoadState.Failed
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    // Pile des composants ouverts (ids séparés par « | ») : survit à une rotation.
    var stackRaw by rememberSaveable { mutableStateOf("") }
    val stack = stackRaw.split('|').filter { it.isNotEmpty() }
    fun push(c: Component) { stackRaw = (stack + c.id).joinToString("|") }
    fun pop() { stackRaw = stack.dropLast(1).joinToString("|") }
    val catalog = (state as? LoadState.Ready)?.catalog
    val opened = stack.lastOrNull()?.let { catalog?.component(it) }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }
    val back = { if (stack.isNotEmpty()) pop() else onBack() }

    Column(modifier.fillMaxSize().testTag("licenses")) {
        TopAppBar(
            title = {
                Text(opened?.name ?: stringResource(R.string.licenses_title),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = {
                IconButton(onClick = back) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            }
        )
        when {
            state is LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            catalog == null -> Text(
                stringResource(R.string.licenses_unreadable), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(20.dp)
            )
            opened != null -> ComponentDetail(catalog, opened, onOpen = { push(it) })
            else -> ComponentList(catalog, query, onQuery = { query = it }, onOpen = { push(it) })
        }
    }
}

private sealed interface LoadState {
    data object Loading : LoadState
    data object Failed : LoadState
    data class Ready(val catalog: ThirdPartyLicenseCatalog) : LoadState
}

@Composable
private fun ComponentList(
    catalog: ThirdPartyLicenseCatalog,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (Component) -> Unit
) {
    val ctx = LocalContext.current
    val shown = remember(catalog, query) { catalog.displayed(query) }
    // Bord à bord : la liste s'arrête au-dessus de la barre de gestes et du clavier (recherche).
    LazyColumn(
        Modifier.fillMaxSize().navigationBarsPadding().imePadding().testTag("licenses.list"),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                placeholder = { Text(stringResource(R.string.licenses_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.licenses_clear_search))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("licenses.search")
            )
        }
        if (query.isBlank()) {
            item {
                // L'app et sa version, SANS signature d'éditeur.
                val version = remember { AppVersionInfo.current(ctx) }
                val versionText = stringResource(R.string.licenses_version_fmt, version)
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag("licenses.app")
                        .clearAndSetSemantics { contentDescription = "MVR Viewer, $versionText" }
                ) {
                    Text("MVR Viewer", style = MaterialTheme.typography.titleLarge)
                    Text(versionText, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            sectionHeader(R.string.licenses_section_data)
            item {
                ExternalRow(
                    title = stringResource(R.string.licenses_ign_title),
                    detail = stringResource(R.string.licenses_ign_detail),
                    url = IgnImagery.DATASET_URL, tag = "licenses.ign"
                )
            }
            item {
                ExternalRow(
                    title = stringResource(R.string.licenses_gdtf_title),
                    detail = stringResource(R.string.licenses_gdtf_detail),
                    url = GDTF_SHARE_HOME_URL, tag = "licenses.gdtfShare"
                )
            }
            sectionHeader(R.string.licenses_section_components)
        }
        if (shown.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.licenses_none_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.licenses_none_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(shown, key = { it.id }) { c -> ComponentRow(catalog, c, onClick = { onOpen(c) }) }
            if (query.isBlank()) item {
                Text(stringResource(R.string.licenses_components_footer), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }
}

private fun LazyListScope.sectionHeader(res: Int) {
    item {
        Text(
            stringResource(res), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp).semantics { heading() }
        )
    }
}

/** Ligne d'un composant : son nom, puis la licence et la version (ou le composant qui l'inclut). */
@Composable
private fun ComponentRow(catalog: ThirdPartyLicenseCatalog, c: Component, onClick: () -> Unit) {
    val parents = catalog.parents(c)
    val children = remember(catalog, c) { catalog.children(c).size }
    val includedIn = if (parents.isEmpty()) null
        else stringResource(R.string.licenses_included_in_fmt, parents.joinToString(", ") { it.name })
    val childCount = if (children > 0) pluralStringResource(R.plurals.licenses_children, children, children) else null
    val detail = listOfNotNull(c.license, includedIn ?: c.version, childCount).joinToString(" · ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("licenses.component.${c.id}")
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Ligne qui ouvre une page du web : titre, phrase secondaire, flèche « externe ». */
@Composable
private fun ExternalRow(title: String, detail: String, url: String, tag: String) {
    val ctx = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { openExternalUrl(ctx, url) }.testTag(tag)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Un composant : adresse de son code source (visible à l'ouverture, sans
 * défiler), bibliothèques empaquetées, texte COMPLET de sa licence
 * (sélectionnable, un paragraphe par élément), puis les composants inclus.
 */
@Composable
private fun ComponentDetail(catalog: ThirdPartyLicenseCatalog, c: Component, onOpen: (Component) -> Unit) {
    val parents = catalog.parents(c)
    val children = remember(catalog, c) { catalog.children(c) }
    val blocks = remember(catalog, c) {
        c.texts.map { ref -> ref to LicenseTextLayout.paragraphs(catalog.text(ref.textId).orEmpty()) }
    }
    LazyColumn(
        Modifier.fillMaxSize().navigationBarsPadding().testTag("licenses.detail.${c.id}"),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        c.url?.let { url ->
            item {
                ExternalRow(
                    title = stringResource(if (c.sourceAvailable) R.string.licenses_source_code else R.string.licenses_project_page),
                    detail = url, url = url, tag = "licenses.source"
                )
            }
        }
        if (c.url == null && c.license == null) item {
            Text(stringResource(R.string.licenses_google_notice), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        if (parents.isNotEmpty()) item {
            Text(stringResource(R.string.licenses_included_in_fmt, parents.joinToString(", ") { it.name }),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        if (c.artifacts.isNotEmpty()) {
            sectionHeader(R.string.licenses_packaged)
            items(c.artifacts) { a ->
                Text(a, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            }
        }
        item { HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 4.dp)) }
        blocks.forEach { (ref, paragraphs) ->
            if (ref.from.isNotEmpty()) item {
                val names = ref.from.mapNotNull { catalog.component(it)?.name }.joinToString(", ")
                Text(stringResource(R.string.licenses_text_from_fmt, names), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
            }
            items(paragraphs) { p ->
                SelectionContainer {
                    Text(p, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).testTag("licenses.text"))
                }
            }
        }
        if (children.isNotEmpty()) {
            item { HorizontalDivider(Modifier.padding(top = 12.dp)) }
            sectionHeader(R.string.licenses_included)
            items(children, key = { "child-" + it.id }) { child -> ComponentRow(catalog, child, onClick = { onOpen(child) }) }
        }
    }
}
