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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 轻量级熔断器，不依赖 Resilience4j。
 * <ul>
 * <li>CLOSED → 连续 failureThreshold 次失败 → OPEN</li>
 * <li>OPEN → 经历 openDurationMs 后 → HALF_OPEN</li>
 * <li>HALF_OPEN → halfOpenMaxCalls 次成功 → CLOSED；任一次失败 → OPEN</li>
 * </ul>
 */
@Slf4j
@Component
class MetricCircuitBreaker {

	private volatile State state = State.CLOSED;

	private final MetricCapabilityStatus metricCapabilityStatus;

	private final AtomicInteger failureCount = new AtomicInteger(0);

	private final AtomicLong lastFailureTime = new AtomicLong(0);

	private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0);

	private final AtomicInteger halfOpenTotalCalls = new AtomicInteger(0);

	private volatile int failureThreshold = 3;

	private volatile long openDurationMs = 30_000L;

	private volatile int halfOpenMaxCalls = 2;

	MetricCircuitBreaker(MetricCapabilityStatus metricCapabilityStatus) {
		this.metricCapabilityStatus = metricCapabilityStatus;
		metricCapabilityStatus.markCircuitBreakerState(state.name());
	}

	void configure(int failureThreshold, long openDurationMs, int halfOpenMaxCalls) {
		this.failureThreshold = failureThreshold;
		this.openDurationMs = openDurationMs;
		this.halfOpenMaxCalls = halfOpenMaxCalls;
	}

	boolean allowRequest() {
		switch (state) {
			case CLOSED:
				return true;
			case OPEN:
				if (System.currentTimeMillis() - lastFailureTime.get() >= openDurationMs) {
					transitionHalfOpen();
					return true;
				}
				return false;
			case HALF_OPEN:
				return halfOpenTotalCalls.incrementAndGet() <= halfOpenMaxCalls;
			default:
				return false;
		}
	}

	void recordSuccess() {
		if (state == State.CLOSED) {
			failureCount.set(0);
		}
		else if (state == State.HALF_OPEN) {
			int successes = halfOpenSuccessCount.incrementAndGet();
			if (successes >= halfOpenMaxCalls) {
				transitionClosed();
			}
		}
	}

	void recordFailure() {
		lastFailureTime.set(System.currentTimeMillis());
		switch (state) {
			case CLOSED:
				int failures = failureCount.incrementAndGet();
				if (failures >= failureThreshold) {
					transitionOpen();
				}
				break;
			case HALF_OPEN:
				transitionOpen();
				break;
			case OPEN:
				break;
		}
	}

	boolean isOpen() {
		return state == State.OPEN;
	}

	State getState() {
		return state;
	}

	private void transitionOpen() {
		if (state != State.OPEN) {
			log.warn("Metric circuit breaker OPEN. failureThreshold={}, openDurationMs={}", failureThreshold,
					openDurationMs);
		}
		state = State.OPEN;
		metricCapabilityStatus.markCircuitBreakerState(state.name());
		failureCount.set(0);
		halfOpenSuccessCount.set(0);
		halfOpenTotalCalls.set(0);
	}

	private void transitionHalfOpen() {
		log.info("Metric circuit breaker HALF_OPEN. halfOpenMaxCalls={}", halfOpenMaxCalls);
		state = State.HALF_OPEN;
		metricCapabilityStatus.markCircuitBreakerState(state.name());
		halfOpenSuccessCount.set(0);
		halfOpenTotalCalls.set(0);
	}

	private void transitionClosed() {
		log.info("Metric circuit breaker CLOSED.");
		state = State.CLOSED;
		metricCapabilityStatus.markCircuitBreakerState(state.name());
		failureCount.set(0);
		halfOpenSuccessCount.set(0);
		halfOpenTotalCalls.set(0);
	}

	enum State {
		CLOSED, OPEN, HALF_OPEN
	}

}
