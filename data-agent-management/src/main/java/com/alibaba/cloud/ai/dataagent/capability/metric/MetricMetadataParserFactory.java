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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
class MetricMetadataParserFactory {

	private final Map<String, MetricMetadataParser> parsers;

	public MetricMetadataParserFactory(List<MetricMetadataParser> parserList) {
		parsers = new LinkedHashMap<>();
		for (MetricMetadataParser parser : parserList) {
			parsers.put(parser.formatName(), parser);
		}
	}

	public MetricMetadataParser getParser(String format) {
		MetricMetadataParser parser = parsers.get(format);
		if (parser == null) {
			throw new IllegalArgumentException("Unknown metric metadata parser format: " + format);
		}
		return parser;
	}

	boolean hasFormat(String format) {
		return parsers.containsKey(format);
	}

}
