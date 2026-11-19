package ceo.villa.matty.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ceo.villa.matty.ui.Navigator
import ceo.villa.matty.ui.components.FloatingTopBar
import ceo.villa.matty.ui.theme.Matty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The asset that carries the notices of the libraries the APK is built with. */
const val LICENCES_ASSET = "licences.txt"

/** The notices other people's work asks to travel with the app. */
@Composable
fun LicencesScreen(nav: Navigator) {
    val context = LocalContext.current
    val text by produceState("") {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(LICENCES_ASSET).bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    Column(Modifier.fillMaxSize().background(Matty.colors.background)) {
        FloatingTopBar("Licences", onBack = { nav.pop() })
        SelectionContainer {
            Text(text, color = Matty.colors.textSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}
