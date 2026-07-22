/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
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

export interface MetricApiContract {
  operationId: string;
  httpMethod: string;
  path: string;
  requestParameters: MetricParameter[];
}

export interface MetricCatalogView {
  metricKey: string;
  metricCode: string;
  metricName: string;
  description: string;
  aliases: string[];
  sourceMetricName: string;
  sourceDescription: string;
  sourceAliases: string[];
  localMetricName?: string;
  localDescription?: string;
  localAliases: string[];
  serviceStatus: 'ONLINE' | 'OFFLINE';
  indexStatus: 'PENDING' | 'COMPLETED' | 'FAILED';
  indexError?: string;
  hasLocalOverride: boolean;
  contract: MetricApiContract;
}

export interface MetricCapabilityStatus {
  ready: boolean;
  definitionCount: number;
  metricCount: number;
  onlineCount: number;
  offlineCount: number;
  overrideCount: number;
  generation: string;
  state: string;
  lastRefreshSuccessTime: number;
  lastRefreshSuccessSecondsAgo?: number;
  lastRefreshFailureTime: number;
  lastRefreshError: string;
  circuitBreakerState: string;
}

export interface MetricSearchCandidate {
  metricKey: string;
  metricCode: string;
  metricName: string;
  operationId: string;
  description: string;
  aliases: string[];
  fusedScore?: number;
  rank: number;
  matchedFields: string[];
  knowledgeEnhanced: boolean;
  reason: string;
}

export interface MetricSearchResult {
  decision: 'MATCH' | 'AMBIGUOUS' | 'NO_MATCH';
  summary: string;
  originalQuery: string;
  effectiveQuery: string;
  businessKnowledgeTerms: string[];
  candidates: MetricSearchCandidate[];
}

export interface MetricOverrideRequest {
  localMetricName?: string;
  localDescription?: string;
  localAliases: string[];
}

class MetricCapabilityService {
  async status(): Promise<MetricCapabilityStatus> {
    const response = await axios.get<MetricCapabilityStatus>(`${API_BASE_URL}/status`);
    return response.data;
  }

  async metrics(): Promise<MetricCatalogView[]> {
    const response = await axios.get<MetricCatalogView[]>(`${API_BASE_URL}/metrics`);
    return response.data;
  }

  async saveOverride(
    metricKey: string,
    request: MetricOverrideRequest,
  ): Promise<MetricCatalogView> {
    const response = await axios.put<MetricCatalogView>(
      `${API_BASE_URL}/metrics/${encodeURIComponent(metricKey)}/override`,
      request,
    );
    return response.data;
  }

  async clearOverride(metricKey: string): Promise<MetricCatalogView> {
    const response = await axios.delete<MetricCatalogView>(
      `${API_BASE_URL}/metrics/${encodeURIComponent(metricKey)}/override`,
    );
    return response.data;
  }

  async updateServiceStatus(
    metricKey: string,
    status: 'ONLINE' | 'OFFLINE',
  ): Promise<MetricCatalogView> {
    const response = await axios.put<MetricCatalogView>(
      `${API_BASE_URL}/metrics/${encodeURIComponent(metricKey)}/service-status`,
      { status },
    );
    return response.data;
  }

  async search(query: string, agentId?: number): Promise<MetricSearchResult> {
    const response = await axios.post<MetricSearchResult>(`${API_BASE_URL}/search`, {
      query,
      agentId: agentId == null ? undefined : String(agentId),
      limit: 5,
    });
    return response.data;
  }

  async refresh(): Promise<{ success: boolean; message: string }> {
    const response = await axios.post<{ success: boolean; message: string }>(
      `${API_BASE_URL}/refresh`,
    );
    return response.data;
  }
}

export default new MetricCapabilityService();
