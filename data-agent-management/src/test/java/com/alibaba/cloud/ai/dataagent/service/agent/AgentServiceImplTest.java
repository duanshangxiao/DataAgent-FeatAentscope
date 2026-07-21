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
package com.alibaba.cloud.ai.dataagent.service.agent;

import com.alibaba.cloud.ai.dataagent.agentscope.template.CommonAgent;
import com.alibaba.cloud.ai.dataagent.entity.Agent;
import com.alibaba.cloud.ai.dataagent.mapper.AgentMapper;
import com.alibaba.cloud.ai.dataagent.service.file.FileStorageService;
import com.alibaba.cloud.ai.dataagent.service.vectorstore.AgentVectorStoreService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceImplTest {

	@Test
	void saveAlwaysConvergesAgentTypeToCommonAgent() {
		AgentMapper mapper = mock(AgentMapper.class);
		AgentServiceImpl service = new AgentServiceImpl(mapper, mock(AgentVectorStoreService.class),
				mock(FileStorageService.class));
		Agent agent = new Agent();
		agent.setId(7L);
		agent.setAgentType("legacy-agent");
		when(mapper.findById(7L)).thenReturn(agent);

		Agent saved = service.save(agent);

		assertEquals(CommonAgent.AGENT_TYPE, agent.getAgentType());
		assertEquals(CommonAgent.AGENT_TYPE, saved.getAgentType());
		verify(mapper).updateById(any(Agent.class));
	}

}
