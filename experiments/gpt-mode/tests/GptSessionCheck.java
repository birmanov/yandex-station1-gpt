import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** GptSession against fake audio, fake OpenAI and a counting AliceControl. No network, no Android. */
public final class GptSessionCheck {
  static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
  static final List<File> roots = new ArrayList<File>();
  static final byte[] SPEECH = new byte[3200];
  static final byte[] TTS = new byte[4800];

  static final class FakeAudioIn implements AudioIn {
    byte[] pcm = SPEECH; long delayMillis; boolean fail; int calls; volatile boolean sawCancel;
    public byte[] record(int maxMillis, int silenceMillis, int silenceRms, Cancel cancel) throws Exception {
      calls++;
      if (fail) throw new IOException("mic busy");
      long end = System.currentTimeMillis() + delayMillis;
      while (System.currentTimeMillis() < end) {
        if (cancel.requested()) { sawCancel = true; break; }
        Thread.sleep(10);
      }
      return pcm;
    }
  }
  static final class FakeAudioOut implements AudioOut {
    byte[] played; int rate; long delayMillis; int calls; volatile boolean sawCancel;
    public void play(byte[] pcm, int sampleRate, Cancel cancel) throws Exception {
      calls++; played = pcm; rate = sampleRate;
      long end = System.currentTimeMillis() + delayMillis;
      while (System.currentTimeMillis() < end) {
        if (cancel.requested()) { sawCancel = true; return; }
        Thread.sleep(10);
      }
    }
  }
  static final class FakeAlice implements AliceControl {
    int acquired, released; boolean failAcquire;
    public void acquire() throws Exception { acquired++; if (failAcquire) throw new IOException("audio focus denied"); }
    public void release() { released++; }
  }
  static final class FakeApi extends OpenAiClient {
    String transcript = "который час"; String answer = "половина третьего"; byte[] pcm = TTS;
    IOException transcribeError; long chatDelayMillis; int transcribeCalls, chatCalls, speakCalls;
    List<String[]> lastHistory; String lastQuestion;
    FakeApi() throws IOException { super("http://127.0.0.1:1", "sk-fixture", null, new PrintStream(new ByteArrayOutputStream(), true, "UTF-8")); }
    @Override public String transcribe(byte[] wav, String model, String language, int timeoutMillis) throws IOException {
      transcribeCalls++;
      require(wav.length == 44 + SPEECH.length || wav.length == 44, "wav wrapped from pcm");
      if (transcribeError != null) throw transcribeError;
      return transcript;
    }
    @Override public String chat(String model, String systemPrompt, List<String[]> history, String question, int maxTokens, int timeoutMillis) throws IOException {
      chatCalls++; lastHistory = new ArrayList<String[]>(history); lastQuestion = question;
      if (chatDelayMillis > 0) try { Thread.sleep(chatDelayMillis); } catch (InterruptedException e) { throw new IOException("interrupted"); }
      return answer;
    }
    @Override public byte[] speak(String model, String voice, String text, int timeoutMillis) {
      speakCalls++; return pcm;
    }
  }

  static GptConfig config(String extra) throws IOException {
    File root = new File(System.getProperty("java.io.tmpdir"), "gpt-session-" + System.nanoTime());
    require(root.mkdirs(), "fixture dir"); roots.add(root);
    PrintStream p = new PrintStream(new FileOutputStream(new File(root, "gpt.properties")), true, "UTF-8");
    p.print("openaiKey=sk-fixture\n" + extra); p.close();
    return GptConfig.load(root);
  }

  static final class Rig {
    final FakeAudioIn in = new FakeAudioIn(); final FakeAudioOut out = new FakeAudioOut();
    final FakeAlice alice = new FakeAlice(); final FakeApi api; final GptSession s;
    Rig(String extra) throws IOException { api = new FakeApi(); s = new GptSession(config(extra), in, out, api, alice, scheduler); }
    void run() throws Exception { s.start(); s.awaitIdle(5000); }
  }

  static void happyPath() throws Exception {
    Rig r = new Rig("");
    require(r.s.state() == GptSession.State.IDLE, "starts idle");
    r.in.delayMillis = 100; r.s.start();
    Thread.sleep(30);
    require(r.s.state() == GptSession.State.LISTENING && r.alice.acquired == 1, "listening after start");
    r.s.awaitIdle(5000);
    require(r.s.state() == GptSession.State.IDLE, "idle after answer");
    require(r.api.transcribeCalls == 1 && r.api.chatCalls == 1 && r.api.speakCalls == 1, "three calls");
    require(r.out.played == TTS && r.out.rate == 24000, "tts played at 24 kHz");
    require(r.alice.released == 1, "alice released once");
    String json = r.s.stateJson();
    require(json.contains("\"state\":\"idle\"") && json.contains("\"question\":\"который час\"") && json.contains("\"answer\":\"половина третьего\"") && json.contains("\"error\":\"\"") && json.contains("\"configured\":true") && json.contains("\"since\":"), "state json: " + json);
    require("который час".equals(r.api.lastQuestion) && r.api.lastHistory.isEmpty(), "first question without history");
  }

  static void noSpeech() throws Exception {
    Rig r = new Rig(""); r.in.pcm = new byte[0]; r.run();
    require(r.s.state() == GptSession.State.IDLE && r.s.stateJson().contains("\"error\":\"Ничего не услышал\""), "no speech message");
    require(r.api.transcribeCalls == 0 && r.api.chatCalls == 0 && r.alice.released == 1, "nothing sent, alice released");
  }

  static void emptyStt() throws Exception {
    Rig r = new Rig(""); r.api.transcript = "  \n"; r.run();
    require(r.s.state() == GptSession.State.IDLE && r.s.stateJson().contains("Ничего не услышал") && r.api.chatCalls == 0 && r.alice.released == 1, "blank transcript");
  }

  static void emptyTts() throws Exception {
    Rig r = new Rig(""); r.api.pcm = new byte[0]; r.run();
    require(r.s.state() == GptSession.State.IDLE && r.s.stateJson().contains("Пустой ответ озвучки") && r.out.calls == 0 && r.alice.released == 1, "empty pcm");
    Rig odd = new Rig(""); odd.api.pcm = new byte[3]; odd.run();
    require(odd.s.stateJson().contains("Пустой ответ озвучки") && odd.out.calls == 0, "odd pcm length");
  }

  static void stopWhileListening() throws Exception {
    Rig r = new Rig(""); r.in.delayMillis = 3000; r.s.start();
    Thread.sleep(50); r.s.stop(); r.s.awaitIdle(5000);
    require(r.in.sawCancel && r.api.chatCalls == 1 && r.out.calls == 1 && r.s.state() == GptSession.State.IDLE, "stop ends recording, answer still spoken");
  }

  static void stopWhileThinking() throws Exception {
    Rig r = new Rig(""); r.api.chatDelayMillis = 500; r.s.start();
    Thread.sleep(80); require(r.s.state() == GptSession.State.THINKING, "thinking during chat");
    r.s.stop(); r.s.awaitIdle(5000);
    require(r.api.speakCalls == 0 && r.out.calls == 0 && r.s.state() == GptSession.State.IDLE && r.alice.released == 1, "stop in thinking drops result");
    require(r.s.stateJson().contains("\"error\":\"\""), "no error on user stop");
  }

  static void stopWhileSpeaking() throws Exception {
    Rig r = new Rig(""); r.out.delayMillis = 3000; r.s.start();
    Thread.sleep(150); require(r.s.state() == GptSession.State.SPEAKING, "speaking");
    r.s.stop(); r.s.awaitIdle(5000);
    require(r.out.sawCancel && r.s.state() == GptSession.State.IDLE && r.alice.released == 1, "stop interrupts playback");
  }

  static void cancelAnywhere() throws Exception {
    Rig r = new Rig(""); r.api.chatDelayMillis = 500; r.s.start();
    Thread.sleep(80); r.s.cancel(); r.s.awaitIdle(5000);
    require(r.api.speakCalls == 0 && r.s.state() == GptSession.State.IDLE && r.alice.released == 1, "cancel from thinking");
    Rig l = new Rig(""); l.in.delayMillis = 3000; l.s.start();
    Thread.sleep(50); l.s.cancel(); l.s.awaitIdle(5000);
    require(l.api.transcribeCalls == 0 && l.s.state() == GptSession.State.IDLE && l.alice.released == 1, "cancel from listening sends nothing");
  }

  static void watchdog() throws Exception {
    Rig r = new Rig("watchdogMillis=300\n"); r.api.chatDelayMillis = 2000; r.s.start();
    r.s.awaitIdle(5000);
    require(r.s.state() == GptSession.State.ERROR && r.s.stateJson().contains("Превышено время ожидания"), "watchdog error: " + r.s.stateJson());
    require(r.alice.released == 1 && r.api.speakCalls == 0, "watchdog releases alice, no speech");
    Thread.sleep(2200);
    require(r.api.speakCalls == 0 && r.alice.released == 1 && r.s.state() == GptSession.State.ERROR, "late chat result discarded");
  }

  static void apiKeyRejected() throws Exception {
    Rig r = new Rig(""); r.api.transcribeError = new ApiError(401, "Ключ отклонён"); r.run();
    require(r.s.state() == GptSession.State.ERROR && r.s.stateJson().contains("\"error\":\"Ключ отклонён\"") && r.s.stateJson().contains("\"configured\":true"), "401 surfaces, key kept");
    require(r.alice.released == 1, "released after api error");
  }

  static void releaseOnException() throws Exception {
    Rig a = new Rig(""); a.alice.failAcquire = true; a.run();
    require(a.s.state() == GptSession.State.ERROR && a.alice.released == 1 && a.api.transcribeCalls == 0, "acquire failure");
    Rig m = new Rig(""); m.in.fail = true; m.run();
    require(m.s.state() == GptSession.State.ERROR && m.alice.released == 1 && m.s.stateJson().contains("mic busy"), "record failure");
  }

  static void startTwice() throws Exception {
    Rig r = new Rig(""); r.in.delayMillis = 500; r.s.start();
    Thread.sleep(50);
    try { r.s.start(); require(false, "second start must be rejected"); } catch (IllegalStateException expected) { }
    r.s.awaitIdle(5000);
    Rig e = new Rig(""); e.alice.failAcquire = true; e.run();
    require(e.s.state() == GptSession.State.ERROR, "in error");
    e.alice.failAcquire = false; e.run();
    require(e.s.state() == GptSession.State.IDLE && e.alice.released == 2, "start allowed from error");
  }

  static void historyLimit() throws Exception {
    Rig r = new Rig("historyTurns=2\n");
    for (int i = 0; i < 4; i++) { r.api.transcript = "вопрос " + i; r.api.answer = "ответ " + i; r.run(); }
    // The fourth question is sent with the history that preceded it: pairs 1 and 2 (pair 0 evicted).
    require(r.api.lastHistory.size() == 2 && "вопрос 1".equals(r.api.lastHistory.get(0)[0]) && "ответ 1".equals(r.api.lastHistory.get(0)[1]) && "вопрос 2".equals(r.api.lastHistory.get(1)[0]) && "вопрос 3".equals(r.api.lastQuestion), "history keeps last two pairs before the current question");
  }

  static void resetClearsHistory() throws Exception {
    Rig r = new Rig(""); r.run(); r.run();
    require(r.api.lastHistory.size() == 1, "history before reset");
    r.s.reset(); r.run();
    require(r.api.lastHistory.isEmpty(), "history cleared by reset");
    r.in.delayMillis = 500; r.s.start(); Thread.sleep(50);
    try { r.s.reset(); require(false, "reset while listening rejected"); } catch (IllegalStateException expected) { }
    r.s.awaitIdle(5000);
  }

  static void unconfigured() throws Exception {
    File root = new File(System.getProperty("java.io.tmpdir"), "gpt-session-none-" + System.nanoTime());
    require(root.mkdirs(), "fixture"); roots.add(root);
    Rig r = new Rig("");
    GptSession s = new GptSession(GptConfig.load(root), r.in, r.out, r.api, r.alice, scheduler);
    try { s.start(); require(false, "unconfigured start rejected"); } catch (IllegalStateException expected) { }
    require(s.state() == GptSession.State.IDLE && s.stateJson().contains("\"configured\":false") && r.alice.acquired == 0, "stays idle");
  }

  static void setClient() throws Exception {
    Rig r = new Rig(""); FakeApi other = new FakeApi(); other.answer = "новый клиент";
    r.s.setClient(other); r.run();
    require(other.chatCalls == 1 && r.api.chatCalls == 0 && r.s.stateJson().contains("новый клиент"), "client replaced");
  }

  public static void main(String[] args) {
    try {
      happyPath(); noSpeech(); emptyStt(); emptyTts(); stopWhileListening(); stopWhileThinking(); stopWhileSpeaking();
      cancelAnywhere(); watchdog(); apiKeyRejected(); releaseOnException(); startTwice(); historyLimit(); resetClearsHistory();
      unconfigured(); setClient();
      System.out.println("PASS GptSession 16 scenarios: flow, stop/cancel per state, watchdog, errors, history, reset, setClient");
    } catch (Throwable t) {
      t.printStackTrace(System.out); System.exit(1);
    } finally {
      scheduler.shutdownNow();
      for (File root : roots) { File[] c = root.listFiles(); if (c != null) for (File f : c) f.delete(); root.delete(); }
    }
  }
}
