/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import axios from 'axios';

const API_BASE_URL = '/api/metric-capability';

export interface MetricParameter {
  name: string;
  location: string;
  jsonPath: string;
  type: string;
  required: boolean;
  description: string;
  enumValues: string[];
  example: string;
  defaultValue: unknown;
}

export interface MetricDefinition {
  metricCode: string;
  metricName: string;
  aliases: string[];
  summary: string;
  description: string;
  operationId: string;
  httpMethod: string;
  path: string;
  requestParameters: MetricParameter[];
  supportedGranularities: string[];
  supportedDimensions: string[];
  supportedFilters: string[];
  examples: string[];
  tags: string[];
  lastSyncTime: number;
}

export interface MetricCapabilityStatus {
  ready: boolean;
  definitionCount: number;
  state: string;
  lastRefreshSuccessTime: number;
  lastRefreshSuccessSecondsAgo: number;
  lastRefreshFailureTime: number;
  lastRefreshError: string;
  circuitBreakerState: string;
}

class MetricCapabilityService {
  async status(): Promise<MetricCapabilityStatus> {
    const response = await axios.get<MetricCapabilityStatus>(`${API_BASE_URL}/status`);
    return response.data;
  }

  async definitions(): Promise<MetricDefinition[]> {
    const response = await axios.get<MetricDefinition[]>(`${API_BASE_URL}/definitions`);
    return response.data;
  }

  async refresh(): Promise<{ success: boolean; message: string }> {
    const response = await axios.post<{ success: boolean; message: string }>(`${API_BASE_URL}/refresh`);
    return response.data;
  }
}

export default new MetricCapabilityService();
