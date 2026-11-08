package com.platito.sheets

import com.platito.config.AppProperties
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Pulls the Google Sheet into Postgres every day (12:00 noon in `platito.sheets.zone` by default). */
@Component
class SheetsSyncJob(
    private val syncService: SheetsSyncService,
    private val properties: AppProperties,
) {
    @Scheduled(cron = "\${platito.sheets.sync-cron}", zone = "\${platito.sheets.zone}")
    @SchedulerLock(name = "sheetsSync", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    fun run() {
        if (!properties.sheets.syncEnabled) return
        syncService.run()
    }
}
