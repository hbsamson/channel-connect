package com.svi.channelconnect.exception;

public class ProviderException extends RuntimeException {
    private final String category;

    public ProviderException(String category) {
        super("Provider request failed: " + category);
        this.category = category;
    }

    public String getCategory() {
        return category;
    }
}
