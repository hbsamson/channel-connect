package com.svi.channelconnect.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.util.List;

import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIIoException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.services.blocking.ResponseService;
import com.svi.channelconnect.ai.client.OpenAiApiClient;
import com.svi.channelconnect.ai.dto.AiRequest;
import com.svi.channelconnect.ai.exception.AiClientException;
import com.svi.channelconnect.common.exception.ExternalFailureCategory;
import com.svi.channelconnect.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiApiClientTests {
	private OpenAIClient sdkClient;
	private ResponseService responseService;
	private OpenAiApiClient client;

	@BeforeEach
	void setUp() {
		sdkClient = mock(OpenAIClient.class);
		responseService = mock(ResponseService.class);
		when(sdkClient.responses()).thenReturn(responseService);
		client = new OpenAiApiClient(sdkClient, TestProperties.openAi());
	}

	@Test
	void extractsTextFromResponsesApi() {
		Response response = mock(Response.class);
		ResponseOutputText text = ResponseOutputText.builder()
				.text("General knowledge answer")
				.annotations(List.of())
				.logprobs(List.of())
				.build();
		ResponseOutputMessage message = ResponseOutputMessage.builder()
				.id("message-id")
				.status(ResponseOutputMessage.Status.COMPLETED)
				.addContent(text)
				.build();
		when(response.output()).thenReturn(List.of(ResponseOutputItem.ofMessage(message)));
		when(responseService.create(any(ResponseCreateParams.class))).thenReturn(response);

		assertThat(client.generateResponse(new AiRequest("conversation", "Question")).text())
				.isEqualTo("General knowledge answer");
		verify(responseService).create(any(ResponseCreateParams.class));
	}

	@Test
	void rejectsEmptyProviderResponse() {
		Response response = mock(Response.class);
		when(response.output()).thenReturn(List.of());
		when(responseService.create(any(ResponseCreateParams.class))).thenReturn(response);

		assertThatThrownBy(() -> client.generateResponse(new AiRequest("conversation", "Question")))
				.isInstanceOfSatisfying(AiClientException.class,
						exception -> assertThat(exception.category())
							.isEqualTo(ExternalFailureCategory.MALFORMED_RESPONSE));
	}

	@Test
	void categorizesSdkNetworkFailure() {
		OpenAIIoException failure = new OpenAIIoException(
				"request timed out", new SocketTimeoutException("timed out"));
		when(responseService.create(any(ResponseCreateParams.class))).thenThrow(failure);

		assertThatThrownBy(() -> client.generateResponse(new AiRequest("conversation", "Question")))
				.isInstanceOfSatisfying(AiClientException.class,
						exception -> assertThat(exception.category()).isEqualTo(ExternalFailureCategory.TIMEOUT));
	}
	@Test
	void appliesDealerInstructionsJsonOutputAndConfiguredReasoningWithoutProviderStorage() {
		Response response = mock(Response.class);
		when(response.output()).thenReturn(List.of());
		when(responseService.create(any(ResponseCreateParams.class))).thenReturn(response);
		assertThatThrownBy(() -> client.generateResponse(new AiRequest("c", "customer context", "Return a JSON intent", true)))
				.isInstanceOf(AiClientException.class);
		var request = org.mockito.ArgumentCaptor.forClass(ResponseCreateParams.class);
		verify(responseService).create(request.capture());
		assertThat(request.getValue().instructions()).contains("Return a JSON intent");
		assertThat(request.getValue().store()).contains(false);
		assertThat(request.getValue().reasoning().orElseThrow().effort()).contains(com.openai.models.ReasoningEffort.MINIMAL);
		assertThat(request.getValue().maxOutputTokens()).contains(2048L);
		assertThat(request.getValue().text().orElseThrow().format().orElseThrow().isJsonObject()).isTrue();
	}
}
