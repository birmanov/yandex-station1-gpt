import java.io.IOException;

/** An HTTP-level failure from OpenAI with the status the client decided on. */
public final class ApiError extends IOException {
  public final int status;

  public ApiError(int status, String message) {
    super(message);
    this.status = status;
  }
}
