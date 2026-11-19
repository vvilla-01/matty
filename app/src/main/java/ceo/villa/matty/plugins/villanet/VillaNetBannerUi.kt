package ceo.villa.matty.plugins.villanet

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import ceo.villa.matty.plugins.PluginHost
import ceo.villa.matty.ui.components.ActionRow
import ceo.villa.matty.ui.components.MattyDialog
import ceo.villa.matty.ui.theme.Matty
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Scale
import kotlin.math.roundToInt

/**
 * The person's banner across the top of their profile, over the coloured band (which shows while
 * it loads, and if it can't), and on your own (or, for a system admin, on a profile with one) the
 * small button that changes it. Nothing at all while this server's VillaNET has no banners.
 */
@Composable
internal fun VillaNetProfileBanner(plugin: VillaNetPlugin, userId: String, host: PluginHost, modifier: Modifier) {
    val access by plugin.access.collectAsState()
    val outdated by plugin.outdated.collectAsState()
    if (outdated != null || !bannersOffered(access?.version)) return
    val repo = host.repo
    val c = Matty.colors
    val held by plugin.banners.collectAsState()
    val busy by plugin.bannerBusy.collectAsState()
    val me by repo.me.collectAsState()
    LaunchedEffect(plugin, userId) { plugin.bannersProfileOpen() }
    val banner = held?.people?.get(userId)?.takeIf { it.v.isNotEmpty() }
    val mine = userId == repo.myId
    // A GIF's shown part is kept by VillaNET (3.8.0), so it can be adjusted; a still's other parts never left the phone.
    val adjustable = banner?.type == "image/gif" && bannerCropOffered(access?.version)
    val entries = bannerEntries(mine, banner != null, me?.isSystemAdmin == true, me?.roles?.split(' ')?.contains("system_guest") == true, adjustable)
    // An animated banner plays only while it can be seen: minimised, it isn't drawn at all.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val cr = LocalContext.current.contentResolver
    // The picture shown for choosing its part is decoded for the screen's longer side.
    val conf = LocalConfiguration.current
    val screenPixels = with(LocalDensity.current) { maxOf(conf.screenWidthDp, conf.screenHeightDp).dp.roundToPx() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), @DontMemoize { uri ->
        if (uri != null) plugin.pickedBanner(cr, uri, screenPixels)
    })
    Box(modifier) {
        val url = banner?.let { plugin.api?.bannerUrl(userId, it.v) }
        // Through the app's image loader, cached by its address (a new picture is a new "v"),
        // with the session token and no X-Requested-With: it is loaded as a picture.
        if (url != null && lifecycle.isAtLeast(Lifecycle.State.STARTED)) {
            val crop = bannerCropOf(banner?.crop)
            if (crop == null) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                // The part chosen fills the banner; decoded at the size that part needs to be sharp there.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val ctx = LocalPlatformContext.current
                    val w = constraints.maxWidth
                    val h = constraints.maxHeight
                    val request = remember(url, crop, w, h) {
                        ImageRequest.Builder(ctx).data(url)
                            .size(maxOf(1, kotlin.math.ceil(w / crop.w).toInt()), maxOf(1, kotlin.math.ceil(h / crop.h).toInt()))
                            .scale(Scale.FILL)
                            // No crossfade: Coil's crossfade fits its pictures into the size drawn
                            // with the content scale again, and a crop's scale applied twice
                            // magnifies the picture by the crop's own factor once more.
                            .crossfade(false).build()
                    }
                    val scale = remember(crop) { CropFill(crop) }
                    val align = remember(crop) { CropAlign(crop) }
                    AsyncImage(model = request, contentDescription = null, contentScale = scale, alignment = align, modifier = Modifier.fillMaxSize())
                }
            }
        }
        if (entries.isNotEmpty()) {
            val label = if (mine) "Change banner" else "Remove banner"
            Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(36.dp).clip(CircleShape).background(c.card.copy(alpha = 0.7f))
                    .clickable(enabled = !busy, onClickLabel = label) { menu = true }
                    .semantics { contentDescription = if (busy) "Changing the banner" else label },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) LoadingIndicator(Modifier.size(24.dp), color = c.textSecondary)
                else Icon(Icons.Outlined.Edit, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
    if (menu) ModalBottomSheet(onDismissRequest = { menu = false }, containerColor = c.card, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            entries.forEach { entry ->
                when (entry) {
                    // Android's photo picker: no storage permission, and GIFs among the pictures.
                    BannerEntry.Choose -> ActionRow(Icons.Outlined.Image, "Choose a picture", "A GIF plays as it is") {
                        menu = false
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    BannerEntry.Adjust -> ActionRow(Icons.Outlined.Crop, "Adjust", "Choose the part that shows") {
                        menu = false
                        plugin.adjustBanner()
                    }
                    BannerEntry.RemoveMine -> ActionRow(Icons.Outlined.Delete, "Remove banner", tint = c.destructive) {
                        menu = false
                        plugin.removeMyBanner()
                    }
                    BannerEntry.RemoveTheirs -> ActionRow(Icons.Outlined.Delete, "Remove banner", tint = c.destructive) {
                        menu = false
                        confirm = true
                    }
                }
            }
        }
    }
    if (confirm) {
        val name = repo.nameOf(userId)
        MattyDialog("Remove Banner", onDismiss = { confirm = false }, icon = Icons.Outlined.Delete, actions = {
            TextButton(onClick = { confirm = false }) { Text("Cancel") }
            TextButton(onClick = {
                confirm = false
                plugin.removeBanner(userId)
            }) { Text("Remove", color = c.destructive) }
        }) {
            Text("Take $name's banner off their profile? They can set another.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        }
    }
}

/**
 * What lasts as long as the profile [userId] is open, composed outside its sheet: the crop screen
 * for one's own banner, and what the profile's opening and closing do to it and to a failure said
 * there. Not in the sheet: Material builds the sheet's window afresh when the screen's density
 * changes, and everything composed in it went with the old one, the part being chosen with it.
 */
@Composable
internal fun VillaNetProfileOver(plugin: VillaNetPlugin, userId: String, host: PluginHost) {
    val access by plugin.access.collectAsState()
    val outdated by plugin.outdated.collectAsState()
    if (outdated != null || !bannersOffered(access?.version)) return
    val busy by plugin.bannerBusy.collectAsState()
    val cropping by plugin.bannerCropping.collectAsState()
    // A crop screen a pick left after the profile closed, as found on opening it (cancelled below).
    val left = remember(plugin, userId) { plugin.bannerCropping.value?.takeIf { it.leftByPick(userId, plugin.bannerBusy.value) } }
    // A failure said on this profile is said once: gone with the sheet, and not found on opening
    // it again (unless the change it is about is still on its way).
    DisposableEffect(plugin, userId) {
        fun forget() { if (plugin.bannerTrouble.value?.first == userId) plugin.bannerTrouble.value = null }
        if (!plugin.bannerBusy.value) forget()
        // A pick that finished reading after the profile closed left a crop screen nobody asked
        // for this time: it goes. A failed Use stays to be tried again, a Use on its way is left alone.
        left?.let { plugin.cancelBannerCrop(it) }
        onDispose {
            forget()
            // The crop screen goes with the profile, unless what it sent is still on its way:
            // a failure then finds the picture there to try again.
            if (!plugin.bannerBusy.value) plugin.bannerCropping.value?.takeIf { it.userId == userId }?.let { plugin.cancelBannerCrop(it) }
        }
    }
    cropping?.takeIf { it.showsOn(userId, userId == host.repo.myId, left) }?.let { s ->
        BannerCropScreen(s, busy, onCancel = { plugin.cancelBannerCrop(s) }, onUse = { plugin.useBannerCrop(s) })
    }
}

/** Why the last change to this profile's banner failed, in a quiet line: the sheet covers the notice. */
@Composable
internal fun BannerTrouble(plugin: VillaNetPlugin, userId: String) {
    val trouble by plugin.bannerTrouble.collectAsState()
    val text = trouble?.takeIf { it.first == userId }?.second ?: return
    Text(text, color = Matty.colors.destructive, style = MaterialTheme.typography.bodySmall)
}

/** Scales a banner's picture so that its [crop] covers the box (see [cropScale]). */
private class CropFill(private val crop: CropRect) : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        val s = cropScale(srcSize.width.toDouble(), srcSize.height.toDouble(), dstSize.width.toDouble(), dstSize.height.toDouble(), crop).toFloat()
        return ScaleFactor(s, s)
    }
}

/** Places a banner's picture, as [CropFill] scaled it, with the middle of its [crop] in the middle of the box (see [cropOffset]). */
private class CropAlign(private val crop: CropRect) : Alignment {
    override fun align(size: IntSize, space: IntSize, layoutDirection: LayoutDirection): IntOffset {
        val (x, y) = cropOffset(size.width.toDouble(), size.height.toDouble(), space.width.toDouble(), space.height.toDouble(), crop)
        return IntOffset(x.roundToInt(), y.roundToInt())
    }
}
