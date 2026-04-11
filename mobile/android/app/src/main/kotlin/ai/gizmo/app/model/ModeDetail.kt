package ai.gizmo.app.model

data class ModeDetail(
    val name: String,
    val label: String,
    val description: String = "",
    val icon: String = "",
    val order: Int = 0,
    val systemPrompt: String = ""
)
