package com.svi.channelconnect.sms.goip;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.svi.channelconnect.sms.config.GoipHttpApiProperties;
import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.provider.SmsProvider;

/** Implements the supplied Postman PHP gateway requests without retrying uncertain submissions. */
@Component
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
@ConditionalOnExpression("'${sms.mode:MOCK}' == 'HTTP_API' || ('${sms.mode:MOCK}' == 'HTTP' && ${sms.http-api.receive-enabled:false} && !${sms.http-inbound.enabled:false} && !${sms.web-inbox.enabled:false})")
public class GoipHttpApiClient {
	private final GoipHttpApiProperties properties;
	private final SmsProperties smsProperties;
	private final HttpClient client;

	@Autowired
	public GoipHttpApiClient(GoipHttpApiProperties properties, SmsProperties smsProperties) {
		this(properties, smsProperties, HttpClient.newBuilder().connectTimeout(smsProperties.sessionTimeout()).build());
	}
	GoipHttpApiClient(GoipHttpApiProperties properties, SmsProperties smsProperties, HttpClient client) {
		if (smsProperties.mode() == SmsProperties.Mode.HTTP_API && properties.sendUrl() == null) {
			throw new IllegalArgumentException("SMS HTTP API requires a configured send URL");
		}
		if (properties.receiveUrl() != null && properties.receiveKey().isBlank()) {
			throw new IllegalArgumentException("SMS HTTP API requires a receive key when its receive URL is configured");
		}
		this.properties = properties;
		this.smsProperties = smsProperties;
		this.client = client;
	}

	public SmsProvider.Result send(int line, String number, String message, Runnable beforeTransmission) {
		if (properties.sendUrl() == null) throw new IllegalStateException("SMS HTTP API send URL is not configured");
		String query = "line=" + encode(Integer.toString(line)) + "&message=" + encode(message) + "&number=" + encode(number);
		HttpRequest request = request(properties.sendUrl(), query).POST(HttpRequest.BodyPublishers.noBody()).build();
		beforeTransmission.run();
		try {
			HttpApiResponse response = exchange(request);
			// The observed HTML contains a management form and history, with no correlated submission acknowledgement.
			return new SmsProvider.Result("UNKNOWN", response.statusCode() >= 200 && response.statusCode() < 300
					? "HTTP_API_SUBMISSION_UNCONFIRMED" : "HTTP_API_RESPONSE_UNCONFIRMED");
		} catch (IOException exception) {
			return new SmsProvider.Result("UNKNOWN", "HTTP_API_TRANSMISSION_UNCONFIRMED");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return new SmsProvider.Result("UNKNOWN", "HTTP_API_TRANSMISSION_INTERRUPTED");
		}
	}

	public HttpApiResponse receive() throws IOException, InterruptedException {
		if (properties.receiveUrl() == null || properties.receiveKey().isBlank()) {
			throw new IllegalStateException("SMS HTTP API receive URL and key are not configured");
		}
		return exchange(request(properties.receiveUrl(), "action=message&key=" + encode(properties.receiveKey())).GET().build());
	}

	private HttpRequest.Builder request(URI endpoint, String query) {
		return HttpRequest.newBuilder(URI.create(endpoint.toASCIIString() + "?" + query)).timeout(smsProperties.sessionTimeout());
	}
	private HttpApiResponse exchange(HttpRequest request) throws IOException, InterruptedException {
		CompletableFuture<HttpResponse<byte[]>> pending;
		try {
			pending = client.sendAsync(request, info -> new LimitedBodySubscriber(properties.maxResponseBytes()));
		} catch (RuntimeException exception) {
			throw new IOException("HTTP_API_REQUEST_FAILED");
		}
		try {
			HttpResponse<byte[]> response = pending.get(smsProperties.sessionTimeout().toMillis(), TimeUnit.MILLISECONDS);
			String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
			return new HttpApiResponse(response.statusCode(), body);
		} catch (TimeoutException exception) {
			pending.cancel(true);
			throw new IOException("HTTP_API_REQUEST_TIMEOUT");
		} catch (ExecutionException exception) {
			// HttpClient exception messages can contain a request URI with its private receive key.
			throw new IOException("HTTP_API_REQUEST_FAILED");
		} catch (CharacterCodingException exception) {
			throw new IOException("HTTP_API_RESPONSE_INVALID");
		} catch (RuntimeException exception) {
			throw new IOException("HTTP_API_REQUEST_FAILED");
		} catch (InterruptedException exception) {
			pending.cancel(true);
			Thread.currentThread().interrupt();
			throw exception;
		}
	}
	private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

	public record HttpApiResponse(int statusCode, String body) {
		@Override public String toString() { return "HttpApiResponse[statusCode=" + statusCode + ", body=<redacted>]"; }
	}

	static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
		private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
		private final int maximumBytes;
		private Flow.Subscription subscription;
		private long receivedBytes;
		private boolean completed;
		LimitedBodySubscriber(int maximumBytes) { this.maximumBytes = maximumBytes; }
		@Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
		@Override public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			delegate.onSubscribe(subscription);
		}
		@Override public void onNext(List<ByteBuffer> buffers) {
			if (completed) return;
			for (ByteBuffer buffer : buffers) receivedBytes += buffer.remaining();
			if (receivedBytes > maximumBytes) {
				completed = true;
				subscription.cancel();
				delegate.onError(new IOException("HTTP_API_RESPONSE_TOO_LARGE"));
				return;
			}
			delegate.onNext(buffers);
		}
		@Override public void onError(Throwable error) {
			if (completed) return;
			completed = true;
			delegate.onError(error);
		}
		@Override public void onComplete() {
			if (completed) return;
			completed = true;
			delegate.onComplete();
		}
	}
}
