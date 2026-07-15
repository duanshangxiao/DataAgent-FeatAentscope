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
package com.alibaba.cloud.ai.dataagent.capability.metric;

import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Component;

@Component
@ManagedResource(objectName = "com.alibaba.cloud.ai.dataagent:type=MetricCapability", description = "指标目录健康状态")
class MetricCapabilityStatus {

	private volatile boolean ready;

	private volatile int definitionCount;

	private volatile long lastRefreshSuccessTime;

	private volatile long lastRefreshFailureTime;

	private volatile String lastRefreshError;

	private volatile String circuitBreakerState;

	@ManagedAttribute(description = "指标目录是否已就绪")
	public boolean isReady() {
		return ready;
	}

	@ManagedAttribute(description = "当前缓存的指标定义数量")
	public int getDefinitionCount() {
		return definitionCount;
	}

	@ManagedAttribute(description = "最近一次成功刷新时间（epoch ms）")
	public long getLastRefreshSuccessTime() {
		return lastRefreshSuccessTime;
	}

	@ManagedAttribute(description = "最近一次刷新失败时间（epoch ms）")
	public long getLastRefreshFailureTime() {
		return lastRefreshFailureTime;
	}

	@ManagedAttribute(description = "最近一次刷新失败原因")
	public String getLastRefreshError() {
		return lastRefreshError;
	}

	@ManagedAttribute(description = "熔断器当前状态（CLOSED / OPEN / HALF_OPEN）")
	public String getCircuitBreakerState() {
		return circuitBreakerState;
	}

	void markReady(int count) {
		this.ready = count > 0;
		this.definitionCount = count;
	}

	void markRefreshSuccess(int count, long timestamp) {
		this.ready = true;
		this.definitionCount = count;
		this.lastRefreshSuccessTime = timestamp;
	}

	void markRefreshFailure(String error, long timestamp) {
		this.lastRefreshFailureTime = timestamp;
		this.lastRefreshError = error;
	}

	void markCircuitBreakerState(String state) {
		this.circuitBreakerState = state;
	}

}
