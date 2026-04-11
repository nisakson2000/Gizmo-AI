package ai.gizmo.app.model

data class MemoryFile(
    val filename: String,
    val subdir: String,
    val size: Long = 0,
    val modified: String = ""
)

data class MemoryContent(
    val filename: String,
    val subdir: String,
    val content: String
)
