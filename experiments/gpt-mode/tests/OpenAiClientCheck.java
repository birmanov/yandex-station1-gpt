import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;

/** Exercises OpenAiClient against a scripted local HTTP server; OpenAI itself is never called. */
public final class OpenAiClientCheck {
  static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

  static final class Reply {
    final int status; final byte[] body; final String type; final int delayMillis;
    Reply(int status, String body, String type, int delayMillis) { this(status, utf8(body), type, delayMillis); }
    Reply(int status, byte[] body, String type, int delayMillis) { this.status = status; this.body = body; this.type = type; this.delayMillis = delayMillis; }
  }
  static byte[] utf8(String s) { try { return s.getBytes("UTF-8"); } catch (UnsupportedEncodingException e) { throw new RuntimeException(e); } }

  static final Deque<Reply> script = new ArrayDeque<Reply>();
  static volatile String lastPath, lastAuth, lastContentType;
  static volatile byte[] lastBody;
  static volatile int requests;

  static void next(Reply... replies) { script.clear(); script.addAll(Arrays.asList(replies)); }

  public static void main(String[] args) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", new HttpHandler() {
      public void handle(HttpExchange x) throws IOException {
        requests++;
        lastPath = x.getRequestURI().getPath();
        lastAuth = x.getRequestHeaders().getFirst("Authorization");
        lastContentType = x.getRequestHeaders().getFirst("Content-Type");
        ByteArrayOutputStream buf = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        InputStream in = x.getRequestBody(); while ((n = in.read(b)) != -1) buf.write(b, 0, n);
        lastBody = buf.toByteArray();
        Reply r = script.poll();
        if (r == null) r = new Reply(500, "{\"error\":{\"message\":\"unscripted\"}}", "application/json", 0);
        if (r.delayMillis > 0) try { Thread.sleep(r.delayMillis); } catch (InterruptedException ignored) { }
        x.getResponseHeaders().set("Content-Type", r.type);
        x.sendResponseHeaders(r.status, r.body.length);
        x.getResponseBody().write(r.body); x.close();
      }
    });
    server.start();
    ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
    PrintStream log = new PrintStream(logBytes, true, "UTF-8");
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    try {
      OpenAiClient client = new OpenAiClient(base, "sk-fixture", null, log);
      client.retryDelayMillis = 50;

      byte[] wav = WavCodec.wrapPcm16(new byte[] {1, 0, 2, 0}, 16000, 1);
      next(new Reply(200, "{\"text\":\"привет\"}", "application/json", 0));
      String text = client.transcribe(wav, "gpt-4o-mini-transcribe", "ru", 5000);
      String multipart = new String(lastBody, "ISO-8859-1");
      require("привет".equals(text), "transcribe text");
      require("/v1/audio/transcriptions".equals(lastPath) && "Bearer sk-fixture".equals(lastAuth), "transcribe path and auth");
      require(lastContentType.startsWith("multipart/form-data; boundary="), "multipart content type");
      require(multipart.contains("name=\"file\"; filename=\"question.wav\"") && multipart.contains("name=\"model\"\r\n\r\ngpt-4o-mini-transcribe") && multipart.contains("name=\"language\"\r\n\r\nru"), "multipart fields");
      require(multipart.contains("RIFF"), "wav bytes in body");

      List<String[]> history = new ArrayList<String[]>();
      history.add(new String[] {"раньше", "ответил"});
      next(new Reply(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ответ\"},\"finish_reason\":\"stop\"}]}", "application/json", 0));
      String answer = client.chat("gpt-4o-mini", "sys", history, "вопрос", 300, 5000);
      String json = new String(lastBody, "UTF-8");
      require("ответ".equals(answer) && "/v1/chat/completions".equals(lastPath), "chat answer and path");
      require(json.contains("\"role\":\"system\"") && json.contains("\"content\":\"sys\"") && json.contains("\"max_tokens\":300") && json.contains("\"model\":\"gpt-4o-mini\""), "chat request fields");
      require(json.indexOf("\"content\":\"раньше\"") < json.indexOf("\"content\":\"ответил\"") && json.indexOf("\"content\":\"ответил\"") < json.indexOf("\"content\":\"вопрос\""), "history order then question");
      require(lastContentType.startsWith("application/json"), "json content type");

      next(new Reply(200, "{\"choices\":[{\"message\":{\"content\":\"обрез\"},\"finish_reason\":\"length\"}]}", "application/json", 0));
      require("обрез".equals(client.chat("gpt-4o-mini", "sys", history, "в", 300, 5000)), "finish_reason=length returns text as is");

      next(new Reply(200, "{\"id\":\"x\"}", "application/json", 0));
      try { client.chat("gpt-4o-mini", "sys", history, "в", 300, 5000); require(false, "no choices must fail"); }
      catch (ApiError e) { require(e.status == 502 && "Неверный ответ сервиса".equals(e.getMessage()), "502 on malformed"); }

      next(new Reply(200, new byte[] {1, 2, 3, 4}, "audio/pcm", 0));
      byte[] pcm = client.speak("gpt-4o-mini-tts", "alloy", "текст", 5000);
      json = new String(lastBody, "UTF-8");
      require(Arrays.equals(pcm, new byte[] {1, 2, 3, 4}) && "/v1/audio/speech".equals(lastPath), "speak bytes and path");
      require(json.contains("\"response_format\":\"pcm\"") && json.contains("\"voice\":\"alloy\"") && json.contains("\"input\":\"текст\"") && json.contains("\"model\":\"gpt-4o-mini-tts\""), "speak request fields");

      requests = 0;
      next(new Reply(401, "{\"error\":{\"message\":\"Incorrect API key provided: sk-fixture\"}}", "application/json", 0));
      try { client.transcribe(wav, "m", "ru", 5000); require(false, "401 must fail"); }
      catch (ApiError e) { require(e.status == 401 && "Ключ отклонён".equals(e.getMessage()), "401 message"); }
      require(requests == 1, "401 is not retried");

      requests = 0;
      next(new Reply(500, "{\"error\":{\"message\":\"boom\"}}", "application/json", 0), new Reply(200, "{\"text\":\"после повтора\"}", "application/json", 0));
      require("после повтора".equals(client.transcribe(wav, "m", "ru", 5000)) && requests == 2, "500 then 200 retried once");

      requests = 0;
      next(new Reply(429, "{\"error\":{\"message\":\"slow down\"}}", "application/json", 0), new Reply(429, "{\"error\":{\"message\":\"slow down\"}}", "application/json", 0));
      try { client.transcribe(wav, "m", "ru", 5000); require(false, "double 429 must fail"); }
      catch (ApiError e) { require(e.status == 429 && requests == 2, "429 retried once then surfaced"); }

      next(new Reply(200, "{\"text\":\"late\"}", "application/json", 2000), new Reply(200, "{\"text\":\"late\"}", "application/json", 2000));
      long start = System.currentTimeMillis();
      try { client.transcribe(wav, "m", "ru", 300); require(false, "timeout must fail"); }
      catch (ApiError e) { require(false, "timeout must be an IOException, not ApiError"); }
      catch (IOException expected) { }
      require(System.currentTimeMillis() - start < 6000, "timeout does not hang");

      String logged = new String(logBytes.toByteArray(), "UTF-8");
      require(!logged.contains("sk-fixture") && !logged.contains("привет") && !logged.contains("ответ") && !logged.contains("текст") && !logged.contains("Incorrect API key"), "log has no secrets or texts");
      require(logged.contains("gpt transcribe 200") && logged.contains("gpt chat 200") && logged.contains("gpt speak 200") && logged.contains("gpt transcribe 401"), "log has step and status");

      File root = new File(System.getProperty("java.io.tmpdir"), "gpt-trust-" + System.nanoTime());
      require(root.mkdirs(), "trust fixture");
      try {
        require(TrustStore.load(new File(root, "openai-ca.pem")) == null, "missing PEM means default trust");
        PrivateFile.writeAtomic(new File(root, "openai-ca.pem"), utf8("not a certificate"));
        try { TrustStore.load(new File(root, "openai-ca.pem")); require(false, "garbage PEM rejected"); } catch (IOException expected) { }
      } finally { new File(root, "openai-ca.pem").delete(); root.delete(); }

      System.out.println("PASS OpenAiClient multipart, chat, speak, 401/429/5xx/timeout, private log; TrustStore");
    } finally {
      server.stop(0);
    }
  }
}
