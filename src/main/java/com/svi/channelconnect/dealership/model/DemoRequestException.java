package com.svi.channelconnect.dealership.model;

/** A rejected business request, distinct from a database or provider failure. */
public class DemoRequestException extends RuntimeException {
	public DemoRequestException(String message) { super(message); }
}
