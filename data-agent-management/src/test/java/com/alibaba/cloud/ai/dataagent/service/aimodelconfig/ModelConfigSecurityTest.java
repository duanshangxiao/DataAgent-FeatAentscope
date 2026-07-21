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
package com.alibaba.cloud.ai.dataagent.service.aimodelconfig;

import com.alibaba.cloud.ai.dataagent.dto.ModelConfigDTO;
import com.alibaba.cloud.ai.dataagent.entity.ModelConfig;
import com.alibaba.cloud.ai.dataagent.enums.ModelType;
import com.alibaba.cloud.ai.dataagent.mapper.ModelConfigMapper;
import com.alibaba.cloud.ai.dataagent.util.SensitiveValueUtil;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelConfigSecurityTest {

	@Test
	void listMasksSecretsWhileActiveConfigKeepsRuntimeValues() {
		ModelConfigMapper mapper = mock(ModelConfigMapper.class);
		ModelConfigDataServiceImpl service = new ModelConfigDataServiceImpl(mapper);
		ModelConfig entity = storedConfig();
		when(mapper.findAll()).thenReturn(List.of(entity));
		when(mapper.selectActiveByType("CHAT")).thenReturn(entity);

		ModelConfigDTO listed = service.listConfigs().get(0);
		ModelConfigDTO active = service.getActiveConfigByType(ModelType.CHAT);

		assertEquals(SensitiveValueUtil.MASK, listed.getApiKey());
		assertEquals(SensitiveValueUtil.MASK, listed.getProxyPassword());
		assertEquals("real-api-key", active.getApiKey());
		assertEquals("real-proxy-password", active.getProxyPassword());
	}

	@Test
	void updatePreservesStoredSecretsWhenClientSendsMasks() {
		ModelConfigMapper mapper = mock(ModelConfigMapper.class);
		ModelConfigDataServiceImpl service = new ModelConfigDataServiceImpl(mapper);
		ModelConfig entity = storedConfig();
		when(mapper.findById(1)).thenReturn(entity);
		ModelConfigDTO dto = baseDto();
		dto.setApiKey(SensitiveValueUtil.MASK);
		dto.setProxyPassword(SensitiveValueUtil.MASK);

		service.updateConfigInDb(dto);

		assertEquals("real-api-key", entity.getApiKey());
		assertEquals("real-proxy-password", entity.getProxyPassword());
		verify(mapper).updateById(entity);
	}

	@Test
	void connectionTestHydratesMaskedSecretsFromStoredConfig() {
		ModelConfigDataService dataService = mock(ModelConfigDataService.class);
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		ChatModel chatModel = mock(ChatModel.class);
		when(dataService.findById(1)).thenReturn(storedConfig());
		when(modelFactory.createChatModel(any(ModelConfigDTO.class))).thenReturn(chatModel);
		when(chatModel.call("Hello")).thenReturn("ok");
		ModelConfigOpsService service = new ModelConfigOpsService(dataService, modelFactory, mock(AiModelRegistry.class));
		ModelConfigDTO dto = baseDto();
		dto.setApiKey(SensitiveValueUtil.MASK);
		dto.setProxyPassword(SensitiveValueUtil.MASK);

		service.testConnection(dto);

		assertEquals("real-api-key", dto.getApiKey());
		assertEquals("real-proxy-password", dto.getProxyPassword());
	}

	private ModelConfig storedConfig() {
		ModelConfig entity = new ModelConfig();
		entity.setId(1);
		entity.setProvider("openai");
		entity.setBaseUrl("https://example.test");
		entity.setApiKey("real-api-key");
		entity.setModelName("chat-model");
		entity.setModelType(ModelType.CHAT);
		entity.setProxyPassword("real-proxy-password");
		return entity;
	}

	private ModelConfigDTO baseDto() {
		return ModelConfigDTO.builder()
			.id(1)
			.provider("openai")
			.baseUrl("https://example.test")
			.modelName("chat-model")
			.modelType("CHAT")
			.build();
	}

}
