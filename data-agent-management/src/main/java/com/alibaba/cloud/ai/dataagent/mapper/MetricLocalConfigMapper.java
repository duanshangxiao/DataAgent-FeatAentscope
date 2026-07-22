/*
 * Copyright 2024-2026 the original author or authors.
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
package com.alibaba.cloud.ai.dataagent.mapper;

import com.alibaba.cloud.ai.dataagent.entity.MetricLocalConfig;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MetricLocalConfigMapper {

	@Select("SELECT * FROM metric_local_config ORDER BY metric_key")
	List<MetricLocalConfig> selectAll();

	@Select("SELECT * FROM metric_local_config WHERE metric_key = #{metricKey}")
	MetricLocalConfig selectByMetricKey(@Param("metricKey") String metricKey);

	@Insert("""
			INSERT INTO metric_local_config
			(metric_key, service_status, index_status, created_time, updated_time)
			VALUES (#{metricKey}, 'ONLINE', 'COMPLETED', NOW(), NOW())
			""")
	int insertDefault(@Param("metricKey") String metricKey);

	@Update("""
			UPDATE metric_local_config
			SET local_metric_name = #{localMetricName}, local_description = #{localDescription},
			    local_aliases = #{localAliases}, index_status = 'PENDING', last_error = NULL,
			    updated_time = NOW()
			WHERE metric_key = #{metricKey}
			""")
	int updateOverride(MetricLocalConfig config);

	@Update("""
			UPDATE metric_local_config
			SET local_metric_name = NULL, local_description = NULL, local_aliases = NULL,
			    index_status = 'PENDING', last_error = NULL, updated_time = NOW()
			WHERE metric_key = #{metricKey}
			""")
	int clearOverride(@Param("metricKey") String metricKey);

	@Update("""
			UPDATE metric_local_config
			SET service_status = #{serviceStatus}, index_status = 'PENDING', last_error = NULL,
			    updated_time = NOW()
			WHERE metric_key = #{metricKey}
			""")
	int updateServiceStatus(@Param("metricKey") String metricKey,
			@Param("serviceStatus") String serviceStatus);

	@Update("""
			UPDATE metric_local_config
			SET index_status = #{indexStatus}, last_error = #{lastError}, updated_time = NOW()
			WHERE metric_key = #{metricKey}
			""")
	int updateIndexStatus(@Param("metricKey") String metricKey, @Param("indexStatus") String indexStatus,
			@Param("lastError") String lastError);

}
