import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** AlsaAudio argument building and subprocess handling with a fake executor; real ALSA is never touched. */
public final class AlsaAudioCheck {
  static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static final Cancel NEVER = new Cancel() { public boolean requested() { return false; } };

  static final class FakeExec implements AlsaAudio.Exec {
    final List<String[]> commands = new ArrayList<String[]>();
    int exit; byte[] wavToWrite;
    public int run(String[] command, Cancel cancel) throws Exception {
      commands.add(command);
      if (wavToWrite != null) {
        FileOutputStream out = new FileOutputStream(command[1]);
        try { out.write(wavToWrite); } finally { out.close(); }
      }
      return exit;
    }
  }

  public static void main(String[] args) throws Exception {
    File tmp = new File(System.getProperty("java.io.tmpdir"), "gpt-alsa-" + System.nanoTime());
    require(tmp.mkdirs(), "fixture dir");
    try {
      File wav = new File(tmp, "q.wav");
      require(Arrays.equals(AlsaAudio.captureCommand(wav, "hw:0,1", 15),
          new String[] {"tinycap", wav.getAbsolutePath(), "-D", "0", "-d", "1", "-c", "1", "-r", "16000", "-b", "16", "-T", "15"}), "tinycap arguments");
      require(Arrays.equals(AlsaAudio.playCommand(wav, "hw:2,0"),
          new String[] {"tinyplay", wav.getAbsolutePath(), "-D", "2", "-d", "0"}), "tinyplay arguments");
      try { AlsaAudio.captureCommand(wav, "plughw", 1); require(false, "bad device rejected"); } catch (IllegalArgumentException expected) { }
      try { AlsaAudio.captureCommand(wav, "hw:x,1", 1); require(false, "non-numeric device rejected"); } catch (IllegalArgumentException expected) { }

      FakeExec exec = new FakeExec();
      byte[] pcm = {1, 0, 2, 0, 3, 0, 4, 0};
      exec.wavToWrite = WavCodec.wrapPcm16(pcm, 16000, 1);
      AlsaAudio audio = new AlsaAudio("hw:0,1", "hw:0,0", tmp, exec);
      byte[] got = audio.record(2500, 1200, 0, NEVER);
      require(Arrays.equals(got, pcm), "record returns pcm from the wav tinycap wrote");
      require(exec.commands.get(0)[0].equals("tinycap") && exec.commands.get(0)[13].equals("3"), "duration rounded up to whole seconds");
      require(tmp.listFiles().length == 0, "capture file removed after use");
      require(audio.record(2500, 1200, 600, NEVER).length == 0, "quiet capture below the RMS threshold counts as no speech");

      exec.exit = 1; exec.wavToWrite = null;
      try { audio.record(1000, 1200, 600, NEVER); require(false, "non-zero tinycap exit must fail"); }
      catch (IOException e) { require(e.getMessage().contains("tinycap"), "error names the tool"); }

      exec.exit = 0;
      audio.play(new byte[] {9, 0, 8, 0}, 24000, NEVER);
      String[] play = exec.commands.get(exec.commands.size() - 1);
      require(play[0].equals("tinyplay") && play[2].equals("-D") && play[3].equals("0") && play[5].equals("0"), "tinyplay uses the playback device");
      require(tmp.listFiles().length == 0, "playback file removed after use");

      exec.exit = 2;
      try { audio.play(new byte[] {1, 0}, 24000, NEVER); require(false, "non-zero tinyplay exit must fail"); }
      catch (IOException e) { require(e.getMessage().contains("tinyplay"), "error names the tool"); }
      System.out.println("PASS AlsaAudio arguments, device parsing, record/play via subprocess, cleanup, exit codes");
    } finally {
      File[] c = tmp.listFiles(); if (c != null) for (File f : c) f.delete(); tmp.delete();
    }
  }
}
