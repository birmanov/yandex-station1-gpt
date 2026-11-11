import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Isolated regression checks for session persistence. Runs on the Station (Android API 23, root
 * context) against fresh directories under /data/local/tmp; never against the live remote.
 *
 * Modes: (none) basic | rotation | login-race | write-failure | concurrent | all | seed DIR |
 * recover DIR
 */
public final class SessionPersistenceCheck {
  static final List<StationRemote> opened = new ArrayList<StationRemote>();
  static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static StationRemote open(File root) throws Exception {
    StationRemote r = new StationRemote(root, "127.0.0.1", 0);
    opened.add(r); return r;
  }
  static StationRemote.Request cookie(String id) {
    StationRemote.Request r = new StationRemote.Request();
    r.headers.put("cookie", StationRemote.COOKIE + "=" + id); return r;
  }
  static String http(StationRemote r, String path, String id, String csrf, String body, String origin) throws Exception {
    ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    Socket client = new Socket("127.0.0.1", listener.getLocalPort());
    client.setSoTimeout(5000);
    Socket accepted = listener.accept(); accepted.setSoTimeout(5000);
    byte[] payload = body.getBytes("UTF-8");
    String headers = "POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1:0\r\nCookie: " + StationRemote.COOKIE + "=" + id
      + "\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: " + payload.length + "\r\n";
    if (csrf != null) headers += "X-Remote-CSRF: " + csrf + "\r\n";
    if (origin != null) headers += "Origin: " + origin + "\r\n";
    client.getOutputStream().write((headers + "\r\n").getBytes("US-ASCII"));
    client.getOutputStream().write(payload); client.getOutputStream().flush();
    r.handle(accepted);
    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] data = new byte[2048]; int n;
    while ((n = client.getInputStream().read(data)) != -1) out.write(data, 0, n);
    client.close(); listener.close(); return out.toString("UTF-8");
  }
  static void clean(File f) {
    File[] children = f.listFiles(); if (children != null) for (File c : children) clean(c);
    f.delete();
  }
  static File fixture(String name) throws Exception {
    File root = new File("/data/local/tmp/remote-session-" + name + "-" + System.nanoTime());
    require(root.mkdirs(), "fresh isolated fixture");
    StationRemote.writeFile(new File(root, "index.html"), "test".getBytes("UTF-8"));
    Properties p = new Properties(); String salt = StationRemote.token();
    p.setProperty("salt", salt); p.setProperty("passwordHash", StationRemote.hash("fixture-only-pass", salt));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); p.store(bytes, "isolated review fixture");
    StationRemote.writeFile(new File(root, "auth.properties"), bytes.toByteArray());
    return root;
  }
  static void closeOpened() throws Exception {
    for (StationRemote r : opened) {
      r.clock.shutdownNow(); r.workers.shutdownNow();
      require(r.clock.awaitTermination(5, TimeUnit.SECONDS), "clock stopped");
      require(r.workers.awaitTermination(5, TimeUnit.SECONDS), "workers stopped");
    }
    opened.clear();
  }
  /** POSIX mode bits via android.system.Os (API 21+); -1 when that API is unavailable. */
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
  static Properties stored(File root) throws Exception {
    Properties p = new Properties();
    File f = new File(root, "sessions.properties");
    if (f.isFile()) p.load(new ByteArrayInputStream(StationRemote.readFile(f, 65536)));
    return p;
  }
  static void stoppedAtMonitorOrFinished(Thread t) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (t.isAlive() && t.getState() != Thread.State.BLOCKED && System.nanoTime() < end) Thread.yield();
    require(!t.isAlive() || t.getState() == Thread.State.BLOCKED, "worker reaches controlled monitor boundary");
  }
  static void joined(Thread t) throws Exception {
    t.join(10000); require(!t.isAlive(), "fixture worker must terminate");
  }

  static void test() throws Exception {
    File root = fixture("check");
    try {
      StationRemote first = open(root); StationRemote.Session s = first.newSession(first.generation());
      requireMode(new File(root, "sessions.properties"), 0600, "session file");
      requireMode(root, 0700, "session directory");
      StationRemote restarted = open(root);
      StationRemote.Session recovered = restarted.session(cookie(s.id));
      require(recovered != null, "RED: login is forgotten after server restart");
      require(s.csrf.equals(recovered.csrf), "CSRF must survive restart unchanged");
      require(restarted.sessionCookie(recovered).contains("Max-Age=" + TimeUnit.DAYS.toSeconds(30)), "remember login for 30 days");
      require(recovered.expires - System.currentTimeMillis() > TimeUnit.DAYS.toMillis(29), "session retention window");
      require(restarted.session(cookie("0000000000000000000000000000000000000000000000000000000000000000")) == null, "forged cookie rejected");
      require(http(restarted, "/api/logout", s.id, null, "", null).startsWith("HTTP/1.1 403"), "missing CSRF rejected");
      require(http(restarted, "/api/logout", s.id, s.csrf, "", "http://untrusted.invalid").startsWith("HTTP/1.1 403"), "foreign origin rejected");
      require(restarted.session(cookie(s.id)) != null, "rejected requests must not log out");
      require(http(restarted, "/api/logout", s.id, s.csrf, "", null).startsWith("HTTP/1.1 200"), "logout succeeds");
      require(restarted.session(cookie(s.id)) == null, "logout revokes current session");
      StationRemote afterLogout = open(root);
      require(afterLogout.session(cookie(s.id)) == null, "logout stays revoked after restart");
      StationRemote.Session changed = afterLogout.newSession(afterLogout.generation());
      require(http(afterLogout, "/api/password", changed.id, changed.csrf,
        "current=fixture-only-pass&password=fixture-only-changed", null).startsWith("HTTP/1.1 200"), "password change succeeds");
      require(afterLogout.session(cookie(changed.id)) == null, "password change revokes old session");
      require(http(afterLogout, "/api/password", changed.id, changed.csrf,
        "current=fixture-only-pass&password=fixture-only-x", null).startsWith("HTTP/1.1 401"), "revoked session cannot act");
      StationRemote afterPassword = open(root);
      require(afterPassword.session(cookie(changed.id)) == null, "old session remains revoked after password restart");
      require(afterPassword.sessions.size() == 1, "fresh password session survives restart");
      StationRemote.Session fresh = afterPassword.sessions.values().iterator().next();
      afterPassword.setPassword("fixture-only-third");
      require(afterPassword.sessions.isEmpty(), "rotation clears sessions in memory");
      StationRemote changedGeneration = open(root);
      require(changedGeneration.session(cookie(fresh.id)) == null, "changed credentials invalidate old disk sessions even across interruption");
      StationRemote.writeFile(new File(root, "sessions.properties"), "broken=\\uZZZZ".getBytes("US-ASCII"));
      StationRemote corrupted = open(root);
      require(corrupted.sessions.isEmpty(), "corrupt session storage fails closed");
      System.out.println("PASS restart persistence; 30-day cookie; CSRF and origin; logout; password rotation; permissions; corruption fails closed");
    } finally { closeOpened(); clean(root); }
  }

  static void processCheck(String mode, File root) throws Exception {
    require(root.getAbsolutePath().startsWith("/data/local/tmp/remote-session-process-"), "isolated fixture path");
    try {
      if ("seed".equals(mode)) {
        require(root.mkdirs(), "fresh process fixture");
        StationRemote.writeFile(new File(root, "index.html"), "test".getBytes("UTF-8"));
        Properties config = new Properties(); String salt = StationRemote.token();
        config.setProperty("salt", salt); config.setProperty("passwordHash", StationRemote.hash("fixture-process-only", salt));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); config.store(bytes, "isolated process fixture");
        StationRemote.writeFile(new File(root, "auth.properties"), bytes.toByteArray());
        StationRemote r = open(root); StationRemote.Session s = r.newSession(r.generation());
        StationRemote.writeFile(new File(root, "fixture-cookie"), (s.id + "\n" + s.csrf).getBytes("US-ASCII"));
        System.out.println("PASS seed session durably saved; process exiting");
      } else if ("recover".equals(mode)) {
        String[] saved = new String(StationRemote.readFile(new File(root, "fixture-cookie"), 256), "US-ASCII").split("\n");
        StationRemote r = open(root); StationRemote.Session s = r.session(cookie(saved[0]));
        require(s != null && saved[1].equals(s.csrf), "session and CSRF must survive full process exit");
        String answer = http(r, "/api/command", s.id, s.csrf, "action=ping&id=restart-test", null);
        require(answer.startsWith("HTTP/1.1 200") && !answer.contains("\"error\""), "authenticated command works after process restart");
        System.out.println("PASS session and CSRF survive full process exit; authenticated HTTP command works");
      } else throw new IllegalArgumentException("unknown process mode");
    } finally {
      closeOpened();
      if ("recover".equals(mode)) clean(root);
    }
  }

  /** Rotation must block while another thread holds sessionLock, and must drop old sessions. */
  static void rotationRace() throws Exception {
    File root = fixture("rotation"); final StationRemote r = open(root);
    final Throwable[] error = new Throwable[1]; Thread rotation = null;
    try {
      StationRemote.Session old = r.newSession(r.generation()); final String generation = r.generation();
      rotation = new Thread(new Runnable() { public void run() {
        try { r.setPassword("fixture-only-changed"); } catch (Throwable t) { error[0] = t; }
      } }, "fixture-rotation");
      synchronized (r.sessionLock) {
        rotation.start(); stoppedAtMonitorOrFinished(rotation);
        require(rotation.isAlive(), "rotation must wait for sessionLock, not run ahead");
        require(generation.equals(r.generation()),
          "RED rotation must not publish new generation while old sessions can be persisted");
        r.saveSessions();
        require(stored(root).containsKey(old.id), "old session persisted under old generation");
      }
      joined(rotation); if (error[0] != null) throw new AssertionError(error[0]);
      require(!generation.equals(r.generation()), "rotation completed after lock release");
      require(r.sessions.isEmpty(), "rotation revoked old session in memory");
      require(!stored(root).containsKey(old.id), "rotation rewrote the store without old session");
      r.newSession(r.generation());
      require(open(root).session(cookie(old.id)) == null, "rotation cannot rebind old sessions to new credentials");
      System.out.println("PASS rotation serialized with concurrent persistence; old session not rebound");
    } finally { if (rotation != null) joined(rotation); closeOpened(); clean(root); }
  }

  /** A password verified before rotation must not yield a session after it. */
  static void loginRace() throws Exception {
    File root = fixture("login-race"); StationRemote r = open(root);
    try {
      String verified = r.verifiedGeneration("fixture-only-pass");
      require(verified != null, "fixture password verifies");
      r.setPassword("fixture-only-changed");
      try {
        r.newSession(verified);
        require(false, "RED stale verification must not issue a session after rotation");
      } catch (StationRemote.Bad e) { require(e.code == 401, "stale login answers 401"); }
      require(r.sessions.isEmpty() && stored(root).size() <= 1, "no session leaked to memory or disk");
      require(r.verifiedGeneration("fixture-only-pass") == null, "old password no longer verifies");
      require(r.newSession(r.verifiedGeneration("fixture-only-changed")) != null, "new password issues session");
      System.out.println("PASS stale verification rejected across rotation");
    } finally { closeOpened(); clean(root); }
  }

  /** A directory in place of the temp file makes createNewFile fail: a controlled write error. */
  static void block(File root) throws Exception {
    File obstacle = new File(root, "sessions.properties.new");
    require(obstacle.mkdir() && new File(obstacle, "keep").createNewFile(), "write obstacle in place");
  }
  static void unblock(File root) { clean(new File(root, "sessions.properties.new")); }

  static void writeFailure() throws Exception {
    File root = fixture("write-failure"); StationRemote r = open(root);
    try {
      StationRemote.Session kept = r.newSession(r.generation());
      block(root);
      try { r.newSession(r.generation()); require(false, "issue must fail when store cannot be written"); }
      catch (IOException expected) { }
      require(r.sessions.size() == 1 && r.sessions.containsKey(kept.id), "failed issue leaves no session in memory");
      require(stored(root).containsKey(kept.id), "failed issue leaves store intact");

      // Revocation with a broken store: memory revoked, stale store discarded, restart cannot resurrect.
      r.removeSession(kept.id);
      require(r.sessions.isEmpty(), "revocation holds in memory despite write failure");
      require(r.sessionStoreDirty, "store flagged dirty");
      require(stored(root).isEmpty(), "stale store discarded, not left with revoked session");
      require(open(root).session(cookie(kept.id)) == null, "RED revoked session must not return after restart");

      // Recovery: once writes succeed again the store is rebuilt from memory and the flag clears.
      unblock(root);
      r.retrySave();
      require(!r.sessionStoreDirty && new File(root, "sessions.properties").isFile(), "store repaired on retry");
      StationRemote.Session next = r.newSession(r.generation());
      require(stored(root).containsKey(next.id), "normal operation resumes");

      // Even discarding fails: error propagates, memory still revoked.
      block(root);
      File store = new File(root, "sessions.properties");
      require(store.delete() && store.mkdir() && new File(store, "keep").createNewFile(), "undeletable store stand-in");
      try { r.removeSession(next.id); require(false, "revocation must report when nothing on disk could be fixed"); }
      catch (IOException expected) { }
      require(r.sessions.isEmpty(), "memory never rolls back a revocation");
      System.out.println("PASS write failures fail closed; revocation never resurrects; store self-repairs");
    } finally { closeOpened(); clean(root); }
  }

  /** Parallel issue and revoke must end with the store equal to memory. */
  static void concurrent() throws Exception {
    File root = fixture("concurrent"); final StationRemote r = open(root);
    final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
    try {
      final StationRemote.Session victim = r.newSession(r.generation());
      List<Thread> threads = new ArrayList<Thread>();
      for (int i = 0; i < 6; i++) threads.add(new Thread(new Runnable() { public void run() {
        try { for (int k = 0; k < 8; k++) r.newSession(r.generation()); } catch (Throwable t) { errors.add(t); }
      } }, "fixture-issue-" + i));
      threads.add(new Thread(new Runnable() { public void run() {
        try { r.removeSession(victim.id); } catch (Throwable t) { errors.add(t); }
      } }, "fixture-revoke"));
      for (Thread t : threads) t.start();
      for (Thread t : threads) joined(t);
      require(errors.isEmpty(), "no worker failed: " + errors);
      require(!r.sessions.containsKey(victim.id), "victim revoked in memory");
      Properties disk = stored(root);
      require(!disk.containsKey(victim.id), "victim revoked on disk");
      require(r.sessions.size() == 49 && disk.size() == 50, "store matches memory (49 sessions + generation)");
      for (String id : r.sessions.keySet()) require(disk.containsKey(id), "every live session persisted");
      requireMode(new File(root, "sessions.properties"), 0600, "session file after concurrency");
      System.out.println("PASS concurrent issue and revoke keep memory and disk consistent");
    } finally { closeOpened(); clean(root); }
  }

  public static void main(String[] args) {
    try {
      String mode = args.length == 1 ? args[0] : args.length == 0 ? "basic" : "process";
      if ("process".equals(mode)) processCheck(args[0], new File(args[1]));
      else if ("basic".equals(mode)) test();
      else if ("rotation".equals(mode)) rotationRace();
      else if ("login-race".equals(mode)) loginRace();
      else if ("write-failure".equals(mode)) writeFailure();
      else if ("concurrent".equals(mode)) concurrent();
      else if ("all".equals(mode)) { test(); rotationRace(); loginRace(); writeFailure(); concurrent(); System.out.println("PASS all"); }
      else throw new IllegalArgumentException("unknown mode " + mode);
    } catch (Throwable t) { t.printStackTrace(System.out); System.exit(1); }
  }
}
