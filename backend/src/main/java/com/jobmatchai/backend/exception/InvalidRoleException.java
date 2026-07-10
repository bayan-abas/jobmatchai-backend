package com.jobmatchai.backend.exception;

public class InvalidRoleException extends RuntimeException {
    public InvalidRoleException() {
        super("Role must be either 'candidate' or 'company'");
    }
}
