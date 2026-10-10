package com.boydti.fawe.object.exception;

/**
 * Thrown when a task submitted to the main thread does not finish within the requested timeout.
 * The task may still run (or be running) after this is thrown.
 */
public class TaskTimeoutException extends RuntimeException {
    public TaskTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
