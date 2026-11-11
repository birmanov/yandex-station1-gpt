import java.io.IOException;

/** Minimal RIFF/WAVE wrapper for 16-bit PCM. No library; Java 7 / Android 6 friendly. */
public final class WavCodec {
  private WavCodec() { }

  /** Wraps little-endian PCM16 samples in a 44-byte WAV header. */
  public static byte[] wrapPcm16(byte[] pcm, int sampleRate, int channels) {
    int blockAlign = channels * 2;
    byte[] out = new byte[44 + pcm.length];
    ascii(out, 0, "RIFF");
    le32(out, 4, 36 + pcm.length);
    ascii(out, 8, "WAVE");
    ascii(out, 12, "fmt ");
    le32(out, 16, 16);
    le16(out, 20, 1);
    le16(out, 22, channels);
    le32(out, 24, sampleRate);
    le32(out, 28, sampleRate * blockAlign);
    le16(out, 32, blockAlign);
    le16(out, 34, 16);
    ascii(out, 36, "data");
    le32(out, 40, pcm.length);
    System.arraycopy(pcm, 0, out, 44, pcm.length);
    return out;
  }

  /** Returns the payload of the first {@code data} chunk; other chunks are skipped. */
  public static byte[] stripWav(byte[] wav) throws IOException {
    if (wav.length < 12 || !"RIFF".equals(ascii(wav, 0)) || !"WAVE".equals(ascii(wav, 8)))
      throw new IOException("Not a WAV file");
    int at = 12;
    while (at + 8 <= wav.length) {
      String id = ascii(wav, at);
      int size = le32(wav, at + 4);
      at += 8;
      if (size < 0 || at + size > wav.length) throw new IOException("Truncated WAV chunk " + id);
      if ("data".equals(id)) {
        byte[] pcm = new byte[size];
        System.arraycopy(wav, at, pcm, 0, size);
        return pcm;
      }
      at += size + (size & 1);
    }
    throw new IOException("WAV without data chunk");
  }

  static void ascii(byte[] b, int at, String s) {
    for (int i = 0; i < s.length(); i++) b[at + i] = (byte) s.charAt(i);
  }

  static String ascii(byte[] b, int at) {
    return new String(b, at, 4, java.nio.charset.Charset.forName("US-ASCII"));
  }

  static void le16(byte[] b, int at, int v) {
    b[at] = (byte) v;
    b[at + 1] = (byte) (v >>> 8);
  }

  static void le32(byte[] b, int at, int v) {
    le16(b, at, v & 0xFFFF);
    le16(b, at + 2, v >>> 16);
  }

  static int le16(byte[] b, int at) {
    return (b[at] & 255) | ((b[at + 1] & 255) << 8);
  }

  static int le32(byte[] b, int at) {
    return le16(b, at) | (le16(b, at + 2) << 16);
  }
}
