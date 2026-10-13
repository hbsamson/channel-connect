package com.svi.channelconnect.exception;

public class ProviderException extends RuntimeException {
    private final String category;
    private final String provider;

    public ProviderException(String category) {
        this("openai", category, null);
    }

    public ProviderException(String provider, String category, Throwable cause) {
        super("External request failed: " + provider + "/" + category, cause);
        this.category = category;
        this.provider = provider;
    }

    public String getCategory() {
        return category;
    }

    public String getProvider() {
        return provider;
    }
}
