package ceo.villa.matty

import ceo.villa.matty.data.api.Post
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A post's name and picture of somebody else's: only for a webhook or a bot, and only as the server allows. */
class PostOverrideTest {
    private fun post(vararg props: Pair<String, String>) =
        Post(id = "p", userId = "u-member", props = buildJsonObject { props.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })

    private val webhook = post("from_webhook" to "true", "override_username" to "Build Bot", "override_icon_url" to "https://x.example/v.png")

    @Test
    fun theServersSettingsDecide() {
        // Mattermost's defaults: neither is allowed, and the post shows its real author.
        assertNull(webhook.overrideUsername(allowed = false))
        assertNull(webhook.overrideIconUrl(allowed = false))
        // Allowed, a webhook's post is shown as it asks.
        assertEquals("Build Bot", webhook.overrideUsername(allowed = true))
        assertEquals("https://x.example/v.png", webhook.overrideIconUrl(allowed = true))
    }

    @Test
    fun onlyAWebhooksOrABotsPost() {
        val member = post("override_username" to "Build Bot", "override_icon_url" to "https://x.example/v.png")
        assertNull(member.overrideUsername(allowed = true))
        assertNull(member.overrideIconUrl(allowed = true))
        val bot = post("from_bot" to "true", "override_username" to "Build bot", "override_icon_url" to "https://x.example/b.png")
        assertEquals("Build bot", bot.overrideUsername(allowed = true))
        // A picture only for a webhook, as before.
        assertNull(bot.overrideIconUrl(allowed = true))
        // An empty name is no name.
        assertNull(post("from_webhook" to "true", "override_username" to " ").overrideUsername(allowed = true))
    }
}
