package org.gotson.komga.infrastructure.datasource

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import javax.sql.DataSource

class DataSourcesConfigurationTest {
  @SpringBootTest
  @Nested
  inner class WalMode(
    @Autowired private val dataSourceRW: DataSource,
    @Autowired @Qualifier("sqliteDataSourceRO") private val dataSourceRO: DataSource,
    @Autowired @Qualifier("tasksDataSourceRW") private val tasksDataSourceRW: DataSource,
    @Autowired @Qualifier("tasksDataSourceRO") private val tasksDataSourceRO: DataSource,
  ) {
    @Test
    fun `given wal mode when autoriwiring beans then bean instances are different between RW and RO`() {
      assertThat(dataSourceRW).isNotSameAs(dataSourceRO)
      assertThat(tasksDataSourceRW).isNotSameAs(tasksDataSourceRO)
    }

    @Test
    fun `given file database when autowiring beans then connections use the default lifetime`() {
      val defaultLifetime = HikariConfig().maxLifetime
      listOf(dataSourceRW, dataSourceRO, tasksDataSourceRW, tasksDataSourceRO).forEach {
        assertThat((it as HikariDataSource).maxLifetime).isEqualTo(defaultLifetime)
      }
    }
  }

  @SpringBootTest
  @ActiveProfiles("test", "memorydb")
  @Nested
  inner class MemoryMode(
    @Autowired private val dataSourceRW: DataSource,
    @Autowired @Qualifier("sqliteDataSourceRO") private val dataSourceRO: DataSource,
    @Autowired @Qualifier("tasksDataSourceRW") private val tasksDataSourceRW: DataSource,
    @Autowired @Qualifier("tasksDataSourceRO") private val tasksDataSourceRO: DataSource,
  ) {
    @Test
    fun `given wal mode when autoriwiring beans then bean instances are the same between RW and RO`() {
      assertThat(dataSourceRW).isSameAs(dataSourceRO)
      assertThat(tasksDataSourceRW).isSameAs(tasksDataSourceRO)
    }

    @Test
    fun `given memory database when autowiring beans then connections are never retired`() {
      // an in-memory database only lives as long as its connection
      assertThat((dataSourceRW as HikariDataSource).maxLifetime).isEqualTo(0)
      assertThat((tasksDataSourceRW as HikariDataSource).maxLifetime).isEqualTo(0)
    }
  }
}
