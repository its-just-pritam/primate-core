package com.platito.garmin

import com.platito.config.AppProperties
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Periodically syncs today and yesterday for every user with a Garmin connection. */
@Component
class GarminSyncJob(
    private val syncService: GarminSyncService,
    private val properties: AppProperties,
) {
    @Scheduled(cron = "\${platito.garmin.sync-cron}", zone = "UTC")
    @SchedulerLock(name = "garminSync", lockAtMostFor = "PT25M", lockAtLeastFor = "PT1M")
    fun run() {
        if (!properties.garmin.syncEnabled) return
        syncService.syncAllUsers()
    }
}
