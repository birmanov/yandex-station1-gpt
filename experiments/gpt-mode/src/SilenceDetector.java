/**
 * Decides when a recording may stop: after speech was heard, once {@code silenceMillis} of audio
 * below the RMS threshold has accumulated. Any chunk above the threshold restarts the count.
 */
public final class SilenceDetector {
  private final int sampleRate;
  private final int rmsThreshold;
  private final int silenceMillis;
  private boolean heardSpeech;
  private long silentSamples;

  public SilenceDetector(int sampleRate, int rmsThreshold, int silenceMillis) {
    this.sampleRate = sampleRate;
    this.rmsThreshold = rmsThreshold;
    this.silenceMillis = silenceMillis;
  }

  /** Feeds one PCM16 mono chunk; returns true when the recording should stop. */
  public boolean feed(byte[] pcm16) {
    int samples = pcm16.length / 2;
    if (samples == 0) return false;
    if (rms(pcm16, samples) >= rmsThreshold) {
      heardSpeech = true;
      silentSamples = 0;
      return false;
    }
    if (!heardSpeech) return false;
    silentSamples += samples;
    return silentSamples * 1000L >= (long) silenceMillis * sampleRate;
  }

  public boolean heardSpeech() {
    return heardSpeech;
  }

  static double rms(byte[] pcm16, int samples) {
    double sum = 0;
    for (int i = 0; i < samples; i++) {
      int v = (short) ((pcm16[i * 2] & 255) | (pcm16[i * 2 + 1] << 8));
      sum += (double) v * v;
    }
    return Math.sqrt(sum / samples);
  }
}
