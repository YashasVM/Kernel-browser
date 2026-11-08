package com.kernel.browser

/** Main-thread-only ownership tokens for asynchronous captures of a shared browser view. */
internal class ThumbnailCaptureGuard<S : Any> {
    class Ticket<S : Any> internal constructor(val tabId: Long, internal val session: S)

    private val latest = mutableMapOf<Long, Ticket<S>>()

    fun begin(tabId: Long, session: S, displayedSession: S?): Ticket<S>? {
        if (session !== displayedSession) return null
        return Ticket(tabId, session).also { latest[tabId] = it }
    }

    /** A closed/replaced tab is represented by a null/different currentSession. */
    fun complete(ticket: Ticket<S>, currentSession: S?, displayedSession: S?): Boolean {
        if (latest[ticket.tabId] !== ticket) return false
        latest.remove(ticket.tabId)
        return ticket.session === currentSession && ticket.session === displayedSession
    }

    fun invalidate(tabId: Long) {
        latest.remove(tabId)
    }

    fun clear() {
        latest.clear()
    }
}
