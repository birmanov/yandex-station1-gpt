/**
 * PROVISIONAL: a no-op until the device spike (SPIKE.md) shows whether the stock assistant must
 * be paused to free the microphone and speakers. If it must, acquire() stops
 * ru.yandex.quasar.app and release() starts it again; if AudioRecord/AudioTrack work alongside
 * it, this stays empty. Either way release() never throws.
 */
public final class QuasarAliceControl implements AliceControl {
  public void acquire() throws Exception {
  }

  public void release() {
  }
}
