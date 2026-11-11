import java.io.IOException;
import java.util.Arrays;

public final class WavCodecCheck {
  static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static int le16(byte[] b, int at) { return (b[at] & 255) | ((b[at + 1] & 255) << 8); }
  static int le32(byte[] b, int at) { return le16(b, at) | (le16(b, at + 2) << 16); }

  public static void main(String[] args) throws Exception {
    byte[] pcm = {1, 0, 2, 0, 3, 0};
    byte[] wav = WavCodec.wrapPcm16(pcm, 16000, 1);
    require(wav.length == 44 + 6, "header + data");
    require(new String(wav, 0, 4, "US-ASCII").equals("RIFF") && new String(wav, 8, 4, "US-ASCII").equals("WAVE"), "RIFF/WAVE");
    require(le32(wav, 24) == 16000 && le16(wav, 22) == 1 && le16(wav, 34) == 16, "fmt fields");
    require(le32(wav, 40) == 6 && le32(wav, 4) == 36 + 6, "sizes");
    require(le16(wav, 20) == 1 && le32(wav, 28) == 32000 && le16(wav, 32) == 2, "format 1, byte rate, block align");
    require(Arrays.equals(WavCodec.stripWav(wav), pcm), "round trip");
    try { WavCodec.stripWav(new byte[] {1, 2, 3}); require(false, "garbage rejected"); } catch (IOException expected) { }
    System.out.println("PASS WavCodec header, sizes, round trip, garbage rejected");
  }
}
