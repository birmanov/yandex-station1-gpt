/** Polled by blocking audio operations so a user "stop" ends them promptly. */
public interface Cancel {
  boolean requested();
}
