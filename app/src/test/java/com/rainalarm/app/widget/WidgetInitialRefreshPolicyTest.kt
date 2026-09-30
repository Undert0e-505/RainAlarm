package com.rainalarm.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetInitialRefreshPolicyTest {
    @Test fun `first and only fixed widget retains its acquisition registration`() {
        val groups = WidgetInitialRefreshPolicy.coalesce(
            listOf(WidgetRefreshRegistration(7, "fixed-a")),
        )

        assertEquals(setOf(7), groups["fixed-a"])
    }

    @Test fun `pending widget acquisition runs regardless of app visibility`() {
        assertEquals(
            WidgetInitialRefreshPolicy.WorkerDirective.RUN,
            WidgetInitialRefreshPolicy.workerDirective(hasPendingRefresh = true, appVisible = true),
        )
        assertEquals(
            WidgetInitialRefreshPolicy.WorkerDirective.FINISH,
            WidgetInitialRefreshPolicy.workerDirective(hasPendingRefresh = false, appVisible = true),
        )
        assertEquals(
            WidgetInitialRefreshPolicy.WorkerDirective.RUN,
            WidgetInitialRefreshPolicy.workerDirective(hasPendingRefresh = true, appVisible = false),
        )
    }

    @Test fun `distinct saved registrations coexist without waking each other`() {
        val groups = WidgetInitialRefreshPolicy.coalesce(
            listOf(
                WidgetRefreshRegistration(1, "fixed-a"),
                WidgetRefreshRegistration(2, "fixed-b"),
            ),
        )

        assertEquals(setOf("fixed-a", "fixed-b"), groups.keys)
        assertEquals(setOf(1), groups["fixed-a"])
        assertEquals(setOf(2), groups["fixed-b"])
    }

    @Test fun `two distinct fixed targets both retain initial demand`() {
        val groups = WidgetInitialRefreshPolicy.coalesce(
            listOf(
                WidgetRefreshRegistration(3, "fixed-a"),
                WidgetRefreshRegistration(4, "fixed-b"),
            ),
        )

        assertEquals(2, groups.size)
        assertTrue(groups.values.flatten().containsAll(listOf(3, 4)))
    }

    @Test fun `identical targets coalesce without starving either widget`() {
        val groups = WidgetInitialRefreshPolicy.coalesce(
            listOf(
                WidgetRefreshRegistration(5, "fixed-a"),
                WidgetRefreshRegistration(6, "fixed-a"),
            ),
        )

        assertEquals(1, groups.size)
        assertEquals(setOf(5, 6), groups.getValue("fixed-a"))
    }

    @Test fun `transient failures retry to a bounded terminal result and success completes`() {
        assertEquals(
            WidgetInitialRefreshPolicy.AttemptDisposition.RETRY,
            WidgetInitialRefreshPolicy.attemptDisposition(false, 1),
        )
        assertEquals(
            WidgetInitialRefreshPolicy.AttemptDisposition.RETRY,
            WidgetInitialRefreshPolicy.attemptDisposition(false, 2),
        )
        assertEquals(
            WidgetInitialRefreshPolicy.AttemptDisposition.TERMINAL_FAILURE,
            WidgetInitialRefreshPolicy.attemptDisposition(false, 3),
        )
        assertEquals(
            WidgetInitialRefreshPolicy.AttemptDisposition.COMPLETE,
            WidgetInitialRefreshPolicy.attemptDisposition(true, 3),
        )
    }

    @Test fun `deleted widget is absent from the next coalesced plan`() {
        val registrations = listOf(
            WidgetRefreshRegistration(8, "fixed-a"),
            WidgetRefreshRegistration(9, "fixed-b"),
        )
        val activeIds = setOf(9)

        val groups = WidgetInitialRefreshPolicy.coalesce(
            registrations.filter { it.widgetId in activeIds },
        )

        assertEquals(setOf("fixed-b"), groups.keys)
        assertEquals(setOf(9), groups.getValue("fixed-b"))
    }
}
