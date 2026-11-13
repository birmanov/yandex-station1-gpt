import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * The three OpenAI calls the GPT mode needs, over HttpsURLConnection with hand-built multipart and
 * JSON. One retry on network errors, 429 and 5xx. The log never receives the key, request or
 * response bodies, or spoken text: only step, status and duration.
 */
public class OpenAiClient {
  static final int MAX_RESPONSE = 8 * 1024 * 1024;

  final String baseUrl;
  final String apiKey;
  final SSLSocketFactory tls;
  final PrintStream log;
  /** Delay before the single retry; tests shorten it. */
  int retryDelayMillis = 2000;

  public OpenAiClient(String baseUrl, String apiKey, SSLSocketFactory tls, PrintStream log) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.apiKey = apiKey;
    this.tls = tls;
    this.log = log;
  }

  public String transcribe(byte[] wav, String model, String language, int timeoutMillis) throws IOException {
    String boundary = "----station-remote-" + Long.toHexString(System.nanoTime());
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    part(body, boundary, "model", model);
    part(body, boundary, "language", language);
    part(body, boundary, "response_format", "json");
    body.write(ascii("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"question.wav\"\r\nContent-Type: audio/wav\r\n\r\n"));
    body.write(wav);
    body.write(ascii("\r\n--" + boundary + "--\r\n"));
    byte[] reply = request("transcribe", "/v1/audio/transcriptions", "multipart/form-data; boundary=" + boundary, body.toByteArray(), timeoutMillis);
    String text = MiniJson.string(MiniJson.parse(utf8(reply)), "text");
    if (text == null) throw new ApiError(502, "Неверный ответ сервиса");
    return text;
  }

  /** {@code history} holds {question, answer} pairs, oldest first. */
  public String chat(String model, String systemPrompt, List<String[]> history, String question, int maxTokens, int timeoutMillis) throws IOException {
    StringBuilder json = new StringBuilder("{\"model\":").append(MiniJson.quote(model)).append(",\"max_tokens\":").append(maxTokens).append(",\"messages\":[");
    json.append("{\"role\":\"system\",\"content\":").append(MiniJson.quote(systemPrompt)).append('}');
    for (String[] turn : history) {
      json.append(",{\"role\":\"user\",\"content\":").append(MiniJson.quote(turn[0])).append('}');
      json.append(",{\"role\":\"assistant\",\"content\":").append(MiniJson.quote(turn[1])).append('}');
    }
    json.append(",{\"role\":\"user\",\"content\":").append(MiniJson.quote(question)).append("}]}");
    byte[] reply = request("chat", "/v1/chat/completions", "application/json", utf8(json.toString()), timeoutMillis);
    String answer = MiniJson.string(MiniJson.parse(utf8(reply)), "choices", "0", "message", "content");
    if (answer == null) throw new ApiError(502, "Неверный ответ сервиса");
    return answer;
  }

  /** Returns raw PCM16 mono at 24 kHz (OpenAI's {@code pcm} format). */
  public byte[] speak(String model, String voice, String text, int timeoutMillis) throws IOException {
    String json = "{\"model\":" + MiniJson.quote(model) + ",\"voice\":" + MiniJson.quote(voice)
        + ",\"input\":" + MiniJson.quote(text) + ",\"response_format\":\"pcm\"}";
    return request("speak", "/v1/audio/speech", "application/json", utf8(json), timeoutMillis);
  }

  byte[] request(String step, String path, String contentType, byte[] body, int timeoutMillis) throws IOException {
    for (int attempt = 1; ; attempt++) {
      long start = System.nanoTime();
      try {
        return once(step, path, contentType, body, timeoutMillis, start);
      } catch (ApiError e) {
        if (attempt == 2 || (e.status != 429 && e.status < 500)) throw e;
      } catch (IOException e) {
        log.println("gpt " + step + " error " + e.getClass().getSimpleName() + " " + ms(start) + "ms");
        if (attempt == 2) throw e;
      }
      try {
        Thread.sleep(retryDelayMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted");
      }
    }
  }

  private byte[] once(String step, String path, String contentType, byte[] body, int timeoutMillis, long start) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
    if (tls != null && c instanceof HttpsURLConnection) ((HttpsURLConnection) c).setSSLSocketFactory(tls);
    c.setConnectTimeout(timeoutMillis);
    c.setReadTimeout(timeoutMillis);
    c.setRequestMethod("POST");
    c.setDoOutput(true);
    c.setFixedLengthStreamingMode(body.length);
    c.setRequestProperty("Authorization", "Bearer " + apiKey);
    c.setRequestProperty("Content-Type", contentType);
    c.setRequestProperty("Accept", "*/*");
    try {
      OutputStream out = c.getOutputStream();
      try {
        out.write(body);
      } finally {
        out.close();
      }
      int status = c.getResponseCode();
      log.println("gpt " + step + " " + status + " " + ms(start) + "ms");
      if (status >= 200 && status < 300) return read(c.getInputStream());
      String message = status == 401 ? "Ключ отклонён" : errorMessage(c, status);
      throw new ApiError(status, message);
    } finally {
      c.disconnect();
    }
  }

  private String errorMessage(HttpURLConnection c, int status) {
    try {
      InputStream err = c.getErrorStream();
      String text = err == null ? null : MiniJson.string(MiniJson.parse(utf8(read(err))), "error", "message");
      if (text != null && text.length() <= 200) return text;
    } catch (IOException ignored) {
    }
    return "Ошибка сервиса " + status;
  }

  static byte[] read(InputStream in) throws IOException {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) > 0) {
        out.write(b, 0, n);
        if (out.size() > MAX_RESPONSE) throw new IOException("Response too large");
      }
      return out.toByteArray();
    } finally {
      in.close();
    }
  }

  private static void part(ByteArrayOutputStream body, String boundary, String name, String value) throws IOException {
    body.write(ascii("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"));
    body.write(utf8(value));
    body.write(ascii("\r\n"));
  }

  static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1000000L;
  }

  static byte[] ascii(String s) throws IOException {
    return s.getBytes("US-ASCII");
  }

  static byte[] utf8(String s) throws IOException {
    return s.getBytes("UTF-8");
  }

  static String utf8(byte[] b) throws IOException {
    return new String(b, "UTF-8");
  }
}
