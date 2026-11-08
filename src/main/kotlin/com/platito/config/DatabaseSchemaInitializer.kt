package com.platito.config

import liquibase.integration.spring.SpringLiquibase
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.stereotype.Component

/**
 * Creates the schema Liquibase migrates into (`DATABASE_SCHEMA`) before the migrations run,
 * since Liquibase only creates tables, not the schema holding them.
 */
@Component
class DatabaseSchemaInitializer : BeanPostProcessor {
    override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
        if (bean is SpringLiquibase) {
            val schema = bean.defaultSchema?.takeIf { it.isNotBlank() } ?: return bean
            require(SCHEMA_NAME.matches(schema)) { "DATABASE_SCHEMA must be a plain lowercase identifier, got \"$schema\"" }
            bean.dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("CREATE SCHEMA IF NOT EXISTS $schema") }
            }
        }
        return bean
    }

    private companion object {
        val SCHEMA_NAME = Regex("^[a-z_][a-z0-9_]*$")
    }
}
