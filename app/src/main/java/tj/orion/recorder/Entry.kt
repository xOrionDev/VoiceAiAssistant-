package tj.orion.recorder

/**
 * Canonical capture entry. Local mirror of the Supabase `entries` contract
 * (minus server-filled fields: summary, action_items, embedding).
 */
data class Entry(
    val id: Long = 0L,
    val clientId: String,
    val capturedAt: Long,          // epoch millis, when captured on device
    val type: String,              // recording | thought | command
    val source: String,
    val text: String? = null,      // transcript or dictated thought
    val lang: String = "ru",
    val tags: List<String> = emptyList(),
    val audioLocalRef: String? = null, // local file path; audio is never uploaded
    val syncState: String = "pending"  // pending | synced
) {
    companion object {
        const val TYPE_RECORDING = "recording"
        const val TYPE_THOUGHT = "thought"
        const val TYPE_COMMAND = "command"

        const val SYNC_PENDING = "pending"
        const val SYNC_SYNCED = "synced"
    }
}
