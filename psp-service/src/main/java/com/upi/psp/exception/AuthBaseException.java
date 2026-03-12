package com.upi.psp.exception;


// ROLE: Root of all PSP Service custom exceptions.
//       Every domain-specific exception extends this class.
//
// LOGIC:
//   Extending RuntimeException (unchecked) means:
//   - You don't need to declare 'throws AuthBaseException' on every method.
//   - Spring's @Transactional automatically rolls back on RuntimeException.
//   - GlobalExceptionHandler catches these and converts them to HTTP responses.
//
//   The errorCode field carries a machine-readable code (e.g. "USER_NOT_FOUND")
//   that the client can use to show the right UI message, independent of the
//   human-readable message which may change.
//
//   JAVA CONCEPT: Abstract class — cannot be instantiated directly. Forces
//   subclasses to provide specific error codes via super(message, errorCode).

public class AuthBaseException extends RuntimeException
{
    private final String errorCode;

    protected AuthBaseException(String message, String errorCode)
    {
        super(message);
        this.errorCode = errorCode;
    }
    protected AuthBaseException(String message, String errorCode, Throwable cause)
    {
        super(message, cause);
        this.errorCode = errorCode;
    }
    public String getErrorCode()
    {
        return errorCode;
    }
}
