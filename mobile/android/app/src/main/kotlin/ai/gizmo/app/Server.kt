package ai.gizmo.app

import android.content.Intent
import java.util.UUID

data class Server(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val url: String,
    val isDefault: Boolean = false
) {
    companion object {
        const val EXTRA_ID = "server_id"
        const val EXTRA_URL = "server_url"
        const val EXTRA_NAME = "server_name"
        const val EXTRA_ONBOARDING = "onboarding"
        const val EXTRA_EDIT_ID = "edit_server_id"

        fun fromIntent(intent: Intent): Server? {
            val id = intent.getStringExtra(EXTRA_ID) ?: return null
            val url = intent.getStringExtra(EXTRA_URL) ?: return null
            val name = intent.getStringExtra(EXTRA_NAME) ?: ""
            return Server(id = id, name = name, url = url)
        }
    }
}

fun Intent.putServerExtras(server: Server): Intent = apply {
    putExtra(Server.EXTRA_ID, server.id)
    putExtra(Server.EXTRA_URL, server.url)
    putExtra(Server.EXTRA_NAME, server.name)
}
