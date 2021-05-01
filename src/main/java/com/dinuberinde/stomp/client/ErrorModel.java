package com.dinuberinde.stomp.client;

public class ErrorModel {
    /**
     * The message of the exception.
     */
    private final String message;
    /**
     * The fully-qualified name of the class of the exception.
     */
    private final String exceptionClassName;


    public ErrorModel(String message, String exceptionClassName) {
        this.message = message;
        this.exceptionClassName = exceptionClassName;
    }

    public String getExceptionClassName() {
        return exceptionClassName;
    }

    public String getMessage() {
        return message;
    }
}
