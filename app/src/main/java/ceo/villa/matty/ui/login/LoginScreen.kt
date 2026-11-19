package ceo.villa.matty.ui.login

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import android.app.Activity
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import ceo.villa.matty.BuildConfig
import ceo.villa.matty.data.net.NetErrors
import ceo.villa.matty.data.net.TransportPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ceo.villa.matty.R
import ceo.villa.matty.container
import ceo.villa.matty.data.api.ApiException
import ceo.villa.matty.ui.components.FloatingTopBar
import ceo.villa.matty.ui.components.PillButton
import ceo.villa.matty.ui.theme.Matty
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

private sealed interface Step {
    data object Server : Step
    data class Credentials(val url: String, val config: Map<String, String>) : Step
    data class Sso(val url: String, val config: Map<String, String>, val path: String, val label: String) : Step
}

@Composable
fun LoginScreen(onCancel: (() -> Unit)? = null) {
    // One flag for the whole flow: per-step flags would be cleared by the outgoing step's
    // disposal while the next step (a password form or the GitLab page) is already showing.
    SecureScreen()
    val autofill = LocalAutofillManager.current
    var step by remember { mutableStateOf<Step>(Step.Server) }
    BackHandler(enabled = step !is Step.Server || onCancel != null) {
        step = when (val s = step) {
            is Step.Sso -> Step.Credentials(s.url, s.config)
            is Step.Credentials -> { autofill?.cancel(); Step.Server }
            Step.Server -> { onCancel?.invoke(); Step.Server }
        }
    }
    Box(Modifier.fillMaxSize().background(Matty.colors.background)) {
        AnimatedContent(step, transitionSpec = {
            (fadeIn() + slideInHorizontally { it / 4 }) togetherWith (fadeOut() + slideOutHorizontally { -it / 4 })
        }, label = "login") { s ->
            when (s) {
                Step.Server -> ServerStep(onCancel) { url, cfg -> step = Step.Credentials(url, cfg) }
                is Step.Credentials -> CredentialsStep(s, onBack = { autofill?.cancel(); step = Step.Server }) { path, label ->
                    autofill?.cancel(); step = Step.Sso(s.url, s.config, path, label)
                }
                is Step.Sso -> SsoStep(s) { step = Step.Credentials(s.url, s.config) }
            }
        }
    }
}

@Composable
private fun Brand() {
    val c = Matty.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(96.dp).background(c.pill, CircleShape), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, modifier = Modifier.size(96.dp), colorFilter = ColorFilter.tint(c.icon))
        }
        Spacer(Modifier.height(16.dp))
        Text("Matty", style = MaterialTheme.typography.headlineMedium, color = c.title)
    }
}

/** MarkOut's pill, as a text field: 56dp, fully rounded, no outline. */
@Composable
private fun PillField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    actions: KeyboardActions = KeyboardActions.Default,
    hidden: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Matty.colors
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder, color = c.textTertiary) },
        shape = RoundedCornerShape(28.dp),
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboard, keyboardActions = actions, trailingIcon = trailing,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.pill, unfocusedContainerColor = c.pill, disabledContainerColor = c.pill,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
            cursorColor = c.accent, focusedTextColor = c.title, unfocusedTextColor = c.title,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
}

@Composable
private fun ErrorLine(error: String?) {
    AnimatedVisibility(error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Text(error.orEmpty(), color = Matty.colors.error, style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 8.dp, end = 8.dp))
    }
}

/** A server address the user typed, checked against Matty's HTTPS-only policy. */
sealed interface ServerAddress {
    data class Ok(val url: String) : ServerAddress
    data class Rejected(val reason: String) : ServerAddress
}

fun parseServerAddress(raw: String): ServerAddress {
    var u = raw.trim().trimEnd('/')
    if (u.isEmpty()) return ServerAddress.Rejected("Enter your server's address.")
    val explicit = u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)
    if (!explicit) {
        // No scheme typed means HTTPS. (Debug builds talk plain http to the local dev server.)
        val host = u.substringBefore('/').substringBefore(':').lowercase()
        u = (if (BuildConfig.DEBUG && TransportPolicy.isDevHost(host)) "http://" else "https://") + u
    }
    // People paste sign-in links; the server is everything before them.
    u = u.replace(Regex("/(login|signup_user_complete)$"), "")
    val parsed = u.toHttpUrlOrNull() ?: return ServerAddress.Rejected("That doesn't look like a server address.")
    if (!TransportPolicy.isAllowed(parsed)) return ServerAddress.Rejected(NetErrors.HTTPS_REQUIRED)
    // Canonical form (lower-case scheme and host, punycode) is what gets saved and used everywhere.
    return ServerAddress.Ok(parsed.toString().trimEnd('/'))
}

/**
 * The server locked the account after too many wrong tries (wrong two-factor codes count too):
 * its own words, not "wrong password", which would only invite more tries.
 */
internal fun lockedOutText(e: ApiException): String? =
    if (e.errorId != "api.user.check_user_login_attempts.too_many.app_error") null
    else e.serverMessage ?: "This account is locked after too many tries. Reset your password in Mattermost on the web."

/** Keeps passwords out of screenshots, screen recordings and the recent-apps preview. */
@Composable
private fun SecureScreen() {
    val activity = LocalContext.current as? Activity ?: return
    // Through the one decision about the window, so leaving this screen never lifts what a server requires.
    DisposableEffect(activity) {
        ceo.villa.matty.ui.lock.SecureWindow.hold(activity)
        onDispose { ceo.villa.matty.ui.lock.SecureWindow.release(activity) }
    }
}

@Composable
private fun SessionNotice() {
    val sessions = LocalContext.current.container.sessions
    val notice by sessions.notice.collectAsState()
    val msg = notice ?: return
    val c = Matty.colors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).background(c.accent.copy(alpha = 0.14f), Matty.CardShape).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, null, tint = c.accent)
        Text(msg, style = MaterialTheme.typography.bodySmall, color = c.text, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        Icon(Icons.Outlined.Close, "Dismiss", tint = c.textSecondary, modifier = Modifier.size(20.dp).clickable { sessions.notice.value = null })
    }
}

@Composable
private fun ServerStep(onCancel: (() -> Unit)?, onFound: (String, Map<String, String>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ended by ctx.container.sessions.ended.collectAsState()
    var url by rememberSaveable { mutableStateOf(ended?.url?.removePrefix("https://").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun connect() {
        if (url.isBlank() || busy) return
        error = null
        val base = when (val a = parseServerAddress(url)) {
            is ServerAddress.Rejected -> { error = a.reason; return }
            is ServerAddress.Ok -> a.url
        }
        busy = true
        scope.launch {
            // HTTPS only. There is deliberately no fallback to plain http: an attacker who
            // blocks port 443 must not be able to talk Matty into sending a password in the clear.
            runCatching {
                val client = ctx.container.sessions.probeClient(base)
                client.ping()
                base to client.clientConfig()
            }.onSuccess { (u, cfg) -> onFound(u, cfg) }
                .onFailure {
                    android.util.Log.w("Matty", "server probe failed for $base", it)
                    error = when {
                        NetErrors.isTls(it) || NetErrors.isCleartextRefused(it) -> NetErrors.describe(it, base)
                        else -> "Couldn't reach a Mattermost server there. Check the address and that it's online."
                    }
                }
            busy = false
        }
    }
    val c = Matty.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        if (onCancel != null) FloatingTopBar("", onBack = onCancel)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Brand()
            Spacer(Modifier.height(40.dp))
            SessionNotice()
            PillField(
                value = url, onChange = { url = it; error = null }, placeholder = "Server address",
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                actions = KeyboardActions(onGo = { connect() }),
                trailing = {
                    Box(
                        Modifier.padding(end = 6.dp).size(44.dp).clip(CircleShape)
                            .background(if (url.isNotBlank()) c.accent else c.selected)
                            .clickable(enabled = url.isNotBlank() && !busy) { connect() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) LoadingIndicator(Modifier.size(28.dp), color = c.onAccent)
                        else Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Continue", tint = if (url.isNotBlank()) c.onAccent else c.textTertiary)
                    }
                },
            )
            ErrorLine(error)
        }
        }
    }
}

@Composable
private fun CredentialsStep(s: Step.Credentials, onBack: () -> Unit, onSso: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val autofill = LocalAutofillManager.current
    val scope = rememberCoroutineScope()
    val c = Matty.colors
    val cfg = s.config
    val ended by ctx.container.sessions.ended.collectAsState()
    var login by rememberSaveable { mutableStateOf(ended?.takeIf { it.url == s.url }?.username.orEmpty()) }
    // Secrets live only in memory (never saved state) and are cleared once used.
    var password by remember { mutableStateOf("") }
    var mfa by remember { mutableStateOf("") }
    var needMfa by remember { mutableStateOf(false) }
    var showPw by remember { mutableStateOf(false) }
    var useToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val site = cfg["SiteName"]?.ifBlank { null } ?: s.url.removePrefix("https://").removePrefix("http://")
    val passwordAllowed = cfg["EnableSignInWithEmail"] != "false" || cfg["EnableSignInWithUsername"] != "false"
    // GitLab is the single sign-on Mattermost's free edition offers; the paid-tier providers
    // (Google, Microsoft, OpenID, SAML) are out of scope for a self-hoster's client.
    val ssoOptions = buildList {
        if (cfg["EnableSignUpWithGitLab"] == "true") add("/oauth/gitlab/login" to (cfg["GitLabButtonText"]?.ifBlank { null } ?: "GitLab"))
    }

    fun signIn() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            val sessions = ctx.container.sessions
            try {
                // Once the server is asked, see it through: a session it creates must be saved
                // (or it would live on, unused, on the server) even if this screen goes away.
                withContext(NonCancellable) { if (useToken) {
                    val t = token.trim()
                    val me = sessions.clientFor(s.url, t).me()
                    token = ""
                    sessions.signedIn(s.url, site, me, t)
                } else {
                    val (me, t) = sessions.clientFor(s.url).login(login.trim(), password, mfa.ifBlank { null })
                    // Offer the password manager the credentials that just worked, then forget them.
                    autofill?.commit()
                    password = ""; mfa = ""
                    sessions.signedIn(s.url, site, me, t)
                } }
            } catch (e: ApiException) {
                if (e.errorId.contains("mfa")) {
                    if (needMfa) error = "That code didn't work. Try the newest one." else needMfa = true
                } else error = when (e.status) {
                    401 -> if (useToken) "That token wasn't accepted." else lockedOutText(e) ?: "Wrong username or password."
                    403 -> e.message ?: "This account can't sign in right now."
                    429 -> "Too many tries. Wait a minute, then try again."
                    in 300..399 -> "The server tried to redirect the sign-in somewhere else, so Matty stopped."
                    else -> e.message ?: "Sign in failed."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = NetErrors.describe(e, site)
            }
            busy = false
        }
    }
    // Back is held while signing in, so the request is never abandoned half-way.
    BackHandler(enabled = busy) {}

    val host = s.url.substringAfter("://")
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
        FloatingTopBar(site, subtitle = if (s.url.startsWith("https://")) "🔒 $host" else "⚠ $host · debug only", onBack = { if (!busy) onBack() })
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SessionNotice()
            if (passwordAllowed || useToken) {
                if (useToken) {
                    PillField(token, { token = it; error = null }, "Access token", hidden = true,
                        // A token is not a password: keep password managers from filling or saving it.
                        modifier = Modifier.semantics { contentDataType = ContentDataType.None },
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        actions = KeyboardActions(onDone = { signIn() }))
                } else {
                    PillField(login, { login = it; error = null }, "Email or username",
                        modifier = Modifier.semantics { contentType = ContentType.Username + ContentType.EmailAddress },
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false))
                    PillField(password, { password = it; error = null }, "Password", hidden = !showPw,
                        modifier = Modifier.semantics { contentType = ContentType.Password },
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (needMfa) ImeAction.Next else ImeAction.Done),
                        actions = KeyboardActions(onDone = { signIn() }),
                        trailing = {
                            IconButton(onClick = { showPw = !showPw }) {
                                Icon(if (showPw) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showPw) "Hide password" else "Show password", tint = c.textSecondary)
                            }
                        })
                    AnimatedVisibility(needMfa) {
                        PillField(mfa, { mfa = it.filter(Char::isDigit).take(8); error = null }, "Authenticator code",
                            modifier = Modifier.semantics { contentType = ContentType("2faAppOTPCode") },
                            keyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                            actions = KeyboardActions(onDone = { signIn() }))
                    }
                }
                ErrorLine(error)
                Spacer(Modifier.height(4.dp))
                PillButton("Sign in", primary = true, loading = busy, modifier = Modifier.fillMaxWidth(), height = 56.dp,
                    enabled = if (useToken) token.isNotBlank() else login.isNotBlank() && password.isNotBlank()) { signIn() }
            }
            ssoOptions.forEach { (path, label) ->
                PillButton("Continue with $label", modifier = Modifier.fillMaxWidth(), height = 56.dp) { onSso(path, label) }
            }
            Text(
                if (useToken) "Use a password" else "Use an access token",
                color = c.textSecondary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { useToken = !useToken; error = null; token = "" }.padding(10.dp),
            )
        }
        }
    }
}

/** Forget everything a web sign-in left behind: cookies (incl. the session cookie), storage, cache. */
private fun wipeWebSession(webView: WebView?) {
    runCatching { CookieManager.getInstance().apply { removeAllCookies(null); flush() } }
    runCatching { WebStorage.getInstance().deleteAllData() }
    webView?.let { runCatching { it.stopLoading(); it.clearHistory(); it.clearCache(true); it.clearFormData(); it.destroy() } }
}

/**
 * GitLab SSO in a locked-down WebView on the server's own sign-in page. It only navigates
 * over HTTPS, refuses certificate errors and mixed content, has no file/content access, and
 * takes the MMAUTHTOKEN session cookie only when a page on the server's exact origin has
 * finished loading. The cookie jar is wiped as soon as the token is read, and again on exit.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SsoStep(s: Step.Sso, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val site = s.config["SiteName"]?.ifBlank { null } ?: s.url
    val origin = remember(s.url) { s.url.toHttpUrlOrNull() }
    // The host actually on screen (there's no address bar), punycode so look-alikes show as such.
    var pageHost by remember { mutableStateOf(origin?.host.orEmpty()) }
    // Set only when the server's GitLab callback is reached: a session cookie seen before that
    // (e.g. planted by a sibling site) is never accepted.
    var sawComplete by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { wipeWebSession(webView) } }
    if (origin == null || !TransportPolicy.isAllowed(origin)) {
        Column(Modifier.fillMaxSize()) {
            FloatingTopBar("Sign in with ${s.label}", subtitle = site, onBack = onBack)
            Text(NetErrors.HTTPS_REQUIRED, color = Matty.colors.error, modifier = Modifier.padding(24.dp))
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        FloatingTopBar("Sign in with ${s.label}", subtitle = "🔒 $pageHost", onBack = onBack)
        error?.let { Text(it, color = Matty.colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val cookies = CookieManager.getInstance()
                cookies.removeAllCookies(null)
                WebView(context).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setGeolocationEnabled(false)
                    settings.safeBrowsingEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            url.toHttpUrlOrNull()?.let { pageHost = if (it.isHttps) it.host else "${it.scheme}://${it.host}:${it.port}" }
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url.toString()
                            if (request.isForMainFrame && TransportPolicy.sameOrigin(u, origin) &&
                                Regex("^/(signup|login|oauth)/gitlab/complete$").matches(request.url.path.orEmpty())
                            ) sawComplete = true
                            // Hops to the identity provider and back are fine — over HTTPS only.
                            val ok = TransportPolicy.isAllowed(u)
                            if (!ok && request.isForMainFrame) error = "Matty blocked an unencrypted page during sign-in."
                            return !ok
                        }

                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, e: SslError) {
                            handler.cancel()
                            error = NetErrors.UNTRUSTED_CERT
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (done || !TransportPolicy.sameOrigin(url, origin)) return
                            val all = cookies.getCookie(s.url)?.split(';')?.map { it.trim() }
                                ?.filter { it.startsWith("MMAUTHTOKEN=") }.orEmpty()
                            if (all.isEmpty()) return
                            if (!sawComplete || all.size != 1) {
                                // Not from this sign-in (or ambiguous): refuse it and start clean.
                                runCatching { cookies.removeAllCookies(null); cookies.flush() }
                                sawComplete = false
                                error = "Sign-in was interrupted by another site. Try again."
                                return
                            }
                            val token = all.single().substringAfter('=')
                            if (token.isBlank()) return
                            done = true
                            // The token is in memory now; it must not stay in the WebView's cookie store.
                            runCatching { cookies.removeAllCookies(null); cookies.flush() }
                            scope.launch {
                                val sessions = ctx.container.sessions
                                runCatching {
                                    val me = sessions.clientFor(s.url, token).me()
                                    sessions.signedIn(s.url, site, me, token)
                                }.onFailure { done = false; sawComplete = false; error = "Sign-in didn't complete. Try again." }
                            }
                        }
                    }
                    loadUrl(s.url + s.path)
                }
            },
        )
    }
}
