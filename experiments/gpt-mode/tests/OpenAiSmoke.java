import java.io.*;
import java.util.ArrayList;

/**
 * Manual end-to-end call against the real OpenAI API. Not run by check.ps1 (name does not end
 * with "Check"). Needs OPENAI_API_KEY in the environment and a 16 kHz mono WAV as the argument:
 *
 *   java -cp %TEMP%\gpt-mode-classes OpenAiSmoke question.wav
 *
 * Prints the transcript, the answer and the PCM length; writes nothing to the repository.
 */
public final class OpenAiSmoke {
  public static void main(String[] args) throws Exception {
    String key = System.getenv("OPENAI_API_KEY");
    if (key == null || key.trim().isEmpty()) {
      System.out.println("SKIP OpenAiSmoke: OPENAI_API_KEY not set");
      return;
    }
    if (args.length != 1) throw new IllegalArgumentException("usage: OpenAiSmoke question.wav");
    FileInputStream in = new FileInputStream(args[0]);
    byte[] wav;
    try {
      wav = OpenAiClient.read(in);
    } finally {
      in.close();
    }
    OpenAiClient client = new OpenAiClient("https://api.openai.com", key.trim(), null, System.err);
    String text = client.transcribe(wav, "gpt-4o-mini-transcribe", "ru", 20000);
    System.out.println("transcript: " + text);
    String answer = client.chat("gpt-4o-mini", GptConfig.DEFAULT_SYSTEM_PROMPT, new ArrayList<String[]>(), text, 300, 30000);
    System.out.println("answer: " + answer);
    byte[] pcm = client.speak("gpt-4o-mini-tts", "alloy", answer, 20000);
    System.out.println("pcm bytes: " + pcm.length + " (" + (pcm.length / 48000.0) + " s at 24 kHz)");
  }
}
