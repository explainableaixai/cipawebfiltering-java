package com.alphaquantum.cipawebfiltering;

/** Thrown when the API answers with a non-2xx status. */
public final class ApiException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final int status;

  /**
   * @param status HTTP status
   * @param message message sent by the API
   */
  public ApiException(int status, String message) {
    super("HTTP " + status + ": " + message);
    this.status = status;
  }

  /** @return the HTTP status code */
  public int status() {
    return status;
  }
}
