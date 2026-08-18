package ru.srr.passwords.autofill

/**
 * Remembers a login attempt so we can offer save after navigation.
 * Password may be empty (AssistStructure often hides it) — dialog can ask.
 */
object PendingManualSave {
    data class Candidate(
        val pkg: String,
        val domain: String,
        val username: String,
        val password: String,
        val at: Long = System.currentTimeMillis()
    )

    @Volatile
    private var current: Candidate? = null

    @Volatile
    private var lastOfferedAt: Long = 0L

    @Volatile
    private var suppressUntil: Long = 0L

    fun suppressAfterAutofill(ms: Long = 90_000L) {
        suppressUntil = System.currentTimeMillis() + ms
        current = null
    }

    fun remember(pkg: String, domain: String, username: String, password: String) {
        if (System.currentTimeMillis() < suppressUntil) return
        if (username.isBlank() && password.isBlank()) return
        val prev = current
        val pass = when {
            password.length >= 2 -> password
            prev != null &&
                prev.pkg == pkg &&
                prev.domain.equals(domain, true) &&
                prev.username.equals(username, true) &&
                prev.password.length >= 2 -> prev.password
            else -> password
        }
        current = Candidate(pkg = pkg, domain = domain, username = username, password = pass)
    }

    fun peek(pkg: String, domain: String, maxAgeMs: Long = 120_000L): Candidate? {
        if (System.currentTimeMillis() < suppressUntil) return null
        val c = current ?: return null
        if (System.currentTimeMillis() - c.at > maxAgeMs) return null
        val samePkg = pkg.isNotBlank() && c.pkg == pkg
        val sameDomain = domain.isNotBlank() && c.domain.equals(domain, ignoreCase = true)
        if (!samePkg && !sameDomain) return null
        return c
    }

    fun consume(pkg: String, domain: String, maxAgeMs: Long = 120_000L): Candidate? {
        val c = peek(pkg, domain, maxAgeMs) ?: return null
        val now = System.currentTimeMillis()
        if (now - lastOfferedAt < 15_000L) return null
        lastOfferedAt = now
        current = null
        return c
    }

    fun clear() {
        current = null
    }
}
