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
package com.alibaba.cloud.ai.dataagent.agentscope.runtime;

import com.alibaba.cloud.ai.dataagent.agentscope.vo.AgentResponse;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.List;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

public class AgentScopeStreamingHook implements Hook {

	private static final String PLANNER_REASONING_NODE = "planner-reasoning";

	private final String agentId;

	private final String threadId;

	private final AgentRuntimeEventPublisher eventPublisher;

	public AgentScopeStreamingHook(String agentId, String threadId, AgentRuntimeEventPublisher eventPublisher) {
		this.agentId = agentId;
		this.threadId = threadId;
		this.eventPublisher = eventPublisher;
	}

	@Override
	public <T extends HookEvent> Mono<T> onEvent(T event) {
		if (event instanceof ReasoningChunkEvent reasoningChunkEvent) {
			emit(PLANNER_REASONING_NODE, reasoningChunkEvent.getIncrementalChunk().getTextContent());
		}
		else if (event instanceof PreActingEvent preActingEvent) {
			emit(resolveToolNodeName(preActingEvent.getToolUse().getName()),
					"Calling tool: " + preActingEvent.getToolUse().getName());
		}
		else if (event instanceof ActingChunkEvent actingChunkEvent) {
			String text = extractToolResultText(actingChunkEvent.getChunk());
			emit(resolveToolNodeName(actingChunkEvent.getToolUse().getName()),
					text, detectTextType(text));
		}
		else if (event instanceof PostActingEvent postActingEvent) {
			String text = extractToolResultText(postActingEvent.getToolResult());
			emit(resolveToolNodeName(postActingEvent.getToolUse().getName()),
					text, detectTextType(text));
		}
		return Mono.just(event);
	}

	private void emit(String nodeName, String text) {
		emit(nodeName, text, TextType.TEXT);
	}

	private void emit(String nodeName, String text, TextType textType) {
		if (text == null || text.isBlank()) {
			return;
		}
		eventPublisher.publish(AgentResponse.builder()
			.agentId(agentId)
			.threadId(threadId)
			.nodeName(nodeName)
			.textType(textType)
			.text(text)
			.build());
	}

	private static TextType detectTextType(String text) {
		if (text == null || text.isBlank()) {
			return TextType.TEXT;
		}
		String trimmed = text.trim();
		if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
				|| (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
			return TextType.JSON;
		}
		return TextType.TEXT;
	}

	private String resolveToolNodeName(String toolName) {
		return toolName == null || toolName.isBlank() ? "AgentScopeTool" : "tool:" + toolName;
	}

	private String extractToolResultText(ToolResultBlock toolResultBlock) {
		if (toolResultBlock == null) {
			return "";
		}
		List<ContentBlock> output = toolResultBlock.getOutput();
		if (output == null || output.isEmpty()) {
			return "";
		}
		return output.stream()
			.filter(TextBlock.class::isInstance)
			.map(TextBlock.class::cast)
			.map(TextBlock::getText)
			.filter(text -> text != null && !text.isBlank())
			.collect(Collectors.joining(System.lineSeparator()));
	}

}
