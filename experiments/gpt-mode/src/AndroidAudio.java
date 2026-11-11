import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Microphone and speaker through android.media.AudioRecord / AudioTrack, reached by reflection so
 * the class compiles on the PC and binds on the Station at runtime (same approach as the remote's
 * InputManager use). Constants are the API 23 values.
 */
public final class AndroidAudio implements AudioIn, AudioOut {
  static final int SOURCE_MIC = 1, CHANNEL_IN_MONO = 16, CHANNEL_OUT_MONO = 4, ENCODING_PCM_16BIT = 2,
      STREAM_MUSIC = 3, MODE_STREAM = 1, STATE_INITIALIZED = 1, SAMPLE_RATE_IN = 16000, CHUNK = 10240;

  final Class<?> recordClass, trackClass;

  public AndroidAudio() {
    try {
      recordClass = Class.forName("android.media.AudioRecord");
      trackClass = Class.forName("android.media.AudioTrack");
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("Android audio API unavailable", e);
    }
  }

  public byte[] record(int maxMillis, int silenceMillis, int silenceRms, Cancel cancel) throws Exception {
    int minBuffer = (Integer) recordClass.getMethod("getMinBufferSize", int.class, int.class, int.class)
        .invoke(null, SAMPLE_RATE_IN, CHANNEL_IN_MONO, ENCODING_PCM_16BIT);
    if (minBuffer <= 0) throw new IOException("AudioRecord unsupported format");
    Object rec = recordClass.getConstructor(int.class, int.class, int.class, int.class, int.class)
        .newInstance(SOURCE_MIC, SAMPLE_RATE_IN, CHANNEL_IN_MONO, ENCODING_PCM_16BIT, Math.max(minBuffer, CHUNK) * 4);
    Method read = recordClass.getMethod("read", byte[].class, int.class, int.class);
    try {
      if ((Integer) recordClass.getMethod("getState").invoke(rec) != STATE_INITIALIZED)
        throw new IOException("AudioRecord not initialized (microphone busy?)");
      recordClass.getMethod("startRecording").invoke(rec);
      SilenceDetector detector = new SilenceDetector(SAMPLE_RATE_IN, silenceRms, silenceMillis);
      ByteArrayOutputStream pcm = new ByteArrayOutputStream();
      long maxBytes = (long) maxMillis * SAMPLE_RATE_IN * 2 / 1000;
      byte[] chunk = new byte[CHUNK];
      while (pcm.size() < maxBytes && !cancel.requested()) {
        int n = (Integer) read.invoke(rec, chunk, 0, chunk.length);
        if (n < 0) throw new IOException("AudioRecord read failed: " + n);
        if (n == 0) continue;
        byte[] got = new byte[n];
        System.arraycopy(chunk, 0, got, 0, n);
        pcm.write(got, 0, n);
        if (detector.feed(got)) break;
      }
      return detector.heardSpeech() ? pcm.toByteArray() : new byte[0];
    } finally {
      try { recordClass.getMethod("stop").invoke(rec); } catch (Exception ignored) { }
      try { recordClass.getMethod("release").invoke(rec); } catch (Exception ignored) { }
    }
  }

  public void play(byte[] pcm, int sampleRate, Cancel cancel) throws Exception {
    int minBuffer = (Integer) trackClass.getMethod("getMinBufferSize", int.class, int.class, int.class)
        .invoke(null, sampleRate, CHANNEL_OUT_MONO, ENCODING_PCM_16BIT);
    if (minBuffer <= 0) throw new IOException("AudioTrack unsupported format");
    int buffer = Math.max(minBuffer, 8192);
    Object track = trackClass.getConstructor(int.class, int.class, int.class, int.class, int.class, int.class)
        .newInstance(STREAM_MUSIC, sampleRate, CHANNEL_OUT_MONO, ENCODING_PCM_16BIT, buffer, MODE_STREAM);
    Method write = trackClass.getMethod("write", byte[].class, int.class, int.class);
    try {
      if ((Integer) trackClass.getMethod("getState").invoke(track) != STATE_INITIALIZED)
        throw new IOException("AudioTrack not initialized");
      trackClass.getMethod("play").invoke(track);
      int at = 0;
      while (at < pcm.length && !cancel.requested()) {
        int n = (Integer) write.invoke(track, pcm, at, Math.min(4096, pcm.length - at));
        if (n < 0) throw new IOException("AudioTrack write failed: " + n);
        at += n;
      }
      if (!cancel.requested()) Thread.sleep(buffer * 1000L / (sampleRate * 2) + 50);
    } finally {
      try { trackClass.getMethod("stop").invoke(track); } catch (Exception ignored) { }
      try { trackClass.getMethod("release").invoke(track); } catch (Exception ignored) { }
    }
  }
}
