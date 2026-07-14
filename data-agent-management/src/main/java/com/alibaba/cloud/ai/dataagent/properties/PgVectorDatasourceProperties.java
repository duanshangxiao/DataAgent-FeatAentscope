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
package com.alibaba.cloud.ai.dataagent.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @Author: dsx
 * @Date: 2026/6/5 16:50
 * @Description:
 */
@Getter
@Setter
@ConfigurationProperties(prefix = PgVectorStoreProperties.CONFIG_PREFIX)
public class PgVectorDatasourceProperties {

    private String url;
    private String username;
    private String password;
    private String driverClassName;
    private String schemaName = PgVectorStore.DEFAULT_SCHEMA_NAME;
    private String tableName = PgVectorStore.DEFAULT_TABLE_NAME;
    private int dimensions = 1024;
}
