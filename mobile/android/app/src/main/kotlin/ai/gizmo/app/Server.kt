package ai.gizmo.app

import java.util.UUID

data class Server(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val url: String,
    val isDefault: Boolean = false
)
