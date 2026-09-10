package com.nxr.platform.notifications;

public interface NotificationTransport {

    boolean isConfigured();

    void send(NotificationMessage message) throws NotificationTransportException;

    final class NotificationTransportException extends Exception {
        private final String errorCode;

        public NotificationTransportException(String errorCode, Throwable cause) {
            super(errorCode, cause);
            this.errorCode = errorCode;
        }

        public NotificationTransportException(String errorCode) {
            super(errorCode);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
