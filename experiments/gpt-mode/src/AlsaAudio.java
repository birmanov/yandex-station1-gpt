import java.io.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback audio through the firmware's tinyalsa tools: {@code tinycap} records a fixed-length
 * WAV, {@code tinyplay} plays one. Silence-based early stop is not available here; the capture
 * runs for the full duration and speech presence is judged afterwards.
 */
public final class AlsaAudio implements AudioIn, AudioOut {
  /** Runs a command to completion; separated so tests never start real processes. */
  public interface Exec {
    int run(String[] command, Cancel cancel) throws Exception;
  }

  static final Pattern DEVICE = Pattern.compile("hw:(\\d+),(\\d+)");
  static final int SAMPLE_RATE = 16000, CHUNK = 10240;

  final String captureDevice, playbackDevice;
  final File tmpDir;
  final Exec exec;

  public AlsaAudio(String captureDevice, String playbackDevice, File tmpDir) {
    this(captureDevice, playbackDevice, tmpDir, new ProcessExec());
  }

  public AlsaAudio(String captureDevice, String playbackDevice, File tmpDir, Exec exec) {
    this.captureDevice = captureDevice;
    this.playbackDevice = playbackDevice;
    this.tmpDir = tmpDir;
    this.exec = exec;
  }

  static String[] captureCommand(File wav, String device, int seconds) {
    String[] card = card(device);
    return new String[] {"tinycap", wav.getAbsolutePath(), "-D", card[0], "-d", card[1],
        "-c", "1", "-r", String.valueOf(SAMPLE_RATE), "-b", "16", "-T", String.valueOf(seconds)};
  }

  static String[] playCommand(File wav, String device) {
    String[] card = card(device);
    return new String[] {"tinyplay", wav.getAbsolutePath(), "-D", card[0], "-d", card[1]};
  }

  static String[] card(String device) {
    Matcher m = DEVICE.matcher(device == null ? "" : device.trim());
    if (!m.matches()) throw new IllegalArgumentException("ALSA device must look like hw:0,1");
    return new String[] {m.group(1), m.group(2)};
  }

  public byte[] record(int maxMillis, int silenceMillis, int silenceRms, Cancel cancel) throws Exception {
    int seconds = Math.max(1, (maxMillis + 999) / 1000);
    File wav = new File(tmpDir, "capture-" + System.nanoTime() + ".wav");
    try {
      int code = exec.run(captureCommand(wav, captureDevice, seconds), cancel);
      if (code != 0) throw new IOException("tinycap exited with " + code);
      byte[] pcm = WavCodec.stripWav(readAll(wav));
      SilenceDetector detector = new SilenceDetector(SAMPLE_RATE, silenceRms, silenceMillis);
      for (int at = 0; at < pcm.length; at += CHUNK) {
        int n = Math.min(CHUNK, pcm.length - at);
        byte[] chunk = new byte[n];
        System.arraycopy(pcm, at, chunk, 0, n);
        detector.feed(chunk);
      }
      return detector.heardSpeech() ? pcm : new byte[0];
    } finally {
      wav.delete();
    }
  }

  public void play(byte[] pcm, int sampleRate, Cancel cancel) throws Exception {
    File wav = new File(tmpDir, "play-" + System.nanoTime() + ".wav");
    try {
      PrivateFile.writeAtomic(wav, WavCodec.wrapPcm16(pcm, sampleRate, 1));
      int code = exec.run(playCommand(wav, playbackDevice), cancel);
      if (code != 0) throw new IOException("tinyplay exited with " + code);
    } finally {
      wav.delete();
    }
  }

  static byte[] readAll(File f) throws IOException {
    FileInputStream in = new FileInputStream(f);
    try {
      return OpenAiClient.read(in);
    } finally {
      in.close();
    }
  }

  /** Real subprocess runner: drains output, polls for cancel, kills the tool when asked to stop. */
  static final class ProcessExec implements Exec {
    public int run(String[] command, Cancel cancel) throws Exception {
      Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
      p.getOutputStream().close();
      InputStream out = p.getInputStream();
      byte[] sink = new byte[1024];
      try {
        while (true) {
          while (out.available() > 0 && out.read(sink) > 0) { }
          try {
            return p.exitValue();
          } catch (IllegalThreadStateException running) {
          }
          if (cancel.requested()) {
            p.destroy();
            return p.waitFor();
          }
          Thread.sleep(20);
        }
      } finally {
        out.close();
      }
    }
  }
}
