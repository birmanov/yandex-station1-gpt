import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One question at a time: record, transcribe, ask, synthesize, play, hand audio back to the stock
 * assistant. Owns the state shown on the remote's page. Runs the chain in its own thread so HTTP
 * handlers never block; a watchdog ends a run that overstays {@code watchdogMillis}.
 */
public final class GptSession {
  public enum State { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

  static final int SAMPLE_RATE_IN = 16000, SAMPLE_RATE_OUT = 24000, MAX_TOKENS = 300, ANSWER_SHOWN = 2000;
  static final String NO_SPEECH = "Ничего не услышал", EMPTY_TTS = "Пустой ответ озвучки",
      TIMEOUT = "Превышено время ожидания", NOT_CONFIGURED = "Ключ OpenAI не задан", BUSY = "Уже выполняется";

  final GptConfig cfg;
  final AudioIn in;
  final AudioOut out;
  final AliceControl alice;
  final ScheduledExecutorService scheduler;
  OpenAiClient api;
  final List<String[]> history = new ArrayList<String[]>();
  volatile State state = State.IDLE;
  volatile String question = "", answer = "", error = "";
  volatile long since = System.currentTimeMillis();
  Run current;

  public GptSession(GptConfig cfg, AudioIn in, AudioOut out, OpenAiClient api, AliceControl alice, ScheduledExecutorService scheduler) {
    this.cfg = cfg;
    this.in = in;
    this.out = out;
    this.api = api;
    this.alice = alice;
    this.scheduler = scheduler;
  }

  /** Replaces the client for later runs, e.g. after the key changed; a running question keeps its own. */
  public synchronized void setClient(OpenAiClient client) {
    this.api = client;
  }

  public synchronized void start() {
    if (!cfg.configured()) throw new IllegalStateException(NOT_CONFIGURED);
    if (current != null && !current.finished) throw new IllegalStateException(BUSY);
    Run run = new Run(api);
    current = run;
    error = "";
    run.thread = new Thread(run, "gpt-session");
    run.thread.setDaemon(true);
    run.watchdog = scheduler.schedule(new Runnable() {
      public void run() {
        current().timeout();
      }
    }, cfg.watchdogMillis(), TimeUnit.MILLISECONDS);
    run.thread.start();
  }

  /** While listening: finish recording and answer. Otherwise: abandon the current question. */
  public void stop() {
    Run run = current();
    if (run == null || run.finished) return;
    if (state == State.LISTENING) run.stopRequested = true;
    else run.cancelRequested = true;
  }

  public void cancel() {
    Run run = current();
    if (run != null) run.cancelRequested = true;
  }

  /** Starts a new conversation; only between questions. */
  public synchronized void reset() {
    if (current != null && !current.finished) throw new IllegalStateException(BUSY);
    synchronized (history) {
      history.clear();
    }
    question = "";
    answer = "";
    error = "";
  }

  public State state() {
    return state;
  }

  public String stateJson() {
    String shown = answer.length() > ANSWER_SHOWN ? answer.substring(0, ANSWER_SHOWN) + "…" : answer;
    return "{\"configured\":" + cfg.configured()
        + ",\"state\":" + MiniJson.quote(state.name().toLowerCase(Locale.US))
        + ",\"question\":" + MiniJson.quote(question)
        + ",\"answer\":" + MiniJson.quote(shown)
        + ",\"error\":" + MiniJson.quote(error)
        + ",\"since\":" + since + "}";
  }

  /** Blocks until the current question has finished (or {@code millis} elapsed). */
  public synchronized void awaitIdle(long millis) throws InterruptedException {
    long end = System.currentTimeMillis() + millis;
    while (current != null && !current.finished) {
      long left = end - System.currentTimeMillis();
      if (left <= 0) return;
      wait(left);
    }
  }

  synchronized Run current() {
    return current;
  }

  final class Run implements Runnable {
    final OpenAiClient client;
    volatile boolean stopRequested, cancelRequested, timedOut, finished, released;
    Thread thread;
    ScheduledFuture<?> watchdog;
    final Cancel recordCancel = new Cancel() {
      public boolean requested() {
        return stopRequested || cancelRequested;
      }
    };
    final Cancel playCancel = new Cancel() {
      public boolean requested() {
        return cancelRequested;
      }
    };

    Run(OpenAiClient client) {
      this.client = client;
    }

    public void run() {
      try {
        alice.acquire();
        setState(State.LISTENING);
        byte[] pcm = in.record(cfg.maxRecordSeconds() * 1000, cfg.silenceMillis(), cfg.silenceRms(), recordCancel);
        if (cancelRequested) { finish(State.IDLE, ""); return; }
        if (pcm == null || pcm.length == 0) { finish(State.IDLE, NO_SPEECH); return; }
        setState(State.THINKING);
        String q = client.transcribe(WavCodec.wrapPcm16(pcm, SAMPLE_RATE_IN, 1), cfg.sttModel(), "ru", cfg.sttTimeoutMillis());
        if (cancelRequested) { finish(State.IDLE, ""); return; }
        if (q == null || q.trim().isEmpty()) { finish(State.IDLE, NO_SPEECH); return; }
        q = q.trim();
        question = q;
        answer = "";
        List<String[]> snapshot;
        synchronized (history) {
          snapshot = new ArrayList<String[]>(history);
        }
        String a = client.chat(cfg.chatModel(), cfg.systemPrompt(), snapshot, q, MAX_TOKENS, cfg.chatTimeoutMillis());
        if (cancelRequested) { finish(State.IDLE, ""); return; }
        a = a == null ? "" : a.trim();
        answer = a;
        synchronized (history) {
          history.add(new String[] {q, a});
          while (history.size() > Math.max(0, cfg.historyTurns())) history.remove(0);
        }
        byte[] speech = client.speak(cfg.ttsModel(), cfg.ttsVoice(), a, cfg.ttsTimeoutMillis());
        if (cancelRequested) { finish(State.IDLE, ""); return; }
        if (speech == null || speech.length < 2 || (speech.length & 1) != 0) { finish(State.IDLE, EMPTY_TTS); return; }
        setState(State.SPEAKING);
        out.play(speech, SAMPLE_RATE_OUT, playCancel);
        finish(State.IDLE, "");
      } catch (Throwable t) {
        if (cancelRequested && !timedOut) finish(State.IDLE, "");
        else finish(State.ERROR, message(t));
      } finally {
        releaseOnce();
        if (watchdog != null) watchdog.cancel(false);
      }
    }

    void timeout() {
      timedOut = true;
      cancelRequested = true;
      finish(State.ERROR, TIMEOUT);
      if (thread != null) thread.interrupt();
    }

    void setState(State next) {
      synchronized (GptSession.this) {
        if (finished) return;
        state = next;
        since = System.currentTimeMillis();
      }
    }

    void finish(State next, String message) {
      synchronized (GptSession.this) {
        if (finished) return;
        finished = true;
        state = next;
        error = message;
        since = System.currentTimeMillis();
        GptSession.this.notifyAll();
      }
      releaseOnce();
    }

    synchronized void releaseOnce() {
      if (released) return;
      released = true;
      try {
        alice.release();
      } catch (RuntimeException ignored) {
      }
    }
  }

  static String message(Throwable t) {
    String m = t.getMessage();
    return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
  }
}
