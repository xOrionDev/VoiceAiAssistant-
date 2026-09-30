package tj.orion.recorder

/** Supabase connection. The publishable key is public by design (RLS + auth protect data). */
object Config {
    const val SUPABASE_URL = "https://batgckpricdljxzwdqmh.supabase.co"
    const val SUPABASE_KEY = "sb_publishable_dYclhqjhxbJ9Mq_ZkjN07A_CKv32vJx"

    // Upload every hour, or sooner when this many characters are waiting.
    const val SYNC_PERIOD_MS = 60L * 60L * 1000L
    const val SYNC_CHAR_THRESHOLD = 4000
    // Local safety purge: drop anything older than this even if unsynced.
    const val LOCAL_PURGE_DAYS = 30
}
