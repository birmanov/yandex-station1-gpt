/**
 * Hands the microphone and speakers from the stock assistant to the GPT mode and back. What
 * acquire/release actually do on the Station is decided by the device spike (see SPIKE.md).
 */
public interface AliceControl {
  void acquire() throws Exception;

  /** Must be safe to call after a failed acquire and must not throw. */
  void release();
}
