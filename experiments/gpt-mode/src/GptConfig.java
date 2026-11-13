import java.io.*;
import java.util.Properties;

/**
 * Settings of the GPT mode from {@code gpt.properties} in the remote's root directory. The OpenAI
 * key is held in memory only here and never appears in {@link #toString()} or API responses.
 */
public final class GptConfig {
  public static final String FILE = "gpt.properties";
  public static final String DEFAULT_SYSTEM_PROMPT =
      "Ты голосовой помощник. Отвечай по-русски, кратко, не больше трёх предложений, без списков и разметки.";

  private final File file;
  private final Properties props;

  private GptConfig(File file, Properties props) {
    this.file = file;
    this.props = props;
  }

  public static GptConfig load(File root) throws IOException {
    File file = new File(root, FILE);
    Properties props = new Properties();
    if (file.isFile()) {
      FileInputStream in = new FileInputStream(file);
      try {
        props.load(in);
      } finally {
        in.close();
      }
    }
    return new GptConfig(file, props);
  }

  public String openaiKey() {
    String key = props.getProperty("openaiKey");
    if (key == null) return null;
    key = key.trim();
    return key.isEmpty() ? null : key;
  }

  public boolean configured() {
    return openaiKey() != null;
  }

  /** Trims the key, stores it with owner-only permissions, keeps every other property. */
  public void setOpenaiKey(String key) throws IOException {
    if (key == null || key.trim().isEmpty()) throw new IllegalArgumentException("Empty key");
    props.setProperty("openaiKey", key.trim());
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    props.store(bytes, "Station GPT settings; contains the OpenAI key, never publish");
    PrivateFile.writeAtomic(file, bytes.toByteArray());
  }

  public String audio() { return text("audio", "android"); }
  public String alsaCaptureDevice() { return text("alsaCaptureDevice", ""); }
  public String alsaPlaybackDevice() { return text("alsaPlaybackDevice", ""); }
  public int maxRecordSeconds() { return number("maxRecordSeconds", 15); }
  public int silenceMillis() { return number("silenceMillis", 1200); }
  public int silenceRms() { return number("silenceRms", 600); }
  public int historyTurns() { return number("historyTurns", 6); }
  public String sttModel() { return text("sttModel", "gpt-4o-mini-transcribe"); }
  public String chatModel() { return text("chatModel", "gpt-4o-mini"); }
  public String ttsModel() { return text("ttsModel", "gpt-4o-mini-tts"); }
  public String ttsVoice() { return text("ttsVoice", "alloy"); }
  public String systemPrompt() { return text("systemPrompt", DEFAULT_SYSTEM_PROMPT); }
  public int sttTimeoutMillis() { return number("sttTimeoutMillis", 20000); }
  public int chatTimeoutMillis() { return number("chatTimeoutMillis", 30000); }
  public int ttsTimeoutMillis() { return number("ttsTimeoutMillis", 20000); }
  public int watchdogMillis() { return number("watchdogMillis", 90000); }

  private String text(String key, String fallback) {
    String v = props.getProperty(key);
    return v == null ? fallback : v.trim();
  }

  private int number(String key, int fallback) {
    String v = props.getProperty(key);
    if (v == null) return fallback;
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  @Override
  public String toString() {
    return "GptConfig{configured=" + configured() + ", audio=" + audio() + ", chatModel=" + chatModel()
        + ", sttModel=" + sttModel() + ", ttsModel=" + ttsModel() + ", voice=" + ttsVoice() + "}";
  }
}
