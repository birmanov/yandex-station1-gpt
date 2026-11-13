public final class SilenceDetectorCheck {
  static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

  /** n zero samples as PCM16. */
  static byte[] silence(int n) { return new byte[n * 2]; }

  /** n samples of a square wave at +/-amp as PCM16. */
  static byte[] tone(int n, int amp) {
    byte[] b = new byte[n * 2];
    for (int i = 0; i < n; i++) {
      int v = (i / 20) % 2 == 0 ? amp : -amp;
      b[i * 2] = (byte) v;
      b[i * 2 + 1] = (byte) (v >> 8);
    }
    return b;
  }

  public static void main(String[] args) {
    SilenceDetector d = new SilenceDetector(16000, 600, 1200);
    require(!d.feed(silence(16000)) && !d.heardSpeech(), "silence before speech never stops");
    require(!d.feed(tone(16000, 8000)) && d.heardSpeech(), "speech detected");
    require(!d.feed(silence(16000)), "1.0 s of silence not enough");
    require(d.feed(silence(4000)), "1.25 s of silence stops");

    SilenceDetector resets = new SilenceDetector(16000, 600, 1200);
    resets.feed(tone(16000, 8000));
    resets.feed(silence(16000));
    require(!resets.feed(tone(1600, 8000)), "speech resets the silence counter");
    require(!resets.feed(silence(16000)), "counter restarted after speech");
    require(resets.feed(silence(4000)), "stops once silence accumulates again");
    System.out.println("PASS SilenceDetector thresholds, start of speech, reset on speech");
  }
}
