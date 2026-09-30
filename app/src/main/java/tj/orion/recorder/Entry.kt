package tj.orion.recorder

/**
 * Canonical capture entry. Local mirror of the Supabase `entries` contract.
 * Audio is never stored; server-filled fields (summary, action_items) live
 * only in Supabase.
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
    val syncState: String = "pending"  // pending | synced (synced rows are deleted locally)
) {
    companion object {
        const val TYPE_RECORDING = "recording"
        const val TYPE_THOUGHT = "thought"
        const val TYPE_COMMAND = "command"

        const val SYNC_PENDING = "pending"
    }
}
