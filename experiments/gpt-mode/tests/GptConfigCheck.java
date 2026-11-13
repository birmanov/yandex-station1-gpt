import java.io.*;
import java.util.Properties;

public final class GptConfigCheck {
  static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

  /** POSIX mode via android.system.Os; -1 off-device. */
  static int mode(File f) {
    try {
      Class<?> os = Class.forName("android.system.Os");
      Object stat = os.getMethod("stat", String.class).invoke(null, f.getAbsolutePath());
      return ((Integer) stat.getClass().getField("st_mode").get(stat)).intValue() & 0777;
    } catch (Throwable t) { return -1; }
  }
  static void requireMode(File f, int expected, String what) {
    int m = mode(f);
    if (m < 0) { System.out.println("SKIP mode check (" + what + "): android.system.Os unavailable"); return; }
    require(m == expected, what + " must be " + Integer.toOctalString(expected) + ", found " + Integer.toOctalString(m));
  }
  static void clean(File f) {
    File[] children = f.listFiles(); if (children != null) for (File c : children) clean(c);
    f.delete();
  }

  public static void main(String[] args) throws Exception {
    File root = new File(System.getProperty("java.io.tmpdir"), "gpt-config-" + System.nanoTime());
    require(root.mkdirs(), "fixture directory");
    try {
      GptConfig empty = GptConfig.load(root);
      require(!empty.configured() && empty.openaiKey() == null, "unconfigured by default");
      require(empty.maxRecordSeconds() == 15 && empty.silenceMillis() == 1200 && empty.silenceRms() == 600 && empty.historyTurns() == 6, "numeric defaults");
      require(empty.sttModel().equals("gpt-4o-mini-transcribe") && empty.chatModel().equals("gpt-4o-mini") && empty.ttsModel().equals("gpt-4o-mini-tts") && empty.ttsVoice().equals("alloy"), "model defaults");
      require(empty.audio().equals("android") && empty.alsaCaptureDevice().equals("") && empty.alsaPlaybackDevice().equals(""), "audio defaults");
      require(empty.sttTimeoutMillis() == 20000 && empty.chatTimeoutMillis() == 30000 && empty.ttsTimeoutMillis() == 20000 && empty.watchdogMillis() == 90000, "timeout defaults");
      require(empty.systemPrompt().contains("по-русски"), "default system prompt");

      empty.setOpenaiKey("  sk-fixture-only\n");
      GptConfig loaded = GptConfig.load(root);
      require(loaded.configured() && "sk-fixture-only".equals(loaded.openaiKey()), "key trimmed and persisted");
      require(!loaded.toString().contains("sk-fixture"), "toString hides key");
      require(!new File(root, "gpt.properties.new").exists(), "temp file removed");
      requireMode(new File(root, "gpt.properties"), 0600, "config file");
      Properties p = new Properties();
      FileInputStream in = new FileInputStream(new File(root, "gpt.properties"));
      try { p.load(in); } finally { in.close(); }
      require("sk-fixture-only".equals(p.getProperty("openaiKey")), "stored under openaiKey");

      PrintStream text = new PrintStream(new FileOutputStream(new File(root, "gpt.properties")), true, "UTF-8");
      text.print("openaiKey=sk-fixture-two\nmaxRecordSeconds=7\naudio=alsa\nalsaCaptureDevice=hw:0,1\nhistoryTurns=notanumber\n");
      text.close();
      GptConfig custom = GptConfig.load(root);
      require(custom.maxRecordSeconds() == 7 && custom.audio().equals("alsa") && custom.alsaCaptureDevice().equals("hw:0,1"), "overrides read");
      require(custom.historyTurns() == 6, "unparseable number falls back to default");
      custom.setOpenaiKey("sk-fixture-three");
      GptConfig kept = GptConfig.load(root);
      require(kept.maxRecordSeconds() == 7 && "sk-fixture-three".equals(kept.openaiKey()), "setting the key keeps other properties");

      try { empty.setOpenaiKey("   "); require(false, "blank key rejected"); } catch (IllegalArgumentException expected) { }
      System.out.println("PASS GptConfig defaults, key trim/persist/hide, overrides, blank key rejected");
    } finally { clean(root); }
  }
}
