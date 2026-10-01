package org.nem.nis.dao;

import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@ComponentScan(basePackages = "org.nem.nis.dao", excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, value = org.springframework.stereotype.Controller.class))
@EnableTransactionManagement
public class TestConfHardDisk extends TestConf {
	@Bean
	public DataSource dataSource() {
		final DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName("org.h2.Driver");
		dataSource.setUrl(H2Database.getJdbcUrl("test") + ";TRACE_LEVEL_FILE=2");
		return dataSource;
	}

	@Bean
	TestDatabase testDatabase() {
		return new TestDatabase();
	}
}
