/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.config;

import com.alibaba.cloud.ai.dataagent.properties.PgVectorDatasourceProperties;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * @Author: dsx
 * @Date: 2026/1/4 16:14
 * @Description:
 */
@Configuration
@RequiredArgsConstructor
public class DatasourceConfig {

    private final PgVectorDatasourceProperties pgVectorDatasourceProperties;

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().build();
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "pgVectorDataSource")
    @ConditionalOnProperty(name = "spring.ai.vectorstore.type", havingValue = "pgvector")
    public DataSource pgVectorDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(pgVectorDatasourceProperties.getUrl());
        dataSource.setUsername(pgVectorDatasourceProperties.getUsername());
        dataSource.setPassword(pgVectorDatasourceProperties.getPassword());
        dataSource.setDriverClassName(pgVectorDatasourceProperties.getDriverClassName());
        return dataSource;
    }

    @Bean
    @ConditionalOnBean(name = "pgVectorDataSource")
    public JdbcTemplate pgVectorJdbcTemplate(@Qualifier("pgVectorDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @ConditionalOnProperty(name = "spring.ai.vectorstore.type", havingValue = "pgvector")
    @ConditionalOnBean(name = "pgVectorJdbcTemplate")
    public VectorStore pgVectorStore(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
            EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(pgVectorJdbcTemplate, embeddingModel)
                .initializeSchema(false)
                .dimensions(pgVectorDatasourceProperties.getDimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .schemaName(pgVectorDatasourceProperties.getSchemaName())
                .vectorTableName(pgVectorDatasourceProperties.getTableName())
                .build();
    }
}
