import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** The remote's gpt command dispatch, independent of StationRemote (whose constructor needs Android). */
public final class GptCommandsCheck {
  static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

  static Map<String, String> op(String op) {
    Map<String, String> p = new HashMap<String, String>();
    p.put("action", "gpt");
    if (op != null) p.put("op", op);
    return p;
  }

  static int rejected(GptSession s, Map<String, String> p) throws Exception {
    try {
      GptCommands.handle(s, p);
      return 0;
    } catch (GptCommands.Rejected e) {
      return e.code;
    }
  }

  public static void main(String[] args) {
    try {
      GptSessionCheck.Rig r = new GptSessionCheck.Rig("");
      r.in.delayMillis = 600;
      require("{\"ok\":true}".equals(GptCommands.handle(r.s, op("start"))), "start answers ok");
      Thread.sleep(50);
      require(r.s.state() == GptSession.State.LISTENING, "start begins listening");
      require(rejected(r.s, op("start")) == 409, "second start is 409");
      require(rejected(r.s, op("reset")) == 409, "reset while running is 409");
      require(rejected(r.s, op("bogus")) == 400, "unknown op is 400");
      require(rejected(r.s, op(null)) == 400, "missing op is 400");
      require("{\"ok\":true}".equals(GptCommands.handle(r.s, op("stop"))), "stop answers ok");
      r.s.awaitIdle(5000);
      require(r.s.state() == GptSession.State.IDLE && r.out.calls == 1, "stop ended recording and the answer played");
      require("{\"ok\":true}".equals(GptCommands.handle(r.s, op("reset"))), "reset when idle");
      require("{\"ok\":true}".equals(GptCommands.handle(r.s, op("cancel"))), "cancel when idle is harmless");

      File none = new File(System.getProperty("java.io.tmpdir"), "gpt-cmd-none-" + System.nanoTime());
      require(none.mkdirs(), "fixture");
      GptSessionCheck.roots.add(none);
      GptSession bare = new GptSession(GptConfig.load(none), r.in, r.out, r.api, r.alice, GptSessionCheck.scheduler);
      try {
        GptCommands.handle(bare, op("start"));
        require(false, "unconfigured start rejected");
      } catch (GptCommands.Rejected e) {
        require(e.code == 409 && e.getMessage().contains("Ключ"), "unconfigured message names the key");
      }
      System.out.println("PASS GptCommands start/stop/cancel/reset, 400 on bad op, 409 on busy or unconfigured");
    } catch (Throwable t) {
      t.printStackTrace(System.out);
      System.exit(1);
    } finally {
      GptSessionCheck.scheduler.shutdownNow();
      for (File root : GptSessionCheck.roots) { File[] c = root.listFiles(); if (c != null) for (File f : c) f.delete(); root.delete(); }
    }
  }
}
