package com.jobmatchai.backend.exception;

public class InvalidVerificationCodeException extends RuntimeException {
    public InvalidVerificationCodeException() {
        super("That verification code is incorrect or has expired. Please request a new one.");
    }
}
