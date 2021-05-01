package com.dinuberinde.stomp.client.exceptions;

import com.dinuberinde.stomp.client.ErrorModel;

/**
 * A network exception which wraps an error.
 */
public class NetworkExceptionResponse extends Exception {
    private final ErrorModel errorModel;

    public NetworkExceptionResponse(ErrorModel errorModel) {
        this.errorModel = errorModel;
    }

    /**
     * Returns the message of the exception.
     */
    @Override
    public String getMessage() {
        return this.errorModel.getMessage();
    }

    /**
     * Returns the fully-qualified name of the class of the exception.
     */
    public String getExceptionClassName() {
        return errorModel.getExceptionClassName();
    }
}
