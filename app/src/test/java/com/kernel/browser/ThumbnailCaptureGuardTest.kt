package com.kernel.browser

import org.junit.Assert.*
import org.junit.Test

class ThumbnailCaptureGuardTest {
    private val guard = ThumbnailCaptureGuard<Any>()
    private val privateSession = Any()
    private val normalSession = Any()

    @Test fun rejectsNewlySelectedTabWhileViewStillShowsPrivateSession() {
        assertNull(guard.begin(2, normalSession, privateSession))
    }

    @Test fun acceptsCaptureOwnedByDisplayedAndLiveSession() {
        val ticket = guard.begin(1, privateSession, privateSession)!!
        assertTrue(guard.complete(ticket, privateSession, privateSession))
        assertFalse(guard.complete(ticket, privateSession, privateSession))
    }

    @Test fun rejectsCaptureAfterSwitchingTheDisplayedSession() {
        val ticket = guard.begin(1, privateSession, privateSession)!!
        assertFalse(guard.complete(ticket, privateSession, normalSession))
    }

    @Test fun rejectsClosedOrReplacedTabs() {
        val closed = guard.begin(1, privateSession, privateSession)!!
        assertFalse(guard.complete(closed, null, privateSession))
        val replaced = guard.begin(1, privateSession, privateSession)!!
        assertFalse(guard.complete(replaced, normalSession, privateSession))
    }

    @Test fun newestCaptureWinsEvenWhenCallbacksArriveOutOfOrder() {
        val old = guard.begin(1, normalSession, normalSession)!!
        val new = guard.begin(1, normalSession, normalSession)!!
        assertFalse(guard.complete(old, normalSession, normalSession))
        assertTrue(guard.complete(new, normalSession, normalSession))
    }

    @Test fun navigationInvalidatesPendingCapture() {
        val ticket = guard.begin(1, normalSession, normalSession)!!
        guard.invalidate(1)
        assertFalse(guard.complete(ticket, normalSession, normalSession))
    }

    @Test fun destructionInvalidatesAllPendingCaptures() {
        val ticket = guard.begin(1, normalSession, normalSession)!!
        guard.clear()
        assertFalse(guard.complete(ticket, normalSession, normalSession))
    }

    @Test fun aFailedOlderCaptureCannotInvalidateItsReplacement() {
        val old = guard.begin(1, normalSession, normalSession)!!
        val new = guard.begin(1, normalSession, normalSession)!!
        assertFalse(guard.complete(old, null, null))
        assertTrue(guard.complete(new, normalSession, normalSession))
    }
}
