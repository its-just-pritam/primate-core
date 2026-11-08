package com.platito.config

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.sql.DataSource

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
class AppConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun lockProvider(dataSource: DataSource): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .usingDbTime()
                .build(),
        )

    /** Runs Garmin backfills started by a new connection without holding up the request. */
    @Bean(destroyMethod = "shutdown")
    fun garminBackfillExecutor(): ExecutorService = Executors.newFixedThreadPool(2)
}
