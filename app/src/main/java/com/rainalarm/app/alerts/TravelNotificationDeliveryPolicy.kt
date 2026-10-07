package com.rainalarm.app.alerts

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-scoped mirror of the main app's Travel request. The ViewModel remains authoritative;
 * this thread-safe bridge lets monitoring transactions make the final delivery decision without
 * persisting Travel state or coupling widget work to app visibility.
 */
object TravelNotificationDeliveryState {
    private val active = AtomicBoolean(false)

    val isActive: Boolean
        get() = active.get()

    fun update(followRequested: Boolean) {
        active.set(followRequested)
    }
}

/**
 * Active Travel consumes only main-app delivery components. It deliberately leaves widget
 * candidates unchanged and creates no deliverable component from which a cross-source claim
 * could be recorded.
 */
object TravelNotificationDeliveryPolicy {
    fun apply(
        sourceClass: NotificationSourceClass,
        travelActive: Boolean,
        resolution: MonitoringDeliveryResolution,
    ): MonitoringDeliveryResolution {
        if (!travelActive || sourceClass != NotificationSourceClass.APP) return resolution
        return resolution.copy(
            deliverRainEvent = null,
            deliverLightningEvent = null,
            consumeRainEvent = resolution.consumeRainEvent ?: resolution.deliverRainEvent,
            consumeLightningEvent = resolution.consumeLightningEvent ?:
                resolution.deliverLightningEvent,
        )
    }
}
